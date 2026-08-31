package com.githubclient.app.ui.repo

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.data.remote.model.WorkflowArtifact
import com.githubclient.app.data.remote.model.WorkflowRun
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.util.DownloadHelper

private val GitHubGreen = Color(0xFF238636)
private val GitHubRed = Color(0xFFDA3633)
private val GitHubBlue = Color(0xFF0969DA)
private val GitHubYellow = Color(0xFFD29922)
private val LightBg = Color(0xFFF6F8FA)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudBuildScreen(
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onOpenWeb: (String, String) -> Unit = { _, _ -> },
    viewModel: CloudBuildViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedRunIds = remember { mutableStateMapOf<Long, Boolean>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(owner, repo) { viewModel.init(owner, repo) }

    LaunchedEffect(uiState.message, uiState.error) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val selectedCount = selectedRunIds.count { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(
                            "已选 $selectedCount 项",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            "构建 APK",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedRunIds.clear()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "退出选择" else "返回"
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            if (selectedCount == uiState.runs.size) {
                                selectedRunIds.clear()
                            } else {
                                uiState.runs.forEach { run ->
                                    selectedRunIds[run.id] = true
                                }
                            }
                        }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = selectedCount > 0
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = if (selectedCount > 0) GitHubRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        IconButton(onClick = { viewModel.loadRuns() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (selectionMode) GitHubRed.copy(alpha = 0.1f)
                                     else MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ===== 构建配置面板 =====
            if (!selectionMode) {
                BuildConfigPanel(
                    owner = owner,
                    repo = repo,
                    uiState = uiState,
                    onBuild = { viewModel.createAndTriggerBuild() },
                    onTriggerOnly = { viewModel.triggerBuild() }
                )
            }

            // ===== 构建记录列表 =====
            if (uiState.isLoading && uiState.runs.isEmpty()) {
                LoadingState(message = "加载构建记录...")
            } else if (uiState.runs.isEmpty()) {
                EmptyState(message = "暂无构建记录\n填写上方信息后点击「构建 APK」即可开始")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!selectionMode) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    "构建记录",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "(${uiState.runs.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    "长按可多选删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(uiState.runs, key = { it.id }) { run ->
                        val isSelected = selectedRunIds[run.id] == true
                        BuildRunCard(
                            run = run,
                            artifacts = uiState.artifacts[run.id] ?: emptyList(),
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onLongClick = {
                                selectionMode = true
                                selectedRunIds[run.id] = true
                            },
                            onToggleSelect = {
                                if (selectedRunIds[run.id] == true) {
                                    selectedRunIds.remove(run.id)
                                } else {
                                    selectedRunIds[run.id] = true
                                }
                            },
                            onDownloadArtifact = { artifact ->
                                if (artifact.expired) {
                                    Toast.makeText(
                                        context,
                                        "产物已过期，请重新触发构建",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    DownloadHelper.downloadArtifact(
                                        context,
                                        artifact.archiveDownloadUrl,
                                        artifact.name
                                    )
                                }
                            },
                            onViewDetails = { url ->
                                onOpenWeb(url, "Actions 详情")
                            }
                        )
                    }
                }
            }
        }
    }

    // ===== 删除确认对话框 =====
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定要删除选中的 $selectedCount 条构建记录吗？\n此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedRunIds.filter { it.value }.keys.toList()
                        viewModel.deleteRuns(idsToDelete)
                        selectedRunIds.clear()
                        selectionMode = false
                        showDeleteConfirm = false
                    }
                ) {
                    Text("删除", color = GitHubRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ===== 构建配置面板（表单式） =====
@Composable
private fun BuildConfigPanel(
    owner: String,
    repo: String,
    uiState: CloudBuildUiState,
    onBuild: () -> Unit,
    onTriggerOnly: () -> Unit
) {
    val isBusy = uiState.isCreating || uiState.isTriggering

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // ===== 标题区域 =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GitHubBlue.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = GitHubBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "云端构建 APK",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "填写信息后点击构建，GitHub Actions 自动编译",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ===== 构建步骤指引 =====
            BuildStepGuide(workflowReady = uiState.workflowExists)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ===== 基本信息表单 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 仓库信息
                InfoRow(label = "仓库", value = "$owner/$repo")
                InfoRow(label = "构建方式", value = "GitHub Actions")
                InfoRow(
                    label = "编译环境",
                    value = "Ubuntu + JDK 17 + Android SDK"
                )
                InfoRow(
                    label = "构建命令",
                    value = "./gradlew assembleDebug",
                    mono = true
                )
                InfoRow(
                    label = "产物路径",
                    value = "app/build/outputs/apk/debug/app-debug.apk",
                    mono = true
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 构建 APK 按钮 =====
            Button(
                onClick = onBuild,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GitHubGreen,
                    disabledContainerColor = GitHubGreen.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        when {
                            uiState.isCreating -> "正在创建工作流..."
                            uiState.isTriggering -> "正在触发构建..."
                            else -> "处理中..."
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)
                    )
                } else {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("构建 APK", fontWeight = FontWeight.SemiBold, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp))
                }
            }

            // 仅触发构建按钮（工作流已存在时）
            if (uiState.workflowExists && !isBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTriggerOnly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GitHubBlue
                    )
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重新触发构建")
                }
            }

            // 工作流状态提示
            if (uiState.workflowExists) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = GitHubGreen.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = GitHubGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "工作流已就绪，可直接构建",
                            style = MaterialTheme.typography.bodySmall,
                            color = GitHubGreen,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// ===== 构建步骤指引 =====
@Composable
private fun BuildStepGuide(workflowReady: Boolean) {
    val steps = listOf(
        Triple("1", "创建/检查工作流", workflowReady),
        Triple("2", "触发 GitHub Actions 构建", false),
        Triple("3", "等待云端编译完成", false),
        Triple("4", "下载 APK 产物", false)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        steps.forEachIndexed { index, (num, label, done) ->
            val color = if (done) GitHubGreen else MaterialTheme.colorScheme.outline
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = color.copy(alpha = 0.12f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                num,
                                style = MaterialTheme.typography.labelMedium,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            // 连接线
            if (index < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .weight(0.3f)
                        .height(2.dp)
                        .padding(top = 15.dp)
                        .background(color.copy(alpha = 0.3f))
                )
            }
        }
    }
}

// ===== 信息行 =====
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// ===== 构建记录卡片 =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BuildRunCard(
    run: WorkflowRun,
    artifacts: List<WorkflowArtifact>,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onDownloadArtifact: (WorkflowArtifact) -> Unit,
    onViewDetails: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelect()
                },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GitHubBlue.copy(alpha = 0.08f)
                             else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, GitHubBlue)
                 else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 运行编号 + 状态图标 + 选择框
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                        contentDescription = null,
                        tint = if (isSelected) GitHubBlue else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                val (statusIcon, statusColor, statusText) = when {
                    run.status == "queued" -> Triple(
                        Icons.Filled.Schedule, GitHubYellow, "排队中"
                    )
                    run.status == "in_progress" -> Triple(
                        Icons.Filled.Cloud, GitHubBlue, "构建中"
                    )
                    run.status == "completed" && run.conclusion == "success" -> Triple(
                        Icons.Filled.CloudDone, GitHubGreen, "成功"
                    )
                    run.status == "completed" && run.conclusion == "failure" -> Triple(
                        Icons.Filled.Error, GitHubRed, "失败"
                    )
                    run.status == "completed" && run.conclusion == "cancelled" -> Triple(
                        Icons.Filled.CloudOff, MaterialTheme.colorScheme.onSurface, "已取消"
                    )
                    else -> Triple(
                        Icons.Filled.Build, MaterialTheme.colorScheme.onSurface, run.status
                    )
                }

                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "#${run.runNumber}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (run.headBranch != null) {
                    Text(
                        run.headBranch,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 构建标题
            if (run.displayTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    run.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 时间信息
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "创建于 ${run.createdAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (run.updatedAt != run.createdAt) {
                Text(
                    "更新于 ${run.updatedAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // APK 产物下载（非选择模式时显示）
            if (!selectionMode && artifacts.isNotEmpty() && run.conclusion == "success") {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = GitHubGreen.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Smartphone,
                                contentDescription = null,
                                tint = GitHubGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "APK 产物 (${artifacts.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GitHubGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        artifacts.forEach { artifact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = GitHubBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        artifact.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${formatBytes(artifact.sizeInBytes)}" +
                                            if (artifact.expired) " · 已过期" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (artifact.expired) GitHubRed
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!artifact.expired) {
                                    Button(
                                        onClick = { onDownloadArtifact(artifact) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GitHubGreen
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 14.dp, vertical = 6.dp
                                        )
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("下载 APK", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 在 GitHub 网页查看
            if (!selectionMode && run.htmlUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onViewDetails(run.htmlUrl) }
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
                        "查看 Actions 详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = GitHubBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ===== 辅助函数 =====
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
