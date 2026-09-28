package org.jellyfin.mobile.shorts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.shorts.model.ShortsItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DefaultShortsRepository(
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
) : ShortsRepository {

    /** Items already shown in this session (avoid duplicates while scrolling). */
    private val playedItemIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Cache of resolved stream URLs per item so replays don't re-resolve. */
    private val streamUrlCache: MutableMap<UUID, String> = ConcurrentHashMap()

    override suspend fun fetchShortsBatch(
        limit: Int,
        targetLibraryId: UUID?,
    ): List<ShortsItem> = withContext(Dispatchers.IO) {
        val dtoItems = try {
            fetchItemDtos(limit, targetLibraryId)
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to fetch shorts batch")
            return@withContext emptyList()
        }

        dtoItems.map { dto -> dto.toShortsItem() }
    }

    private suspend fun fetchItemDtos(limit: Int, targetLibraryId: UUID?): List<BaseItemDto> {
        val userEntity = apiClientController.loadSavedUser() ?: return emptyList()
        val userId = userEntity.userId

        val itemTypes = setOf(
            BaseItemKind.VIDEO,
            BaseItemKind.MOVIE,
            BaseItemKind.EPISODE,
            BaseItemKind.MUSIC_VIDEO,
        )
        val fields = setOf(
            ItemFields.MEDIA_SOURCES,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.OVERVIEW,
        )

        // 1. Unwatched items first, newest first (new server additions surface automatically).
        val unwatchedResponse by apiClient.itemsApi.getItems(
            userId = userId,
            parentId = targetLibraryId,
            includeItemTypes = itemTypes,
            filters = setOf(ItemFilter.IS_UNPLAYED),
            fields = fields,
            limit = limit,
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            sortOrder = listOf(SortOrder.DESCENDING),
            recursive = true,
        )

        val resultItems = unwatchedResponse.items.filterNot { it.id in playedItemIds }.toMutableList()

        // 2. Fill the remainder with random playback once the source has been watched.
        if (resultItems.size < limit) {
            val randomResponse by apiClient.itemsApi.getItems(
                userId = userId,
                parentId = targetLibraryId,
                includeItemTypes = itemTypes,
                fields = fields,
                limit = limit,
                sortBy = listOf(ItemSortBy.RANDOM),
                recursive = true,
            )

            randomResponse.items
                .filterNot { item -> resultItems.any { it.id == item.id } }
                .filterNot { it.id in playedItemIds }
                .shuffled()
                .take(limit - resultItems.size)
                .forEach { resultItems.add(it) }
        }

        // Respect the configurable duration cap so full-length movies don't flood the feed.
        return resultItems.filter { dto -> isWithinShortsDuration(dto) }
    }

    private fun isWithinShortsDuration(dto: BaseItemDto): Boolean {
        val runtimeMs = (dto.runTimeTicks ?: 0L) / 10_000L
        return runtimeMs in 0..MAX_SHORTS_DURATION_MS
    }

    override suspend fun toggleFavorite(itemId: UUID, setFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val userEntity = apiClientController.loadSavedUser() ?: return@withContext false
            val userId = userEntity.userId
            if (setFavorite) {
                apiClient.userLibraryApi.markFavoriteItem(userId = userId, itemId = itemId)
            } else {
                apiClient.userLibraryApi.unmarkFavoriteItem(userId = userId, itemId = itemId)
            }
            true
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to toggle favorite for $itemId")
            false
        }
    }

    override suspend fun getLibraries(): List<ShortsLibrary> = withContext(Dispatchers.IO) {
        try {
            val response by apiClient.userViewsApi.getUserViews()
            response.items
                .filter { dto ->
                    val type = dto.collectionType
                    type == null || type == CollectionType.MOVIES ||
                        type == CollectionType.HOMEVIDEOS || type == CollectionType.TVSHOWS
                }
                .map { dto -> ShortsLibrary(id = dto.id, name = dto.name.orEmpty()) }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to load libraries")
            emptyList()
        }
    }

    override fun markItemAsPlayedInMemory(itemId: UUID) {
        playedItemIds.add(itemId)
    }

    override fun reset() {
        playedItemIds.clear()
        streamUrlCache.clear()
    }

    private fun BaseItemDto.toShortsItem(): ShortsItem {
        val primaryTag = imageTags?.get(ImageType.PRIMARY)
        val imgUrl = if (primaryTag != null) {
            apiClient.imageApi.getItemImageUrl(
                itemId = id,
                imageType = ImageType.PRIMARY,
                tag = primaryTag,
            )
        } else null

        val videoUrl = streamUrlCache.getOrPut(id) {
            apiClient.videosApi.getVideoStreamUrl(
                itemId = id,
                static = true,
            )
        }

        return ShortsItem(
            id = id,
            name = name.orEmpty(),
            overview = overview,
            durationMs = (runTimeTicks ?: 0L) / 10_000L,
            imageUrl = imgUrl,
            streamUrl = videoUrl,
            isFavorite = userData?.isFavorite == true,
            isPlayed = userData?.played == true,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
            libraryId = parentId,
            aspectRatio = primaryImageAspectRatio?.toFloat() ?: (9f / 16f),
            baseItemDto = this,
        )
    }

    private companion object {
        // Feed only contains short videos: 15 minutes cap by default.
        const val MAX_SHORTS_DURATION_MS = 15L * 60L * 1000L
    }
}
