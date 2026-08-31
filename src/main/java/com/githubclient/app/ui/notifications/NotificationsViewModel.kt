package com.githubclient.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.remote.model.Notification
import com.githubclient.app.data.repository.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val notifications: List<Notification> = emptyList(),
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val unreadCount: Int = 0,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, page = 1)
            val result = gitHubRepository.getMyNotifications(page = 1)
            result.onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notifications = list,
                    unreadCount = list.count { it.unread },
                    hasMore = list.size >= 30
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false, error = e.message ?: "加载失败"
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, page = 1)
            val result = gitHubRepository.getMyNotifications(page = 1)
            result.onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    notifications = list,
                    unreadCount = list.count { it.unread },
                    hasMore = list.size >= 30
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        viewModelScope.launch {
            val nextPage = _uiState.value.page + 1
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val result = gitHubRepository.getMyNotifications(page = nextPage)
            result.onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    page = nextPage,
                    notifications = _uiState.value.notifications + list,
                    hasMore = list.size >= 30
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    fun markRead(notification: Notification) {
        viewModelScope.launch {
            gitHubRepository.markNotificationRead(notification.id)
            _uiState.value = _uiState.value.copy(
                notifications = _uiState.value.notifications.map {
                    if (it.id == notification.id) it.copy(unread = false) else it
                }
            )
            _uiState.value = _uiState.value.copy(
                unreadCount = _uiState.value.notifications.count { it.unread }
            )
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            gitHubRepository.markAllNotificationsRead()
            _uiState.value = _uiState.value.copy(
                notifications = _uiState.value.notifications.map { it.copy(unread = false) },
                unreadCount = 0
            )
        }
    }

    // ===== 多选删除功能 =====

    fun enterMultiSelectMode() {
        _uiState.value = _uiState.value.copy(isMultiSelectMode = true)
    }

    fun exitMultiSelectMode() {
        _uiState.value = _uiState.value.copy(isMultiSelectMode = false, selectedIds = emptySet())
    }

    fun toggleSelection(id: String) {
        val current = _uiState.value.selectedIds
        val newSet = if (current.contains(id)) current - id else current + id
        _uiState.value = _uiState.value.copy(selectedIds = newSet)
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedIds = _uiState.value.notifications.map { it.id }.toSet()
        )
    }

    fun unselectAll() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            var failedCount = 0
            for (id in ids) {
                val result = gitHubRepository.deleteNotification(id)
                if (result.isFailure) failedCount++
            }
            _uiState.value = _uiState.value.copy(
                isDeleting = false,
                isMultiSelectMode = false,
                selectedIds = emptySet(),
                notifications = _uiState.value.notifications.filter { it.id !in ids },
                unreadCount = _uiState.value.notifications.filter { it.id !in ids }.count { it.unread },
            )
        }
    }
}
