package com.gohar.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app self-update (v1.4.1): the store checks its own version in
 * store-version.json (same repo as catalog.json, fetched with the
 * CatalogLoader pattern — HttpURLConnection, 8 s timeouts, background
 * thread, main-thread callback) and, when a newer build is published,
 * downloads its APK through the existing AppDownloader +
 * StoreActions flow and verifies its SHA-256 before installing.
 *
 * Rules:
 * - Any fetch/parse failure is a silent no-op on cold start — the
 *   check never blocks launch, never crashes, never toasts.
 * - Every update is OPTIONAL in v1.4.1, even when the JSON says
 *   mandatory=true (mandatory enforcement is a later owner decision;
 *   see UpdateInfo.mandatory). The user always confirms via the
 *   dialog, and Android's own install prompt confirms again — the
 *   store never installs silently.
 * - No new dependency: framework + org.json + HttpURLConnection only.
 */
object SelfUpdate {

    /** The store's own id inside AppDownloader / StoreActions state. */
    const val STORE_ID = "talha-gohar-apps"

    /** Raw GitHub URL of the store's version file. */
    const val VERSION_URL =
        "https://raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/store-version.json"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Parsed store-version.json. Missing fields tolerate defaults;
     * [mandatory] is parsed but deliberately unread in v1.4.1. */
    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val sha256: String,
        val sizeBytes: Long,
        /** v1.4.1: parsed for forward compatibility only. EVERY update
         * is treated as optional, even when this is true — mandatory
         * enforcement is deferred to a later owner decision. */
        val mandatory: Boolean,
        val whatsNew: List<String>,
        val whatsNewUr: List<String>,
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        object UpToDate : CheckResult
        object Failed : CheckResult
    }

    /** The versionCode the user dismissed with "Later" this session.
     * In-memory only — a fresh launch offers the update again. */
    private var dismissedVersionCode: Long? = null

    /** The update whose APK is downloading / downloaded right now.
     * Needed to verify the SHA when AppDownloader reports COMPLETE,
     * including after a rotation (the progress dialog is gone then,
     * but the listener below still fires). Cleared on install,
     * failure, cancel, or an up-to-date check. */
    private var activeInfo: UpdateInfo? = null

    private var appContext: Context? = null
    private var activityRef: WeakReference<AppCompatActivity>? = null

    /** The update dialog, so a second check result cannot stack
     * another one for the same version. */
    private var updateDialog: AlertDialog? = null
    private var updateDialogVersionCode: Long? = null

    /** The progress dialog and its views, plus the Activity that
     * owns them (a rotated Activity's dialog is dead even if
     * isShowing still says true). */
    private var progressDialog: AlertDialog? = null
    private var progressActivity: AppCompatActivity? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var progressApp: StoreApp? = null

    private var listenerRegistered = false

    private val downloadListener: (String) -> Unit = { appId ->
        if (appId == STORE_ID) onStoreDownloadChanged()
    }

    private fun ensureListener() {
        if (listenerRegistered) return
        AppDownloader.addListener(downloadListener)
        listenerRegistered = true
    }

    private fun stringList(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) list += s
        }
        return list
    }

    private fun parse(jsonText: String): UpdateInfo {
        val o = JSONObject(jsonText)
        return UpdateInfo(
            versionName = o.optString("versionName", ""),
            versionCode = o.optLong("versionCode", 0),
            apkUrl = o.optString("apkUrl", ""),
            sha256 = o.optString("sha256", ""),
            sizeBytes = o.optLong("sizeBytes", 0),
            mandatory = o.optBoolean("mandatory", false),
            whatsNew = stringList(o, "whatsNew"),
            whatsNewUr = stringList(o, "whatsNewUr"),
        )
    }

    /** Fetches store-version.json on a background thread; the callback
     * always runs on the main thread. Any failure yields Failed —
     * the caller decides whether that is silent (cold start) or a
     * toast (manual check). */
    fun check(context: Context, onResult: (CheckResult) -> Unit) {
        appContext = context.applicationContext
        Thread {
            val result = try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/json")
                val info = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream))
                        .use { it.readText() }
                    parse(text)
                } else {
                    null
                }
                conn.disconnect()
                when {
                    info == null -> CheckResult.Failed
                    info.versionCode > BuildConfig.VERSION_CODE &&
                        info.versionName.isNotBlank() &&
                        info.apkUrl.isNotBlank() &&
                        info.sha256.isNotBlank() -> CheckResult.Available(info)
                    else -> CheckResult.UpToDate
                }
            } catch (_: Exception) {
                CheckResult.Failed
            }
            mainHandler.post { onResult(result) }
        }.start()
    }

    /**
     * Acts on a check result. Cold start ([manual] = false) is
     * silent: no toast when up to date, no toast on failure, and a
     * "Later" dismissal suppresses the dialog until the next launch.
     * A manual check reports up-to-date / check-failed with a toast
     * and re-shows the dialog even for a dismissed version.
     */
    fun handleCheckResult(activity: AppCompatActivity, result: CheckResult, manual: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        activityRef = WeakReference(activity)
        when (result) {
            is CheckResult.Available -> {
                // A download already running / paused for this update:
                // show its progress, not the offer dialog again.
                val state = AppDownloader.state(activity, STORE_ID)
                if (state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED) {
                    showProgressDialog(activity, toStoreApp(result.info), result.info)
                    return
                }
                if (!manual && dismissedVersionCode == result.info.versionCode) return
                showUpdateDialog(activity, result.info)
            }
            CheckResult.UpToDate -> {
                // The installed build is current: a stale COMPLETE
                // from a previous version must not linger. Only the
                // persisted state is cleared — a download actually in
                // flight is left alone (it cannot exist here in
                // practice: we only start one when the JSON is newer
                // than the installed build).
                if (AppDownloader.state(activity, STORE_ID) != DownloadState.DOWNLOADING) {
                    AppDownloader.clear(activity, STORE_ID)
                }
                activeInfo = null
                if (manual) {
                    ToastHelper.show(
                        activity, ToastHelper.Type.INFO,
                        R.string.self_update_up_to_date,
                    )
                }
            }
            CheckResult.Failed -> {
                if (manual) {
                    ToastHelper.show(
                        activity, ToastHelper.Type.ERROR,
                        R.string.self_update_check_failed,
                    )
                }
            }
        }
    }

    /** The store itself as a StoreApp, so the existing downloader +
     * installer can handle it unchanged. apkUrlArm64 / apkUrlArmv7
     * stay null, so downloadUrl() always returns the single apkUrl. */
    fun toStoreApp(info: UpdateInfo): StoreApp = StoreApp(
        id = STORE_ID,
        name = "Talha Gohar Apps",
        nameUr = "طلحہ گوہر ایپس",
        tagline = "",
        description = "",
        developer = "Talha Gohar",
        category = "",
        packageName = "com.gohar.store",
        versionName = info.versionName,
        versionCode = info.versionCode,
        sizeBytes = info.sizeBytes,
        iconColor = "#0E9594",
        iconColor2 = "#0B2545",
        iconLetter = "T",
        iconUrl = null,
        screenshots = emptyList(),
        featured = false,
        whatsNew = info.whatsNew,
        apkUrl = info.apkUrl,
        apkUrlArm64 = null,
        apkUrlArmv7 = null,
        comingSoon = null,
        minSdk = 26,
        taglineUr = null,
        descriptionUr = null,
        categoryUr = null,
        whatsNewUr = info.whatsNewUr,
        comingSoonUr = null,
    )

    private fun buildMessage(context: Context, info: UpdateInfo): String {
        // sizeLabel() is the same "17.2 MB" format the rows use; an
        // unknown size (sizeBytes <= 0) just drops the " · size" part.
        val size = toStoreApp(info).sizeLabel()
        val meta = context.getString(
            R.string.self_update_message,
            info.versionName,
            if (size.isEmpty()) "" else " · $size",
        )
        val lines = if (StoreApp.useUrdu() && info.whatsNewUr.isNotEmpty()) {
            info.whatsNewUr
        } else {
            info.whatsNew
        }
        if (lines.isEmpty()) return meta
        return meta + "\n\n" + context.getString(R.string.whats_new) + "\n" +
            lines.joinToString("\n") { "• $it" }
    }

    /** Offer dialog: Update / Later. The positive button reads
     * "Install" when a finished download for this update is already
     * on disk (verified by SHA on tap — see startUpdate). */
    fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        activityRef = WeakReference(activity)
        if (updateDialog?.isShowing == true && updateDialogVersionCode == info.versionCode) return
        val app = toStoreApp(info)
        val alreadyDownloaded = AppDownloader.isDownloaded(activity, app)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.self_update_title, info.versionName))
            .setMessage(buildMessage(activity, info))
            .setPositiveButton(
                if (alreadyDownloaded) R.string.self_update_install
                else R.string.self_update_update,
            ) { _, _ -> startUpdate(activity, info) }
            .setNegativeButton(R.string.self_update_later) { _, _ ->
                dismissedVersionCode = info.versionCode
            }
            .show()
            .also {
                updateDialog = it
                updateDialogVersionCode = info.versionCode
                styleDialogButtons(it, destructive = false)
            }
        dialog.setOnDismissListener {
            if (updateDialog === dialog) {
                updateDialog = null
                updateDialogVersionCode = null
            }
        }
    }

    /** Positive-button path: a finished download for this exact file
     * is SHA-verified and offered as Install instead of
     * re-downloading; anything else starts a fresh download. The SHA
     * runs off the main thread (a ~17 MB file hashes in well under a
     * second, but there is no reason to jank the tap). */
    private fun startUpdate(activity: AppCompatActivity, info: UpdateInfo) {
        activityRef = WeakReference(activity)
        activeInfo = info
        ensureListener()
        val app = toStoreApp(info)
        if (AppDownloader.isDownloaded(activity, app)) {
            val file = StoreActions.apkFile(activity, app)
            Thread {
                val ok = shaMatches(file, info.sha256)
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        // The Activity is gone; the verified file can
                        // still install from the application context.
                        if (ok) installVerified(activity.applicationContext, app)
                        return@post
                    }
                    if (ok) {
                        installVerified(activity, app)
                    } else {
                        // Stale or corrupt file (e.g. a COMPLETE from
                        // an older versionCode): drop it and download
                        // the real one.
                        file.delete()
                        AppDownloader.clear(activity, STORE_ID)
                        startFreshDownload(activity, app, info)
                    }
                }
            }.start()
            return
        }
        startFreshDownload(activity, app, info)
    }

    private fun startFreshDownload(activity: AppCompatActivity, app: StoreApp, info: UpdateInfo) {
        if (!StoreActions.canInstallFromUnknownSources(activity)) {
            // Let the established path show its toast + open Settings;
            // no progress dialog: nothing is downloading.
            StoreActions.startDownload(activity, app)
            return
        }
        StoreActions.startDownload(activity, app)
        showProgressDialog(activity, app, info)
    }

    /** Progress dialog: horizontal bar + % text, Pause/Resume
     * (neutral, toggles in place) and Cancel (negative). Not
     * cancellable by back-press — Cancel is the explicit way out, so
     * the download can never run invisibly with no UI to stop it. */
    private fun showProgressDialog(activity: AppCompatActivity, app: StoreApp, info: UpdateInfo) {
        activityRef = WeakReference(activity)
        activeInfo = info
        progressApp = app
        ensureListener()
        if (progressDialog?.isShowing == true && progressActivity === activity) {
            updateProgressDialog()
            return
        }
        // A dialog owned by a destroyed (e.g. rotated) Activity is
        // dead even if isShowing still says true — drop it first.
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null

        val density = activity.resources.displayMetrics.density
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val percent = TextView(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
            // Created in code, so the theme's fontFamily does not
            // apply — Urdu needs Nastaleeq explicitly (C-47).
            applyUrduFontIfNeeded(this)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(),
                (8 * density).toInt(),
                (24 * density).toInt(),
                (8 * density).toInt(),
            )
            addView(bar)
            addView(percent)
        }
        progressBar = bar
        progressText = percent
        progressActivity = activity
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.self_update_downloading)
            .setView(content)
            .setNeutralButton(R.string.pause_download, null)
            .setNegativeButton(R.string.cancel_download) { _, _ ->
                StoreActions.cancelDownload(activity, app)
                activeInfo = null
            }
            .setCancelable(false)
            .show()
            .also { styleDialogButtons(it, destructive = false) }
        progressDialog = dialog
        // The neutral button must NOT dismiss the dialog — Pause
        // toggles to Resume in place. Replacing its click listener
        // after show() drops AlertDialog's default auto-dismiss for
        // that button.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            togglePause()
        }
        dialog.setOnDismissListener {
            progressDialog = null
            progressActivity = null
            progressBar = null
            progressText = null
            progressApp = null
        }
        updateProgressDialog()
    }

    private fun togglePause() {
        val ctx = appContext ?: return
        val app = progressApp ?: return
        val activity = progressActivity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (AppDownloader.state(ctx, STORE_ID) == DownloadState.PAUSED) {
            // Same wrapper the rows use: resumes the .part. With no
            // live Activity, start the downloader directly (it needs
            // no UI, and the permission was already checked when the
            // download first started).
            if (activity != null) StoreActions.resumeDownload(activity, app)
            else AppDownloader.start(ctx, app, resume = true)
        } else {
            AppDownloader.pause(ctx, STORE_ID)
        }
    }

    private fun updateProgressDialog() {
        val dialog = progressDialog ?: return
        val activity = progressActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val ctx = appContext ?: return
        val app = progressApp ?: return
        val bar = progressBar ?: return
        val text = progressText ?: return
        val progress = AppDownloader.progress(ctx, STORE_ID)
        if (progress < 0) {
            bar.isIndeterminate = true
            text.text = activity.getString(R.string.downloading)
        } else {
            bar.isIndeterminate = false
            bar.progress = progress
            text.text = activity.getString(R.string.downloading_progress, progress)
        }
        val paused = AppDownloader.state(ctx, STORE_ID) == DownloadState.PAUSED
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setText(
            if (paused) R.string.resume_download else R.string.pause_download,
        )
    }

    /** AppDownloader state changes for the store id, on the main
     * thread. This is the only place a finished store download turns
     * into an install — and only after its SHA-256 matches the JSON. */
    private fun onStoreDownloadChanged() {
        val ctx = appContext ?: return
        when (AppDownloader.state(ctx, STORE_ID)) {
            DownloadState.DOWNLOADING, DownloadState.PAUSED -> updateProgressDialog()
            DownloadState.COMPLETE -> {
                val info = activeInfo
                progressDialog?.dismiss()
                if (info == null) return
                activeInfo = null
                val app = toStoreApp(info)
                val file = StoreActions.apkFile(ctx, app)
                Thread {
                    val ok = shaMatches(file, info.sha256)
                    mainHandler.post {
                        if (ok) {
                            installVerified(ctx, app)
                        } else {
                            file.delete()
                            AppDownloader.clear(ctx, STORE_ID)
                            ToastHelper.show(
                                ctx, ToastHelper.Type.ERROR,
                                R.string.self_update_verify_failed, long = true,
                            )
                        }
                    }
                }.start()
            }
            DownloadState.FAILED -> {
                progressDialog?.dismiss()
                if (activeInfo != null) {
                    activeInfo = null
                    ToastHelper.show(
                        ctx, ToastHelper.Type.ERROR,
                        R.string.self_update_download_failed, long = true,
                    )
                }
            }
            DownloadState.IDLE -> {
                // Cancelled: just close the progress UI, no toast.
                activeInfo = null
                progressDialog?.dismiss()
            }
        }
    }

    /** SHA-256 of [file] as lowercase hex, or null on any error. */
    fun sha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun shaMatches(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        val actual = sha256Hex(file) ?: return false
        return actual.equals(expectedSha256.trim().lowercase(), ignoreCase = true)
    }

    /** Existing install flow (FileProvider + ACTION_VIEW). Prefers a
     * live Activity; with none (e.g. rotated away mid-download) the
     * application context works because the intent carries
     * FLAG_ACTIVITY_NEW_TASK — but only when installs are already
     * allowed, since the Settings fallback has no NEW_TASK flag. */
    private fun installVerified(context: Context, app: StoreApp) {
        val activity = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity != null) {
            StoreActions.installDownloaded(activity, app)
            return
        }
        if (StoreActions.canInstallFromUnknownSources(context)) {
            StoreActions.installDownloaded(context, app)
        } else {
            ToastHelper.show(
                context, ToastHelper.Type.ERROR,
                R.string.allow_install_again, long = true,
            )
        }
    }
}
