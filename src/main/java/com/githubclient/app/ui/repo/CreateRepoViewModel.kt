package com.githubclient.app.ui.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateRepoUiState(
    val name: String = "",
    val description: String = "",
    val isPrivate: Boolean = false,
    val autoInit: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

@HiltViewModel
class CreateRepoViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateRepoUiState())
    val uiState: StateFlow<CreateRepoUiState> = _uiState.asStateFlow()

    fun onNameChange(v: String) { _uiState.value = _uiState.value.copy(name = v) }
    fun onDescriptionChange(v: String) { _uiState.value = _uiState.value.copy(description = v) }
    fun onPrivateChange(v: Boolean) { _uiState.value = _uiState.value.copy(isPrivate = v) }
    fun onAutoInitChange(v: Boolean) { _uiState.value = _uiState.value.copy(autoInit = v) }

    fun create() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(error = "仓库名称不能为空")
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = gitHubRepository.createRepo(
                name = state.name.trim(),
                description = state.description.ifBlank { null },
                isPrivate = state.isPrivate,
                autoInit = state.autoInit
            )
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, success = true)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "创建失败"
                )
            }
        }
    }
}
