package com.githubclient.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.data.repository.AuthRepository
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubPurple
import com.githubclient.app.ui.theme.GitHubRed
import com.githubclient.app.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class TokenSettingsUiState(
    val currentToken: String? = null,
    val maskedToken: String = "未设置",
    val isLoggedIn: Boolean = false,
    val inputToken: String = "",
    val isTokenVisible: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TokenSettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TokenSettingsUiState())
    val uiState: StateFlow<TokenSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        val token = authRepository.getToken()
        _uiState.value = _uiState.value.copy(
            currentToken = token,
            maskedToken = maskToken(token),
            isLoggedIn = authRepository.isLoggedIn()
        )
    }

    fun onTokenChange(token: String) {
        _uiState.value = _uiState.value.copy(inputToken = token, error = null)
    }

    fun toggleTokenVisibility() {
        _uiState.value = _uiState.value.copy(isTokenVisible = !_uiState.value.isTokenVisible)
    }

    fun saveToken(onSaved: () -> Unit) {
        val token = _uiState.value.inputToken.trim()
        if (token.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "请输入 Personal Access Token")
            return
        }
        authRepository.saveToken(token)
        _uiState.value = _uiState.value.copy(inputToken = "", error = null)
        refresh()
        onSaved()
    }

    fun clearToken(onCleared: () -> Unit) {
        authRepository.clearToken()
        _uiState.value = _uiState.value.copy(inputToken = "", error = null)
        refresh()
        onCleared()
    }

    companion object {
        /**
         * 对 Token 做脱敏处理：仅显示前 4 位和后 4 位，中间以圆点占位。
         * Token 过短时整段以圆点显示，未设置时返回"未设置"。
         */
        fun maskToken(token: String?): String {
            if (token.isNullOrBlank()) return "未设置"
            val t = token.trim()
            if (t.length <= 8) return "•".repeat(t.length.coerceAtMost(6))
            return "${t.take(4)}••••••${t.takeLast(4)}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenSettingsScreen(
    onBack: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onTokenSaved: () -> Unit,
    viewModel: TokenSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除 Token") },
            text = { Text("清除后将退出登录并无法继续访问私有数据，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    // onTokenSaved 在此统一表示 Token 状态已变更，调用方可据此刷新或跳转登录
                    viewModel.clearToken { onTokenSaved() }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Token 设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ===== 当前 Token 状态 =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = GitHubPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "当前 Token",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.maskedToken,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "状态：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isLoggedIn) "已登录" else "未登录",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (uiState.isLoggedIn) GitHubGreen else GitHubRed
                        )
                    }
                }
            }

            // ===== 更新 Token 输入 =====
            OutlinedTextField(
                value = uiState.inputToken,
                onValueChange = viewModel::onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新的 Personal Access Token") },
                placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx") },
                singleLine = true,
                visualTransformation = if (uiState.isTokenVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleTokenVisibility) {
                        Icon(
                            imageVector = if (uiState.isTokenVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription = if (uiState.isTokenVisible) "隐藏" else "显示"
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // ===== 保存按钮 =====
            Button(
                onClick = { viewModel.saveToken { onTokenSaved() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = uiState.inputToken.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF238636),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存 Token", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            // ===== 创建 Token 链接 =====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenWeb(Constants.GITHUB_TOKEN_SETTINGS_URL, "创建 Token")
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        tint = GitHubBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "在 GitHub 创建 Token",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            Constants.GITHUB_TOKEN_SETTINGS_URL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ===== 清除 / 退出登录按钮 =====
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("清除 Token / 退出登录", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            // ===== 错误提示 =====
            val errMsg = uiState.error
            if (errMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
