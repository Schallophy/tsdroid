package dev.tsdroid

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last uncaught exception, on disk.
 *
 * Voice clients crash in places that are hard to reproduce on a desk — a real
 * server, a real headset, a real network. Asking someone to attach a debugger is
 * not an option, so the app writes the stack trace itself and shows it on the
 * next launch. One file, overwritten every time: the interesting crash is always
 * the latest one.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "crash_breadcrumb"
    private const val KEY_PHASE = "phase"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Records what the app was doing, so a native SIGSEGV or a process the system
     * killed still leaves a trace.
     *
     * A Java stack trace is only written for exceptions that unwind through
     * Java. A Rust abort, an OOM kill or a swipe-away never reaches that handler
     * — and "it closed while connecting" is exactly the report those produce.
     */
    fun markPhase(context: Context, phase: String) {
        runCatching {
            prefs(context).edit().putString(KEY_PHASE, phase).apply()
        }
    }

    fun clearPhase(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_PHASE).apply() }
    }

    /** The phase of a run that never finished, or null when the last run was clean. */
    fun readPendingPhase(context: Context): String? =
        runCatching { prefs(context).getString(KEY_PHASE, null) }.getOrNull()

    fun record(context: Context, error: Throwable) {
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("time   $stamp")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis   ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread ${Thread.currentThread().name}")
            appendLine()
        }

        // Failing to write a crash log must never make things worse: this runs on
        // the way down, inside the handler that is already reporting a crash.
        runCatching { file(context).writeText(header + trace) }
    }

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
