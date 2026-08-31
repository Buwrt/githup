package com.githubclient.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.AuthRepository
import com.githubclient.app.util.OAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val token: String = "",
    val isTokenVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // 加载上次使用的 Token
        viewModelScope.launch {
            val lastToken = authRepository.getLastToken()
            if (lastToken != null) {
                _uiState.value = _uiState.value.copy(token = lastToken)
            }
        }
    }

    fun onTokenChange(token: String) {
        _uiState.value = _uiState.value.copy(token = token, error = null)
    }

    fun toggleTokenVisibility() {
        _uiState.value = _uiState.value.copy(isTokenVisible = !_uiState.value.isTokenVisible)
    }

    fun loginWithToken(onSuccess: () -> Unit) {
        val token = _uiState.value.token.trim()
        if (token.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "请输入 GitHub Personal Access Token")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authRepository.login(token)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "登录失败，请检查 Token 是否正确"
                )
            }
        }
    }

    /**
     * 使用 OAuth Web Flow 授权码换取 Token 并登录。
     * 由 OAuthLoginScreen 的 WebView 拦截到回调 URL 后调用。
     */
    fun exchangeOAuthCode(code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val token = OAuthManager.exchangeCodeForToken(code)
            if (token != null) {
                val loginResult = authRepository.login(token)
                loginResult.onSuccess {
                    onResult(true)
                }.onFailure {
                    onResult(false)
                }
            } else {
                onResult(false)
            }
        }
    }

    /**
     * 使用从 WebView 提取到的 Personal Access Token 直接登录。
     * 由 OAuthLoginScreen 在用户登录 GitHub 账号、自动创建令牌后调用。
     */
    fun exchangeTokenFromWeb(token: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val loginResult = authRepository.login(token.trim())
            loginResult.onSuccess {
                onResult(true)
            }.onFailure {
                onResult(false)
            }
        }
    }
}
