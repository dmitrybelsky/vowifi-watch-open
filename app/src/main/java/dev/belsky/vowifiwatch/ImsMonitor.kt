package dev.belsky.vowifiwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Определение статуса VoWiFi (IMS-регистрация голоса по Wi-Fi).
 *
 * Основной путь — скрытый SystemApi android.telephony.ims.ImsMmTelManager.isAvailable(
 * capability=1 MMTEL_CAP_TYPE_VOICE, transport=2 TRANSPORT_TYPE_WLAN).
 * На части прошивок (HyperOS/Android 16) он требует READ_PRIVILEGED_PHONE_STATE,
 * который обычному приложению не выдать. Тогда — запасной путь:
 * TelephonyManager.createForSubscriptionId(subId).serviceState (нужен лишь READ_PHONE_STATE),
 * у которого смотрим getRilDataRadioTechnology()==18 (NETWORK_TYPE_IWLAN) или
 * mIsIwlanPreferred=true — признак того, что IMS/данные идут через Wi-Fi.
 *
 * Любая ошибка — штатная деградация: возвращаем UNKNOWN и ничего не предпринимаем.
 */
object ImsMonitor {

    enum class ImsStatus { REGISTERED, NOT_REGISTERED, UNKNOWN }

    private const val CAP_VOICE = 1
    private const val TRANSPORT_WLAN = 2
    private const val NETWORK_TYPE_IWLAN = 18

    @Volatile
    private var hiddenApiExempted = false

    /** Последняя залогированная причина отказа SystemApi — чтобы не писать её в журнал на каждой проверке. */
    @Volatile
    private var lastSystemApiFailure: String? = null

    /** Причина недоступности подписки также логируется один раз до её изменения или восстановления. */
    @Volatile
    private var lastSubscriptionFailure: String? = null

    /** Снимаем ограничение на вызов скрытых API android.telephony.* (однократно, в try/catch). */
    fun ensureHiddenApiAccess() {
        if (hiddenApiExempted) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/telephony/")
            hiddenApiExempted = true
            EventLog.add("HiddenApiBypass: исключение для android.telephony установлено")
        } catch (t: Throwable) {
            // На старых API может быть не нужно / не поддержано — это не фатально
            EventLog.add("HiddenApiBypass не сработал: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun getVoiceWifiStatus(context: Context): ImsStatus {
        return try {
            ensureHiddenApiAccess()
            val subId = activeSubscriptionId(context) ?: return ImsStatus.UNKNOWN
            try {
                val status = queryViaImsApi(subId)
                lastSystemApiFailure = null
                status
            } catch (t: Throwable) {
                val root = generateSequence(t) { it.cause }.last()
                val failure = "${root.javaClass.simpleName}: ${root.message}"
                // Постоянный отказ (нет READ_PRIVILEGED_PHONE_STATE) логируем один раз —
                // иначе строка на каждую проверку съедает кольцевой журнал за час.
                if (failure != lastSystemApiFailure) {
                    lastSystemApiFailure = failure
                    EventLog.add("IMS SystemApi недоступен ($failure), пробую через ServiceState")
                }
                queryViaServiceState(context, subId)
            }
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            EventLog.add(
                "IMS: ошибка определения статуса (${root.javaClass.simpleName}: ${root.message}) — статус неизвестен"
            )
            ImsStatus.UNKNOWN
        }
    }

    /** Точный метод: ImsMmTelManager.isAvailable(VOICE, WLAN). Может требовать privileged-прав. */
    private fun queryViaImsApi(subId: Int): ImsStatus {
        val cls = Class.forName("android.telephony.ims.ImsMmTelManager")
        val create = cls.getMethod("createForSubscriptionId", Int::class.javaPrimitiveType)
        val manager = create.invoke(null, subId)
        val isAvailable = cls.getMethod(
            "isAvailable",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val available = isAvailable.invoke(manager, CAP_VOICE, TRANSPORT_WLAN) as Boolean
        return if (available) ImsStatus.REGISTERED else ImsStatus.NOT_REGISTERED
    }

    /**
     * Запасной метод: ServiceState.getRilDataRadioTechnology() == IWLAN (18)
     * или mIsIwlanPreferred=true в toString() — данные/IMS идут по Wi-Fi.
     * Нужен только READ_PHONE_STATE.
     */
    private fun queryViaServiceState(context: Context, subId: Int): ImsStatus {
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId) ?: return ImsStatus.UNKNOWN
        val ss = try {
            tm.serviceState
        } catch (e: SecurityException) {
            EventLog.add("IMS: SecurityException при чтении ServiceState: ${e.message}")
            return ImsStatus.UNKNOWN
        } ?: run {
            EventLog.add("IMS: ServiceState недоступен")
            return ImsStatus.UNKNOWN
        }

        val viaReflection = try {
            val m = ss.javaClass.getMethod("getRilDataRadioTechnology")
            (m.invoke(ss) as Int) == NETWORK_TYPE_IWLAN
        } catch (_: Throwable) {
            null
        }
        val dump = ss.toString()
        val viaString = dump.contains("getRilDataRadioTechnology=18") ||
            dump.contains("mIsIwlanPreferred=true")

        val dataIwlan = viaReflection ?: viaString

        // Голос: IMS voice registration поднимает mVoiceRegState в IN_SERVICE
        // (ServiceState.getState() — публичный API). Инсидент 2026-09-17: ePDG data
        // по IWLAN работал, а голос был OUT_OF_SERVICE — звонок не проходил, хотя
        // приложение рапортовало REGISTERED. Поэтому одного IWLAN-канала мало.
        val voiceOk = ss.state == android.telephony.ServiceState.STATE_IN_SERVICE

        return if (dataIwlan && voiceOk) ImsStatus.REGISTERED else ImsStatus.NOT_REGISTERED
    }

    /** Первый subId из активных подписок; требует READ_PHONE_STATE. */
    internal fun activeSubscriptionId(context: Context): Int? {
        fun unavailable(reason: String): Int? {
            if (reason != lastSubscriptionFailure) {
                lastSubscriptionFailure = reason
                EventLog.add(reason)
            }
            return null
        }

        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return unavailable("IMS: нет разрешения READ_PHONE_STATE — статус неизвестен")
        }
        val sm = context.getSystemService(SubscriptionManager::class.java)
            ?: return unavailable("IMS: SubscriptionManager недоступен — статус неизвестен")
        val subs = try {
            sm.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            return unavailable("IMS: SecurityException при чтении списка подписок: ${e.message}")
        }
        val subId = subs?.firstOrNull()?.subscriptionId
        if (subId != null) {
            lastSubscriptionFailure = null
            return subId
        }
        val def = SubscriptionManager.getDefaultSubscriptionId()
        if (def != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            lastSubscriptionFailure = null
            return def
        }
        return unavailable("IMS: нет активной SIM-подписки — статус неизвестен")
    }
}
