package dev.belsky.vowifiwatch

import android.content.Context

/** Работа со списками доверенных/отклонённых Wi-Fi сетей. */
object TrustedNetworks {

    /** Добавить сеть в доверенные (используется из UI и adb-команды add_trusted). */
    fun addToTrusted(context: Context, ssid: String) {
        val prefs = Prefs(context)
        val current = prefs.trustedSsids
        if (ssid !in current) {
            prefs.trustedSsidsRaw = (current + ssid).joinToString(";")
        }
        EventLog.add("Сеть $ssid добавлена в доверенные")
    }

    /** Больше не предлагать эту сеть в UI. */
    fun decline(context: Context, ssid: String) {
        val prefs = Prefs(context)
        if (ssid !in prefs.declinedSsids) {
            prefs.declinedSsidsRaw = (prefs.declinedSsids + ssid).joinToString(";")
        }
        EventLog.add("Сеть $ssid: больше не предлагать")
    }
}
