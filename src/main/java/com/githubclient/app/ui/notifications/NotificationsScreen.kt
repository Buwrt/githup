package com.githubclient.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.data.remote.model.Notification
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.LoadingMoreState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubPurple
import com.githubclient.app.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onRepoClick: (String, String) -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { idx ->
                if (idx != null && idx >= uiState.notifications.size - 3) {
                    viewModel.loadMore()
                }
            }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除通知") },
            text = { Text("确定删除选中的 ${uiState.selectedIds.size} 条通知吗？\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSelected()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (uiState.isMultiSelectMode) {
                TopAppBar(
                    title = {
                        Text(
                            "已选 ${uiState.selectedIds.size}/${uiState.notifications.size}",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelectMode() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        // 全选/取消全选
                        IconButton(onClick = {
                            if (uiState.selectedIds.size == uiState.notifications.size) {
                                viewModel.unselectAll()
                            } else {
                                viewModel.selectAll()
                            }
                        }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                        }
                        // 删除选中
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = uiState.selectedIds.isNotEmpty() && !uiState.isDeleting
                        ) {
                            if (uiState.isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("通知", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            if (uiState.unreadCount > 0) {
                                Text(
                                    "${uiState.unreadCount} 条未读",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::markAllRead) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "全部已读")
                        }
                        // 多选模式入口
                        if (uiState.notifications.isNotEmpty()) {
                            IconButton(onClick = { viewModel.enterMultiSelectMode() }) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "多选")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> LoadingState(message = "加载通知...")
                uiState.error != null && uiState.notifications.isEmpty() -> ErrorState(
                    message = uiState.error!!, onRetry = viewModel::load
                )
                uiState.notifications.isEmpty() -> EmptyState(message = "暂无通知")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.notifications, key = { it.id }) { n ->
                        NotificationItem(
                            notification = n,
                            isMultiSelectMode = uiState.isMultiSelectMode,
                            isSelected = uiState.selectedIds.contains(n.id),
                            onSelectToggle = { viewModel.toggleSelection(n.id) },
                            onClick = {
                                if (uiState.isMultiSelectMode) {
                                    viewModel.toggleSelection(n.id)
                                } else {
                                    n.repository?.let { repo ->
                                        val parts = repo.fullName.split("/")
                                        if (parts.size == 2) {
                                            val num = parseIssueNumber(n.subject?.url)
                                            if (num != null && (n.subject?.type?.contains("Issue") == true || n.subject?.type?.contains("Pull") == true)) {
                                                onIssueClick(parts[0], parts[1], num)
                                            } else {
                                                onRepoClick(parts[0], parts[1])
                                            }
                                        }
                                    }
                                    if (n.unread) viewModel.markRead(n)
                                }
                            }
                        )
                    }
                    if (uiState.isLoadingMore) item { LoadingMoreState() }
                }
            }
        }
    }
}

private fun parseIssueNumber(url: String?): Int? {
    if (url.isNullOrBlank()) return null
    return url.trimEnd('/').substringAfterLast('/').toIntOrNull()
}

@Composable
private fun NotificationItem(
    notification: Notification,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onClick: () -> Unit
) {
    val type = notification.subject?.type ?: ""
    val defaultColor = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, color) = subjectVisual(type, defaultColor)

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 多选框
            if (isMultiSelectMode) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) GitHubBlue else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isMultiSelectMode && notification.unread) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(GitHubBlue, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = notification.repository?.fullName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notification.subject?.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = reasonText(notification.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "· ${TimeUtils.formatRelativeTime(notification.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun subjectVisual(type: String, defaultColor: Color): Pair<ImageVector, Color> {
    return when {
        type.contains("Pull") -> Icons.Filled.CallSplit to GitHubPurple
        type.contains("Issue") -> Icons.Filled.Circle to GitHubGreen
        type.contains("Release") -> Icons.Filled.Bookmark to GitHubBlue
        else -> Icons.Filled.Public to defaultColor
    }
}

private fun reasonText(reason: String): String = when (reason) {
    "mention" -> "提及了你"
    "assign" -> "指派给你"
    "author" -> "你创建的"
    "comment" -> "你评论过"
    "review_requested" -> "请求你审核"
    "ci_activity" -> "CI 活动"
    "manual" -> "已订阅"
    "team_mention" -> "团队提及"
    "state_change" -> "状态变更"
    "approval" -> "已批准"
    else -> reason
}
