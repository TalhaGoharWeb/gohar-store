package com.gohar.store

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * One app in the store. Everything the store needs to show, download
 * and update it comes from catalog.json — there is no server and no
 * account. Talha publishes by editing that one file (see PUBLISH-GUIDE.md).
 */
data class StoreApp(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val developer: String,
    val category: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val iconColor: String,
    val iconLetter: String,
    val featured: Boolean,
    val whatsNew: List<String>,
    val apkUrl: String?,
    val apkUrlArm64: String?,
    val apkUrlArmv7: String?,
    val comingSoon: String?,
    val minSdk: Int,
) {
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
}

data class Catalog(
    val apps: List<StoreApp>,
    val updated: String,
    val fromRemote: Boolean,
)

object CatalogLoader {

    /** Raw GitHub URL of catalog.json once the gohar-store repo exists.
     * Until then the fetch fails fast and the bundled copy is used. */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/catalog.json"

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
                tagline = o.optString("tagline"),
                description = o.optString("description"),
                developer = o.optString("developer", "Talha Gohar"),
                category = o.optString("category"),
                packageName = o.optString("packageName"),
                versionName = o.optString("versionName"),
                versionCode = o.optLong("versionCode", 0),
                sizeBytes = o.optLong("sizeBytes", 0),
                iconColor = o.optString("iconColor", "#0E9594"),
                iconLetter = o.optString("iconLetter", "?"),
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
            )
        }
        return Catalog(apps, root.optString("updated", ""), fromRemote = false)
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
