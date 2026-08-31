package com.githubclient.app.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.data.remote.model.Issue
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.remote.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class SearchTab { REPOS, USERS, ISSUES }

enum class SortOption(val apiValue: String, val displayName: String) {
    BEST_MATCH("", "最佳匹配"),
    MOST_STARS("stars", "最多星标"),
    FEWEST_STARS("stars", "最少星标"),
    MOST_FORKS("forks", "最多复刻"),
    FEWEST_FORKS("forks", "最少复刻"),
    RECENTLY_UPDATED("updated", "最近更新"),
    LEAST_RECENTLY_UPDATED("updated", "最早更新");

    val isDescending: Boolean
        get() = when (this) {
            FEWEST_STARS, FEWEST_FORKS, LEAST_RECENTLY_UPDATED -> false
            else -> true
        }

    val sortParam: String
        get() = when (this) {
            BEST_MATCH -> ""
            MOST_STARS, FEWEST_STARS -> "stars"
            MOST_FORKS, FEWEST_FORKS -> "forks"
            RECENTLY_UPDATED, LEAST_RECENTLY_UPDATED -> "updated"
        }
}

data class ExploreUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val query: String = "",
    val selectedTab: SearchTab = SearchTab.REPOS,
    val sortOption: SortOption = SortOption.BEST_MATCH,
    val repos: List<Repository> = emptyList(),
    val users: List<User> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val trendingRepos: List<Repository> = emptyList(),
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val totalCount: Int = 0
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadTrending()
    }

    private fun loadTrending() {
        viewModelScope.launch {
            val result = withTimeoutOrNull(15000) {
                gitHubRepository.searchRepos("stars:>10000", page = 1)
            }
            if (result != null && result.isSuccess) {
                _uiState.value = _uiState.value.copy(trendingRepos = result.getOrNull()?.items?.take(10).orEmpty())
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                repos = emptyList(),
                users = emptyList(),
                issues = emptyList(),
                totalCount = 0,
                page = 1,
                hasMore = true
            )
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            search()
        }
    }

    fun onTabChange(tab: SearchTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
        if (_uiState.value.query.isNotBlank()) {
            search()
        }
    }

    fun onSortChange(sort: SortOption) {
        _uiState.value = _uiState.value.copy(sortOption = sort)
        if (_uiState.value.query.isNotBlank()) {
            search()
        }
    }

    private fun search() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, page = 1)
            val query = _uiState.value.query
            val tab = _uiState.value.selectedTab
            val sort = _uiState.value.sortOption

            var errorMsg: String? = null
            when (tab) {
                SearchTab.REPOS -> {
                    val result = withTimeoutOrNull(15000) {
                        gitHubRepository.searchRepos(
                            query = query,
                            page = 1,
                            sort = if (sort == SortOption.BEST_MATCH) null else sort.sortParam,
                            order = if (sort.isDescending) "desc" else "asc"
                        )
                    }
                    if (result != null && result.isSuccess) {
                        val response = result.getOrNull()!!
                        _uiState.value = _uiState.value.copy(
                            repos = response.items,
                            totalCount = response.totalCount,
                            hasMore = response.items.size >= 30
                        )
                    } else {
                        errorMsg = result?.exceptionOrNull()?.message ?: "搜索超时，请开启代理后重试"
                    }
                }
                SearchTab.USERS -> {
                    val result = withTimeoutOrNull(15000) {
                        gitHubRepository.searchUsers(query, page = 1)
                    }
                    if (result != null && result.isSuccess) {
                        val response = result.getOrNull()!!
                        _uiState.value = _uiState.value.copy(
                            users = response.items,
                            totalCount = response.totalCount,
                            hasMore = response.items.size >= 30
                        )
                    } else {
                        errorMsg = result?.exceptionOrNull()?.message ?: "搜索超时"
                    }
                }
                SearchTab.ISSUES -> {
                    val result = withTimeoutOrNull(15000) {
                        gitHubRepository.searchIssues(query, page = 1)
                    }
                    if (result != null && result.isSuccess) {
                        val response = result.getOrNull()!!
                        _uiState.value = _uiState.value.copy(
                            issues = response.items,
                            totalCount = response.totalCount,
                            hasMore = response.items.size >= 30
                        )
                    } else {
                        errorMsg = result?.exceptionOrNull()?.message ?: "搜索超时"
                    }
                }
            }
            _uiState.value = _uiState.value.copy(isLoading = false, error = errorMsg)
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        viewModelScope.launch {
            val nextPage = _uiState.value.page + 1
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val query = _uiState.value.query
            val tab = _uiState.value.selectedTab
            val sort = _uiState.value.sortOption

            when (tab) {
                SearchTab.REPOS -> {
                    val result = gitHubRepository.searchRepos(
                        query = query,
                        page = nextPage,
                        sort = if (sort == SortOption.BEST_MATCH) null else sort.sortParam,
                        order = if (sort.isDescending) "desc" else "asc"
                    )
                    result.onSuccess { response ->
                        _uiState.value = _uiState.value.copy(
                            repos = _uiState.value.repos + response.items,
                            hasMore = response.items.size >= 30
                        )
                    }
                }
                SearchTab.USERS -> {
                    val result = gitHubRepository.searchUsers(query, page = nextPage)
                    result.onSuccess { response ->
                        _uiState.value = _uiState.value.copy(
                            users = _uiState.value.users + response.items,
                            hasMore = response.items.size >= 30
                        )
                    }
                }
                SearchTab.ISSUES -> {
                    val result = gitHubRepository.searchIssues(query, page = nextPage)
                    result.onSuccess { response ->
                        _uiState.value = _uiState.value.copy(
                            issues = _uiState.value.issues + response.items,
                            hasMore = response.items.size >= 30
                        )
                    }
                }
            }
            _uiState.value = _uiState.value.copy(
                isLoadingMore = false,
                page = nextPage
            )
        }
    }
}
