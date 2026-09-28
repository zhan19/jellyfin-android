package org.jellyfin.mobile.shorts.model

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

data class ShortsItem(
    val id: UUID,
    val name: String,
    val overview: String? = null,
    val durationMs: Long = 0L,
    val imageUrl: String? = null,
    val streamUrl: String = "",
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
    val playbackPositionTicks: Long = 0L,
    val libraryId: UUID? = null,
    val aspectRatio: Float = 9f / 16f,
    val baseItemDto: BaseItemDto? = null,
)
