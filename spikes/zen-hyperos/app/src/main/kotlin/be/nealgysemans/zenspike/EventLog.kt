package be.nealgysemans.zenspike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One timestamped line in the on-screen diagnostic log. */
data class LogEntry(
    val seq: Long,
    val atMillis: Long,
    val kind: Kind,
    val tag: String,
    val message: String,
) {
    enum class Kind { ACTION, RESULT, ERROR, BROADCAST, INFO }

    val clock: String get() = TIME_FORMAT.format(Date(atMillis))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Process-wide append-only event log. A singleton so that broadcast receivers and
 * the notification poster (which have no access to the Compose tree) can write to
 * the same log the UI renders.
 */
object EventLog {
    private const val LOGCAT_TAG = "ZenSpike"
    private const val MAX_ENTRIES = 500

    private var nextSeq = 0L
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun action(tag: String, message: String) = add(LogEntry.Kind.ACTION, tag, message)
    fun result(tag: String, message: String) = add(LogEntry.Kind.RESULT, tag, message)
    fun error(tag: String, message: String) = add(LogEntry.Kind.ERROR, tag, message)
    fun broadcast(tag: String, message: String) = add(LogEntry.Kind.BROADCAST, tag, message)
    fun info(tag: String, message: String) = add(LogEntry.Kind.INFO, tag, message)

    fun failure(tag: String, t: Throwable) =
        error(tag, "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}")

    fun clear() {
        synchronized(this) { _entries.value = emptyList() }
        info("log", "cleared")
    }

    /** Whole log as text, newest last — for share / adb copy-out. */
    fun asPlainText(): String = _entries.value.asReversed().joinToString("\n") {
        "${it.clock}  ${it.kind.name.take(5).padEnd(5)}  ${it.tag}: ${it.message}"
    }

    private fun add(kind: LogEntry.Kind, tag: String, message: String) {
        val entry = synchronized(this) {
            LogEntry(nextSeq++, System.currentTimeMillis(), kind, tag, message).also {
                // Newest first: the UI renders top-down without reversing.
                _entries.value = (listOf(it) + _entries.value).take(MAX_ENTRIES)
            }
        }
        if (kind == LogEntry.Kind.ERROR) {
            Log.w(LOGCAT_TAG, "[$tag] ${entry.message}")
        } else {
            Log.i(LOGCAT_TAG, "[$tag] ${entry.message}")
        }
    }
}
