package com.gohar.store

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
    NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, DOWNLOADING, PAUSED, READY_TO_INSTALL, COMING_SOON
}

object StoreActions {

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
        val installed = installedVersionCode(context, app.packageName)
        if (installed != null && installed >= app.versionCode) {
            // Up to date — a finished download has served its purpose.
            AppDownloader.resetAfterInstall(context, app)
            return AppState.INSTALLED
        }
        return when (AppDownloader.state(context, app.id)) {
            DownloadState.COMPLETE ->
                if (AppDownloader.isDownloaded(context, app)) AppState.READY_TO_INSTALL
                else if (installed != null) AppState.UPDATE_AVAILABLE else AppState.NOT_INSTALLED
            DownloadState.DOWNLOADING -> AppState.DOWNLOADING
            DownloadState.PAUSED -> AppState.PAUSED
            // FAILED / IDLE: fall through so the button offers a
            // retry (Install / Update), which resumes the .part.
            else -> if (installed != null) AppState.UPDATE_AVAILABLE else AppState.NOT_INSTALLED
        }
    }

    fun openApp(context: Context, app: StoreApp) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) context.startActivity(intent)
        else Toast.makeText(context, context.getString(R.string.could_not_open, app.name), Toast.LENGTH_SHORT).show()
    }

    /** The finished APK. (Downloads live in cacheDir/downloads/ so
     * they can be resumed; see AppDownloader.) */
    fun apkFile(context: Context, app: StoreApp): File =
        AppDownloader.apkFile(context, app)

    /** Leftover from the old DownloadManager flow, which saved to the
     * external Downloads folder. Deleted when a new download starts. */
    private fun legacyApkFile(context: Context, app: StoreApp): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, "${app.id}-${app.versionName}.apk")
    }

    fun isDownloading(context: Context, app: StoreApp): Boolean =
        AppDownloader.isDownloading(context, app.id)

    /** 0..100, or -1 when no download is in progress / total unknown. */
    fun downloadProgress(context: Context, app: StoreApp): Int =
        AppDownloader.progress(context, app.id)

    fun isDownloaded(context: Context, app: StoreApp): Boolean =
        AppDownloader.isDownloaded(context, app)

    fun startDownload(context: Context, app: StoreApp) {
        val url = app.downloadUrl()
        if (url.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.no_download_link, app.name), Toast.LENGTH_SHORT).show()
            return
        }
        if (!canInstallFromUnknownSources(context)) {
            Toast.makeText(
                context,
                "Allow installs from Talha Gohar Apps first — opening settings",
                Toast.LENGTH_LONG,
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            return
        }
        legacyApkFile(context, app).delete()
        // resume=true: a failed or paused attempt continues its .part;
        // a fresh download has no .part, so this is also a clean start.
        AppDownloader.start(context, app, resume = true)
        Toast.makeText(context, context.getString(R.string.downloading_app, app.name), Toast.LENGTH_SHORT).show()
    }

    /** Resume a paused download (same as starting — the .part is kept). */
    fun resumeDownload(context: Context, app: StoreApp) {
        startDownload(context, app)
    }

    fun pauseDownload(context: Context, app: StoreApp) {
        AppDownloader.pause(context, app.id)
    }

    fun cancelDownload(context: Context, app: StoreApp) {
        AppDownloader.cancelForApp(context, app)
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
                "Allow installs from Talha Gohar Apps, then tap Install again",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val file = apkFile(context, app)
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.download_apk_first), Toast.LENGTH_SHORT).show()
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
            AppState.PAUSED -> resumeDownload(context, app)
            AppState.DOWNLOADING -> Toast.makeText(
                context, context.getString(R.string.download_in_progress), Toast.LENGTH_SHORT,
            ).show()
            AppState.COMING_SOON -> Toast.makeText(
                context,
                app.displayComingSoon() ?: context.getString(R.string.coming_soon),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun actionLabel(context: Context, app: StoreApp): String {
        return when (stateFor(context, app)) {
            AppState.NOT_INSTALLED -> context.getString(R.string.install)
            AppState.UPDATE_AVAILABLE -> context.getString(R.string.update)
            AppState.INSTALLED -> context.getString(R.string.open)
            AppState.READY_TO_INSTALL -> context.getString(R.string.install)
            AppState.DOWNLOADING -> context.getString(R.string.downloading)
            AppState.PAUSED -> context.getString(R.string.resume_download)
            AppState.COMING_SOON -> context.getString(R.string.coming_soon)
        }
    }
}
