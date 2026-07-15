package com.voxli.audio.engine

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LRU cache eviction for audiobook MP3 files.
 * Runs via WorkManager daily. Removes files for books not opened in >30 days
 * if free space is below 1 GB.
 *
 * Reference: roadmap §10 Phase 3 — "Очистка кэша MP3 (LRU)".
 */
class Mp3CacheCleaner(
    private val context: Context,
) {
    companion object {
        private const val WORK_NAME = "mp3_cache_cleanup"
        private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024  // 1 GB
        private const val MAX_AGE_DAYS = 30L
    }

    /** Schedule daily cleanup. */
    fun scheduleDaily() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }

    /** Run cleanup once immediately. */
    suspend fun cleanupNow() {
        val audioDir = File(context.cacheDir, "audio")
        if (!audioDir.exists()) return

        val freeBytes = audioDir.freeSpace
        if (freeBytes >= MIN_FREE_BYTES) return  // enough space

        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 60 * 60 * 1000

        audioDir.listFiles()
            ?.filter { it.name.endsWith(".mp3") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    class CleanupWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val cleaner = Mp3CacheCleaner(applicationContext)
            cleaner.cleanupNow()
            return Result.success()
        }
    }
}
