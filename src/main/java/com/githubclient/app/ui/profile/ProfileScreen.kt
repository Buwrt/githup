package com.githubclient.app.ui.profile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.components.RepoCard
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.util.Constants
import com.githubclient.app.util.NumberUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String?,
    onBack: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    onLogout: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStarred: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isMe = username == null
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(username) { viewModel.load(username) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除仓库") },
            text = { Text("确定删除选中的 ${uiState.selectedRepoIds.size} 个仓库吗？\n此操作不可撤销，仓库中的所有代码和 Issue 都将永久丢失。") },
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
                            "已选 ${uiState.selectedRepoIds.size}/${uiState.repos.size}",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelectMode() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (uiState.selectedRepoIds.size == uiState.repos.size) {
                                viewModel.unselectAll()
                            } else {
                                viewModel.selectAll()
                            }
                        }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                        }
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = uiState.selectedRepoIds.isNotEmpty() && !uiState.isDeleting
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
                    title = { Text(if (isMe) "我的" else "@${username}", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (!isMe) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        if (isMe) {
                            IconButton(onClick = onNavigateToStarred) {
                                Icon(Icons.Filled.Book, contentDescription = "我的星标")
                            }
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "设置")
                            }
                            androidx.compose.material3.TextButton(onClick = {
                                viewModel.logout()
                                onLogout()
                            }) {
                                Text("登出")
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
                uiState.isLoading -> LoadingState(message = "加载资料...")
                uiState.error != null && uiState.user == null -> ErrorState(
                    message = uiState.error!!, onRetry = { viewModel.load(username) }
                )
                uiState.user != null -> {
                    val user = uiState.user!!
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 头部资料
                        item { ProfileHeader(user = user, isMe = isMe) }

                        // 关注/取消关注
                        if (!isMe) {
                            item {
                                if (uiState.isFollowing) {
                                    OutlinedButton(
                                        onClick = viewModel::toggleFollow,
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text("已关注 - 取消关注") }
                                } else {
                                    Button(
                                        onClick = viewModel::toggleFollow,
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GitHubBlue)
                                    ) { Text("关注", color = Color.White, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        }

                        // 仓库标题
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "仓库 (${user.publicRepos})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                // 多选模式入口（仅自己仓库）
                                if (isMe && uiState.repos.isNotEmpty() && !uiState.isMultiSelectMode) {
                                    TextButton(onClick = { viewModel.enterMultiSelectMode() }) {
                                        Text("多选", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        if (uiState.repos.isEmpty()) {
                            item { EmptyState(message = "暂无公开仓库", modifier = Modifier.height(120.dp)) }
                        } else {
                            items(uiState.repos, key = { it.id }) { repo ->
                                RepoItemWithSelect(
                                    repo = repo,
                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                    isSelected = uiState.selectedRepoIds.contains(repo.id),
                                    onClick = {
                                        if (uiState.isMultiSelectMode) {
                                            viewModel.toggleSelection(repo.id)
                                        } else {
                                            onRepoClick(repo.owner?.login ?: "", repo.name)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                else -> EmptyState(message = "未找到用户")
            }
        }
    }
}

@Composable
private fun RepoItemWithSelect(
    repo: Repository,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选框
            if (isMultiSelectMode) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 12.dp)
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
                Spacer(modifier = Modifier.width(8.dp))
            }

            RepoCard(
                repo = repo,
                onClick = onClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProfileHeader(user: com.githubclient.app.data.remote.model.User, isMe: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.name ?: user.login,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "@${user.login}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            user.bio?.let { bio ->
                if (bio.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StatItem(count = user.followers, label = "followers")
                StatItem(count = user.following, label = "following")
                StatItem(count = user.publicRepos, label = "repos")
            }

            if (!user.company.isNullOrBlank() || !user.location.isNullOrBlank() || !user.blog.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    user.company?.let { if (it.isNotBlank()) InfoLine(Icons.Filled.Business, it) }
                    user.location?.let { if (it.isNotBlank()) InfoLine(Icons.Filled.LocationOn, it) }
                    user.blog?.let { if (it.isNotBlank()) InfoLine(Icons.Filled.Book, it) }
                }
            }
        }
    }
}

@Composable
private fun StatItem(count: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = NumberUtils.formatCount(count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
