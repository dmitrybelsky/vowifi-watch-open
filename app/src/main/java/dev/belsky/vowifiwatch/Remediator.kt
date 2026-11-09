package dev.belsky.vowifiwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Эскалирующие меры восстановления VoWiFi.
 *
 * Шаги выполняются по нарастанию (перезапуск IMS -> A -> B -> C, далее по кругу),
 * между попытками — cooldown. После maxAttempts неудач подряд — уведомление
 * «вмешайтесь вручную» и пауза.
 * Состояние эскалации хранится в Prefs и сбрасывается, как только VoWiFi поднимается.
 */
class Remediator(private val context: Context) {

    private val prefs = Prefs(context)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val adb = AdbShellExecutor(context)
    private val greezeRepairMutex = Mutex()

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIF_MANUAL = 2
        const val NOTIF_WIFI_PANEL = 3

        /** Кэш проверки доступности локального ADB, чтобы не дёргать TLS-connect часто. */
        private const val ADB_CACHE_MS = 5 * 60_000L
    }

    // Кэш результата adb.isAvailable() (см. ADB_CACHE_MS); сбрасывается при ошибке подключения.
    private var adbCheckedAt = 0L
    private var adbAvailable = false

    /** Доступен ли локальный ADB-канал (с кэшем на ADB_CACHE_MS). */
    private suspend fun adbUsable(): Boolean {
        if (!prefs.useLocalAdb) return false
        val now = System.currentTimeMillis()
        if (now - adbCheckedAt < ADB_CACHE_MS) return adbAvailable
        adbAvailable = adb.isAvailable()
        if (!adbAvailable) {
            // HyperOS выключает или оставляет зависшую беспроводную отладку.
            // Перезапускаем её через WRITE_SECURE_SETTINGS и повторяем подключение.
            adbAvailable = tryReviveWirelessDebug()
        }
        adbCheckedAt = now
        EventLog.add("Локальный ADB: ${if (adbAvailable) "доступен (uid 2000)" else "недоступен"}")
        return adbAvailable
    }

    /** Самолечение беспроводной отладки через WRITE_SECURE_SETTINGS. true, если канал поднялся. */
    private suspend fun tryReviveWirelessDebug(): Boolean {
        // По умолчанию выключено: включение adb_wifi_enabled вызывает системные
        // диалоги беспроводной отладки, перехватывающие фокус.
        if (!prefs.autoReviveAdb) return false
        if (!hasWriteSecureSettings()) return false
        return try {
            val cur = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
            if (cur !in 0..1) return false
            EventLog.add(
                if (cur == 0) "Беспроводная отладка выключена системой — включаю обратно (WRITE_SECURE_SETTINGS)"
                else "Беспроводная отладка включена, но канал недоступен — перезапускаю её"
            )
            if (cur == 1) {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
                kotlinx.coroutines.delay(2_000)
            }
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            // adbd поднимается несколько секунд и анонсирует новый порт
            kotlinx.coroutines.delay(6_000)
            adb.isAvailable().also { ok ->
                EventLog.add("Самолечение беспроводной отладки: ${if (ok) "успешно" else "не помогло"}")
            }
        } catch (t: Throwable) {
            EventLog.add("Самолечение беспроводной отладки: ошибка (${t.javaClass.simpleName}: ${t.message})")
            false
        }
    }

    private fun resetAdbCache() {
        adbCheckedAt = 0L
        adbAvailable = false
    }

    fun createAlertChannel() {
        val ch = NotificationChannel(
            CHANNEL_ALERTS,
            "Предупреждения vowifi-watch",
            NotificationManager.IMPORTANCE_HIGH
        )
        nm?.createNotificationChannel(ch)
    }

    fun isRooted(): Boolean {
        return try {
            val root = Shell.getShell().isRoot
            EventLog.add("Root-доступ: ${if (root) "есть" else "нет"}")
            root
        } catch (t: Throwable) {
            EventLog.add("libsu: не удалось проверить root (${t.javaClass.simpleName}: ${t.message})")
            false
        }
    }

    /** Сброс эскалации — вызывается, когда VoWiFi снова активен. */
    fun resetEscalation() {
        if (prefs.escStep != 3 || prefs.escAttempts != 0) {
            prefs.escStep = 3
            prefs.escAttempts = 0
            EventLog.add("Эскалация сброшена (VoWiFi восстановлен)")
        }
    }

    /**
     * Последний признак IMS-регистрации из radio-буфера через локальный ADB.
     * Принимаем записи только текущей загрузки устройства: строка может не
     * повторяться, пока состояние IMS не меняется.
     */
    suspend fun radioImsRegistered(): Boolean? {
        if (!adbUsable()) return null
        val r = adb.exec("logcat -b radio -d -v epoch -t 300")
        if (r.error != null) {
            resetAdbCache()
            return null
        }
        if (r.exitCode != 0) return null
        val now = System.currentTimeMillis()
        return RadioLogParser.latestImsRegistered(
            output = r.stdout,
            bootAt = now - SystemClock.elapsedRealtime(),
            now = now
        )
    }

    /** Пользовательский переключатель VoWiFi, а не временное состояние IMS-регистрации. */
    suspend fun voWiFiSettingEnabled(): Boolean? {
        val subId = ImsMonitor.activeSubscriptionId(context) ?: return null
        return voWiFiSettingEnabled(subId)
    }

    private suspend fun voWiFiSettingEnabled(subId: Int): Boolean? {
        // ITelephony.isVoWiFiSettingEnabled(); номер транзакции стабилен в AOSP.
        val r = execShell("service call phone 202 i32 $subId")
        if (!r.isSuccess) return null
        return VoWiFiSettingParser.enabled(r.stdout)
    }

    /** Восстанавливает оба независимых исключения из дневной и ночной заморозки HyperOS. */
    suspend fun ensureGreezeExemption() = greezeRepairMutex.withLock {
        ensureMilletExemption()
        ensureNightFreezeExemption()
    }

    private suspend fun ensureMilletExemption() {
        val key = "MILLET_NO_RESTRICT_APP"
        val currentResult = execShell("settings --user 0 get system $key")
        if (!currentResult.isSuccess) {
            EventLog.add("Не удалось проверить исключение Greeze: ${currentResult.describe()}")
            return
        }
        val current = currentResult.stdout.trim()
        if (current == "null") return
        if (PackageListParser.hasMilletPackage(current, context.packageName)) return

        val updated = PackageListParser.withMilletPackage(current, context.packageName)
        val put = execShell("settings --user 0 put system $key '$updated'")
        if (!put.isSuccess) {
            EventLog.add("Не удалось восстановить исключение Greeze: ${put.describe()}")
            return
        }
        val verified = execShell("settings --user 0 get system $key")
        if (verified.isSuccess && PackageListParser.hasMilletPackage(verified.stdout, context.packageName)) {
            EventLog.add("Исключение Greeze восстановлено в $key")
        } else {
            EventLog.add("Исключение Greeze не подтверждено повторным чтением $key")
        }
    }

    private suspend fun ensureNightFreezeExemption() {
        val key = "com.miui.game.allowlist"
        val currentResult = execShell("settings --user 0 get system $key")
        if (!currentResult.isSuccess) {
            EventLog.add("Не удалось проверить исключение ночной заморозки: ${currentResult.describe()}")
            return
        }
        val current = currentResult.stdout.trim()
        if (PackageListParser.hasNightPackage(current, context.packageName)) return

        val updated = PackageListParser.withNightPackage(current, context.packageName)
        val put = execShell("settings --user 0 put system $key '$updated'")
        if (!put.isSuccess) {
            EventLog.add("Не удалось восстановить исключение ночной заморозки: ${put.describe()}")
            return
        }
        val verified = execShell("settings --user 0 get system $key")
        if (verified.isSuccess &&
            PackageListParser.hasNightPackage(verified.stdout.trim(), context.packageName)
        ) {
            EventLog.add("Исключение ночной заморозки восстановлено в $key")
        } else {
            EventLog.add("Исключение ночной заморозки не подтверждено повторным чтением $key")
        }
    }

    /**
     * Verify-before-act: authoritative-проверка VoWiFi через `dumpsys telephony.registry`
     * по локальному ADB. true — VoWiFi активен (IWLAN), false — точно не активен,
     * null — проверить не удалось (канал недоступен/ошибка).
     */
    suspend fun groundTruthVowifiActive(): Boolean? {
        if (voWiFiSettingEnabled() == false) return false
        if (!adbUsable()) return null
        // Требуем оба признака: данные по IWLAN И голос IN_SERVICE
        // (инцидент 2026-09-17: data-IWLAN был, а голосовая регистрация слетела).
        val r = adb.exec("dumpsys telephony.registry")
        if (r.error != null) {
            resetAdbCache()
            return null
        }
        if (r.exitCode != 0) return null
        val dataIwlan = r.stdout.contains("getRilDataRadioTechnology=18") ||
            r.stdout.contains("mIsIwlanPreferred=true")
        val voiceOk = r.stdout.contains("mVoiceRegState=0(IN_SERVICE)")
        val serviceStateActive = dataIwlan && voiceOk
        // ServiceState может показать живую CS-регистрацию при isImsRegistered=false.
        // Свежий radio-лог в этом случае отменяет ложноположительный результат.
        return when (radioImsRegistered()) {
            false -> false
            true, null -> serviceStateActive
        }
    }

    suspend fun remediate() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Не восстанавливаем, пока пользователь активно пользуется телефоном:
        // цикл авиарежима/перезапуск Wi-Fi на глазах рвёт звонки и SMS-коды.
        // Попытки и cooldown при этом не тратятся.
        if (prefs.guardInteractive &&
            context.getSystemService(PowerManager::class.java)?.isInteractive == true
        ) {
            EventLog.add("Remediation отложено: экран включён")
            return@withContext
        }

        // Дневной лимит попыток (скользящее окно 24 ч)
        if (now - prefs.attemptsDayStart > 24 * 3_600_000L) {
            prefs.attemptsDay = 0
            prefs.attemptsDayStart = now
        }
        if (prefs.attemptsDay >= prefs.maxAttemptsDay) {
            EventLog.add("Дневной лимит попыток восстановления исчерпан (${prefs.maxAttemptsDay}/сутки)")
            notifyManualIntervention()
            return@withContext
        }

        val cooldownMs = prefs.cooldownMin * 60_000L
        if (now - prefs.escLastTime < cooldownMs) {
            val leftMin = (cooldownMs - (now - prefs.escLastTime)) / 60_000L + 1
            EventLog.add("Remediation: активен cooldown, следующая попытка через ~$leftMin мин")
            return@withContext
        }

        if (prefs.escAttempts >= prefs.maxAttempts) {
            EventLog.add("Исчерпано ${prefs.maxAttempts} попыток подряд — нужно ручное вмешательство")
            notifyManualIntervention()
            prefs.escStep = 3
            prefs.escAttempts = 0
            prefs.escLastTime = now
            return@withContext
        }

        val fallbackSteps = buildList {
            if (prefs.stepAEnabled) add(0)
            if (prefs.stepBEnabled) add(1)
            if (prefs.stepCEnabled) add(2)
        }
        if (fallbackSteps.isEmpty()) {
            EventLog.add("Remediation: все шаги выключены в настройках")
            return@withContext
        }
        val enabledSteps = buildList {
            add(3)
            addAll(fallbackSteps)
        }

        // Следующий включённый шаг; порядок списка задаёт цикл IMS -> A -> B -> C.
        val idx = enabledSteps.indexOf(prefs.escStep).let { if (it == -1) 0 else it }
        val step = enabledSteps[idx]
        prefs.escStep = enabledSteps.getOrElse(idx + 1) { enabledSteps.first() }
        prefs.escAttempts += 1
        prefs.attemptsDay += 1
        prefs.escLastTime = now

        EventLog.add("Remediation: попытка ${prefs.escAttempts}/${prefs.maxAttempts}, шаг ${stepName(step)}")
        when (step) {
            3 -> stepImsRestart()
            0 -> stepWifiToggle()
            1 -> stepAirplaneCycle()
            2 -> stepCustomCommand()
        }
    }

    private fun stepName(step: Int): String = when (step) {
        3 -> "IMS"
        0 -> "A"
        1 -> "B"
        2 -> "C"
        else -> "?"
    }

    /** Восстанавливает переключатель VoWiFi и перезапускает IMS без разрыва радиотракта. */
    private suspend fun stepImsRestart() {
        val subId = ImsMonitor.activeSubscriptionId(context)
        if (subId != null && voWiFiSettingEnabled(subId) == false) {
            EventLog.add("Переключатель VoWiFi выключен — включаю и выбираю «Предпочитать Wi-Fi»")
            // ITelephony.setVoWiFiSettingEnabled() и setVoWiFiModeSetting(WIFI_PREFERRED).
            val enabled = execShell(
                "service call phone 203 i32 $subId i32 1; " +
                    "service call phone 210 i32 $subId i32 2"
            )
            if (!enabled.isSuccess) {
                EventLog.add("Команда включения переключателя VoWiFi -> ${enabled.describe()}")
            } else {
                delay(2_000)
                val state = when (voWiFiSettingEnabled(subId)) {
                    true -> "включён (подтверждено повторным чтением)"
                    false -> "ОШИБКА: остался выключенным после успешной команды"
                    null -> "ОШИБКА: не удалось подтвердить состояние повторным чтением"
                }
                EventLog.add("Восстановление переключателя VoWiFi: $state")
            }
        }
        val r = execShell("cmd phone ims disable; sleep 5; cmd phone ims enable")
        EventLog.add("Шаг IMS: cmd phone ims disable/enable -> ${r.describe()}")
    }

    private suspend fun execShell(cmd: String): AdbShellExecutor.Result {
        if (isRooted()) {
            val r = Shell.cmd(cmd).exec()
            return AdbShellExecutor.Result(
                exitCode = if (r.isSuccess) 0 else 1,
                stdout = r.out.joinToString("\n"),
                stderr = r.err.joinToString("\n")
            )
        }
        if (adbUsable()) {
            val r = adb.exec(cmd)
            if (r.error != null) resetAdbCache()
            return r
        }
        return AdbShellExecutor.Result(error = "нет ни root, ни локального ADB")
    }

    /** Шаг A: перезапуск Wi-Fi. Каналы: root -> локальный ADB -> API<29 -> уведомление. */
    private suspend fun stepWifiToggle() {
        val cmd = "svc wifi disable && sleep 3 && svc wifi enable"
        when {
            isRooted() -> {
                val r = Shell.cmd(cmd).exec()
                EventLog.add("Шаг A (root): svc wifi disable/enable -> ${if (r.isSuccess) "OK" else "ошибка: ${r.err}"}")
            }
            adbUsable() -> {
                // Wi-Fi off рвёт TLS к adbd вместе с shell-процессом. Обратное
                // включение делает отсоединённый процесс, как и в цикле авиарежима.
                val detachedCmd = "svc wifi disable; " +
                    "setsid sh -c 'sleep 3; svc wifi enable' </dev/null >/dev/null 2>&1 &"
                val r = adb.exec(detachedCmd)
                if (r.error != null) resetAdbCache()
                EventLog.add("Шаг A (локальный ADB): svc wifi disable/enable (отсоединённое включение) -> ${r.describe()}")
            }
            Build.VERSION.SDK_INT < 29 -> {
                // До Android 10 программное переключение Wi-Fi разрешено
                val wm = context.applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = false
                delay(3_000)
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = true
                EventLog.add("Шаг A: WifiManager.setWifiEnabled(false/true) выполнен")
            }
            else -> {
                EventLog.add("Шаг A пропущен: без root на API>=29 Wi-Fi программно не переключить")
                notifyOpenWifiPanel()
            }
        }
    }

    /** Шаг B: цикл авиарежима (перезагрузка радиотракта). Каналы: root -> локальный ADB -> WRITE_SECURE_SETTINGS. */
    private suspend fun stepAirplaneCycle() {
        when {
            isRooted() -> {
                val cmd = "settings put global airplane_mode_on 1 && " +
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true && " +
                    "sleep 8 && " +
                    "settings put global airplane_mode_on 0 && " +
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                val r = Shell.cmd(cmd).exec()
                EventLog.add("Шаг B (root): цикл авиарежима -> ${if (r.isSuccess) "OK" else "ошибка: ${r.err}"}")
            }
            adbUsable() -> {
                // На HyperOS protected-broadcast AIRPLANE_MODE из shell заблокирован,
                // поэтому используем cmd connectivity — работает на уровне ConnectivityService.
                // ВАЖНО: при включении авиарежима Wi-Fi гаснет и TLS-соединение с adbd
                // рвётся (процесс shell при этом убивается), поэтому обратное выключение
                // выполняет отсоединённый процесс (setsid), переживающий обрыв соединения.
                val cmd = "cmd connectivity airplane-mode enable; " +
                    "setsid sh -c 'sleep 8; cmd connectivity airplane-mode disable' " +
                    "</dev/null >/dev/null 2>&1 &"
                val r = adb.exec(cmd)
                if (r.error != null) resetAdbCache()
                EventLog.add("Шаг B (локальный ADB): цикл авиарежима через cmd connectivity (отсоединённое выключение) -> ${r.describe()}")
            }
            hasWriteSecureSettings() -> {
                // Не работает на HyperOS (проверено: флаг меняется, но радиотракт не перезагружается)
                EventLog.add("Шаг B (экспериментальный): AIRPLANE_MODE_ON через WRITE_SECURE_SETTINGS, без broadcast (не работает на HyperOS)")
                try {
                    Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
                    delay(8_000)
                    Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                    EventLog.add("Шаг B: флаг авиарежима переключён (1 -> 0)")
                } catch (t: Throwable) {
                    EventLog.add("Шаг B: ошибка записи настройки (${t.javaClass.simpleName}: ${t.message})")
                }
            }
            else -> {
                EventLog.add("Шаг B пропущен: нет ни root, ни локального ADB, ни WRITE_SECURE_SETTINGS")
            }
        }
    }

    /** Шаг C: кастомная shell-команда (через любой доступный shell-канал: root -> локальный ADB). */
    private suspend fun stepCustomCommand() {
        val cmd = prefs.customCommand.trim()
        if (cmd.isEmpty()) {
            EventLog.add("Шаг C пропущен: команда не задана")
            return
        }
        when {
            isRooted() -> {
                val r = Shell.cmd(cmd).exec()
                EventLog.add("Шаг C (root): `$cmd` -> ${if (r.isSuccess) "OK" else "ошибка: ${r.err}"}")
            }
            adbUsable() -> {
                val r = adb.exec(cmd)
                if (r.error != null) resetAdbCache()
                EventLog.add("Шаг C (локальный ADB): `$cmd` -> ${r.describe()}")
            }
            else -> EventLog.add("Шаг C пропущен: нет ни root, ни локального ADB")
        }
    }

    /** Тестовый запуск: 3=перезапуск IMS, 0=A (Wi-Fi), 1=B (авиарежим), 2=C (команда). */
    suspend fun debugRunStep(step: Int) {
        EventLog.add("Тест: принудительный запуск шага ${stepName(step)}")
        when (step) {
            3 -> stepImsRestart()
            0 -> stepWifiToggle()
            1 -> stepAirplaneCycle()
            2 -> stepCustomCommand()
            else -> EventLog.add("Тест: неизвестный шаг $step")
        }
    }

    private fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyManualIntervention() {
        createAlertChannel()
        val openApp = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("vowifi-watch: нужно ручное вмешательство")
            .setContentText("VoWiFi не восстановлен после ${prefs.maxAttempts} попыток. Перезагрузите телефон или проверьте настройки.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        nm?.notify(NOTIF_MANUAL, n)
    }

    private fun notifyOpenWifiPanel() {
        createAlertChannel()
        val panel = PendingIntent.getActivity(
            context, 1,
            Intent(Settings.Panel.ACTION_WIFI),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("vowifi-watch: перезапустите Wi-Fi")
            .setContentText("Нажмите, чтобы открыть панель Wi-Fi и выключить/включить его вручную")
            .setContentIntent(panel)
            .setAutoCancel(true)
            .build()
        nm?.notify(NOTIF_WIFI_PANEL, n)
    }
}
