package org.jellyfin.mobile.shorts.ui

import androidx.compose.runtime.Composable
import org.jellyfin.mobile.ui.ComposeFragment
import org.koin.androidx.viewmodel.ext.android.viewModel

class ShortsFragment : ComposeFragment() {
    private val shortsViewModel: ShortsViewModel by viewModel()

    @Composable
    override fun Content() {
        ShortsScreen(
            viewModel = shortsViewModel,
            onBackPressed = {
                if (isAdded && !parentFragmentManager.isStateSaved) {
                    parentFragmentManager.popBackStackImmediate()
                }
            },
        )
    }
}
