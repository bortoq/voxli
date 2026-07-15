package com.voxli.audio.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Downloads MP3 audiobook tracks from knigavuhe to app cache directory.
 * Supports progress reporting and resume (range requests).
 *
 * Reference: roadmap §10 Phase 3 — "Загрузчик MP3 (с прогрессом, докачкой)".
 */
class AudioDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val cacheDir: File get() = File(context.cacheDir, "audio").also { it.mkdirs() }

    /**
     * Download a single MP3 track.
     * @param url full MP3 URL (from TrackInfo)
     * @param trackId unique identifier for the track (e.g., "bookId_trackIndex")
     * @param onProgress progress callback (0.0 – 1.0)
     * @return the downloaded file, or null on failure
     */
    suspend fun downloadTrack(
        url: String,
        trackId: String,
        onProgress: ((Float) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$trackId.mp3")

        // If fully downloaded, return cached
        if (file.exists() && file.length() > 0) {
            val existingLen = file.length()
            // Quick check: try to get content-length
            val headRequest = okhttp3.Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://knigavuhe.org/")
                .build()

            try {
                val headResponse = client.newCall(headRequest).execute()
                val contentLength = headResponse.body?.contentLength() ?: -1
                headResponse.close()

                if (contentLength > 0 && existingLen >= contentLength) {
                    return@withContext file
                }

                // Partial download — resume
                if (contentLength > 0 && existingLen < contentLength) {
                    return@withContext resumeDownload(url, file, existingLen, contentLength, onProgress)
                }
            } catch (_: Exception) {
                // Can't verify, use cached file as-is
                return@withContext file
            }
        }

        // Fresh download
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://knigavuhe.org/")
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

    /**
     * Resume a partial download using Range header.
     */
    private fun resumeDownload(
        url: String,
        file: File,
        existingBytes: Long,
        totalBytes: Long,
        onProgress: ((Float) -> Unit)?,
    ): File? {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://knigavuhe.org/")
                .header("Range", "bytes=$existingBytes-")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body ?: return null
            body.byteStream().use { input ->
                file.appendBytes(input.readBytes())
            }

            onProgress?.invoke(1.0f)
            return file
        } catch (_: Exception) {
            return null
        }
    }

    /** Download all tracks for a book. Returns number of successfully downloaded tracks. */
    suspend fun downloadAllTracks(
        tracks: List<com.voxli.knigavuhe.matcher.TrackInfo>,
        bookId: Long,
        onTrackProgress: (trackIndex: Int, progress: Float) -> Unit,
    ): Int {
        var successCount = 0
        for ((index, track) in tracks.withIndex()) {
            val trackId = "${bookId}_$index"
            val result = downloadTrack(track.url, trackId) { progress ->
                onTrackProgress(index, progress)
            }
            if (result != null) successCount++
        }
        return successCount
    }

    /** Check if a track is cached. */
    fun isCached(trackId: String): Boolean {
        return File(cacheDir, "$trackId.mp3").exists()
    }

    /** Get cached track file. */
    fun getCachedFile(trackId: String): File? {
        val file = File(cacheDir, "$trackId.mp3")
        return if (file.exists()) file else null
    }

    /** Get total cache size. */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Delete cached files for a specific book. */
    fun deleteBookCache(bookId: Long) {
        cacheDir.listFiles()?.filter { it.name.startsWith("${bookId}_") }?.forEach { it.delete() }
    }

    /** Clear all audio cache. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
