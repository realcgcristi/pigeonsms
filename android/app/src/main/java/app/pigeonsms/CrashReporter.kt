package app.pigeonsms

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Last-ditch crash capture. Samsung/One UI only shows a generic "a bug occurred"
 * dialog with no trace, so we persist the stack ourselves and surface it on the
 * next launch (see MainActivity). Installed first thing in Application.onCreate,
 * so it covers container init + Compose composition crashes.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                appContext.filesDir.resolve(FILE).writeText(
                    buildString {
                        append("PigeonSMS ").append(BuildConfig.VERSION_NAME)
                        append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                        append(Date().toString()).append('\n')
                        append("device: ").append(android.os.Build.MANUFACTURER)
                        append(' ').append(android.os.Build.MODEL)
                        append(", Android ").append(android.os.Build.VERSION.RELEASE)
                        append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                        append("thread: ").append(thread.name).append("\n\n")
                        append(trace)
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the saved trace once, then clears it. */
    fun consume(context: Context): String? {
        val file = context.applicationContext.filesDir.resolve(FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { runCatching { file.delete() } }
    }
}
