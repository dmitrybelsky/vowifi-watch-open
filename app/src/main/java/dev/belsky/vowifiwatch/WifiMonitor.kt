package dev.belsky.vowifiwatch

import android.content.Context
import android.net.wifi.WifiManager

/** Снимок состояния Wi-Fi и проверка условия «хорошая доверенная сеть». */
class WifiMonitor(context: Context) {

    data class WifiState(
        val ssid: String?,
        val rssi: Int,
        val connected: Boolean
    )

    private val appContext = context.applicationContext

    /** EMA сигнала по проверкам: сглаживает мгновенный дребезг RSSI. */
    private var emaRssi: Double? = null

    /** Предыдущее решение «хорошая доверенная сеть» — нужно для гистерезиса. */
    private var lastTrustedGood = false

    /** Последнее сглаженное значение RSSI (для журнала/статуса), null — ещё не было проверок. */
    var lastSmoothedRssi: Double? = null
        private set

    @Suppress("DEPRECATION") // connectionInfo — единственный способ получить SSID/RSSI без callback-API
    fun currentState(): WifiState {
        return try {
            val wm = appContext.getSystemService(WifiManager::class.java)
                ?: return WifiState(null, -127, false)
            val info = wm.connectionInfo ?: return WifiState(null, -127, false)
            // SSID требует ACCESS_FINE_LOCATION; приходит в кавычках или как <unknown ssid>
            var ssid = info.ssid?.removeSurrounding("\"")
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") ssid = null
            val connected = ssid != null && info.networkId != -1
            WifiState(ssid, info.rssi, connected)
        } catch (t: Throwable) {
            EventLog.add("Wi-Fi: ошибка чтения состояния (${t.javaClass.simpleName}: ${t.message})")
            WifiState(null, -127, false)
        }
    }

    /**
     * «Хорошая доверенная сеть»: SSID входит в список доверенных
     * (пустой список = любая сеть) и сглаженный сигнал не хуже порога.
     *
     * Решение принимается по EMA сигнала (alpha 0.4 на проверку — память ~2–3
     * проверок) с гистерезисом: «хорошей» сеть становится при сигнале ≥ порога,
     * а перестаёт быть таковой лишь ниже (порог − гистерезис). Иначе при RSSI
     * рядом с порогом состояние дребезжит «да/нет» от одной проверки к другой.
     */
    fun isTrustedGood(state: WifiState, prefs: Prefs): Boolean {
        if (!state.connected) {
            emaRssi = null
            lastSmoothedRssi = null
            lastTrustedGood = false
            return false
        }
        val trusted = prefs.trustedSsids
        val ssidOk = trusted.isEmpty() || trusted.contains(state.ssid)
        if (!ssidOk) {
            lastTrustedGood = false
            return false
        }
        val smoothed = smoothRssi(state.rssi)
        val enterAt = prefs.rssiThreshold
        val exitAt = enterAt - prefs.rssiHysteresisDb.coerceAtLeast(0)
        lastTrustedGood = if (lastTrustedGood) smoothed >= exitAt else smoothed >= enterAt
        return lastTrustedGood
    }

    private fun smoothRssi(raw: Int): Double {
        val next = emaRssi?.let { it + EMA_ALPHA * (raw - it) } ?: raw.toDouble()
        emaRssi = next
        lastSmoothedRssi = next
        return next
    }

    private companion object {
        const val EMA_ALPHA = 0.4
    }
}
