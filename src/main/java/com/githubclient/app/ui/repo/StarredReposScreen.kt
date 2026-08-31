package com.githubclient.app.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.components.RepoCard
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.SwipeRefreshIndicator
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * “我的星标”页面的 UI 状态。
 */
data class StarredReposUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val repos: List<Repository> = emptyList(),
    val error: String? = null
)

/**
 * “我的星标”页面 ViewModel。通过 Hilt 注入 [GitHubRepository]，
 * 调用 [GitHubRepository.getStarredRepos] 获取当前用户的星标仓库列表。
 */
@HiltViewModel
class StarredReposViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StarredReposUiState())
    val uiState: StateFlow<StarredReposUiState> = _uiState.asStateFlow()

    init {
        loadStarredRepos()
    }

    /** 首次加载 / 重试：用全屏加载状态替换当前内容。 */
    fun loadStarredRepos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetchStarredRepos(refreshing = false)
        }
    }

    /** 下拉刷新：保留当前列表可见，仅显示刷新指示器。 */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            fetchStarredRepos(refreshing = true)
        }
    }

    private suspend fun fetchStarredRepos(refreshing: Boolean) {
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            gitHubRepository.getStarredRepos()
        }
        if (result != null && result.isSuccess) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                repos = result.getOrNull().orEmpty(),
                error = null
            )
        } else {
            val message = result?.exceptionOrNull()?.message
                ?: "加载超时，请检查网络或代理设置后重试"
            // 刷新失败时若已有数据，则保留当前列表，仅停止刷新指示器。
            val showError = if (refreshing) _uiState.value.repos.isEmpty() else true
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                error = if (showError) message else null
            )
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

/**
 * “我的星标”页面。展示当前用户的星标仓库列表，支持下拉刷新。
 *
 * @param onBackClick 点击返回按钮的回调
 * @param onRepoClick 点击某个仓库时的回调，参数为 (owner, repo)
 * @param viewModel 由 Hilt 提供的 [StarredReposViewModel]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredReposScreen(
    onBackClick: () -> Unit,
    onRepoClick: (owner: String, repo: String) -> Unit,
    viewModel: StarredReposViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "我的星标",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GitHubBlue,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> LoadingState(message = "加载中...")
                uiState.error != null -> ErrorState(
                    message = uiState.error!!,
                    onRetry = { viewModel.loadStarredRepos() }
                )
                uiState.repos.isEmpty() -> EmptyStarredState()
                else -> StarredReposContent(
                    repos = uiState.repos,
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    onRepoClick = onRepoClick
                )
            }
        }
    }
}

/**
 * 星标仓库列表内容，包裹在 [SwipeRefresh] 中以支持下拉刷新。
 */
@Composable
private fun StarredReposContent(
    repos: List<Repository>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRepoClick: (owner: String, repo: String) -> Unit
) {
    SwipeRefresh(
        state = rememberSwipeRefreshState(isRefreshing = isRefreshing),
        onRefresh = onRefresh,
        indicator = { state, trigger ->
            SwipeRefreshIndicator(
                state = state,
                refreshTriggerDistance = trigger,
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = GitHubBlue
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(repos, key = { it.id }) { repo ->
                RepoCard(
                    repo = repo,
                    onClick = {
                        onRepoClick(repo.owner?.login ?: "", repo.name)
                    }
                )
            }
        }
    }
}

/**
 * 空状态：使用 [GitHubGreen] 星标图标提示用户暂无星标仓库。
 */
@Composable
private fun EmptyStarredState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = GitHubGreen.copy(alpha = 0.12f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = GitHubGreen
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无星标仓库",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在探索页面星标喜欢的仓库后，会显示在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
