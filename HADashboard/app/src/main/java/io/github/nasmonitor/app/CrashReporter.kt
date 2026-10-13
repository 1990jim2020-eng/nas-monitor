package io.github.nasmonitor.app

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Persists an uncaught exception summary for no-ADB field diagnosis. */
object CrashReporter {
    private var appContext: Context? = null
    private const val PREFS = "hadashboard_crash_report"
    private const val KEY = "last_uncaught"
    private const val STAGE = "gateway_stage"

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val report = "${error.javaClass.name}: ${error.message ?: ""}\n" + writer.toString().take(6000)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, report).apply()
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    fun mark(context: Context, stage: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(STAGE, stage).commit() }
    fun mark(stage: String) { appContext?.let { mark(it, stage) } }
    fun stage(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STAGE, "尚未点击加载") ?: "尚未点击加载"
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).remove(STAGE).apply() }
}
