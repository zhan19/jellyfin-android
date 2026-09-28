package org.jellyfin.mobile.shorts.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.shorts.data.ShortsLibrary
import org.jellyfin.mobile.shorts.data.ShortsLoadState
import org.jellyfin.mobile.shorts.data.ShortsQueueManager
import org.jellyfin.mobile.shorts.model.ShortsItem
import org.jellyfin.mobile.shorts.player.ShortsPlayerManager
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

data class ShortsUiState(
    val items: List<ShortsItem> = emptyList(),
    val isLoading: Boolean = false,
    val isFailed: Boolean = false,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

class ShortsViewModel(
    val queueManager: ShortsQueueManager,
    val playerManager: ShortsPlayerManager,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /** Libraries available as shorts sources (方案A: user-selected library). */
    private val _librariesFlow = MutableStateFlow<List<ShortsLibrary>>(emptyList())
    val librariesFlow: StateFlow<List<ShortsLibrary>> = _librariesFlow.asStateFlow()

    /** Currently selected shorts library id, or null for "all libraries". */
    private val _selectedLibraryIdFlow = MutableStateFlow<UUID?>(null)
    val selectedLibraryIdFlow: StateFlow<UUID?> = _selectedLibraryIdFlow.asStateFlow()

    /** Whether the library picker should be shown (first entry without a saved library). */
    private val _showLibraryPickerFlow = MutableStateFlow(false)
    val showLibraryPickerFlow: StateFlow<Boolean> = _showLibraryPickerFlow.asStateFlow()

    val uiState: StateFlow<ShortsUiState> = combine(
        queueManager.itemsFlow,
        queueManager.loadStateFlow,
        playerManager.isPlayingFlow,
        playerManager.isMutedFlow,
        playerManager.currentPositionMs,
    ) { items, loadState, isPlaying, isMuted, positionMs ->
        ShortsUiState(
            items = items,
            // Idle (initial state) and Loading both render as loading so the "no shorts"
            // empty state never flashes while the first batch is being fetched.
            isLoading = loadState == ShortsLoadState.Loading || loadState == ShortsLoadState.Idle,
            isFailed = loadState == ShortsLoadState.Failed,
            isPlaying = isPlaying,
            isMuted = isMuted,
            positionMs = positionMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ShortsUiState(),
    )

    fun initialize() {
        // 方案A: restore the user-selected shorts library, if any.
        val savedLibraryId = appPreferences.shortsLibraryId?.toUUIDOrNull()
        _selectedLibraryIdFlow.value = savedLibraryId

        // Show the picker when the user has not chosen a library yet.
        _showLibraryPickerFlow.value = savedLibraryId == null

        queueManager.initialize(savedLibraryId)

        if (savedLibraryId == null && _librariesFlow.value.isEmpty()) {
            loadLibraries()
        }
    }

    private fun loadLibraries() {
        viewModelScope.launch {
            _librariesFlow.value = queueManager.fetchLibraries()
        }
    }

    fun selectLibrary(libraryId: UUID?) {
        appPreferences.shortsLibraryId = libraryId?.toString()
        _selectedLibraryIdFlow.value = libraryId
        _showLibraryPickerFlow.value = false
        queueManager.initialize(libraryId)
    }

    fun dismissLibraryPicker() {
        // "All libraries" fallback when the user dismisses the picker.
        if (_selectedLibraryIdFlow.value == null) {
            _showLibraryPickerFlow.value = false
        }
    }

    fun retry() {
        queueManager.retry()
    }

    fun onPageSelected(index: Int) {
        queueManager.onPageSelected(index)

        val items = queueManager.itemsFlow.value
        if (index in items.indices) {
            playerManager.playFrom(items, index)
        }
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun toggleMute() {
        playerManager.toggleMute()
    }

    fun toggleFavorite(index: Int) {
        viewModelScope.launch {
            queueManager.toggleFavorite(index)
        }
    }

    override fun onCleared() {
        playerManager.release()
    }
}
