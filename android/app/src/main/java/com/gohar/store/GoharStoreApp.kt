package com.gohar.store

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
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
    }

    override fun onCreate() {
        super.onCreate()
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
