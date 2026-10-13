package io.github.nasmonitor.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberPullRefreshState(refreshing, onRefresh = { if (!refreshing) onRefresh() })
    Box(modifier.fillMaxSize().pullRefresh(state, enabled = !refreshing)) {
        content()
        PullRefreshIndicator(refreshing, state, Modifier.align(Alignment.TopCenter), backgroundColor = OverlaySurface, contentColor = Ink)
    }
}
