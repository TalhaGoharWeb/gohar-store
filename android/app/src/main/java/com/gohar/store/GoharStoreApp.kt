package com.gohar.store

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global uncaught-exception handler as early as possible so
 * a launch crash leaves a readable report behind instead of dying
 * silently. The report is written to filesDir/crash-report.txt and
 * shown on the next launch (see MainActivity.showCrashReportIfAny).
 * No new dependencies; the handler itself never throws.
 *
 * NOTE on the name: "StoreApp" is already the catalogue data class in
 * Catalog.kt, so the Application subclass is GoharStoreApp.
 */
class GoharStoreApp : Application() {

    companion object {
        const val CRASH_REPORT_FILE = "crash-report.txt"

        /** Drawer theme switch (I-4.0.8): the choice lives in the "ui"
         * prefs, key "theme_mode"; default "system" keeps the current
         * DayNight-follows-system behaviour. */
        const val PREFS_UI = "ui"
        const val KEY_THEME_MODE = "theme_mode"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** Reads the saved theme choice and applies it via
         * AppCompatDelegate. Called from onCreate (before any
         * Activity starts) and from MainActivity when the drawer
         * choice changes. */
        fun applySavedThemeMode(context: Context) {
            val mode = context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Apply the saved drawer theme choice before any Activity
        // starts, so the first screen already uses it (I-4.0.8).
        applySavedThemeMode(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(thread, throwable)
            } catch (_: Exception) {
                // Never let the reporter itself mask the original crash.
            }
            // Hand the crash back to the platform so the process still
            // dies the normal way (system "app keeps stopping" dialog).
            try {
                previousHandler?.uncaughtException(thread, throwable)
            } catch (_: Exception) {
            }
            // If there was no previous handler, make sure we still exit
            // instead of lingering in a half-dead process.
            if (previousHandler == null) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val (versionName, versionCode) = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            (info.versionName ?: "?") to
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
                else @Suppress("DEPRECATION") info.versionCode.toString()
        } catch (_: Exception) {
            "?" to "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Talha Gohar Apps crash report")
            appendLine("Time: $time")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(stack)
        }
        File(filesDir, CRASH_REPORT_FILE).writeText(text)
    }
}
