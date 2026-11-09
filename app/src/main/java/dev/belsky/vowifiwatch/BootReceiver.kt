package dev.belsky.vowifiwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Перезапуск сервиса мониторинга после перезагрузки, если он был включён. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EventLog.init(context)
        if (!Prefs(context).enabled) {
            EventLog.add("Загрузка ОС: мониторинг выключен, автозапуск пропущен")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, MonitorService::class.java).putExtra(MonitorService.EXTRA_REASON, "boot")
            )
            EventLog.add("Загрузка ОС: сервис мониторинга запущен автоматически")
        } catch (t: Throwable) {
            EventLog.add("Загрузка ОС: не удалось запустить сервис (${t.javaClass.simpleName}: ${t.message})")
        }
        // Страховка на случай, если запуск не удался или сервис позже будет убит
        WatchdogReceiver.schedule(context)
    }
}
