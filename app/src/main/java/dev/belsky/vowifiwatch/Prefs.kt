package dev.belsky.vowifiwatch

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения и состояние эскалации (SharedPreferences). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Включён ли фоновый мониторинг (переживает перезагрузку). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Список доверенных SSID через «;» (SSID может содержать запятую); пусто = любая сеть. */
    var trustedSsidsRaw: String
        get() = sp.getString("trusted_ssids", "") ?: ""
        set(v) = sp.edit().putString("trusted_ssids", v).apply()

    val trustedSsids: Set<String>
        get() = trustedSsidsRaw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Сети, для которых пользователь нажал «не предлагать» (через «;»). */
    var declinedSsidsRaw: String
        get() = sp.getString("declined_ssids", "") ?: ""
        set(v) = sp.edit().putString("declined_ssids", v).apply()

    val declinedSsids: Set<String>
        get() = declinedSsidsRaw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Порог «хорошего» сигнала Wi-Fi, dBm. */
    var rssiThreshold: Int
        get() = sp.getInt("rssi_threshold", -67)
        set(v) = sp.edit().putInt("rssi_threshold", v).apply()

    /**
     * Гистерезис порога RSSI, дБ: сеть становится «хорошей» при сигнале ≥ порога,
     * а перестаёт быть таковой только при падении ниже (порог − гистерезис).
     * Защита от дребезга «доверенная да/нет» при сигнале рядом с порогом.
     */
    var rssiHysteresisDb: Int
        get() = sp.getInt("rssi_hysteresis_db", 3)
        set(v) = sp.edit().putInt("rssi_hysteresis_db", v).apply()

    /** Интервал периодической проверки, секунды. */
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 60)
        set(v) = sp.edit().putInt("interval_sec", v).apply()

    /** Сколько подряд проверок «VoWiFi нет» нужно, прежде чем начинать remediation. */
    var confirmChecks: Int
        get() = sp.getInt("confirm_checks", 3)
        set(v) = sp.edit().putInt("confirm_checks", v).apply()

    /** Пауза между попытками восстановления, минуты. */
    var cooldownMin: Int
        get() = sp.getInt("cooldown_min", 15)
        set(v) = sp.edit().putInt("cooldown_min", v).apply()

    /** Максимум попыток подряд, после чего просим пользователя вмешаться вручную. */
    var maxAttempts: Int
        get() = sp.getInt("max_attempts", 3)
        set(v) = sp.edit().putInt("max_attempts", v).apply()

    var stepAEnabled: Boolean
        get() = sp.getBoolean("step_a", true)
        set(v) = sp.edit().putBoolean("step_a", v).apply()

    var stepBEnabled: Boolean
        get() = sp.getBoolean("step_b", true)
        set(v) = sp.edit().putBoolean("step_b", v).apply()

    var stepCEnabled: Boolean
        get() = sp.getBoolean("step_c", false)
        set(v) = sp.edit().putBoolean("step_c", v).apply()

    var customCommand: String
        // Пусто по умолчанию = шаг C отключён. svc data в качестве дефолта недопустим:
        // рвёт мобильные данные, SMS/звонки.
        get() = sp.getString("custom_command", "") ?: ""
        set(v) = sp.edit().putString("custom_command", v).apply()

    /** Не выполнять remediation, пока экран включён (пользователь сам заметит и вмешается). */
    var guardInteractive: Boolean
        get() = sp.getBoolean("guard_interactive", true)
        set(v) = sp.edit().putBoolean("guard_interactive", v).apply()

    /** Дневной лимит попыток восстановления (защита от циклического авиарежима и т.п.). */
    var maxAttemptsDay: Int
        get() = sp.getInt("max_attempts_day", 2)
        set(v) = sp.edit().putInt("max_attempts_day", v).apply()

    /** Попыток израсходовано за текущие сутки. */
    var attemptsDay: Int
        get() = sp.getInt("attempts_day", 0)
        set(v) = sp.edit().putInt("attempts_day", v).apply()

    /** Начало текущих «суток» для дневного лимита (скользящее окно 24 ч). */
    var attemptsDayStart: Long
        get() = sp.getLong("attempts_day_start", 0L)
        set(v) = sp.edit().putLong("attempts_day_start", v).apply()

    /**
     * Автовозврат беспроводной отладки через WRITE_SECURE_SETTINGS (adb_wifi_enabled).
     * Выключен по умолчанию: на HyperOS включение вызывает системные диалоги
     * беспроводной отладки, перехватывающие фокус.
     */
    var autoReviveAdb: Boolean
        get() = sp.getBoolean("auto_revive_adb", false)
        set(v) = sp.edit().putBoolean("auto_revive_adb", v).apply()

    /** Использовать ли локальный ADB (беспроводная отладка, uid 2000) как канал shell-команд. */
    var useLocalAdb: Boolean
        get() = sp.getBoolean("use_local_adb", true)
        set(v) = sp.edit().putBoolean("use_local_adb", v).apply()

    /** Последний известный порт подключения беспроводной отладки (0 = неизвестен, найдёт NSD). */
    var adbPort: Int
        get() = sp.getInt("adb_port", 0)
        set(v) = sp.edit().putInt("adb_port", v).apply()

    /** Лёгкий TCP-keepalive по Wi-Fi раз в интервал проверки (держит ассоциацию активной). */
    var keepaliveEnabled: Boolean
        get() = sp.getBoolean("keepalive_enabled", false)
        set(v) = sp.edit().putBoolean("keepalive_enabled", v).apply()

    /** Watchdog: уведомление, если VoWiFi отсутствует дольше стольких часов в доверенной сети. */
    var watchdogHours: Int
        get() = sp.getInt("watchdog_hours", 6)
        set(v) = sp.edit().putInt("watchdog_hours", v).apply()

    /** Когда последний раз показывали watchdog-уведомление (анти-спам, 24 ч). */
    var lastWatchdogNotifiedAt: Long
        get() = sp.getLong("last_watchdog_notified_at", 0L)
        set(v) = sp.edit().putLong("last_watchdog_notified_at", v).apply()

    // --- Статистика сессий VoWiFi ---

    /** Время последнего перехода в REGISTERED. UNKNOWN его не трогает. */
    var lastRegisteredAt: Long
        get() = sp.getLong("last_registered_at", 0L)
        set(v) = sp.edit().putLong("last_registered_at", v).apply()

    /**
     * Когда любая проверка последний раз видела REGISTERED (обновляется каждой
     * проверкой, не только переходом) — основа для watchdog. lastRegisteredAt для
     * этого не подходит: при неделях стабильного VoWiFi переходов нет, отметка
     * протухает, и первый же краткий слёт выглядит как «отсутствует N часов»
     * (ложный watchdog 2026-10-19).
     */
    var lastSeenRegisteredAt: Long
        get() = sp.getLong("last_seen_registered_at", 0L)
        set(v) = sp.edit().putLong("last_seen_registered_at", v).apply()

    var statSessions: Int
        get() = sp.getInt("stat_sessions", 0)
        set(v) = sp.edit().putInt("stat_sessions", v).apply()

    var statDrops: Int
        get() = sp.getInt("stat_drops", 0)
        set(v) = sp.edit().putInt("stat_drops", v).apply()

    var statRecoveries: Int
        get() = sp.getInt("stat_recoveries", 0)
        set(v) = sp.edit().putInt("stat_recoveries", v).apply()

    /** Суммарное время восстановления после слётов (для среднего). */
    var statRecoveryTotalMs: Long
        get() = sp.getLong("stat_recovery_total_ms", 0L)
        set(v) = sp.edit().putLong("stat_recovery_total_ms", v).apply()

    var statLastDropAt: Long
        get() = sp.getLong("stat_last_drop_at", 0L)
        set(v) = sp.edit().putLong("stat_last_drop_at", v).apply()

    var statLastDropReason: String
        get() = sp.getString("stat_last_drop_reason", "") ?: ""
        set(v) = sp.edit().putString("stat_last_drop_reason", v).apply()

    // --- Состояние эскалации ---

    /** Следующий шаг: 3 = перезапуск IMS, 0 = A (Wi-Fi), 1 = B (авиарежим), 2 = C (команда). */
    var escStep: Int
        get() = sp.getInt("esc_step", 3)
        set(v) = sp.edit().putInt("esc_step", v).apply()

    /** Сколько попыток восстановления выполнено подряд. */
    var escAttempts: Int
        get() = sp.getInt("esc_attempts", 0)
        set(v) = sp.edit().putInt("esc_attempts", v).apply()

    /** Время последней попытки (для cooldown). */
    var escLastTime: Long
        get() = sp.getLong("esc_last_time", 0L)
        set(v) = sp.edit().putLong("esc_last_time", v).apply()
}
