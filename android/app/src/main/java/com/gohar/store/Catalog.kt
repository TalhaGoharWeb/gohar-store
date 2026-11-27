package com.gohar.store

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Programmatically-created text views do NOT inherit the theme's
 * fontFamily: Material widgets resolve their own text appearance
 * (Chip uses its chip text appearance, a plain TextView created in
 * code gets the default), so Urdu text would render in the Latin
 * font. Call this on every view created in code that shows localized
 * text — category chips, "What's new" lines, dialog messages. Views
 * inflated from XML already get Nastaleeq from the Urdu theme.
 */
fun applyUrduFontIfNeeded(view: TextView) {
    if (StoreApp.useUrdu()) {
        ResourcesCompat.getFont(view.context, R.font.urdu)?.let { view.typeface = it }
    }
}

/**
 * Dialog button colours (C-43 / D-5.1, I-4.0.12): the confirm and
 * cancel buttons use the confirm colour; on a destructive dialog the
 * confirm (positive) button uses the error colour instead. Call
 * AFTER show() — the buttons only exist then. Buttons are 48dp by
 * default in Material 3; no height is forced here.
 *
 * Colours come from the night-qualified resources
 * R.color.dialog_confirm (light #0A6E6D / dark #17D6B5) and
 * R.color.dialog_destructive (light #B3261E / dark #F87171) — the
 * theme worker owns those two resources.
 */
fun styleDialogButtons(dialog: AlertDialog, destructive: Boolean) {
    val context = dialog.context
    val confirm = ContextCompat.getColor(context, R.color.dialog_confirm)
    val destructiveColor = ContextCompat.getColor(context, R.color.dialog_destructive)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ?.setTextColor(if (destructive) destructiveColor else confirm)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(confirm)
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(confirm)
}

/**
 * One app in the store. Everything the store needs to show, download
 * and update it comes from catalog.json — there is no server and no
 * account. Talha publishes by editing that one file (see PUBLISH-GUIDE.md).
 */
data class StoreApp(
    val id: String,
    val name: String,
    val nameUr: String? = null,
    val tagline: String,
    val description: String,
    val developer: String,
    val category: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val iconColor: String,
    val iconColor2: String,
    val iconLetter: String,
    /** Raw URL of the app's real launcher icon (PNG in the talha-gohar-apps
     * repo's icons/ folder). Null = keep the gradient letter tile. */
    val iconUrl: String?,
    /** Raw URLs of Play Store-style screenshots, in display order.
     * Empty = no gallery is shown for this app. */
    val screenshots: List<String>,
    val featured: Boolean,
    val whatsNew: List<String>,
    val apkUrl: String?,
    val apkUrlArm64: String?,
    val apkUrlArmv7: String?,
    val comingSoon: String?,
    val minSdk: Int,
    val taglineUr: String?,
    val descriptionUr: String?,
    val categoryUr: String?,
    val whatsNewUr: List<String>,
    val comingSoonUr: String?,
) {
    companion object {
        /** True when the store is currently showing Urdu. */
        fun useUrdu(): Boolean =
            java.util.Locale.getDefault().language == "ur"
    }

    fun displayName(): String =
        if (useUrdu()) nameUr?.takeIf { it.isNotBlank() } ?: name else name

    fun displayTagline(): String =
        if (useUrdu()) taglineUr?.takeIf { it.isNotBlank() } ?: tagline else tagline

    fun displayDescription(): String =
        if (useUrdu()) descriptionUr?.takeIf { it.isNotBlank() } ?: description else description

    fun displayCategory(): String =
        if (useUrdu()) categoryUr?.takeIf { it.isNotBlank() } ?: category else category

    fun displayWhatsNew(): List<String> =
        if (useUrdu() && whatsNewUr.isNotEmpty()) whatsNewUr else whatsNew

    fun displayComingSoon(): String? =
        if (useUrdu()) comingSoonUr?.takeIf { it.isNotBlank() } ?: comingSoon else comingSoon

    /** The APK link for this device (arm64 build when the device has it). */
    fun downloadUrl(): String? {
        val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (isArm64 && !apkUrlArm64.isNullOrBlank()) return apkUrlArm64
        if (!isArm64 && !apkUrlArmv7.isNullOrBlank()) return apkUrlArmv7
        return apkUrl?.takeIf { it.isNotBlank() }
    }

    val hasDownload: Boolean get() = downloadUrl() != null

    fun sizeLabel(): String {
        if (sizeBytes <= 0) return ""
        val mb = sizeBytes / 1_048_576.0
        return if (mb >= 100) "%d MB".format(mb.toLong()) else "%.1f MB".format(mb)
    }

    /** Serializes this app back to the catalog.json shape, with fields in
     * the same order as the file. Used by the admin console when Talha
     * edits an app; the store itself only reads. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("apkUrl", apkUrl?.let { it } ?: JSONObject.NULL)
        if (apkUrlArm64 != null) o.put("apkUrlArm64", apkUrlArm64)
        if (apkUrlArmv7 != null) o.put("apkUrlArmv7", apkUrlArmv7)
        o.put("category", category)
        // comingSoon is optional in the file; only emit it when set.
        if (comingSoon != null) o.put("comingSoon", comingSoon)
        o.put("description", description)
        o.put("developer", developer)
        o.put("featured", featured)
        o.put("iconColor", iconColor)
        o.put("iconLetter", iconLetter)
        o.put("iconUrl", iconUrl?.let { it } ?: JSONObject.NULL)
        o.put("id", id)
        o.put("minSdk", minSdk)
        o.put("name", name)
        o.put("nameUr", nameUr?.let { it } ?: JSONObject.NULL)
        o.put("packageName", packageName)
        o.put("screenshots", JSONArray(screenshots))
        o.put("sizeBytes", sizeBytes)
        o.put("tagline", tagline)
        o.put("versionCode", versionCode)
        o.put("versionName", versionName)
        o.put("whatsNew", JSONArray(whatsNew))
        o.put("iconColor2", iconColor2)
        o.put("taglineUr", taglineUr?.let { it } ?: JSONObject.NULL)
        o.put("descriptionUr", descriptionUr?.let { it } ?: JSONObject.NULL)
        o.put("categoryUr", categoryUr?.let { it } ?: JSONObject.NULL)
        o.put("whatsNewUr", JSONArray(whatsNewUr))
        o.put("comingSoonUr", comingSoonUr?.let { it } ?: JSONObject.NULL)
        return o
    }
}

data class Catalog(
    val apps: List<StoreApp>,
    val updated: String,
    val fromRemote: Boolean,
    val catalogVersion: Int = 1,
    val storeName: String = "Talha Gohar Apps",
    val tagline: String = "",
) {
    /** Serializes the whole catalogue back to the catalog.json shape. */
    fun toJsonText(): String {
        val root = JSONObject()
        val arr = JSONArray()
        for (app in apps) arr.put(app.toJson())
        root.put("apps", arr)
        root.put("catalogVersion", catalogVersion)
        root.put("storeName", storeName)
        root.put("tagline", tagline)
        root.put("updated", updated)
        return root.toString(2)
    }
}

object CatalogLoader {

    /** Raw GitHub URL of catalog.json once the talha-gohar-apps repo exists.
     * Until then the fetch fails fast and the bundled copy is used. */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/catalog.json"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun parse(jsonText: String): Catalog {
        val root = JSONObject(jsonText)
        val apps = mutableListOf<StoreApp>()
        val arr: JSONArray = root.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val whatsNew = mutableListOf<String>()
            val wn = o.optJSONArray("whatsNew")
            if (wn != null) for (j in 0 until wn.length()) whatsNew += wn.optString(j)
            apps += StoreApp(
                id = o.optString("id"),
                name = o.optString("name"),
                nameUr = o.optString("nameUr")
                    .takeIf { o.has("nameUr") && !o.isNull("nameUr") },
                tagline = o.optString("tagline"),
                description = o.optString("description"),
                developer = o.optString("developer", "Talha Gohar"),
                category = o.optString("category"),
                packageName = o.optString("packageName"),
                versionName = o.optString("versionName"),
                versionCode = o.optLong("versionCode", 0),
                sizeBytes = o.optLong("sizeBytes", 0),
                iconColor = o.optString("iconColor", "#0E9594"),
                iconColor2 = o.optString("iconColor2", o.optString("iconColor", "#0B2545")),
                iconLetter = o.optString("iconLetter", "?"),
                iconUrl = o.optString("iconUrl")
                    .takeIf { o.has("iconUrl") && !o.isNull("iconUrl") },
                screenshots = run {
                    val list = mutableListOf<String>()
                    val shots = o.optJSONArray("screenshots")
                    if (shots != null) for (j in 0 until shots.length()) list += shots.optString(j)
                    list
                },
                featured = o.optBoolean("featured", false),
                whatsNew = whatsNew,
                apkUrl = o.optString("apkUrl").takeIf { o.has("apkUrl") && !o.isNull("apkUrl") },
                apkUrlArm64 = o.optString("apkUrlArm64")
                    .takeIf { o.has("apkUrlArm64") && !o.isNull("apkUrlArm64") },
                apkUrlArmv7 = o.optString("apkUrlArmv7")
                    .takeIf { o.has("apkUrlArmv7") && !o.isNull("apkUrlArmv7") },
                comingSoon = o.optString("comingSoon")
                    .takeIf { o.has("comingSoon") && !o.isNull("comingSoon") },
                minSdk = o.optInt("minSdk", 21),
                taglineUr = o.optString("taglineUr")
                    .takeIf { o.has("taglineUr") && !o.isNull("taglineUr") },
                descriptionUr = o.optString("descriptionUr")
                    .takeIf { o.has("descriptionUr") && !o.isNull("descriptionUr") },
                categoryUr = o.optString("categoryUr")
                    .takeIf { o.has("categoryUr") && !o.isNull("categoryUr") },
                whatsNewUr = run {
                    val list = mutableListOf<String>()
                    val wnUr = o.optJSONArray("whatsNewUr")
                    if (wnUr != null) for (j in 0 until wnUr.length()) list += wnUr.optString(j)
                    list
                },
                comingSoonUr = o.optString("comingSoonUr")
                    .takeIf { o.has("comingSoonUr") && !o.isNull("comingSoonUr") },
            )
        }
        return Catalog(
            apps,
            root.optString("updated", ""),
            fromRemote = false,
            catalogVersion = root.optInt("catalogVersion", 1),
            storeName = root.optString("storeName", "Talha Gohar Apps"),
            tagline = root.optString("tagline", ""),
        )
    }

    fun loadBundled(context: Context): Catalog {
        val text = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        return parse(text)
    }

    /** Tries the remote catalog, falls back to the bundled copy on any
     * failure. The callback always runs on the main thread. */
    fun load(context: Context, onResult: (Catalog) -> Unit) {
        Thread {
            var remote: Catalog? = null
            try {
                val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream))
                        .use { it.readText() }
                    remote = parse(text).copy(fromRemote = true)
                }
                conn.disconnect()
            } catch (_: Exception) {
                remote = null
            }
            val result = try {
                remote ?: loadBundled(context)
            } catch (_: Exception) {
                Catalog(emptyList(), "", fromRemote = false)
            }
            mainHandler.post { onResult(result) }
        }.start()
    }
}
