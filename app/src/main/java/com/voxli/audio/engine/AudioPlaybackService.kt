package com.voxli.audio.engine

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.voxli.MainActivity
import com.voxli.knigavuhe.matcher.TrackInfo

/**
 * Audio playback service for audiobooks.
 * Uses Media3 ExoPlayer with custom HTTP headers (knigavuhe hotlinking fix).
 * Supports Bluetooth media buttons via MediaSession.
 *
 * Reference: roadmap §10 Phase 3, §14.2 ExoPlayer hotlinking fix.
 */
class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://knigavuhe.org/"))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpDataSourceFactory)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(pendingIntent)
            .build()
    }

    /**
     * Load a playlist of tracks into the player.
     */
    fun loadPlaylist(
        tracks: List<TrackInfo>,
        startIndex: Int = 0,
        bookTitle: String,
    ) {
        val player = player ?: return

        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.url)
                .setUri(track.url)
                .setTitle(track.title)
                .build()
        }

        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        player.prepare()
    }

    /**
     * Start or resume playback.
     */
    fun play() {
        player?.play()
    }

    /**
     * Pause playback.
     */
    fun pause() {
        player?.pause()
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /**
     * Skip to previous track (or restart current if near beginning).
     */
    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3000) {
            p.seekTo(0)
        } else {
            p.seekToPreviousMediaItem()
        }
    }

    /**
     * Skip to next track.
     */
    fun next() {
        player?.seekToNextMediaItem()
    }

    /**
     * Seek to a specific position.
     */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /**
     * Set playback speed.
     */
    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    /**
     * Get current playback position.
     */
    val currentPosition: Long
        get() = player?.currentPosition ?: 0L

    /**
     * Get total duration of current track.
     */
    val duration: Long
        get() = player?.duration ?: 0L

    /**
     * Check if currently playing.
     */
    val isPlaying: Boolean
        get() = player?.isPlaying ?: false

    /**
     * Release player resources.
     */
    fun release() {
        player?.stop()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
    }

    // ---- MediaSessionService overrides ----

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
