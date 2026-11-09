package dev.belsky.vowifiwatch

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import kotlin.math.roundToInt

/**
 * Foreground-сервис мониторинга VoWiFi.
 *
 * Периодически (настраиваемый интервал) и при смене сети проверяет:
 * Wi-Fi (SSID/RSSI) + статус IMS-регистрации. Если сеть доверенная и сигнал хороший,
 * а VoWiFi не работает N проверок подряд — запускает Remediator.
 * Статус UNKNOWN (нет доступа к IMS API) remediation не инициирует.
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_STATUS = "monitor_status"
        const val NOTIF_STATUS = 1
        const val NOTIF_WATCHDOG = 4
        const val NOTIF_PERMISSIONS = 5

        /** Intent-extra: источник запуска (boot/watchdog/user/autostart) — только для журнала. */
        const val EXTRA_REASON = "reason"

        /** Источник «приложение открылось на переднем плане» — штатно, в журнал не пишем. */
        const val REASON_UI_FOREGROUND = "ui-foreground"

        /** Окно, в котором событие network callback считается причиной слёта (роуминг и т.п.). */
        private const val NETWORK_EVENT_WINDOW_MS = 30_000L
        private const val WATCHDOG_REPEAT_MS = 24 * 3_600_000L

        /** Как часто перепроверять стабильный REGISTERED прямым признаком из radio-лога. */
        private const val REGISTERED_RADIO_VERIFY_MS = 10 * 60_000L
        private const val WFC_SETTING_VERIFY_MS = 60_000L
        private const val GREEZE_EXEMPTION_VERIFY_MS = 60_000L
        private const val GREEZE_EXEMPTION_KEY = "MILLET_NO_RESTRICT_APP"
        private const val NIGHT_FREEZE_EXEMPTION_KEY = "com.miui.game.allowlist"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: Prefs
    private lateinit var wifiMonitor: WifiMonitor
    private lateinit var remediator: Remediator
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private var consecutiveNotRegistered = 0
    private var checkInFlight = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var greezeExemptionObserver: ContentObserver? = null
    private var greezeObserverThread: HandlerThread? = null

    // Превентивный контур: отслеживание переходов IMS для статистики слётов
    private var prevIms: ImsMonitor.ImsStatus = ImsMonitor.ImsStatus.UNKNOWN
    private var dropOpenedAt = 0L
    private var lastNetworkEventAt = 0L
    private var lastNetworkEventReason = ""
    private var lastNetEventLoggedAt = 0L
    private var lastNetEventLoggedReason = ""
    private var lastRegisteredRadioCheckAt = 0L
    private var lastWfcSettingCheckAt = 0L
    private var lastGreezeExemptionCheckAt = 0L
    private var missingMonitorPermissions: List<String>? = null

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        prefs = Prefs(this)
        wifiMonitor = WifiMonitor(this)
        remediator = Remediator(this)
        remediator.createAlertChannel()

        // Статусное уведомление сведено к минимальному немому (FGS без уведомления
        // невозможен — ограничение платформы); содержательные — только о проблемах.
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Фоновая работа", NotificationManager.IMPORTANCE_MIN)
        )
        if (!goForeground("запуск")) {
            // Останавливаемся честно, а не падаем: повторные падения сервиса
            // заставляют систему отказаться от его перезапуска.
            // Поднимет watchdog-будильник (см. WatchdogReceiver).
            stopSelf()
            return
        }
        MonitorState.running = true
        WatchdogReceiver.schedule(this)

        registerWifiCallback()
        registerGreezeExemptionObserver()
        scope.launch { monitorLoop() }
        EventLog.add("Сервис мониторинга запущен (интервал ${prefs.intervalSec} c)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON)
        if (reason != null && reason != REASON_UI_FOREGROUND) {
            EventLog.add("Запуск сервиса, источник: $reason")
        }
        if (MonitorState.running) {
            // Повторный вызов (приложение вышло на передний план и т.п.) —
            // пересчитываем FGS-типы: с foreground-состоянием становится доступен
            // тип location (SSID при выключенном экране).
            goForeground("обновление типов")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        greezeExemptionObserver?.let { contentResolver.unregisterContentObserver(it) }
        greezeObserverThread?.quitSafely()
        scope.cancel()
        MonitorState.running = false
        EventLog.add("Сервис мониторинга остановлен")
        super.onDestroy()
    }

    private fun registerGreezeExemptionObserver() {
        val thread = HandlerThread("vowifi-greeze-observer").apply { start() }
        val observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) {
                lastGreezeExemptionCheckAt = System.currentTimeMillis()
                scope.launch(Dispatchers.IO) { remediator.ensureGreezeExemption() }
            }
        }
        greezeExemptionObserver = observer
        greezeObserverThread = thread
        try {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(GREEZE_EXEMPTION_KEY), false, observer
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(NIGHT_FREEZE_EXEMPTION_KEY), false, observer
            )
        } catch (t: Throwable) {
            contentResolver.unregisterContentObserver(observer)
            greezeExemptionObserver = null
            greezeObserverThread = null
            thread.quitSafely()
            EventLog.add("Не удалось отслеживать исключения Greeze: ${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Вывод сервиса в foreground с динамическим набором FGS-типов.
     * Тип location включается только когда он законен: разрешение на геолокацию
     * выдано И (приложение на переднем плане ИЛИ есть фоновая геолокация).
     * Иначе — SecurityException, а краш onCreate убивает весь процесс
     * (инцидент 2026-09: система отозвала runtime-разрешения по «неиспользуемости»,
     * и каждый запуск сервиса ронял приложение).
     * При отказе — повтор без типа location; если и он невозможен — false.
     */
    private fun goForeground(what: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIF_STATUS, buildNotification("Мониторинг VoWiFi активен"))
            return true
        }
        val notification = buildNotification("Мониторинг VoWiFi активен")
        val types = foregroundServiceTypes()
        try {
            startForeground(NOTIF_STATUS, notification, types)
            return true
        } catch (t: Throwable) {
            if (types != ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
                EventLog.add(
                    "FGS с типом location отклонён ($what: ${t.javaClass.simpleName}: ${t.message}) — повтор без него"
                )
                return try {
                    startForeground(
                        NOTIF_STATUS, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    true
                } catch (t2: Throwable) {
                    EventLog.add("Не удалось вывести сервис в foreground ($what: ${t2.javaClass.simpleName}: ${t2.message}) — останов")
                    false
                }
            }
            EventLog.add("Не удалось вывести сервис в foreground ($what: ${t.javaClass.simpleName}: ${t.message}) — останов")
            return false
        }
    }

    /** Допустимые сейчас FGS-типы: specialUse всегда, location — только при наличии прав и состояния. */
    private fun foregroundServiceTypes(): Int {
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val locGranted =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!locGranted) return base
        // «При использовании» не действует для фонового процесса: для запуска из фона
        // (boot/watchdog) тип location легален только с фоновой геолокацией.
        val bgLocGranted =
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return if (bgLocGranted || isInForeground()) {
            base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            base
        }
    }

    private fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Проверка сразу при смене/переконфигурации Wi-Fi сети. */
    private fun registerWifiCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = triggerCheck("подключение к Wi-Fi")
            override fun onLost(network: Network) = triggerCheck("потеря Wi-Fi")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                triggerCheck("изменение параметров сети")
        }
        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (t: Throwable) {
            EventLog.add("Не удалось зарегистрировать network callback: ${t.message}")
        }
    }

    private fun triggerCheck(reason: String) {
        lastNetworkEventAt = System.currentTimeMillis()
        lastNetworkEventReason = reason
        scope.launch {
            if (checkInFlight) return@launch
            // При роуминге onCapabilitiesChanged штормит десятки раз в минуту —
            // одинаковые события логируем не чаще раза в окно, иначе кольцевой
            // журнал заполняется шумом и теряет историю.
            val now = System.currentTimeMillis()
            if (reason != lastNetEventLoggedReason ||
                now - lastNetEventLoggedAt > NETWORK_EVENT_WINDOW_MS
            ) {
                lastNetEventLoggedAt = now
                lastNetEventLoggedReason = reason
                EventLog.add("Внеплановая проверка: $reason")
            }
            checkOnce(countForRemediation = false)
        }
    }

    private suspend fun monitorLoop() {
        while (true) {
            Log.i("vowifi-watch", "monitorLoop tick")
            checkOnce(countForRemediation = true)
            if (prefs.keepaliveEnabled) {
                withContext(Dispatchers.IO) { keepaliveCheck() }
            }
            delay(prefs.intervalSec.coerceAtLeast(15) * 1_000L)
        }
    }

    /**
     * Лёгкий TCP-connect, чтобы Wi-Fi-ассоциация оставалась активной (не даёт точке
     * «уснуть» по idle-таймауту). IPSec-keepalive ePDG-туннеля делает сам стек —
     * это не замена NAT-keepalive, а будильник для Wi-Fi-линка.
     */
    @Suppress("DEPRECATION") // allNetworks — самый простой способ найти Wi-Fi-интерфейс
    private fun keepaliveCheck() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val wifiNet = cm?.allNetworks?.firstOrNull {
                cm.getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val socket = wifiNet?.socketFactory?.createSocket() ?: java.net.Socket()
            socket.use { it.connect(java.net.InetSocketAddress("1.1.1.1", 80), 3_000) }
        } catch (t: Throwable) {
            EventLog.add("Keepalive: нет связности по Wi-Fi")
        }
    }

    private fun checkMonitoringPermissions() {
        val missing = buildList {
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .map { it.substringAfterLast('.') }
        val previous = missingMonitorPermissions
        if (missing == previous) return
        missingMonitorPermissions = missing

        if (missing.isEmpty()) {
            if (previous != null) {
                EventLog.add("Разрешения мониторинга восстановлены")
                nm?.cancel(NOTIF_PERMISSIONS)
            }
            return
        }

        val names = missing.joinToString()
        EventLog.add("Мониторинг ограничен: разрешения отозваны ($names)")
        val openApp = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, Remediator.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Мониторинг VoWiFi ограничен")
            .setContentText("Откройте приложение и восстановите разрешения: $names")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            nm?.notify(NOTIF_PERMISSIONS, notification)
        } catch (t: Throwable) {
            EventLog.add("Не удалось показать предупреждение о разрешениях (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /**
     * @param countForRemediation false для внеплановых проверок по событиям сети:
     * они обновляют состояние/статистику, но НЕ инкрементируют счётчик и не запускают
     * remediation — иначе «шторм» callback-ов при переподключении Wi-Fi набирал
     * confirmChecks за секунды на транзиентах.
     */
    private suspend fun checkOnce(countForRemediation: Boolean) {
        if (checkInFlight) return
        checkInFlight = true
        try {
            checkMonitoringPermissions()
            val now = System.currentTimeMillis()
            if (now - lastGreezeExemptionCheckAt >= GREEZE_EXEMPTION_VERIFY_MS) {
                lastGreezeExemptionCheckAt = now
                withContext(Dispatchers.IO) { remediator.ensureGreezeExemption() }
            }
            val ws = wifiMonitor.currentState()
            val trusted = wifiMonitor.isTrustedGood(ws, prefs)
            val rawIms = withContext(Dispatchers.IO) { ImsMonitor.getVoiceWifiStatus(this@MonitorService) }
            val ims = verifyRegisteredByRadio(rawIms)

            MonitorState.ssid = ws.ssid
            MonitorState.rssi = ws.rssi
            MonitorState.trustedGood = trusted
            MonitorState.ims = ims
            MonitorState.lastCheck = System.currentTimeMillis()

            val smoothed = wifiMonitor.lastSmoothedRssi
            val rssiText = if (ws.connected && smoothed != null) {
                "${ws.rssi} dBm (сгл. ${smoothed.roundToInt()})"
            } else {
                "${ws.rssi} dBm"
            }
            val summary = "Проверка: ${ws.ssid ?: "нет сети"} $rssiText, " +
                "доверенная=${if (trusted) "да" else "нет"}, VoWiFi=$ims"
            EventLog.add(summary)
            Log.i("vowifi-watch", summary)

            trackImsTransitions(ims, ws, trusted)
            checkWatchdog(ims, trusted)

            when (ims) {
                ImsMonitor.ImsStatus.REGISTERED -> {
                    consecutiveNotRegistered = 0
                    remediator.resetEscalation()
                }
                ImsMonitor.ImsStatus.UNKNOWN -> {
                    // Не уверены в состоянии — remediation не выполняем
                    consecutiveNotRegistered = 0
                }
                ImsMonitor.ImsStatus.NOT_REGISTERED -> {
                    if (!trusted || !countForRemediation) {
                        if (!trusted) consecutiveNotRegistered = 0
                        return
                    }
                    consecutiveNotRegistered++
                    EventLog.add(
                        "VoWiFi не активен в доверенной сети с хорошим сигналом " +
                            "($consecutiveNotRegistered/${prefs.confirmChecks})"
                    )
                    if (consecutiveNotRegistered >= prefs.confirmChecks) {
                        // Verify-before-act: сверяемся с authoritative-источником
                        // (dumpsys telephony.registry по локальному ADB), прежде чем
                        // дёргать радиотракт — защита от ложных срабатываний.
                        if (remediator.groundTruthVowifiActive() == true) {
                            EventLog.add("Ложная тревога: dumpsys показывает IWLAN, remediation отменён")
                            consecutiveNotRegistered = 0
                            return
                        }
                        consecutiveNotRegistered = 0
                        remediator.remediate()
                        // Ждём поднятия IMS и перепроверяем
                        delay(45_000)
                        val rawAfter = withContext(Dispatchers.IO) {
                            ImsMonitor.getVoiceWifiStatus(this@MonitorService)
                        }
                        val after = verifyRegisteredByRadio(rawAfter, force = true)
                        MonitorState.ims = after
                        trackImsTransitions(after, ws, trusted)
                        if (after == ImsMonitor.ImsStatus.REGISTERED) {
                            EventLog.add("VoWiFi восстановлен после remediation")
                            remediator.resetEscalation()
                        } else {
                            EventLog.add("После remediation VoWiFi: $after")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            EventLog.add("Ошибка цикла проверки (${t.javaClass.simpleName}: ${t.message})")
        } finally {
            checkInFlight = false
        }
    }

    /** Снимает ложный REGISTERED при выключенном переключателе VoWiFi или по radio-логу. */
    private suspend fun verifyRegisteredByRadio(
        status: ImsMonitor.ImsStatus,
        force: Boolean = false
    ): ImsMonitor.ImsStatus {
        if (status != ImsMonitor.ImsStatus.REGISTERED) {
            lastRegisteredRadioCheckAt = 0L
            lastWfcSettingCheckAt = 0L
            return status
        }
        val now = System.currentTimeMillis()
        if (force || now - lastWfcSettingCheckAt >= WFC_SETTING_VERIFY_MS) {
            lastWfcSettingCheckAt = now
            if (remediator.voWiFiSettingEnabled() == false) {
                EventLog.add("Переключатель VoWiFi выключен — статус скорректирован")
                return ImsMonitor.ImsStatus.NOT_REGISTERED
            }
        }

        val due = force || prevIms != ImsMonitor.ImsStatus.REGISTERED ||
            now - lastRegisteredRadioCheckAt >= REGISTERED_RADIO_VERIFY_MS
        if (!due) return status
        lastRegisteredRadioCheckAt = now

        val explain = prevIms != ImsMonitor.ImsStatus.REGISTERED
        return when (remediator.radioImsRegistered()) {
            false -> {
                if (explain) {
                    EventLog.add("Radio-проверка IMS: isImsRegistered=false — статус скорректирован")
                }
                ImsMonitor.ImsStatus.NOT_REGISTERED
            }
            true -> {
                if (explain) EventLog.add("Radio-проверка IMS: isImsRegistered=true")
                status
            }
            null -> {
                if (explain) EventLog.add("Radio-проверка IMS недоступна — используется ServiceState")
                status
            }
        }
    }

    /**
     * Статистика сессий VoWiFi: переход в REGISTERED — новая сессия;
     * REGISTERED -> NOT_REGISTERED в доверенной сети — «слёт» с классификацией причины;
     * возврат в REGISTERED после открытого слёта — восстановление (засекаем длительность).
     * UNKNOWN lastRegisteredAt не трогает (не уверены в состоянии).
     */
    private fun trackImsTransitions(
        ims: ImsMonitor.ImsStatus,
        ws: WifiMonitor.WifiState,
        trusted: Boolean
    ) {
        val now = System.currentTimeMillis()

        if (ims == ImsMonitor.ImsStatus.REGISTERED) prefs.lastSeenRegisteredAt = now

        if (ims == ImsMonitor.ImsStatus.REGISTERED && prevIms != ImsMonitor.ImsStatus.REGISTERED) {
            prefs.statSessions += 1
            prefs.lastRegisteredAt = now
            if (dropOpenedAt > 0) {
                val durationMs = now - dropOpenedAt
                prefs.statRecoveries += 1
                prefs.statRecoveryTotalMs += durationMs
                EventLog.add("VoWiFi восстановлен за ${durationMs / 1000} с")
                dropOpenedAt = 0
            }
        }

        if (prevIms == ImsMonitor.ImsStatus.REGISTERED &&
            ims == ImsMonitor.ImsStatus.NOT_REGISTERED && trusted
        ) {
            prefs.statDrops += 1
            prefs.statLastDropAt = now
            dropOpenedAt = now
            val reason = when {
                !ws.connected -> "потеря Wi-Fi"
                now - lastNetworkEventAt <= NETWORK_EVENT_WINDOW_MS ->
                    "событие сети: $lastNetworkEventReason"
                else -> "IMS слетел при стабильном Wi-Fi (RSSI ${ws.rssi} dBm)"
            }
            prefs.statLastDropReason = reason
            val sessionMin = if (prefs.lastRegisteredAt > 0) (now - prefs.lastRegisteredAt) / 60_000L else 0
            EventLog.add("Слёт VoWiFi: $reason, сессия длилась $sessionMin мин")
        }

        prevIms = ims
    }

    /** Watchdog: VoWiFi длительное время отсутствует в доверенной сети несмотря на remediation. */
    private fun checkWatchdog(ims: ImsMonitor.ImsStatus, trusted: Boolean) {
        val now = System.currentTimeMillis()
        val hours = prefs.watchdogHours.coerceAtLeast(1)
        if (!trusted || ims != ImsMonitor.ImsStatus.NOT_REGISTERED) return
        // lastSeenRegisteredAt — основная метка; lastRegisteredAt — фолбэк для
        // установок, обновлённых с версии без неё.
        val lastSeen = prefs.lastSeenRegisteredAt.takeIf { it > 0 } ?: prefs.lastRegisteredAt
        if (lastSeen <= 0) return
        if (now - lastSeen <= hours * 3_600_000L) return
        if (now - prefs.lastWatchdogNotifiedAt <= WATCHDOG_REPEAT_MS) return

        prefs.lastWatchdogNotifiedAt = now
        EventLog.add("Watchdog: VoWiFi отсутствует более $hours ч в доверенной сети")
        val openApp = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Remediator.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("VoWiFi отсутствует длительное время")
            .setContentText(
                "Более $hours ч без VoWiFi в доверенной сети несмотря на восстановление. " +
                    "Возможна проблема уровня модема (MBN-профиль) или оператора."
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        nm?.notify(NOTIF_WATCHDOG, n)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("vowifi-watch")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
