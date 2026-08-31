package com.githubclient.app.ui.settings

import android.app.Activity
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.BuildConfig
import com.githubclient.app.data.repository.AuthRepository
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubRed
import com.githubclient.app.util.AppSettings
import com.githubclient.app.util.Constants
import com.githubclient.app.util.UpdateChecker
import com.githubclient.app.util.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(authRepository.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun logout() {
        authRepository.logout()
        _isLoggedIn.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToTokenSettings: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()

    // 使用 AppSettings 持久化设置 - 通过 settingsChanged 触发刷新
    val settingsVersion by AppSettings.settingsChanged.collectAsState()
    val darkMode by AppSettings.darkMode.collectAsState()
    val language by AppSettings.language.collectAsState()
    val pushEnabled by AppSettings.pushEnabled.collectAsState()
    val emailEnabled by AppSettings.emailEnabled.collectAsState()
    val repoActivityEnabled by AppSettings.repoActivityEnabled.collectAsState()
    val downloadPath by AppSettings.downloadPath.collectAsState()
    val autoCheckUpdate by AppSettings.autoCheckUpdate.collectAsState()

    var darkModeExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var showDownloadPathDialog by remember { mutableStateOf(false) }
    var showLanguageRestartDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    val updateChecking by UpdateChecker.checking.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingLanguage by remember { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("设置"), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ===== 账户设置 =====
            item { SectionHeader(tr("账户设置")) }
            item {
                SettingsCard {
                    SettingClickRow(
                        icon = Icons.Filled.AccountCircle,
                        iconTint = GitHubBlue,
                        title = tr("个人资料"),
                        subtitle = tr("管理 GitHub 账户信息"),
                        showDivider = true,
                        onClick = { onOpenWeb(Constants.GITHUB_ACCOUNT_SETTINGS_URL, tr("个人资料")) }
                    )
                    SettingClickRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = GitHubRed,
                        title = tr("退出登录"),
                        subtitle = if (isLoggedIn) tr("已登录，点击退出") else tr("当前未登录"),
                        showDivider = false,
                        onClick = {
                            viewModel.logout()
                            onLogout()
                        }
                    )
                }
            }

            // ===== 外观设置 =====
            item { SectionHeader(tr("外观设置")) }
            item {
                SettingsCard {
                    // 深色模式
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { darkModeExpanded = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.DarkMode,
                                contentDescription = null,
                                tint = GitHubBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("深色模式"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    tr("切换深色 / 浅色 / 跟随系统"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                AppSettings.getDarkModeName(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = darkModeExpanded,
                            onDismissRequest = { darkModeExpanded = false }
                        ) {
                            AppSettings.darkModeOptions.forEachIndexed { index, option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (index == darkMode) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(end = 8.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(option)
                                        }
                                    },
                                    onClick = {
                                        AppSettings.setDarkMode(index)
                                        darkModeExpanded = false
                                        // 重新创建 Activity 以应用主题
                                        (context as? Activity)?.recreate()
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )

                    // 语言
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { languageExpanded = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Language,
                                contentDescription = null,
                                tint = GitHubBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("语言"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    tr("应用界面语言"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                AppSettings.getLanguageName(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            AppSettings.languageOptions.forEachIndexed { index, option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (index == language) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(end = 8.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(option)
                                        }
                                    },
                                    onClick = {
                                        languageExpanded = false
                                        if (index != language) {
                                            AppSettings.setLanguage(index)
                                            // 重新创建 Activity 以应用语言
                                            (context as? Activity)?.recreate()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ===== 通知设置 =====
            item { SectionHeader(tr("通知设置")) }
            item {
                SettingsCard {
                    SettingToggleRow(
                        icon = Icons.Filled.Notifications,
                        iconTint = GitHubBlue,
                        title = tr("推送通知"),
                        subtitle = tr("接收 GitHub 通知推送"),
                        showDivider = true,
                        checked = pushEnabled,
                        onCheckedChange = { AppSettings.setPushEnabled(it) }
                    )
                    SettingToggleRow(
                        icon = Icons.Filled.Email,
                        iconTint = GitHubBlue,
                        title = tr("邮件通知"),
                        subtitle = tr("通过邮件接收通知"),
                        showDivider = true,
                        checked = emailEnabled,
                        onCheckedChange = { AppSettings.setEmailEnabled(it) }
                    )
                    SettingToggleRow(
                        icon = Icons.Filled.NotificationsActive,
                        iconTint = GitHubBlue,
                        title = tr("仓库动态"),
                        subtitle = tr("关注仓库的动态提醒"),
                        showDivider = false,
                        checked = repoActivityEnabled,
                        onCheckedChange = { AppSettings.setRepoActivityEnabled(it) }
                    )
                }
            }

            // ===== 下载设置 =====
            item { SectionHeader(tr("下载设置")) }
            item {
                SettingsCard {
                    SettingClickRow(
                        icon = Icons.Filled.Download,
                        iconTint = GitHubBlue,
                        title = tr("下载路径"),
                        subtitle = "APK 保存位置：$downloadPath",
                        showDivider = false,
                        onClick = { showDownloadPathDialog = true }
                    )
                }
            }

            // ===== 安全设置 =====
            item { SectionHeader(tr("安全设置")) }
            item {
                SettingsCard {
                    SettingClickRow(
                        icon = Icons.Filled.Security,
                        iconTint = GitHubRed,
                        title = tr("安全与隐私"),
                        subtitle = tr("两步验证、密码与安全检查"),
                        showDivider = false,
                        onClick = { onOpenWeb(Constants.GITHUB_SECURITY_URL, tr("安全设置")) }
                    )
                }
            }

            // ===== Token 设置 =====
            item { SectionHeader(tr("Token 设置")) }
            item {
                SettingsCard {
                    SettingClickRow(
                        icon = Icons.Filled.Key,
                        iconTint = GitHubBlue,
                        title = tr("访问令牌"),
                        subtitle = tr("查看与更新 Personal Access Token"),
                        showDivider = false,
                        onClick = onNavigateToTokenSettings
                    )
                }
            }

            // ===== 插件管理 =====
            item { SectionHeader(tr("插件管理")) }
            item {
                SettingsCard {
                    SettingClickRow(
                        icon = Icons.Filled.Extension,
                        iconTint = GitHubGreen,
                        title = tr("插件管理"),
                        subtitle = tr("翻译插件等扩展功能"),
                        showDivider = false,
                        onClick = onNavigateToPlugins
                    )
                }
            }

            // ===== 关于 =====
            item { SectionHeader(tr("关于")) }
            item {
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
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = GitHubBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                "githup",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${tr("版本")} ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            tr("基于 GitHub API 构建的第三方客户端，提供仓库、议题、通知等管理能力。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWeb("https://github.com", "GitHub 官网") }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                tint = GitHubBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                tr("访问 GitHub 官网"),
                                style = MaterialTheme.typography.labelSmall,
                                color = GitHubBlue,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        // 自动检查更新开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = GitHubBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                tr("自动检查更新"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoCheckUpdate,
                                onCheckedChange = { AppSettings.setAutoCheckUpdate(it) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val info = UpdateChecker.checkForUpdate()
                                        if (info != null) {
                                            updateInfo = info
                                            showUpdateDialog = true
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (updateChecking) Icons.Filled.Refresh else Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = GitHubGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                tr("检查更新"),
                                style = MaterialTheme.typography.labelSmall,
                                color = GitHubGreen,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 下载路径选择对话框 =====
    if (showDownloadPathDialog) {
        var customPath by remember { mutableStateOf(downloadPath) }
        AlertDialog(
            onDismissRequest = { showDownloadPathDialog = false },
            title = { Text(tr("选择下载路径"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        tr("选择文件下载的保存位置："),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AppSettings.downloadPathOptions.forEach { (label, path) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    customPath = path
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (path == customPath) GitHubBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (path == customPath) GitHubBlue else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (path == customPath) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (path == customPath) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = GitHubBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        tr("自定义路径："),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        placeholder = { Text(tr("输入自定义路径")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        tr("路径相对于内部存储根目录（如 Download/githup）"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customPath.isNotBlank()) {
                            AppSettings.setDownloadPath(customPath)
                        }
                        showDownloadPathDialog = false
                    }
                ) { Text(tr("确定"), color = GitHubBlue) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDownloadPathDialog = false }
                ) { Text(tr("取消")) }
            }
        )
    }

    // ===== 更新检查对话框 =====
    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(tr("检查更新"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (info.hasUpdate) {
                        Text(
                            "发现新版本 v${info.latestVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GitHubGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                info.releaseNotes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            tr("点击下方按钮下载最新版本"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "当前版本 v${BuildConfig.VERSION_NAME} 已是最新",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (info.hasUpdate) {
                    TextButton(
                        onClick = {
                            onOpenWeb(info.downloadUrl, "下载 githup v${info.latestVersion}")
                            showUpdateDialog = false
                        }
                    ) { Text(tr("下载"), color = GitHubGreen, fontWeight = FontWeight.Bold) }
                } else {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(tr("确定"), color = GitHubBlue)
                    }
                }
            },
            dismissButton = if (info.hasUpdate) {
                { TextButton(onClick = { showUpdateDialog = false }) { Text(tr("取消")) } }
            } else null
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingClickRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = false,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = false,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = { newValue ->
                    onCheckedChange(newValue)
                }
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
        }
    }
}
