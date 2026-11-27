package com.gohar.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The developer console's link to GitHub. The store itself only ever
 * *reads* catalog.json; this object is the only place that writes it.
 *
 * Auth is a Personal Access Token (classic, `repo` scope, or
 * fine-grained with Contents read/write on TalhaGoharWeb/gohar-store).
 * The token lives only in this phone's private SharedPreferences — it
 * is never logged, never written to any file, and can be revoked at
 * any time from GitHub. Only the last 4 characters are ever shown.
 *
 * Flow: GET the file (keeps its blob `sha`), edit in memory, PUT the
 * new content back with that `sha`. GitHub rejects the PUT with 409
 * if the file changed remotely in the meantime, so a stale edit can
 * never silently overwrite someone else's change.
 */
object GitHubCatalog {

    const val REPO = "TalhaGoharWeb/gohar-store"
    private const val API_URL =
        "https://api.github.com/repos/TalhaGoharWeb/gohar-store/contents/catalog.json"

    private const val PREFS = "admin"
    private const val KEY_PAT = "github_pat"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAT, null)?.takeIf { it.isNotBlank() }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAT, token.trim()).apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PAT).apply()
    }

    /** Only the tail is ever displayed, e.g. "•••• ab12". */
    fun tokenTail(token: String): String =
        "•••• " + token.takeLast(4)

    data class RemoteCatalog(val catalog: Catalog, val sha: String)

    /** What a successful publish returns: the new blob sha (needed for
     * the next publish) and the commit sha (shown to Talha). */
    data class Published(val newSha: String, val commitSha: String)

    /** Reads the live catalog.json from the repo. Runs off the main
     * thread; the callback always runs on the main thread. */
    fun load(token: String, onResult: (Result<RemoteCatalog>) -> Unit) {
        Thread {
            val result = runCatching {
                val conn = openConnection(token, "GET")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw Exception(githubError(conn, code))
                }
                val text = readAll(conn)
                conn.disconnect()
                val root = JSONObject(text)
                val content = root.optString("content", "")
                val sha = root.optString("sha", "")
                if (content.isBlank() || sha.isBlank()) {
                    throw Exception("GitHub returned an unexpected response.")
                }
                val json = String(
                    Base64.decode(content.replace("\\s".toRegex(), ""), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                RemoteCatalog(CatalogLoader.parse(json), sha)
            }
            mainHandler.post { onResult(result) }
        }.start()
    }

    /** Publishes the edited catalogue. The `sha` must be the one from
     * the matching GET — pass the new sha from the previous publish
     * when publishing twice in a row. */
    fun publish(
        token: String,
        catalog: Catalog,
        sha: String,
        onResult: (Result<Published>) -> Unit,
    ) {
        Thread {
            val result = runCatching {
                val body = JSONObject().apply {
                    put("message", "Update catalogue via Talha Gohar Apps console")
                    put(
                        "content",
                        Base64.encodeToString(
                            catalog.toJsonText().toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP,
                        ),
                    )
                    put("sha", sha)
                }
                val conn = openConnection(token, "PUT")
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_CREATED) {
                    throw Exception(githubError(conn, code))
                }
                val text = readAll(conn)
                conn.disconnect()
                val root = JSONObject(text)
                val newSha = root.optJSONObject("content")?.optString("sha", "") ?: ""
                val commitSha = root.optJSONObject("commit")?.optString("sha", "") ?: ""
                if (newSha.isBlank()) throw Exception("GitHub accepted the publish but returned no new version.")
                Published(newSha, commitSha)
            }
            mainHandler.post { onResult(result) }
        }.start()
    }

    private fun openConnection(token: String, method: String): HttpURLConnection {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "TalhaGoharApps-Console/1.0")
        return conn
    }

    private fun readAll(conn: HttpURLConnection): String =
        BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }

    /** Turns an HTTP failure into a plain-language message. */
    private fun githubError(conn: HttpURLConnection, code: Int): String {
        val detail = try {
            conn.errorStream?.let {
                BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
            } ?: ""
        } catch (_: Exception) {
            ""
        }
        val apiMessage = try {
            JSONObject(detail).optString("message", "")
        } catch (_: Exception) {
            ""
        }
        return when (code) {
            401 -> "GitHub rejected the token (401). Check the token or generate a new one."
            403 -> "GitHub refused the request (403). The token may lack the repo/Contents permission, or the rate limit was hit."
            404 -> "catalog.json was not found in TalhaGoharWeb/gohar-store (404)."
            409 -> "Someone changed the catalogue on GitHub — reload first, then publish again."
            422 -> "GitHub rejected the update (422). ${apiMessage.ifBlank { "Check the catalogue content." }}"
            else -> "GitHub request failed (HTTP $code). ${apiMessage.ifBlank { "Check the connection and try again." }}"
        }
    }
}
