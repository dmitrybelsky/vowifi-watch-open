package dev.belsky.vowifiwatch

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Кольцевой журнал событий: последние ~1000 строк.
 * Хранится в памяти + персистится в SharedPreferences (переживает перезапуск процесса).
 */
object EventLog {

    private const val PREFS = "event_log"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 1000

    private val lines = ArrayDeque<String>()
    private var sp: SharedPreferences? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Колбэк для обновления UI (вызывается из произвольного потока). */
    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = sp?.getString(KEY_LINES, "") ?: ""
        lines.addAll(saved.split('\n').filter { it.isNotBlank() })
    }

    @Synchronized
    fun add(message: String) {
        lines.addLast(fmt.format(Date()) + "  " + message)
        while (lines.size > MAX_LINES) lines.removeFirst()
        sp?.edit()?.putString(KEY_LINES, lines.joinToString("\n"))?.apply()
        android.util.Log.i("vowifi-watch", message)
        listener?.invoke()
    }

    @Synchronized
    fun all(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        sp?.edit()?.remove(KEY_LINES)?.apply()
        listener?.invoke()
    }
}
