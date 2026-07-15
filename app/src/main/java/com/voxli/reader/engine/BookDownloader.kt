package com.voxli.reader.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Downloads book files (FB2/EPUB) from flibusta to app cache directory.
 * Reference: roadmap §10 Phase 1 — "Загрузчик книг: скачивание FB2/EPUB в кэш".
 */
class BookDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val cacheDir: File get() = File(context.cacheDir, "books").also { it.mkdirs() }

    /**
     * Download a book from flibusta.
     * @param bookId flibusta book ID
     * @param format "fb2" or "epub"
     * @param baseUrl flibusta mirror base URL (e.g. "http://flibusta.is")
     * @return the downloaded file, or null on failure
     */
    suspend fun download(
        bookId: Long,
        format: String,
        baseUrl: String,
        onProgress: ((Float) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/b/$bookId/$format"
        val file = File(cacheDir, "$bookId.$format")

        // Skip if already cached
        if (file.exists() && file.length() > 0) {
            return@withContext file
        }

        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            onProgress?.invoke(bytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }

            return@withContext file
        } catch (_: Exception) {
            file.delete()
            return@withContext null
        }
    }

    /** Check if a book is already cached. */
    fun isCached(bookId: Long, format: String): Boolean {
        return File(cacheDir, "$bookId.$format").exists()
    }

    /** Get cached file if exists. */
    fun getCachedFile(bookId: Long, format: String): File? {
        val file = File(cacheDir, "$bookId.$format")
        return if (file.exists()) file else null
    }

    /** Delete a cached book file. */
    fun delete(bookId: Long, format: String) {
        File(cacheDir, "$bookId.$format").delete()
    }

    /** Clear all cached books. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
