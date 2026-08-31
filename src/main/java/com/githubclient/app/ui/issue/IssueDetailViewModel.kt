package com.githubclient.app.ui.issue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.data.remote.model.Comment
import com.githubclient.app.data.remote.model.Issue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IssueDetailUiState(
    val isLoading: Boolean = false,
    val issue: Issue? = null,
    val comments: List<Comment> = emptyList(),
    val error: String? = null,
    val isAddingComment: Boolean = false,
    val commentText: String = "",
    val commentAdded: Boolean = false
)

@HiltViewModel
class IssueDetailViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IssueDetailUiState())
    val uiState: StateFlow<IssueDetailUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repo: String = ""
    private var number: Int = 0

    fun loadIssue(owner: String, repo: String, number: Int) {
        this.owner = owner
        this.repo = repo
        this.number = number
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val issueResult = gitHubRepository.getIssue(owner, repo, number)
            issueResult.onSuccess { issue ->
                _uiState.value = _uiState.value.copy(issue = issue)
                loadComments()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    private fun loadComments() {
        viewModelScope.launch {
            val result = gitHubRepository.getIssueComments(owner, repo, number)
            result.onSuccess { comments ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    comments = comments
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun onCommentTextChange(text: String) {
        _uiState.value = _uiState.value.copy(commentText = text)
    }

    fun submitComment() {
        if (_uiState.value.commentText.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAddingComment = true)
            val result = gitHubRepository.addIssueComment(owner, repo, number, _uiState.value.commentText)
            result.onSuccess { comment ->
                _uiState.value = _uiState.value.copy(
                    isAddingComment = false,
                    commentText = "",
                    comments = _uiState.value.comments + comment,
                    commentAdded = true
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isAddingComment = false)
            }
        }
    }

    fun toggleState() {
        val issue = _uiState.value.issue ?: return
        viewModelScope.launch {
            val newState = if (issue.state == "open") "closed" else "open"
            val result = gitHubRepository.updateIssue(owner, repo, number, mapOf("state" to newState))
            result.onSuccess { updatedIssue ->
                _uiState.value = _uiState.value.copy(issue = updatedIssue)
            }
        }
    }
}
