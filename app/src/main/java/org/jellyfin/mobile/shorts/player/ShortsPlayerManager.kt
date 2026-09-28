package org.jellyfin.mobile.shorts.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.shorts.data.ShortsRepository
import org.jellyfin.mobile.shorts.model.ShortsItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the Media3 [ExoPlayer] used by the shorts feed and keeps the Jellyfin server playback state
 * in sync.
 *
 * A *single* player instance is reused for the whole feed. Media3 keeps the render surface attached
 * to that player, and its built-in playlist pre-buffers the next window, so switching videos is both
 * seamless and free of surface swap glitches (audio-without-video regressions).
 *
 * Threading: every [ExoPlayer] access must happen on the main thread. All server calls are therefore
 * executed on [Dispatchers.IO] only *after* the needed player values have been read on the main thread.
 *
 * Caching: the player is built on top of the shared [mediaSourceFactory], which wraps the
 * application-level [androidx.media3.datasource.cache.Cache].
 */
class ShortsPlayerManager(
    private val context: Context,
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
    private val shortsRepository: ShortsRepository,
    private val mediaSourceFactory: MediaSource.Factory,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private var progressReportJob: Job? = null

    /** Maps a player window index to the corresponding [ShortsItem] currently in the playlist. */
    private val playlistItems = mutableListOf<ShortsItem>()
    private var currentItem: ShortsItem? = null
    private var currentPlaySessionId: String = UUID.randomUUID().toString()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _isMutedFlow = MutableStateFlow(false)
    val isMutedFlow: StateFlow<Boolean> = _isMutedFlow.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlayingFlow.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
                onVideoEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer error occurred in ShortsPlayerManager")
        }
    }

    /**
     * The single player used by the feed. Recreated on demand so a released manager can safely be
     * reused when the user reopens the shorts feed.
     */
    var activePlayer: ExoPlayer? = null
        private set

    private val player: ExoPlayer
        get() = activePlayer ?: buildPlayer().also { activePlayer = it }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            // The playlist is driven manually, so never auto-advance / auto-loop.
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(playerListener)
            volume = if (_isMutedFlow.value) 0f else 1f
        }

    /**
     * Ensures the playlist contains [items] (from [startIndex] onwards) and starts playback of the
     * item at [startIndex]. Calling this repeatedly keeps a rolling forward window in the playlist
     * so the next video is always pre-buffered by Media3.
     */
    fun playFrom(items: List<ShortsItem>, startIndex: Int) {
        if (startIndex !in items.indices) return

        val playable = items.filter { it.streamUrl.isNotEmpty() }
        if (playable.isEmpty()) return

        val targetItem = items[startIndex]
        if (targetItem.streamUrl.isEmpty()) {
            // Skip non-playable entries gracefully.
            return
        }

        val exoPlayer = player

        // Build the playlist starting at the selected item so window 0 == current item.
        val window = items.subList(startIndex, items.size).filter { it.streamUrl.isNotEmpty() }
        val currentWindowIndex = window.indexOfFirst { it.id == targetItem.id }.coerceAtLeast(0)

        val alreadyAtTarget = currentItem?.id == targetItem.id
        if (alreadyAtTarget && exoPlayer.currentMediaItemIndex == currentWindowIndex) {
            if (!exoPlayer.isPlaying) exoPlayer.play()
            return
        }

        // Report stop for the item we are leaving.
        stopCurrentPlayback()

        playlistItems.clear()
        playlistItems.addAll(window)

        runCatching {
            val mediaItems = window.map { MediaItem.Builder().setUri(it.streamUrl).setMediaId(it.id.toString()).build() }
            // Resume from the server-saved playback position (ticks -> ms) when available.
            val startMs = (targetItem.playbackPositionTicks.takeIf { it > 0L } ?: 0L) / 10_000L
            exoPlayer.setMediaItems(mediaItems, currentWindowIndex, startMs)
            exoPlayer.prepare()
            exoPlayer.play()
        }.onFailure { e ->
            Timber.e(e, "Failed to start player for item: ${targetItem.id}")
        }

        currentItem = targetItem
        currentPlaySessionId = UUID.randomUUID().toString()
        _isPlayingFlow.value = exoPlayer.isPlaying

        reportPlaybackStart(targetItem)
        startProgressReporting()
    }

    fun togglePlayPause() {
        val exoPlayer = activePlayer ?: return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    /** Pauses playback without releasing the player (e.g. when the screen is paused). */
    fun pause() {
        activePlayer?.pause()
    }

    fun toggleMute() {
        val newMuteState = !_isMutedFlow.value
        _isMutedFlow.value = newMuteState
        activePlayer?.volume = if (newMuteState) 0f else 1f
    }

    private fun startProgressReporting() {
        progressReportJob?.cancel()
        progressReportJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                // Player values must be read on the main thread.
                val exoPlayer = activePlayer ?: continue
                val item = currentItem ?: continue
                val posMs = exoPlayer.currentPosition
                _currentPositionMs.value = posMs
                reportPlaybackProgress(item, posMs, exoPlayer.isPlaying)
            }
        }
    }

    private fun stopCurrentPlayback() {
        progressReportJob?.cancel()
        progressReportJob = null

        val item = currentItem ?: return

        // Read player state on the main thread before dispatching the network call.
        val finalPosMs = activePlayer?.currentPosition ?: 0L
        val finalPosTicks = finalPosMs.milliseconds.inWholeTicks
        val playSessionId = currentPlaySessionId
        val durationMs = activePlayer?.duration?.takeIf { it > 0L } ?: item.durationMs

        // "Watched" definition: only count as watched when at least 95% was played.
        val hasReachedWatchedThreshold = isWatched(finalPosMs, durationMs)

        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = item.id,
                        positionTicks = finalPosTicks,
                        playSessionId = playSessionId,
                        failed = false,
                    ),
                )

                // Mark as played on the server only when the watched threshold is met.
                if (hasReachedWatchedThreshold) {
                    val userEntity = apiClientController.loadSavedUser()
                    val userId = userEntity?.userId
                    if (userId != null) {
                        apiClient.playStateApi.markPlayedItem(
                            userId = userId,
                            itemId = item.id,
                        )
                    }
                }
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback stop")
            }
        }
    }

    /**
     * "Watched" definition per requirement: a video only counts as watched when at least
     * [WATCHED_THRESHOLD_PERCENT]% of its duration has been played.
     */
    private fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return positionMs > 0L
        return positionMs >= durationMs * WATCHED_THRESHOLD_PERCENT / 100L
    }

    private fun onVideoEnded() {
        val item = currentItem ?: return
        val durationMs = activePlayer?.duration?.takeIf { it > 0L } ?: item.durationMs
        val finalPosMs = activePlayer?.currentPosition ?: durationMs

        // STATE_ENDED with repeat-off: reached the end -> always past the threshold.
        if (isWatched(finalPosMs, durationMs)) {
            shortsRepository.markItemAsPlayedInMemory(item.id)
        }

        scope.launch(Dispatchers.IO) {
            try {
                val userEntity = apiClientController.loadSavedUser() ?: return@launch
                val userId = userEntity.userId
                if (isWatched(finalPosMs, durationMs)) {
                    apiClient.playStateApi.markPlayedItem(
                        userId = userId,
                        itemId = item.id,
                    )
                }
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to mark item as played")
            }
        }
    }

    private fun reportPlaybackStart(item: ShortsItem) {
        val isMuted = _isMutedFlow.value
        val playSessionId = currentPlaySessionId
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = item.id,
                        playSessionId = playSessionId,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        isPaused = false,
                        isMuted = isMuted,
                        canSeek = true,
                        positionTicks = item.playbackPositionTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback start")
            }
        }
    }

    private fun reportPlaybackProgress(item: ShortsItem, positionMs: Long, isPlaying: Boolean) {
        val isMuted = _isMutedFlow.value
        val playSessionId = currentPlaySessionId
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = item.id,
                        playSessionId = playSessionId,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        isPaused = !isPlaying,
                        isMuted = isMuted,
                        canSeek = true,
                        positionTicks = positionMs.milliseconds.inWholeTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback progress")
            }
        }
    }

    /**
     * Stops playback and releases the underlying player. Safe to call multiple times; the next
     * access to [activePlayer] recreates a fresh instance.
     */
    fun release() {
        stopCurrentPlayback()
        progressReportJob = null

        activePlayer?.let { exoPlayer ->
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
        activePlayer = null

        playlistItems.clear()
        currentItem = null
        _isPlayingFlow.value = false
    }

    private companion object {
        const val PROGRESS_REPORT_INTERVAL_MS = 10_000L

        /** A video counts as watched after 95% of its duration was played. */
        const val WATCHED_THRESHOLD_PERCENT = 95L
    }
}
