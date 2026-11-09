package dev.belsky.vowifiwatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Страховочный будильник на случай гибели процесса мониторинга.
 * HyperOS периодически выгружает приложения даже с foreground service, и при таком
 * «жёстком» убийстве START_STICKY сервис не перезапускает — раз в
 * [WATCHDOG_INTERVAL_MS] проверяем, жив ли сервис, и поднимаем его заново.
 * Будильник цепочечный (каждое срабатывание ставит следующее), интервал неточный:
 * setAndAllowWhileIdle не требует SCHEDULE_EXACT_ALARM, который на targetSdk 34
 * по умолчанию не выдан. Когда мониторинг выключен (prefs.enabled == false),
 * цепочка не продолжается.
 *
 * Ограничение: будильник не сработает, если приложение в состоянии force-stop
 * (HyperOS «Ограничить фоновую активность» делает именно это) — поэтому на Xiaomi
 * обязательны «Автозапуск» и «Контроль активности → Без ограничений»
 * (кнопка на главном экране).
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
        private const val REQUEST_CODE = 42

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                    pendingIntent(context)
                )
            } catch (t: Throwable) {
                EventLog.add("Watchdog: не удалось поставить будильник (${t.javaClass.simpleName}: ${t.message})")
            }
        }

        fun cancel(context: Context) {
            try {
                context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
            } catch (_: Throwable) {
            }
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        if (!Prefs(context).enabled) return // мониторинг выключен — цепочка будильника обрывается
        // Если процесс был убит, MonitorState.running сброшен — сервис надо поднять заново.
        if (!MonitorState.running) {
            try {
                context.startForegroundService(
                    Intent(context, MonitorService::class.java)
                        .putExtra(MonitorService.EXTRA_REASON, "watchdog")
                )
                EventLog.add("Watchdog: сервис мониторинга был мёртв — перезапущен")
            } catch (t: Throwable) {
                EventLog.add("Watchdog: перезапуск не удался (${t.javaClass.simpleName}: ${t.message})")
            }
        }
        schedule(context)
    }
}
