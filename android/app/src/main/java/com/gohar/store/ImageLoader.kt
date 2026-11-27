package com.gohar.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Tiny image loader with no new dependencies — HttpURLConnection only.
 *
 * Used for the apps' real launcher icons and Play Store-style
 * screenshots, which live as raw files in the talha-gohar-apps repo.
 * Icons and screenshots are cached on disk (context.cacheDir/images)
 * so the store doesn't re-download them on every scroll.
 *
 * The [fallback] view (usually the gradient letter tile) is shown
 * while loading and whenever the load fails, so a missing or slow
 * image never leaves a blank hole in the UI.
 */
object ImageLoader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)

    /** ~20 bitmaps in memory; screenshots are downsampled to 1024px max. */
    private val memoryCache = object : LruCache<String, Bitmap>(20) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private const val MAX_DISK_BYTES = 50L * 1024 * 1024
    private const val PRUNE_TARGET_BYTES = 40L * 1024 * 1024
    private const val MAX_DIMENSION_PX = 1024

    fun load(url: String?, target: ImageView, fallback: View? = null) {
        if (url.isNullOrBlank()) {
            target.tag = null
            target.visibility = View.GONE
            fallback?.visibility = View.VISIBLE
            return
        }
        target.tag = url
        val cached = synchronized(memoryCache) { memoryCache.get(url) }
        if (cached != null && !cached.isRecycled) {
            target.setImageBitmap(cached)
            target.visibility = View.VISIBLE
            fallback?.visibility = View.GONE
            return
        }
        // Show the fallback until the real image arrives.
        target.visibility = View.GONE
        fallback?.visibility = View.VISIBLE
        pool.execute {
            val bitmap = loadBitmap(target.context, url)
            mainHandler.post {
                if (target.tag != url) return@post // recycled for another app
                if (bitmap != null) {
                    target.setImageBitmap(bitmap)
                    target.visibility = View.VISIBLE
                    fallback?.visibility = View.GONE
                } else {
                    target.visibility = View.GONE
                    fallback?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadBitmap(context: Context, url: String): Bitmap? {
        val file = cacheFile(context, url)
        if (file.exists()) {
            decodeSampled(file)?.let {
                synchronized(memoryCache) { memoryCache.put(url, it) }
                return it
            }
            file.delete() // corrupt cache entry; re-download below
        }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TalhaGoharApps/1.0")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            file.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            pruneCache(context)
            val bitmap = decodeSampled(file)
            if (bitmap != null) synchronized(memoryCache) { memoryCache.put(url, bitmap) }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /** Downsamples so a tall phone screenshot never blows the heap. */
    private fun decodeSampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_DIMENSION_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "images").apply { mkdirs() }

    private fun cacheFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val ext = when {
            url.substringBefore("?").endsWith(".png", ignoreCase = true) -> ".png"
            url.substringBefore("?").endsWith(".webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        return File(cacheDir(context), "$hash$ext")
    }

    /** Deletes oldest cached images when the cache grows past ~50 MB. */
    @Synchronized
    private fun pruneCache(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= PRUNE_TARGET_BYTES) return
            total -= file.length()
            file.delete()
        }
    }
}
