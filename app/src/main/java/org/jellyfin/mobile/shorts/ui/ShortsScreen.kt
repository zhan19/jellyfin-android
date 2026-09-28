package org.jellyfin.mobile.shorts.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.shorts.data.ShortsLibrary
import org.jellyfin.mobile.shorts.model.ShortsItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel,
    onBackPressed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val libraries by viewModel.librariesFlow.collectAsState()
    val showPicker by viewModel.showLibraryPickerFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            uiState.isLoading && uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            uiState.isFailed && uiState.items.isEmpty() -> {
                ErrorState(
                    message = stringResource(R.string.shorts_load_failed),
                    onRetry = { viewModel.retry() },
                )
            }
            uiState.items.isNotEmpty() -> {
                ShortsFeedContent(
                    viewModel = viewModel,
                    uiState = uiState,
                )
            }
            else -> {
                ErrorState(
                    message = stringResource(R.string.shorts_empty),
                    onRetry = { viewModel.retry() },
                )
            }
        }

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackPressed) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.shorts_back),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.shorts_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.toggleMute() }) {
                Icon(
                    imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.shorts_toggle_mute),
                    tint = Color.White,
                )
            }
        }
    }

    // 方案A: library picker shown on first entry without a saved selection.
    if (showPicker && libraries.isNotEmpty()) {
        LibraryPickerDialog(
            libraries = libraries,
            onSelect = { library -> viewModel.selectLibrary(library?.id) },
            onDismiss = { viewModel.dismissLibraryPicker() },
        )
    }
}

@Composable
private fun ShortsFeedContent(
    viewModel: ShortsViewModel,
    uiState: ShortsUiState,
) {
    val pagerState = rememberPagerState { uiState.items.size }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.onPageSelected(pagerState.currentPage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // A single, always-mounted PlayerView renders the video surface for the whole feed.
        // Keeping it out of the pager avoids tearing down / swapping the Surface on every swipe,
        // which previously caused the "audio but no video" glitch.
        ShortsPlayerSurface(viewModel = viewModel)

        // The pager only renders the per-page overlay UI (title, actions, gradient).
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val item = uiState.items.getOrNull(pageIndex)
            if (item != null) {
                ShortPageOverlay(
                    item = item,
                    uiState = uiState,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onToggleFavorite = { viewModel.toggleFavorite(pageIndex) },
                )
            }
        }

        // Bottom progress bar (server-synced position).
        if (uiState.durationMs > 0) {
            LinearProgressIndicator(
                progress = (uiState.positionMs.toFloat() / uiState.durationMs).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = MaterialTheme.colors.primary,
                backgroundColor = Color.White.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.shorts_retry))
            }
        }
    }
}

@Composable
private fun ShortsPlayerSurface(viewModel: ShortsViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { playerView ->
            val player = viewModel.playerManager.activePlayer
            if (playerView.player !== player) {
                playerView.player = player
            }
            playerView.post {
                // After layout, make sure the player is bound so the surface receives frames.
                val current = viewModel.playerManager.activePlayer
                if (playerView.player !== current) {
                    playerView.player = current
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    // Pause playback when the screen is no longer resumed to avoid background audio/video leaks.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.playerManager.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun ShortPageOverlay(
    item: ShortsItem,
    uiState: ShortsUiState,
    onTogglePlayPause: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTogglePlayPause() },
                    onDoubleTap = { onToggleFavorite() },
                )
            },
    ) {
        // Bottom Gradient Overlay for text contrast
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
        ) {
            // Right Action Column
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.shorts_favorite),
                        tint = if (item.isFavorite) Color.Red else Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.shorts_toggle_play_pause),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Bottom Text Info Column
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.8f),
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!item.overview.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.overview,
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryPickerDialog(
    libraries: List<ShortsLibrary>,
    onSelect: (ShortsLibrary?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.shorts_title)) },
        text = {
            Column {
                libraries.forEach { library ->
                    TextButton(onClick = { onSelect(library) }) {
                        Text(text = library.name)
                    }
                }
                TextButton(onClick = { onSelect(null) }) {
                    Text(text = stringResource(R.string.shorts_library_picker_all))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
