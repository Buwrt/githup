package com.githubclient.app.ui.main

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.githubclient.app.ui.explore.ExploreScreen
import com.githubclient.app.ui.home.HomeScreen
import com.githubclient.app.ui.notifications.NotificationsScreen
import com.githubclient.app.ui.profile.ProfileScreen
import com.githubclient.app.util.DownloadHelper
import com.githubclient.app.util.TranslationPlugin
import com.githubclient.app.util.UpdateChecker
import com.githubclient.app.util.tr

private data class TabItem(
    val label: String,
    val selected: ImageVector,
    val unselected: ImageVector,
)

private val tabs = listOf(
    TabItem("首页", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem("探索", Icons.Filled.Explore, Icons.Outlined.Explore),
    TabItem("通知", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    TabItem("我的", Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun MainScreen(
    onNavigateToRepo: (String, String) -> Unit,
    onNavigateToIssue: (String, String, Int) -> Unit,
    onNavigateToUser: (String) -> Unit,
    onNavigateToCreateRepo: () -> Unit,
    onNavigateToStarred: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTaskManager: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onLogout: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val translationEnabled by TranslationPlugin.enabled.collectAsState()
    val context = LocalContext.current

    // 更新检查状态
    val updateInfo by UpdateChecker.updateInfo.collectAsState()
    // 可选更新是否被用户跳过（本次会话内不再提醒）
    var optionalUpdateSkipped by rememberSaveable { mutableStateOf(false) }

    // 系统返回键：非首页Tab先回到首页，再按退出
    BackHandler(enabled = selected != 0) {
        selected = 0
    }

    Scaffold(
        floatingActionButton = {
            // 只保留插件管理入口，翻译和代理开关在插件管理界面内
            SmallFloatingActionButton(
                onClick = onNavigateToPlugins,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = tr("插件管理")
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = if (selected == index) tab.selected else tab.unselected,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                tr(tab.label),
                                fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selected) {
                0 -> HomeScreen(
                    onRepoClick = onNavigateToRepo,
                    onUserClick = onNavigateToUser,
                    onIssueClick = onNavigateToIssue,
                    onCreateRepo = onNavigateToCreateRepo,
                    onSearchClick = { selected = 1 },
                    onNavigateToTaskManager = onNavigateToTaskManager,
                )
                1 -> ExploreScreen(
                    onRepoClick = onNavigateToRepo,
                    onUserClick = onNavigateToUser,
                )
                2 -> NotificationsScreen(
                    onRepoClick = onNavigateToRepo,
                    onIssueClick = onNavigateToIssue,
                )
                3 -> ProfileScreen(
                    username = null,
                    onBack = {},
                    onRepoClick = onNavigateToRepo,
                    onUserClick = onNavigateToUser,
                    onLogout = onLogout,
                    onOpenWeb = onOpenWeb,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToStarred = onNavigateToStarred,
                )
            }
        }
    }

    // ===== 更新对话框 =====
    val info = updateInfo
    if (info != null && info.hasUpdate) {
        if (info.isForceUpdate) {
            // 强制更新：不可关闭，必须下载
            AlertDialog(
                onDismissRequest = { /* 不可关闭 */ },
                title = {
                    Text(
                        "发现新版本 v${info.latestVersion}",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "当前版本: v${info.currentVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "此版本为重要更新，必须升级后才能继续使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (info.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                info.releaseNotes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // 下载 APK
                            DownloadHelper.download(
                                context,
                                info.downloadUrl,
                                "githup-v${info.latestVersion}.apk"
                            )
                        }
                    ) {
                        Text("立即更新", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            // 打开浏览器查看
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.releasePageUrl))
                            context.startActivity(intent)
                        }
                    ) {
                        Text("在浏览器查看")
                    }
                }
            )
        } else if (!optionalUpdateSkipped) {
            // 可选更新：可跳过
            AlertDialog(
                onDismissRequest = { optionalUpdateSkipped = true },
                title = {
                    Text(
                        "发现新版本 v${info.latestVersion}",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "当前版本: v${info.currentVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                info.releaseNotes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            DownloadHelper.download(
                                context,
                                info.downloadUrl,
                                "githup-v${info.latestVersion}.apk"
                            )
                            optionalUpdateSkipped = true
                        }
                    ) {
                        Text("更新", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { optionalUpdateSkipped = true }
                    ) {
                        Text("跳过")
                    }
                }
            )
        }
    }
}
