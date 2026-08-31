package com.githubclient.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.AuthRepository
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.data.remote.model.Event
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.remote.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val events: List<Event> = emptyList(),
    val repos: List<Repository> = emptyList(),
    val currentUser: User? = null,
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadCurrentUser()
        loadEvents()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val result = withTimeoutOrNull(15000) { authRepository.getCurrentUser() }
            if (result != null && result.isSuccess) {
                _uiState.value = _uiState.value.copy(currentUser = result.getOrNull())
                loadMyRepos()
            } else {
                // 超时或失败，停止加载状态
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "网络连接超时，请检查网络或开启代理后重试"
                )
            }
        }
    }

    private fun loadMyRepos() {
        viewModelScope.launch {
            val result = withTimeoutOrNull(15000) { gitHubRepository.getMyRepos(page = 1) }
            if (result != null && result.isSuccess) {
                _uiState.value = _uiState.value.copy(repos = result.getOrNull().orEmpty())
            }
        }
    }

    fun loadEvents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, page = 1)
            val username = _uiState.value.currentUser?.login ?: authRepository.getCurrentUser().getOrNull()?.login
            if (username == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "无法获取用户信息，请检查网络或代理")
                return@launch
            }

            val result = withTimeoutOrNull(15000) { gitHubRepository.getUserReceivedEvents(username, page = 1) }
            if (result != null && result.isSuccess) {
                val events = result.getOrNull().orEmpty()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    events = events,
                    hasMore = events.size >= 30
                )
                if (events.isEmpty() && _uiState.value.repos.isEmpty()) {
                    loadMyRepos()
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result?.exceptionOrNull()?.message ?: "网络超时，请开启代理后重试"
                )
                if (_uiState.value.repos.isEmpty()) {
                    loadMyRepos()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, page = 1)
            val username = _uiState.value.currentUser?.login ?: return@launch
            val result = gitHubRepository.getUserReceivedEvents(username, page = 1)
            result.onSuccess { events ->
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    events = events,
                    hasMore = events.size >= 30
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message ?: "刷新失败"
                )
            }
            // 同时刷新仓库列表
            loadMyRepos()
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        viewModelScope.launch {
            val nextPage = _uiState.value.page + 1
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val username = _uiState.value.currentUser?.login ?: return@launch
            val result = gitHubRepository.getUserReceivedEvents(username, page = nextPage)
            result.onSuccess { events ->
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    page = nextPage,
                    events = _uiState.value.events + events,
                    hasMore = events.size >= 30
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    // ===== Fork/Clone 仓库 =====
    data class ForkResult(val loading: Boolean = false, val success: String? = null, val error: String? = null)
    private val _forkState = MutableStateFlow(ForkResult())
    val forkState: StateFlow<ForkResult> = _forkState.asStateFlow()

    fun forkRepo(url: String) {
        // 解析 URL: https://github.com/owner/repo 或 git@github.com:owner/repo.git
        val pattern = Regex("""github\.com[/:]([^/]+)/([^/.\s]+)""")
        val match = pattern.find(url)
        if (match == null) {
            _forkState.value = ForkResult(error = "无法解析仓库地址，请检查 URL 格式")
            return
        }
        val (owner, repo) = match.destructured

        viewModelScope.launch {
            _forkState.value = ForkResult(loading = true)
            val result = withTimeoutOrNull(30000) { gitHubRepository.forkRepo(owner, repo) }
            if (result != null && result.isSuccess) {
                val forkedRepo = result.getOrNull()
                _forkState.value = ForkResult(
                    success = "克隆成功！仓库已复制到 ${forkedRepo?.fullName ?: "$owner/$repo"}"
                )
                // 刷新仓库列表
                loadMyRepos()
            } else {
                _forkState.value = ForkResult(
                    error = result?.exceptionOrNull()?.message ?: "克隆失败，请检查网络或权限"
                )
            }
        }
    }

    fun clearForkState() {
        _forkState.value = ForkResult()
    }
}
