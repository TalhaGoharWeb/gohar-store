package com.gohar.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Download state for one app, persisted so a pause survives
 * rotation and process death. */
enum class DownloadState {
    IDLE, DOWNLOADING, PAUSED, COMPLETE, FAILED
}

/**
 * Resumable APK downloader, replacing the old DownloadManager flow
 * (which could not pause or resume). Downloads run on a plain
 * background thread with HttpURLConnection and a `Range` header,
 * appending to a `.part` file in cacheDir/downloads/ until complete.
 *
 * A pause is just a stop flag: the thread exits cleanly and the
 * `.part` file stays, so a resume continues where it stopped. If the
 * server answers 200 instead of 206 (no Range support), the download
 * restarts from 0. A new versionCode also starts a fresh `.part`.
 */
object AppDownloader {

    private const val PREFS = "downloads"
    private const val BUFFER_SIZE = 8192
    private const val NOTIFY_INTERVAL_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class StopReason { PAUSE, CANCEL }

    private class Job {
        @Volatile var stopRequested: Boolean = false
        @Volatile var stopReason: StopReason = StopReason.PAUSE
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    /** In-memory progress (the source of truth while a download runs);
     * the persisted values lag behind by up to NOTIFY_INTERVAL_MS. */
    private val liveBytes = ConcurrentHashMap<String, Long>()
    private val liveTotal = ConcurrentHashMap<String, Long>()

    /** Listeners get the app id whose download changed (state or
     * throttled progress). Called on the main thread. */
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged(appId: String) {
        mainHandler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it(appId) }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun downloadsDir(context: Context): File =
        File(context.cacheDir, "downloads").apply { if (!exists()) mkdirs() }

    fun apkFile(context: Context, app: StoreApp): File =
        File(downloadsDir(context), "${app.packageName}.apk")

    fun partFile(context: Context, app: StoreApp): File =
        File(downloadsDir(context), "${app.packageName}.apk.part")

    fun state(context: Context, appId: String): DownloadState {
        val raw = prefs(context).getString("state_$appId", null) ?: return DownloadState.IDLE
        return try {
            DownloadState.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            DownloadState.IDLE
        }
    }

    private fun setState(context: Context, appId: String, state: DownloadState) {
        prefs(context).edit().putString("state_$appId", state.name).apply()
    }

    private fun persistProgress(
        context: Context,
        appId: String,
        bytes: Long,
        total: Long,
    ) {
        prefs(context).edit()
            .putLong("bytes_$appId", bytes)
            .putLong("total_$appId", total)
            .apply()
    }

    /** 0..100, or -1 when the total is unknown (indeterminate). */
    fun progress(context: Context, appId: String): Int {
        val total = liveTotal[appId] ?: prefs(context).getLong("total_$appId", -1)
        val done = liveBytes[appId] ?: prefs(context).getLong("bytes_$appId", 0)
        if (total <= 0) return -1
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    fun isDownloading(context: Context, appId: String): Boolean =
        state(context, appId) == DownloadState.DOWNLOADING

    /** A finished download is only "downloaded" when the final APK
     * file is actually there. */
    fun isDownloaded(context: Context, app: StoreApp): Boolean {
        if (state(context, app.id) != DownloadState.COMPLETE) return false
        val file = apkFile(context, app)
        return file.exists() && file.length() > 0
    }

    /**
     * Start (or resume) a download. With resume=true an existing
     * `.part` for the same versionCode is continued; a fresh start, a
     * new versionCode, or a server that ignores Range all begin at 0.
     */
    fun start(context: Context, app: StoreApp, resume: Boolean) {
        val appContext = context.applicationContext
        val url = app.downloadUrl()
        if (url.isNullOrBlank()) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.no_download_link, app.name),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (state(appContext, app.id) == DownloadState.DOWNLOADING) return

        val prefs = prefs(appContext)
        val savedVersion = prefs.getLong("vc_${app.id}", -1)
        val part = partFile(appContext, app)
        val final = apkFile(appContext, app)
        if (savedVersion != app.versionCode) {
            // New version: a stale .part / APK from the old build is useless.
            part.delete()
            final.delete()
            liveBytes.remove(app.id)
            liveTotal.remove(app.id)
            persistProgress(appContext, app.id, 0, -1)
        }
        if (!resume) {
            part.delete()
            final.delete()
            liveBytes.remove(app.id)
            liveTotal.remove(app.id)
            persistProgress(appContext, app.id, 0, -1)
        }
        prefs.edit()
            .putString("url_${app.id}", url)
            .putLong("vc_${app.id}", app.versionCode)
            .apply()

        val job = Job()
        jobs[app.id] = job
        setState(appContext, app.id, DownloadState.DOWNLOADING)
        notifyChanged(app.id)

        thread(name = "download-${app.id}") {
            runDownload(appContext, app, url, job)
        }
    }

    private fun runDownload(
        context: Context,
        app: StoreApp,
        url: String,
        job: Job,
    ) {
        val part = partFile(context, app)
        val final = apkFile(context, app)
        var downloaded = if (part.exists()) part.length() else 0L
        var total = prefs(context).getLong("total_${app.id}", -1)
        liveBytes[app.id] = downloaded
        if (total > 0) liveTotal[app.id] = total

        var connection: HttpURLConnection? = null
        var outcome: DownloadState = DownloadState.FAILED
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (downloaded > 0) {
                connection.setRequestProperty("Range", "bytes=$downloaded-")
            }
            connection.connect()
            val code = connection.responseCode
            val contentLength = connection.contentLengthLong
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // 206: the server honours Range; total = already
                    // have + what is left.
                    if (contentLength > 0) total = downloaded + contentLength
                }
                HttpURLConnection.HTTP_OK -> {
                    // 200: the server ignored Range — restart from 0.
                    if (downloaded > 0) {
                        part.delete()
                        downloaded = 0
                    }
                    total = contentLength
                }
                // 416 Requested Range Not Satisfiable: our .part is
                // longer than the file on the server (it shrank) —
                // restart from 0. (Literal: the SDK's HttpURLConnection
                // does not expose the 416 constant here.)
                416 -> {
                    part.delete()
                    downloaded = 0
                    total = -1
                    // Fall through to a plain GET by reconnecting.
                    connection.disconnect()
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.connect()
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        outcome = DownloadState.FAILED
                        return
                    }
                    total = connection.contentLengthLong
                }
                else -> {
                    outcome = DownloadState.FAILED
                    return
                }
            }
            liveTotal[app.id] = total
            persistProgress(context, app.id, downloaded, total)

            var lastNotify = System.currentTimeMillis()
            connection.inputStream.use { input ->
                FileOutputStream(part, downloaded > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (job.stopRequested) break
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()
                        liveBytes[app.id] = downloaded
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                            persistProgress(context, app.id, downloaded, total)
                            notifyChanged(app.id)
                            lastNotify = now
                        }
                    }
                    output.flush()
                }
            }

            when {
                job.stopRequested && job.stopReason == StopReason.CANCEL -> {
                    part.delete()
                    final.delete()
                    outcome = DownloadState.IDLE
                }
                job.stopRequested -> outcome = DownloadState.PAUSED
                total > 0 && downloaded != total -> {
                    // Size mismatch: the file changed mid-download.
                    // Drop the .part so the next attempt starts fresh
                    // instead of resuming into the same mismatch.
                    part.delete()
                    outcome = DownloadState.FAILED
                }
                else -> {
                    val renamed = part.renameTo(final)
                    if (!renamed) {
                        // Same-directory rename should not fail; if it
                        // does, copy instead of losing the download.
                        part.copyTo(final, overwrite = true)
                        part.delete()
                    }
                    if (final.exists() && (total <= 0 || final.length() == total)) {
                        outcome = DownloadState.COMPLETE
                    } else {
                        outcome = DownloadState.FAILED
                    }
                }
            }
        } catch (_: IOException) {
            // No network, dropped connection, … — keep the .part so
            // tapping Install/Update retries from where it stopped.
            outcome = if (job.stopRequested && job.stopReason == StopReason.CANCEL) {
                part.delete()
                final.delete()
                DownloadState.IDLE
            } else if (job.stopRequested) {
                DownloadState.PAUSED
            } else {
                DownloadState.FAILED
            }
        } finally {
            connection?.disconnect()
            jobs.remove(app.id)
            if (outcome == DownloadState.COMPLETE || outcome == DownloadState.IDLE) {
                liveBytes.remove(app.id)
                liveTotal.remove(app.id)
            }
            persistProgress(context, app.id, downloaded, total)
            setState(context, app.id, outcome)
            notifyChanged(app.id)
        }
    }

    /** Pause: the running thread exits at the next chunk and the
     * `.part` file stays for a later resume. If the process died
     * mid-download there is no live thread — just flip the state. */
    fun pause(context: Context, appId: String) {
        val job = jobs[appId]
        if (job != null) {
            job.stopReason = StopReason.PAUSE
            job.stopRequested = true
        } else if (state(context, appId) == DownloadState.DOWNLOADING) {
            setState(context, appId, DownloadState.PAUSED)
            notifyChanged(appId)
        }
    }

    /** Cancel for a known app: also deletes its `.part` and APK. When
     * a download is running, only the stop flag is set here — the
     * thread deletes the files and flips the state to IDLE when it
     * exits, so a quick re-tap of Install cannot start a second
     * thread over the dying one (start() refuses while the state is
     * still DOWNLOADING). */
    fun cancelForApp(context: Context, app: StoreApp) {
        val job = jobs[app.id]
        if (job != null) {
            job.stopReason = StopReason.CANCEL
            job.stopRequested = true
        } else {
            partFile(context, app).delete()
            apkFile(context, app).delete()
            liveBytes.remove(app.id)
            liveTotal.remove(app.id)
            setState(context.applicationContext, app.id, DownloadState.IDLE)
            notifyChanged(app.id)
        }
    }

    /**
     * After a successful install the download state is done: clear it
     * (and the APK file) so the row goes back to Open. Called lazily
     * from StoreActions.stateFor when the installed version is current.
     */
    fun resetAfterInstall(context: Context, app: StoreApp) {
        if (state(context, app.id) != DownloadState.COMPLETE) return
        apkFile(context, app).delete()
        liveBytes.remove(app.id)
        liveTotal.remove(app.id)
        setState(context.applicationContext, app.id, DownloadState.IDLE)
        notifyChanged(app.id)
    }

    /** Forget everything about one app's download (fresh start). */
    fun clear(context: Context, appId: String) {
        jobs[appId]?.let {
            it.stopReason = StopReason.CANCEL
            it.stopRequested = true
        }
        liveBytes.remove(appId)
        liveTotal.remove(appId)
        prefs(context).edit()
            .remove("state_$appId")
            .remove("bytes_$appId")
            .remove("total_$appId")
            .remove("url_$appId")
            .remove("vc_$appId")
            .apply()
    }
}
