package org.jellyfin.mobile.shorts.data

import org.jellyfin.mobile.shorts.model.ShortsItem
import java.util.UUID

/**
 * Source of shorts items backed by the Jellyfin server.
 *
 * Ordering algorithm (per requirements):
 * 1. Unwatched items first, newest first ("play the data source in order").
 * 2. When no unwatched items remain, fall back to random playback.
 * 3. New unwatched items added to the server automatically appear first (they are fetched before
 *    played ones on every batch request).
 *
 * The in-session "already shown" set is only used to avoid re-showing items within a single
 * session; "watched" state itself is authoritative on the server.
 */
interface ShortsRepository {
    suspend fun fetchShortsBatch(limit: Int = 5, targetLibraryId: UUID? = null): List<ShortsItem>

    suspend fun toggleFavorite(itemId: UUID, setFavorite: Boolean): Boolean

    suspend fun getLibraries(): List<ShortsLibrary>

    fun markItemAsPlayedInMemory(itemId: UUID)

    /** Clears in-session state, e.g. when the active server changes. */
    fun reset()
}

data class ShortsLibrary(
    val id: UUID,
    val name: String,
)
