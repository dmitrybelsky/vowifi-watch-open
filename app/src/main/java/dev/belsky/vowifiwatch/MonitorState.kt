package dev.belsky.vowifiwatch

/** Текущее состояние мониторинга, разделяемое сервисом и UI. */
object MonitorState {
    @Volatile var running: Boolean = false
    @Volatile var ssid: String? = null
    @Volatile var rssi: Int = -127
    @Volatile var trustedGood: Boolean = false
    @Volatile var ims: ImsMonitor.ImsStatus = ImsMonitor.ImsStatus.UNKNOWN
    @Volatile var lastCheck: Long = 0L
}
