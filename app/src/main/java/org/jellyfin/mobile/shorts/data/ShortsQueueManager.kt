package org.jellyfin.mobile.shorts.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.shorts.model.ShortsItem
import timber.log.Timber
import java.util.UUID

/** Loading state exposed to the UI so empty vs. failure can be distinguished. */
enum class ShortsLoadState {
    Idle,
    Loading,
    Loaded,
    Failed,
}

class ShortsQueueManager(
    private val shortsRepository: ShortsRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mutex = Mutex()

    private val _itemsFlow = MutableStateFlow<List<ShortsItem>>(emptyList())
    val itemsFlow: StateFlow<List<ShortsItem>> = _itemsFlow.asStateFlow()

    private val _loadStateFlow = MutableStateFlow(ShortsLoadState.Idle)
    val loadStateFlow: StateFlow<ShortsLoadState> = _loadStateFlow.asStateFlow()

    private var targetLibraryId: UUID? = null

    private fun preloadThreshold(): Int = PRELOAD_THRESHOLD
    private fun batchSize(): Int = BATCH_SIZE

    /**
     * (Re)initializes the feed. Called every time the shorts screen is opened, which also clears
     * any state left from a previous server / session.
     */
    fun initialize(libraryId: UUID? = null) {
        this.targetLibraryId = libraryId
        // Drop stale in-session state from a previous server before reloading.
        shortsRepository.reset()
        loadInitial()
    }

    fun loadInitial() {
        scope.launch {
            mutex.withLock {
                _isLoadingFlowSet(ShortsLoadState.Loading, clearItems = true)
            }

            val newBatch = shortsRepository.fetchShortsBatch(
                limit = batchSize(),
                targetLibraryId = targetLibraryId,
            )

            mutex.withLock {
                _loadStateFlow.value = if (newBatch.isEmpty()) ShortsLoadState.Failed else ShortsLoadState.Loaded
                if (newBatch.isNotEmpty()) {
                    _itemsFlow.value = newBatch
                }
            }
        }
    }

    private suspend fun _isLoadingFlowSet(state: ShortsLoadState, clearItems: Boolean) {
        _loadStateFlow.value = state
        if (clearItems) _itemsFlow.value = emptyList()
    }

    fun onPageSelected(currentIndex: Int) {
        val currentList = _itemsFlow.value
        if (currentList.isEmpty()) return

        // Trigger the next preload when the user is within 3 items of the end of the loaded list.
        if (currentIndex >= (currentList.size - preloadThreshold()) && _loadStateFlow.value == ShortsLoadState.Loaded) {
            preloadNextBatch()
        }
    }

    private fun preloadNextBatch() {
        scope.launch {
            mutex.withLock {
                if (_loadStateFlow.value != ShortsLoadState.Loaded) return@launch
                _loadStateFlow.value = ShortsLoadState.Loading
            }

            Timber.d("Preloading next batch of shorts...")
            val newBatch = shortsRepository.fetchShortsBatch(
                limit = batchSize(),
                targetLibraryId = targetLibraryId,
            )

            mutex.withLock {
                val existingIds = _itemsFlow.value.map { it.id }.toSet()
                val uniqueNewItems = newBatch.filterNot { it.id in existingIds }
                _itemsFlow.value = _itemsFlow.value + uniqueNewItems
                _loadStateFlow.value = ShortsLoadState.Loaded
            }
        }
    }

    fun retry() {
        loadInitial()
    }

    /** Exposes the available libraries so the UI can offer a source picker (方案A). */
    suspend fun fetchLibraries(): List<ShortsLibrary> = shortsRepository.getLibraries()

    suspend fun toggleFavorite(index: Int) {
        val currentList = _itemsFlow.value
        if (index !in currentList.indices) return

        val targetItem = currentList[index]
        val newFavoriteState = !targetItem.isFavorite

        val success = shortsRepository.toggleFavorite(targetItem.id, newFavoriteState)
        if (success) {
            mutex.withLock {
                val updatedList = _itemsFlow.value.toMutableList()
                if (index in updatedList.indices) {
                    updatedList[index] = updatedList[index].copy(isFavorite = newFavoriteState)
                    _itemsFlow.value = updatedList
                }
            }
        }
    }

    private companion object {
        const val PRELOAD_THRESHOLD = 3
        const val BATCH_SIZE = 5
    }
}
