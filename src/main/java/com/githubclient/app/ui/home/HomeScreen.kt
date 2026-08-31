package com.githubclient.app.ui.home

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.EventCard
import com.githubclient.app.ui.components.LoadingMoreState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.util.BuildStatusTracker
import com.githubclient.app.util.Constants
import com.githubclient.app.util.DownloadHelper
import com.githubclient.app.util.NumberUtils
import com.githubclient.app.util.TimeUtils
import com.githubclient.app.util.tr
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

private val GitHubGreen = Color(0xFF238636)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRepoClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    onCreateRepo: () -> Unit,
    onSearchClick: () -> Unit = {},
    onNavigateToTaskManager: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val forkState by viewModel.forkState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloadProgress by DownloadHelper.downloadProgress.collectAsState()
    val activeBuilds by BuildStatusTracker.activeBuilds.collectAsState()

    var showCloneDialog by remember { mutableStateOf(false) }
    var cloneUrl by remember { mutableStateOf("") }
    var showCreateOrCloneDialog by remember { mutableStateOf(false) }

    LaunchedEffect(forkState.success, forkState.error) {
        if (forkState.success != null || forkState.error != null) {
            showCloneDialog = false
            viewModel.clearForkState()
            cloneUrl = ""
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= uiState.events.size - 3) {
                    viewModel.loadMore()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val user = uiState.currentUser
                        if (user?.avatarUrl != null) {
                            AsyncImage(
                                model = user.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                text = tr("首页"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            val login = uiState.currentUser?.login
                            if (login != null) {
                                Text(
                                    text = "@$login",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    // 合并的构建和下载任务按钮
                    val hasActiveBuilds = activeBuilds.any {
                        it.status == "queued" || it.status == "in_progress"
                    }
                    val hasActiveDownload = downloadProgress != null && !downloadProgress!!.isComplete
                    val showBadge = hasActiveBuilds || hasActiveDownload

                    IconButton(onClick = onNavigateToTaskManager) {
                        if (showBadge) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(
                                            text = (if (hasActiveBuilds) activeBuilds.count {
                                                it.status == "queued" || it.status == "in_progress"
                                            } else 0).toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cloud,
                                    contentDescription = "任务管理"
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Cloud,
                                contentDescription = "任务管理"
                            )
                        }
                    }

                    // 创建仓库按钮
                    IconButton(onClick = { showCreateOrCloneDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = tr("创建仓库")
                        )
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = tr("搜索")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> LoadingState(message = tr("加载中"))
                uiState.error != null && uiState.events.isEmpty() && uiState.repos.isEmpty() ->
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.loadEvents() }
                    )
                uiState.events.isNotEmpty() -> {
                    // 有动态时显示动态 Feed
                    SwipeRefresh(
                        state = rememberSwipeRefreshState(uiState.isRefreshing),
                        onRefresh = { viewModel.refresh() }
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.events, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onRepoClick = onRepoClick,
                                    onUserClick = onUserClick
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item { LoadingMoreState() }
                            }
                        }
                    }
                }
                uiState.repos.isNotEmpty() -> {
                    // 没有动态时显示用户仓库
                    SwipeRefresh(
                        state = rememberSwipeRefreshState(uiState.isRefreshing),
                        onRefresh = { viewModel.refresh() }
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Text(
                                    tr("我的仓库") + " (${uiState.repos.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(uiState.repos, key = { it.id }) { repo ->
                                RepoListCard(repo = repo, onClick = {
                                    onRepoClick(repo.owner?.login ?: "", repo.name)
                                })
                            }
                        }
                    }
                }
                else -> EmptyState(message = tr("暂无动态"))
            }
        }
    }

    // ===== 克隆仓库对话框 =====
    if (showCloneDialog) {
        AlertDialog(
            onDismissRequest = {
                showCloneDialog = false
                cloneUrl = ""
            },
            title = { Text(tr("克隆仓库"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        tr("输入 GitHub 仓库地址，将 1:1 复制到你的账号下"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = { cloneUrl = it },
                        placeholder = { Text("https://github.com/owner/repo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (forkState.loading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(tr("正在克隆"), color = MaterialTheme.colorScheme.primary)
                    }
                    forkState.error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    forkState.success?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = GitHubGreen)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (cloneUrl.isNotBlank()) {
                            viewModel.forkRepo(cloneUrl)
                        }
                    },
                    enabled = !forkState.loading && cloneUrl.isNotBlank()
                ) {
                    Text(tr("克隆"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCloneDialog = false
                    cloneUrl = ""
                }) { Text(tr("取消")) }
            }
        )
    }

    // ===== 创建/克隆选择对话框 =====
    if (showCreateOrCloneDialog) {
        AlertDialog(
            onDismissRequest = { showCreateOrCloneDialog = false },
            title = { Text(tr("选择操作"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    // 创建新仓库
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateOrCloneDialog = false
                                onCreateRepo()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = GitHubGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                tr("创建新仓库"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                tr("创建一个全新的 GitHub 仓库"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                    // 克隆仓库
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateOrCloneDialog = false
                                showCloneDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                tr("克隆仓库"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                tr("从 URL 复制仓库到你的账号下"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateOrCloneDialog = false }) {
                    Text(tr("取消"))
                }
            }
        )
    }
}

@Composable
private fun RepoListCard(repo: Repository, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = repo.fullName ?: repo.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            repo.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repo.language?.let { lang ->
                    if (lang.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(parseHex(Constants.getLanguageColor(lang))),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.size(10.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(lang, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(NumberUtils.formatCount(repo.stargazersCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(TimeUtils.formatRelativeTime(repo.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun parseHex(hex: String): Long {
    return try {
        val clean = hex.removePrefix("#")
        (0xFF000000L or clean.toLong(16))
    } catch (e: Exception) { 0xFF6E7781L }
}

/**
 * 构建状态卡片 - 显示活跃构建状态和取消按钮
 */
@Composable
private fun BuildStatusCard(
    build: BuildStatusTracker.BuildInfo,
    onCancel: () -> Unit
) {
    val statusText = when (build.status) {
        "queued" -> "排队中"
        "in_progress" -> "构建中"
        "cancelled" -> "已取消"
        "success" -> "构建成功"
        "failure" -> "构建失败"
        else -> build.status
    }
    val statusColor = when (build.status) {
        "queued" -> Color(0xFFD29922)
        "in_progress" -> MaterialTheme.colorScheme.primary
        "cancelled" -> MaterialTheme.colorScheme.outline
        "success" -> GitHubGreen
        "failure" -> Color(0xFFDA3633)
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = build.buildName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${build.owner}/${build.repo} · $statusText",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            // 取消构建按钮
            if (build.status == "queued" || build.status == "in_progress") {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = "取消构建",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 下载进度卡片 - 显示下载进度和取消按钮
 */
@Composable
private fun DownloadStatusCard(
    progress: DownloadHelper.DownloadProgress,
    onCancel: () -> Unit
) {
    val progressColor = if (progress.error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = progressColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = if (progress.error != null) {
                        progress.error!!
                    } else if (progress.progress >= 0) {
                        "下载中 ${progress.progress}%"
                    } else {
                        "下载中..."
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = progressColor
                    )
                }
                // 取消下载按钮
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = "取消下载",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // 进度条
            if (progress.error == null && progress.progress >= 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = progressColor,
                )
            }
        }
    }
}
