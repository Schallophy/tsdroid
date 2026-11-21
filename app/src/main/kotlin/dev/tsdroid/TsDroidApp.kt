package dev.tsdroid

import android.app.Application

/**
 * Installs a crash recorder before any UI exists.
 *
 * The default handler is kept in the chain: the system still kills the process
 * and still shows "app keeps stopping", so the user experience is unchanged —
 * the only difference is that the trace is now on disk and readable from the
 * app's own settings screen.
 */
class TsDroidApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // A breadcrumb with no stack trace means the process died somewhere Java
        // could not see it: a Rust abort, an OOM kill, or a force stop. Turn it
        // into a readable entry so the crash dialog has something to say.
        val unfinished = CrashLog.readPendingPhase(this)
        if (unfinished != null && CrashLog.read(this) == null) {
            CrashLog.record(
                this,
                IllegalStateException(
                    "上次运行没有正常结束，且没有 Java 异常堆栈。\n" +
                        "可能是原生层崩溃、被系统回收、或者被手动强制停止。\n" +
                        "最后已知阶段：$unfinished",
                ),
            )
        }
        CrashLog.clearPhase(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(this, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
