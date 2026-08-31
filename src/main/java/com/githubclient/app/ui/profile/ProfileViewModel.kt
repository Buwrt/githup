package com.githubclient.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.remote.model.User
import com.githubclient.app.data.repository.AuthRepository
import com.githubclient.app.data.repository.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val repos: List<Repository> = emptyList(),
    val isFollowing: Boolean = false,
    val error: String? = null,
    val isMe: Boolean = false,
    val isMultiSelectMode: Boolean = false,
    val selectedRepoIds: Set<Long> = emptySet(),
    val isDeleting: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun load(username: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, isMe = username == null)
            val userResult = if (username == null) authRepository.getCurrentUser()
            else gitHubRepository.getUser(username)

            userResult.onSuccess { user ->
                val reposResult = gitHubRepository.getUserRepos(user.login, page = 1)
                val repos = reposResult.getOrNull().orEmpty().sortedByDescending { it.stargazersCount }

                val following = if (username != null) {
                    gitHubRepository.checkFollowing(user.login).getOrNull() ?: false
                } else false

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    user = user,
                    repos = repos,
                    isFollowing = following,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false, error = e.message ?: "加载失败"
                )
            }
        }
    }

    fun toggleFollow() {
        val user = _uiState.value.user ?: return
        viewModelScope.launch {
            if (_uiState.value.isFollowing) {
                gitHubRepository.unfollowUser(user.login)
            } else {
                gitHubRepository.followUser(user.login)
            }
            _uiState.value = _uiState.value.copy(isFollowing = !_uiState.value.isFollowing)
        }
    }

    fun logout() {
        authRepository.logout()
    }

    // ===== 多选删除仓库 =====

    fun enterMultiSelectMode() {
        _uiState.value = _uiState.value.copy(isMultiSelectMode = true)
    }

    fun exitMultiSelectMode() {
        _uiState.value = _uiState.value.copy(isMultiSelectMode = false, selectedRepoIds = emptySet())
    }

    fun toggleSelection(repoId: Long) {
        val current = _uiState.value.selectedRepoIds
        val newSet = if (current.contains(repoId)) current - repoId else current + repoId
        _uiState.value = _uiState.value.copy(selectedRepoIds = newSet)
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedRepoIds = _uiState.value.repos.map { it.id }.toSet()
        )
    }

    fun unselectAll() {
        _uiState.value = _uiState.value.copy(selectedRepoIds = emptySet())
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedRepoIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val repos = _uiState.value.repos
            for (id in ids) {
                val repo = repos.find { it.id == id } ?: continue
                val owner = repo.owner?.login ?: _uiState.value.user?.login ?: continue
                gitHubRepository.deleteRepo(owner, repo.name)
            }
            _uiState.value = _uiState.value.copy(
                isDeleting = false,
                isMultiSelectMode = false,
                selectedRepoIds = emptySet(),
                repos = repos.filter { it.id !in ids },
            )
        }
    }
}
