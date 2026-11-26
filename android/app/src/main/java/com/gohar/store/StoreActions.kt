package com.gohar.store

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** What the store can do with an app right now. */
enum class AppState {
    NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, DOWNLOADING, READY_TO_INSTALL, COMING_SOON
}

object StoreActions {

    private const val PREFS = "gohar_store_downloads"

    fun installedVersionCode(context: Context, packageName: String): Long? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun stateFor(context: Context, app: StoreApp): AppState {
        if (!app.hasDownload) return AppState.COMING_SOON
        if (isDownloaded(context, app)) return AppState.READY_TO_INSTALL
        if (isDownloading(context, app)) return AppState.DOWNLOADING
        val installed = installedVersionCode(context, app.packageName) ?: return AppState.NOT_INSTALLED
        return if (installed < app.versionCode) AppState.UPDATE_AVAILABLE else AppState.INSTALLED
    }

    fun openApp(context: Context, app: StoreApp) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) context.startActivity(intent)
        else Toast.makeText(context, "Could not open ${app.name}", Toast.LENGTH_SHORT).show()
    }

    fun apkFile(context: Context, app: StoreApp): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, "${app.id}-${app.versionName}.apk")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDownloading(context: Context, app: StoreApp): Boolean {
        val id = prefs(context).getLong(app.id, -1)
        if (id < 0) return false
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_PAUSED
        }
    }

    /** 0..100, or -1 when no download is in progress. */
    fun downloadProgress(context: Context, app: StoreApp): Int {
        val id = prefs(context).getLong(app.id, -1)
        if (id < 0) return -1
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return -1
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            if (total <= 0) return 0
            return ((done * 100) / total).toInt().coerceIn(0, 100)
        }
    }

    fun isDownloaded(context: Context, app: StoreApp): Boolean {
        val file = apkFile(context, app)
        if (!file.exists() || file.length() < 1_000_000) return false
        val id = prefs(context).getLong(app.id, -1)
        if (id < 0) return true // a complete file is enough; id only tracks progress
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return true
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    fun startDownload(context: Context, app: StoreApp) {
        val url = app.downloadUrl()
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No download link yet for ${app.name}", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canInstallFromUnknownSources(context)) {
            Toast.makeText(
                context,
                "Allow installs from GOHAR Store first — opening settings",
                Toast.LENGTH_LONG,
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            return
        }
        val file = apkFile(context, app)
        if (file.exists()) file.delete()
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(app.name)
            setDescription("Downloading ${app.name} ${app.versionName}")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            setMimeType("application/vnd.android.package-archive")
            setDestinationUri(Uri.fromFile(file))
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        prefs(context).edit().putLong(app.id, id).apply()
        Toast.makeText(context, "Downloading ${app.name}…", Toast.LENGTH_SHORT).show()
    }

    fun canInstallFromUnknownSources(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installDownloaded(context: Context, app: StoreApp) {
        if (!canInstallFromUnknownSources(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Allow installs from GOHAR Store, then tap Install again",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val file = apkFile(context, app)
        if (!file.exists()) {
            Toast.makeText(context, "Download the APK first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** The one-tap action for the current state. Returns the state the
     * button should show while the work happens. */
    fun primaryAction(context: Context, app: StoreApp) {
        when (stateFor(context, app)) {
            AppState.NOT_INSTALLED, AppState.UPDATE_AVAILABLE -> startDownload(context, app)
            AppState.READY_TO_INSTALL -> installDownloaded(context, app)
            AppState.INSTALLED -> openApp(context, app)
            AppState.DOWNLOADING -> Toast.makeText(
                context, "Download in progress…", Toast.LENGTH_SHORT,
            ).show()
            AppState.COMING_SOON -> Toast.makeText(
                context,
                app.comingSoon ?: "Coming soon",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun actionLabel(context: Context, app: StoreApp): String {
        return when (stateFor(context, app)) {
            AppState.NOT_INSTALLED -> "Install"
            AppState.UPDATE_AVAILABLE -> "Update"
            AppState.INSTALLED -> "Open"
            AppState.READY_TO_INSTALL -> "Install"
            AppState.DOWNLOADING -> "Downloading…"
            AppState.COMING_SOON -> "Coming soon"
        }
    }
}
