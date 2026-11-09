package dev.belsky.vowifiwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.belsky.vowifiwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adb: AdbShellExecutor
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** Последний результат проверки локального ADB (для строки статуса). */
    private var adbLastError: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        prefs = Prefs(this)
        adb = AdbShellExecutor(this)
        ImsMonitor.ensureHiddenApiAccess()

        // Разовая миграция: старый дефолт шага C рвал мобильные данные — сбрасываем на пустой
        if (prefs.customCommand == "svc data disable && sleep 3 && svc data enable") {
            prefs.customCommand = ""
            EventLog.add("Шаг C: старая команда по умолчанию (svc data disable) сброшена — она рвёт мобильные данные")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonToggle.setOnClickListener { toggleService() }
        binding.buttonPermissions.setOnClickListener { requestAllPermissions() }
        binding.buttonBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.buttonXiaomi.setOnClickListener { openXiaomiBackgroundSettings() }
        binding.buttonSave.setOnClickListener { saveSettings() }
        binding.buttonClearLog.setOnClickListener { EventLog.clear() }

        // Баннер «текущая сеть не в доверенных» — добавление в один тап, только из приложения
        binding.buttonSuggestAdd.setOnClickListener {
            MonitorState.ssid?.let {
                TrustedNetworks.addToTrusted(this, it)
                binding.editTrustedSsids.setText(prefs.trustedSsidsRaw)
                refreshSuggestBanner()
            }
        }
        binding.buttonSuggestDecline.setOnClickListener {
            MonitorState.ssid?.let {
                TrustedNetworks.decline(this, it)
                refreshSuggestBanner()
            }
        }
        binding.buttonAdbCheck.setOnClickListener { checkAdb() }
        binding.buttonAdbPair.setOnClickListener { pairAdb() }
        binding.buttonAdbDevSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (t: Throwable) {
                EventLog.add("Не удалось открыть настройки разработчика: ${t.message}")
            }
        }
        binding.buttonClearDeclined.setOnClickListener {
            prefs.declinedSsidsRaw = ""
            EventLog.add("Список «не предлагать» очищен")
        }

        EventLog.listener = { runOnUiThread { refreshLog() } }

        loadSettings()
        refreshLog()
        refreshStatus()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Остановить мониторинг из adb: --ez stop true
        if (intent?.getBooleanExtra("stop", false) == true) {
            prefs.enabled = false
            WatchdogReceiver.cancel(this)
            stopService(Intent(this, MonitorService::class.java))
            EventLog.add("Мониторинг остановлен (adb stop)")
        }

        // Автозапуск мониторинга: adb shell am start -n dev.belsky.vowifiwatch/.MainActivity --ez autostart true
        if (intent?.getBooleanExtra("autostart", false) == true && !MonitorState.running) {
            prefs.enabled = true
            try {
                startForegroundService(
                    Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_REASON, "autostart")
                )
                WatchdogReceiver.schedule(this)
                EventLog.add("Мониторинг запущен автоматически (autostart)")
            } catch (t: Throwable) {
                EventLog.add("Autostart: не удалось запустить сервис (${t.javaClass.simpleName}: ${t.message})")
            }
        }

        // Задать доверенные SSID из adb: --es ssid "SSID1;SSID2"
        intent?.getStringExtra("ssid")?.let {
            prefs.trustedSsidsRaw = it
            EventLog.add("Доверенные SSID установлены: $it")
        }

        // Добавить одну сеть в доверенные из adb: --es add_trusted "SSID"
        intent?.getStringExtra("add_trusted")?.let {
            TrustedNetworks.addToTrusted(this, it)
            binding.editTrustedSsids.setText(prefs.trustedSsidsRaw)
        }

        // Внедрить авторизованный adb-ключ (base64 PEM): --es adbkey_b64 "..."
        intent?.getStringExtra("adbkey_b64")?.let { b64 ->
            try {
                val dir = java.io.File(filesDir, ".android").apply { mkdirs() }
                java.io.File(dir, "adbkey").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                EventLog.add("ADB-ключ установлен из intent-extra")
            } catch (t: Throwable) {
                EventLog.add("ADB-ключ: ошибка записи (${t.javaClass.simpleName}: ${t.message})")
            }
        }

        // Задать порт беспроводной отладки: --ei adb_port N
        if (intent?.hasExtra("adb_port") == true) {
            val port = intent.getIntExtra("adb_port", 0)
            if (port in 1..65535) {
                prefs.adbPort = port
                EventLog.add("ADB-порт сохранён: $port")
            }
        }

        // Принудительный прогон конкретного шага: --ei test_step 3 (IMS), 0|1|2 (A/B/C)
        if (intent?.hasExtra("test_step") == true) {
            val step = intent.getIntExtra("test_step", -1)
            CoroutineScope(Dispatchers.Default).launch { Remediator(this@MainActivity).debugRunStep(step) }
        }

        // Ручной прогон remediation для теста: --ez test_remediate true
        if (intent?.getBooleanExtra("test_remediate", false) == true) {
            EventLog.add("Тест: ручной запуск remediation")
            CoroutineScope(Dispatchers.Default).launch { Remediator(this@MainActivity).remediate() }
        }

        // Восстановление данных из бэкапа при переустановке: run-as на HyperOS 3
        // читает данные приложения, но не пишет (shell в чужом mount-namespace),
        // поэтому файлы пишет сам процесс. После записи процесс убивается —
        // иначе in-memory SharedPreferences затрёт восстановленный файл при flush.
        var restored = false
        intent?.getStringExtra("restore_settings_b64")?.let { b64 ->
            restored = restoreBackupFile("shared_prefs/settings.xml", b64, "настройки") || restored
        }
        intent?.getStringExtra("restore_eventlog_b64")?.let { b64 ->
            restored = restoreBackupFile("shared_prefs/event_log.xml", b64, "журнал") || restored
        }
        if (restored) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun restoreBackupFile(relPath: String, b64: String, what: String): Boolean {
        return try {
            val f = java.io.File(applicationInfo.dataDir, relPath)
            f.parentFile?.mkdirs()
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            true
        } catch (t: Throwable) {
            EventLog.add("Восстановление ($what): ошибка (${t.javaClass.simpleName}: ${t.message})")
            false
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        if (prefs.enabled) {
            // Автовзвод страховок при ручном запуске: после свайпа из «недавних» HyperOS
            // снимает watchdog-будильник (инцидент 2026-10-01, FINDINGS 5б) — без перевзвода
            // мониторинг мёртв до перезагрузки. Будильник перевзводим всегда, мёртвый
            // сервис поднимаем. Заодно сервис пересчитает FGS-типы: на переднем плане
            // тип location легален даже с геолокацией «при использовании».
            WatchdogReceiver.schedule(this)
            val wasRunning = MonitorState.running
            try {
                startForegroundService(
                    Intent(this, MonitorService::class.java)
                        .putExtra(
                            MonitorService.EXTRA_REASON,
                            if (wasRunning) MonitorService.REASON_UI_FOREGROUND else "ui-rearm"
                        )
                )
                if (!wasRunning) {
                    EventLog.add("Сервис был мёртв — перезапущен при открытии приложения")
                }
            } catch (t: Throwable) {
                EventLog.add("Не удалось поднять сервис при открытии (${t.javaClass.simpleName}: ${t.message})")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        // Автосохранение: если значения в полях отличаются от сохранённых — сохранить тихо
        if (settingsDirty()) {
            saveSettings(log = false)
            EventLog.add("Настройки сохранены автоматически")
        }
    }

    /** true, если хоть одно поле настроек отличается от сохранённого в Prefs. */
    private fun settingsDirty(): Boolean {
        return binding.editTrustedSsids.text.toString() != prefs.trustedSsidsRaw ||
            binding.editRssiThreshold.text.toString() != prefs.rssiThreshold.toString() ||
            binding.editRssiHysteresis.text.toString() != prefs.rssiHysteresisDb.toString() ||
            binding.editInterval.text.toString() != prefs.intervalSec.toString() ||
            binding.editConfirmChecks.text.toString() != prefs.confirmChecks.toString() ||
            binding.editCooldown.text.toString() != prefs.cooldownMin.toString() ||
            binding.editMaxAttempts.text.toString() != prefs.maxAttempts.toString() ||
            binding.editMaxAttemptsDay.text.toString() != prefs.maxAttemptsDay.toString() ||
            binding.switchGuardInteractive.isChecked != prefs.guardInteractive ||
            binding.switchKeepalive.isChecked != prefs.keepaliveEnabled ||
            binding.editWatchdogHours.text.toString() != prefs.watchdogHours.toString() ||
            binding.switchStepA.isChecked != prefs.stepAEnabled ||
            binding.switchStepB.isChecked != prefs.stepBEnabled ||
            binding.switchStepC.isChecked != prefs.stepCEnabled ||
            binding.editCustomCommand.text.toString() != prefs.customCommand ||
            binding.switchUseLocalAdb.isChecked != prefs.useLocalAdb ||
            binding.switchAutoReviveAdb.isChecked != prefs.autoReviveAdb
    }

    override fun onDestroy() {
        super.onDestroy()
        EventLog.listener = null
    }

    // --- Сервис ---

    private fun toggleService() {
        if (prefs.enabled) {
            prefs.enabled = false
            WatchdogReceiver.cancel(this)
            stopService(Intent(this, MonitorService::class.java))
            EventLog.add("Мониторинг остановлен пользователем")
        } else {
            prefs.enabled = true
            try {
                startForegroundService(
                    Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_REASON, "user")
                )
                WatchdogReceiver.schedule(this)
                EventLog.add("Мониторинг запущен пользователем")
            } catch (t: Throwable) {
                EventLog.add("Не удалось запустить сервис (${t.javaClass.simpleName}: ${t.message})")
            }
        }
        refreshStatus()
    }

    // --- Разрешения ---

    private fun requestAllPermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.READ_PRECISE_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            EventLog.add("Все runtime-разрешения уже выданы")
        } else {
            EventLog.add("Запрос разрешений: ${missing.joinToString { it.substringAfterLast('.') }}")
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            EventLog.add("Разрешение ${p.substringAfterLast('.')}: ${if (granted) "выдано" else "ОТКАЗАНО"}")
        }
        refreshStatus()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            EventLog.add("Открыт системный диалог исключения из оптимизации батареи")
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                EventLog.add("Открыт список оптимизации батареи (диалог недоступен)")
            } catch (t2: Throwable) {
                EventLog.add("Не удалось открыть настройки батареи: ${t2.message}")
            }
        }
    }

    /**
     * HyperOS/MIUI выгружает фоновые процессы по своим правилам, независимо от
     * исключения из Doze: нужны «Автозапуск» и «Контроль активности → Без ограничений».
     * Программно это не выставить — открываем системные экраны (intent'ы Xiaomi не
     * задокументированы, поэтому с fallback на страницу приложения в настройках).
     */
    private fun openXiaomiBackgroundSettings() {
        EventLog.add("Xiaomi: для vowifi-watch включите «Автозапуск» и «Контроль активности → Без ограничений»")
        val intents = listOf(
            // Экран управления автозапуском (MIUI/HyperOS)
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // Страница приложения: там «Контроль активности» и остальные переключатели
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Throwable) {
            }
        }
        EventLog.add("Не удалось открыть настройки Xiaomi")
    }

    // --- Настройки ---

    /** Runtime-разрешения, без которых мониторинг деградирует (SSID/статус IMS/уведомления). */
    private fun missingRuntimePermissions(): List<String> {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .map { it.substringAfterLast('.') }
    }

    private fun loadSettings() {
        binding.editTrustedSsids.setText(prefs.trustedSsidsRaw)
        binding.editRssiThreshold.setText(prefs.rssiThreshold.toString())
        binding.editRssiHysteresis.setText(prefs.rssiHysteresisDb.toString())
        binding.editInterval.setText(prefs.intervalSec.toString())
        binding.editConfirmChecks.setText(prefs.confirmChecks.toString())
        binding.editCooldown.setText(prefs.cooldownMin.toString())
        binding.editMaxAttempts.setText(prefs.maxAttempts.toString())
        binding.editMaxAttemptsDay.setText(prefs.maxAttemptsDay.toString())
        binding.switchGuardInteractive.isChecked = prefs.guardInteractive
        binding.switchKeepalive.isChecked = prefs.keepaliveEnabled
        binding.editWatchdogHours.setText(prefs.watchdogHours.toString())
        binding.switchStepA.isChecked = prefs.stepAEnabled
        binding.switchStepB.isChecked = prefs.stepBEnabled
        binding.switchStepC.isChecked = prefs.stepCEnabled
        binding.editCustomCommand.setText(prefs.customCommand)
        binding.switchUseLocalAdb.isChecked = prefs.useLocalAdb
        binding.switchAutoReviveAdb.isChecked = prefs.autoReviveAdb
    }

    private fun saveSettings(log: Boolean = true) {
        prefs.trustedSsidsRaw = binding.editTrustedSsids.text.toString()
        prefs.rssiThreshold = binding.editRssiThreshold.text.toString().toIntOrNull() ?: -67
        prefs.rssiHysteresisDb = binding.editRssiHysteresis.text.toString().toIntOrNull() ?: 3
        prefs.intervalSec = binding.editInterval.text.toString().toIntOrNull() ?: 60
        prefs.confirmChecks = binding.editConfirmChecks.text.toString().toIntOrNull() ?: 3
        prefs.cooldownMin = binding.editCooldown.text.toString().toIntOrNull() ?: 15
        prefs.maxAttempts = binding.editMaxAttempts.text.toString().toIntOrNull() ?: 3
        prefs.maxAttemptsDay = binding.editMaxAttemptsDay.text.toString().toIntOrNull() ?: 2
        prefs.guardInteractive = binding.switchGuardInteractive.isChecked
        prefs.keepaliveEnabled = binding.switchKeepalive.isChecked
        prefs.watchdogHours = binding.editWatchdogHours.text.toString().toIntOrNull() ?: 6
        prefs.stepAEnabled = binding.switchStepA.isChecked
        prefs.stepBEnabled = binding.switchStepB.isChecked
        prefs.stepCEnabled = binding.switchStepC.isChecked
        prefs.customCommand = binding.editCustomCommand.text.toString()
        prefs.useLocalAdb = binding.switchUseLocalAdb.isChecked
        prefs.autoReviveAdb = binding.switchAutoReviveAdb.isChecked
        if (log) EventLog.add("Настройки сохранены")
    }

    // --- Локальный ADB ---

    private fun checkAdb() {
        EventLog.add("ADB: проверка подключения…")
        binding.buttonAdbCheck.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val ok = adb.isAvailable()
            adbLastError = if (ok) null else "нет подключения"
            EventLog.add(
                if (ok) "ADB: подключение успешно, uid=2000 (порт ${prefs.adbPort})"
                else "ADB: подключение не удалось (сопряжение: ${if (adb.isPaired()) "есть" else "нет"}, порт: ${prefs.adbPort.takeIf { it > 0 } ?: "неизвестен"})"
            )
            binding.buttonAdbCheck.isEnabled = true
            refreshStatus()
        }
    }

    private fun pairAdb() {
        val host = binding.editAdbHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = binding.editAdbPairPort.text.toString().toIntOrNull()
        val code = binding.editAdbPairCode.text.toString().trim()
        if (port == null || code.length != 6) {
            EventLog.add("ADB-сопряжение: укажите порт сопряжения и 6-значный код из диалога «Беспроводной отладки»")
            return
        }
        EventLog.add("ADB: сопряжение с $host:$port…")
        binding.buttonAdbPair.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val ok = adb.pair(host, port, code)
            EventLog.add(
                if (ok) "ADB: сопряжение успешно (порт подключения: ${prefs.adbPort.takeIf { it > 0 } ?: "найдётся при первом подключении"})"
                else "ADB: сопряжение не удалось (проверьте код — он одноразовый, и порт сопряжения ≠ порт подключения)"
            )
            binding.buttonAdbPair.isEnabled = true
            refreshStatus()
        }
    }

    // --- Отображение ---

    private fun refreshStatus() {
        binding.buttonToggle.text =
            if (prefs.enabled) "Остановить мониторинг" else "Запустить мониторинг"

        val imsText = when (MonitorState.ims) {
            ImsMonitor.ImsStatus.REGISTERED -> "активен"
            ImsMonitor.ImsStatus.NOT_REGISTERED -> "не активен"
            ImsMonitor.ImsStatus.UNKNOWN -> "неизвестен"
        }
        val lastCheck = if (MonitorState.lastCheck > 0)
            timeFmt.format(Date(MonitorState.lastCheck)) else "—"

        val batteryIgnored = try {
            getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Throwable) {
            false
        }

        binding.textStatus.text = buildString {
            appendLine("Сервис: ${if (MonitorState.running) "работает" else "остановлен"}")
            if (prefs.enabled && !MonitorState.running) {
                appendLine("ВНИМАНИЕ: мониторинг включён, но процесс убит системой — watchdog перезапустит его (до 15 мин)")
            } else if (MonitorState.running && MonitorState.lastCheck > 0) {
                val staleMs = System.currentTimeMillis() - MonitorState.lastCheck
                val staleThresholdMs = maxOf(3L * prefs.intervalSec, 300) * 1_000L
                if (staleMs > staleThresholdMs) {
                    appendLine("ВНИМАНИЕ: проверок не было ${staleMs / 60_000} мин — система замораживает процесс")
                }
            }
            appendLine("Wi-Fi SSID: ${MonitorState.ssid ?: "—"}")
            appendLine("RSSI: ${if (MonitorState.ssid != null) "${MonitorState.rssi} dBm" else "—"}")
            appendLine("Доверенная сеть с хорошим сигналом: ${if (MonitorState.trustedGood) "да" else "нет"}")
            appendLine("VoWiFi: $imsText")
            appendLine("Последняя проверка: $lastCheck")
            appendLine("Исключение из батареи: ${if (batteryIgnored) "да" else "нет"}")
            val missingPerms = missingRuntimePermissions()
            if (missingPerms.isNotEmpty()) {
                appendLine("ВНИМАНИЕ: разрешения не выданы/отозваны системой: ${missingPerms.joinToString()} — нажмите «Запросить разрешения»")
            }
        }

        binding.textAdbStatus.text = "Статус: " + when {
            !prefs.useLocalAdb -> "отключён в настройках"
            !adb.isPaired() -> "не настроен (нет сопряжения)"
            adbLastError != null -> "ошибка: $adbLastError"
            else -> "сопряжён (порт ${prefs.adbPort.takeIf { it > 0 }?.toString() ?: "неизвестен — найдётся через mDNS"})"
        }

        val recoveries = prefs.statRecoveries
        val avgSec = if (recoveries > 0) "${prefs.statRecoveryTotalMs / recoveries / 1000} с" else "—"
        binding.textStats.text = buildString {
            appendLine(
                "Сессий VoWiFi: ${prefs.statSessions} · Слётов: ${prefs.statDrops} · " +
                    "Восстановлений: $recoveries (ср. $avgSec)"
            )
            if (prefs.statLastDropAt > 0) {
                append(
                    "Последний слёт: ${dateFmt.format(Date(prefs.statLastDropAt))} — " +
                        prefs.statLastDropReason.ifEmpty { "причина не определена" }
                )
            } else {
                append("Последний слёт: не зафиксировано")
            }
        }

        refreshSuggestBanner()
    }

    /** Баннер «текущая сеть не в доверенных»: виден только когда сеть подключена и не в trusted/declined. */
    private fun refreshSuggestBanner() {
        val ssid = MonitorState.ssid
        if (ssid != null && ssid !in prefs.trustedSsids && ssid !in prefs.declinedSsids) {
            binding.suggestText.text = "Текущая сеть «$ssid» не в доверенных"
            binding.suggestRow.visibility = android.view.View.VISIBLE
        } else {
            binding.suggestRow.visibility = android.view.View.GONE
        }
    }

    private fun refreshLog() {
        val lines = EventLog.all()
        binding.textLog.text = lines.joinToString("\n")
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
