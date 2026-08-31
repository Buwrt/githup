package com.githubclient.app.ui.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import java.util.zip.ZipInputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.data.remote.model.Branch
import com.githubclient.app.data.remote.model.Content
import com.githubclient.app.data.remote.model.Issue
import com.githubclient.app.data.remote.model.PullRequest
import com.githubclient.app.data.remote.model.Release
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.IssueCard
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.components.MarkdownText
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubPurple
import com.githubclient.app.ui.theme.GitHubRed
import com.githubclient.app.util.Constants
import com.githubclient.app.util.DownloadHelper
import com.githubclient.app.util.NumberUtils
import com.githubclient.app.util.TimeUtils
import kotlinx.coroutines.launch

// GitHub brand-ish colors for the Star toggle and Fork action in the TopAppBar.
// Star = #FFB100, Fork = #238636.
private val StarColor = Color(0xFFFFB100)
private val ForkColor = Color(0xFF238636)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    onUserClick: (String) -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onNavigateToCloudBuild: (String, String) -> Unit = { _, _ -> },
    onNavigateToCreateRelease: (String, String) -> Unit = { _, _ -> },
    viewModel: RepoDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCreateIssueDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(owner, repo) { viewModel.load(owner, repo) }

    // Snackbar: show transient messages (upload/fork/star/create issue success, etc.)
    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // Snackbar: show transient errors only when the repo is already loaded
    // (initial load failures are handled by the full-screen ErrorState below).
    LaunchedEffect(uiState.error) {
        if (uiState.repo != null && uiState.error != null) {
            snackbarHostState.showSnackbar(uiState.error!!)
            viewModel.clearMessage()
        }
    }

    // File picker (ActivityResultContracts.OpenDocument) for uploading a file.
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
                if (fileName.endsWith(".zip", ignoreCase = true)) {
                    // ZIP 文件：解压后逐个文件上传，显示完整目录结构
                    val entries = extractZipEntries(context, uri)
                    if (entries.isEmpty()) {
                        Toast.makeText(context, "ZIP 文件为空或无法读取", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.uploadZipFiles(entries, fileName)
                    }
                } else {
                    // 普通文件：直接上传
                    val base64Content = readAndBase64Encode(context, uri)
                    if (base64Content != null) {
                        viewModel.uploadFile(fileName, base64Content, "Upload $fileName")
                    }
                }
            }
        }
    }

    // 多文件选择
    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                uris.forEach { uri ->
                    val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
                    val base64Content = readAndBase64Encode(context, uri)
                    if (base64Content != null) {
                        viewModel.uploadFile(fileName, base64Content, "Upload $fileName")
                    }
                }
            }
        }
    }

    // 文件夹上传（通过 DocumentTree）
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (treeDoc != null && treeDoc.isDirectory) {
                    val files = mutableListOf<Pair<String, String>>()
                    fun collectFiles(doc: androidx.documentfile.provider.DocumentFile, relativePath: String) {
                        doc.listFiles().forEach { child ->
                            val childPath = if (relativePath.isEmpty()) child.name ?: "file"
                                else "$relativePath/${child.name ?: "file"}"
                            if (child.isDirectory) {
                                collectFiles(child, childPath)
                            } else if (child.isFile) {
                                val base64 = readAndBase64Encode(context, child.uri)
                                if (base64 != null) {
                                    files.add(childPath to base64)
                                }
                            }
                        }
                    }
                    collectFiles(treeDoc, "")
                    if (files.isEmpty()) {
                        Toast.makeText(context, "文件夹为空", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.uploadZipFiles(files, treeDoc.name ?: "folder")
                    }
                }
            }
        }
    }

    val tabs = listOf(
        "代码 (${uiState.contents.size})",
        "Issues (${uiState.issues.size})",
        "PRs (${uiState.pullRequests.size})",
        "分支 (${uiState.branches.size})",
        "发布 (${uiState.releases.size})",
        "构建"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "$owner/$repo",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Star toggle (filled when starred, outline otherwise)
                    IconButton(onClick = { viewModel.toggleStar() }) {
                        Icon(
                            imageVector = if (uiState.isStarred) Icons.Filled.Star
                                else Icons.Filled.StarBorder,
                            contentDescription = if (uiState.isStarred) "取消星标" else "星标",
                            tint = StarColor
                        )
                    }
                    // Fork
                    IconButton(onClick = { viewModel.forkRepo { } }) {
                        Icon(
                            imageVector = Icons.Filled.CallSplit,
                            contentDescription = "Fork",
                            tint = ForkColor
                        )
                    }
                    // Overflow menu with Delete option
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除仓库") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = GitHubRed
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.repo != null) {
                when (tab) {
                    0 -> {
                        var showUploadMenu by remember { mutableStateOf(false) }
                        Box {
                            FloatingActionButton(
                                onClick = { showUploadMenu = true },
                                containerColor = if (uiState.isUploadingZip)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primary,
                                contentColor = if (uiState.isUploadingZip)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onPrimary
                            ) {
                                if (uiState.isUploadingZip) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Icon(Icons.Filled.Upload, contentDescription = "上传文件")
                                }
                            }
                            DropdownMenu(
                                expanded = showUploadMenu,
                                onDismissRequest = { showUploadMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("上传单个文件") },
                                    onClick = {
                                        showUploadMenu = false
                                        if (!uiState.isUploadingZip) {
                                            filePickerLauncher.launch(arrayOf("*/*"))
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("上传多个文件") },
                                    onClick = {
                                        showUploadMenu = false
                                        if (!uiState.isUploadingZip) {
                                            multiFilePickerLauncher.launch(arrayOf("*/*"))
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("上传 ZIP 文件") },
                                    onClick = {
                                        showUploadMenu = false
                                        if (!uiState.isUploadingZip) {
                                            filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("上传文件夹") },
                                    onClick = {
                                        showUploadMenu = false
                                        if (!uiState.isUploadingZip) {
                                            folderPickerLauncher.launch(null)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                )
                            }
                        }
                    }
                    1 -> FloatingActionButton(
                        onClick = { showCreateIssueDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 Issue")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isUploadingZip -> {
                    // 上传/删除进度界面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.uploadProgress ?: "正在处理...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "正在处理文件，请勿关闭页面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                uiState.isLoading -> LoadingState(message = "加载仓库...")
                uiState.error != null && uiState.repo == null -> ErrorState(
                    message = uiState.error!!, onRetry = { viewModel.load(owner, repo) }
                )
                uiState.repo != null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        var showCloneDialog by remember { mutableStateOf(false) }
                        RepoHeaderCard(
                            repo = uiState.repo!!,
                            isStarred = uiState.isStarred,
                            onStarClick = { viewModel.toggleStar() },
                            onForkClick = { viewModel.forkRepo { } },
                            onCloneClick = { showCloneDialog = true }
                        )
                        if (showCloneDialog) {
                            CloneDialog(
                                repo = uiState.repo!!,
                                onDismiss = { showCloneDialog = false },
                                onDownloadZip = {
                                    showCloneDialog = false
                                    val owner = uiState.repo!!.owner?.login ?: ""
                                    val url = "https://github.com/$owner/${uiState.repo!!.name}/archive/refs/heads/${uiState.repo!!.defaultBranch ?: "main"}.zip"
                                    DownloadHelper.download(context, url, "${uiState.repo!!.name}.zip")
                                },
                                onOpenInBrowser = {
                                    showCloneDialog = false
                                    val owner = uiState.repo!!.owner?.login ?: ""
                                    val url = "https://github.com/$owner/${uiState.repo!!.name}"
                                    onOpenWeb(url, uiState.repo!!.name)
                                }
                            )
                        }
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            tabs.forEachIndexed { i, t ->
                                Tab(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    text = {
                                        Text(
                                            t,
                                            fontWeight = if (tab == i) FontWeight.SemiBold
                                                else FontWeight.Normal,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (tab) {
                                0 -> CodeTab(
                                    contents = uiState.contents,
                                    readme = uiState.readme,
                                    currentPath = uiState.currentPath,
                                    onDirClick = { path -> viewModel.loadPath(path) },
                                    onFileClick = { content ->
                                        // 在 App 内查看文件内容，不跳转网页
                                        viewModel.loadFileContent(content)
                                    },
                                    onFileDownload = { content ->
                                        val url = content.downloadUrl ?: content.htmlUrl ?: ""
                                        if (url.isNotEmpty()) {
                                            DownloadHelper.download(context, url, content.name)
                                        }
                                    },
                                    onDeleteFile = { content ->
                                        viewModel.deleteFile(content.path, content.sha)
                                    },
                                    onDeleteFiles = { files ->
                                        viewModel.deleteFiles(files)
                                    },
                                    onDeleteFolder = { folderPath ->
                                        viewModel.deleteFolder(folderPath)
                                    }
                                )
                                1 -> IssuesTab(
                                    issues = uiState.issues,
                                    owner = owner,
                                    repo = repo,
                                    onIssueClick = onIssueClick
                                )
                                2 -> PullRequestsTab(
                                    prs = uiState.pullRequests,
                                    owner = owner,
                                    repo = repo,
                                    onIssueClick = onIssueClick
                                )
                                3 -> BranchesTab(branches = uiState.branches)
                                4 -> ReleasesTab(
                                    releases = uiState.releases,
                                    onCreateRelease = { onNavigateToCreateRelease(owner, repo) },
                                    onDownload = { assetUrl, assetName ->
                                        DownloadHelper.download(context, assetUrl, assetName)
                                    },
                                    onDeleteRelease = { releaseId ->
                                        viewModel.deleteRelease(releaseId)
                                    }
                                )
                                5 -> CloudBuildEntryTab(
                                    owner = owner,
                                    repo = repo,
                                    onNavigateToCloudBuild = onNavigateToCloudBuild
                                )
                            }
                        }
                    }
                }
                else -> EmptyState(message = "未找到仓库")
            }
        }
    }

    // Delete repo confirmation dialog (must type repo name to confirm)
    if (showDeleteDialog) {
        DeleteRepoDialog(
            repoName = repo,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteRepo { onBack() }
            }
        )
    }

    // Create issue dialog
    if (showCreateIssueDialog) {
        CreateIssueDialog(
            onDismiss = { showCreateIssueDialog = false },
            onCreate = { title, body ->
                viewModel.createIssue(title, body) { showCreateIssueDialog = false }
            }
        )
    }

    // App 内文件查看器
    uiState.viewingFile?.let { fileContent ->
        FileViewerDialog(
            fileName = fileContent.name,
            content = uiState.fileContent,
            isLoading = uiState.isLoadingFile,
            onDismiss = { viewModel.closeFileViewer() },
            onDownload = {
                val url = fileContent.downloadUrl ?: fileContent.htmlUrl ?: ""
                if (url.isNotEmpty()) {
                    DownloadHelper.download(context, url, fileContent.name)
                }
            }
        )
    }
}

// ===== App 内文件查看器 =====
@Composable
private fun FileViewerDialog(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "下载")
                    }
                }
                HorizontalDivider()

                // 内容区域
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (content != null) {
                        // 判断是否是图片（base64）
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "无法加载文件内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

// ===== Delete repo confirmation dialog =====
@Composable
private fun DeleteRepoDialog(
    repoName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var confirmText by remember { mutableStateOf("") }
    val matches = confirmText == repoName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除仓库") },
        text = {
            Column {
                Text(
                    "此操作不可撤销，将永久删除仓库 \"$repoName\" 及其所有数据（issues、PR、代码等）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "请输入仓库名称 \"$repoName\" 以确认删除：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    singleLine = true,
                    isError = confirmText.isNotEmpty() && !matches,
                    label = { Text("仓库名称") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches
            ) {
                Text(
                    "我了解后果，删除仓库",
                    color = if (matches) GitHubRed
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ===== Create issue dialog =====
@Composable
private fun CreateIssueDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Issue") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("标题") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("内容 (可选)") },
                    minLines = 3,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title.trim(), body.trim()) },
                enabled = title.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ===== Clone 对话框 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneDialog(
    repo: com.githubclient.app.data.remote.model.Repository,
    onDismiss: () -> Unit,
    onDownloadZip: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    var protocol by remember { mutableStateOf("HTTPS") }
    val owner = repo.owner?.login ?: ""
    val httpsUrl = "https://github.com/$owner/${repo.name}.git"
    val sshUrl = "git@github.com:$owner/${repo.name}.git"
    val cliUrl = "gh repo clone $owner/${repo.name}"
    val currentUrl = when (protocol) {
        "SSH" -> sshUrl
        "CLI" -> cliUrl
        else -> httpsUrl
    }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    var copied by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("克隆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                // 协议切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    listOf("HTTPS", "SSH", "GitHub CLI").forEach { p ->
                        val isSelected = protocol == p || (p == "GitHub CLI" && protocol == "CLI")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    protocol = if (p == "GitHub CLI") "CLI" else p
                                }
                        ) {
                            Text(
                                p,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 克隆 URL + 复制按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        val clip = android.content.ClipData.newPlainText("Clone URL", currentUrl)
                        clipboard.setPrimaryClip(clip)
                        copied = true
                    }) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "复制",
                            tint = if (copied) GitHubGreen
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (copied) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已复制到剪贴板",
                        style = MaterialTheme.typography.labelSmall,
                        color = GitHubGreen
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // 下载 ZIP
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDownloadZip)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "下载 ZIP 压缩包",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 在浏览器中打开
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenInBrowser)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "在浏览器中打开",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

// ===== Repo header card =====
@Composable
private fun RepoHeaderCard(
    repo: com.githubclient.app.data.remote.model.Repository,
    isStarred: Boolean = false,
    onStarClick: () -> Unit = {},
    onForkClick: () -> Unit = {},
    onCloneClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            repo.description?.let { d ->
                if (d.isNotBlank()) {
                    Text(d, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ===== 克隆按钮（全宽，醒目绿色，和官网一样）=====
            Button(
                onClick = onCloneClick,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ForkColor),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.Code, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("克隆", color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== Fork + 星标 按钮（并排，各占一半）=====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Fork 按钮
                OutlinedButton(
                    onClick = onForkClick,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForkColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ForkColor),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复刻", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }

                // 星标 按钮
                OutlinedButton(
                    onClick = onStarClick,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isStarred) StarColor else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (isStarred) StarColor else MaterialTheme.colorScheme.outline
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (isStarred) StarColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isStarred) "已星标" else "星标",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatWithIcon(Icons.Filled.Star, NumberUtils.formatCount(repo.stargazersCount), StarColor)
                StatWithIcon(Icons.Filled.CallSplit, NumberUtils.formatCount(repo.forksCount), ForkColor)
                repo.language?.let { l ->
                    if (l.isNotBlank()) StatWithIcon(
                        Icons.Filled.Circle, l,
                        Color(parseHex(Constants.getLanguageColor(l))), dot = true
                    )
                }
            }

            if (!repo.topics.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repo.topics.take(4).forEach { topic ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(topic, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatWithIcon(icon: ImageVector, text: String, color: Color, dot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(12.dp)) {}
        } else {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== 代码浏览 Tab =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeTab(
    contents: List<Content>,
    readme: String?,
    currentPath: String,
    onDirClick: (String) -> Unit,
    onFileClick: (Content) -> Unit,
    onFileDownload: (Content) -> Unit,
    onDeleteFile: (Content) -> Unit,
    onDeleteFiles: (List<Pair<String, String>>) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    // 多选模式状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }
    // 长按弹出菜单
    var longPressContent by remember { mutableStateOf<Content?>(null) }

    val fileContents = contents.filter { it.type == "file" }
    val selectedCount = fileContents.count { selectedFiles[it.path] == true }

    Column(modifier = Modifier.fillMaxSize()) {
        // 多选模式操作栏
        if (isMultiSelectMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 全选
                    val allSelected = fileContents.isNotEmpty() && fileContents.all { selectedFiles[it.path] == true }
                    TextButton(onClick = {
                        if (allSelected) {
                            selectedFiles.clear()
                        } else {
                            fileContents.forEach { selectedFiles[it.path] = true }
                        }
                    }) {
                        Icon(
                            if (allSelected) Icons.Filled.Check else Icons.Filled.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 已选数量
                    Text(
                        "已选 $selectedCount 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 删除按钮
                    if (selectedCount > 0) {
                        TextButton(onClick = {
                            val toDelete = fileContents
                                .filter { selectedFiles[it.path] == true }
                                .map { it.path to it.sha }
                            onDeleteFiles(toDelete)
                            selectedFiles.clear()
                            isMultiSelectMode = false
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = GitHubRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除", color = GitHubRed)
                        }
                    }
                    // 退出多选
                    TextButton(onClick = {
                        selectedFiles.clear()
                        isMultiSelectMode = false
                    }) {
                        Text("取消")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 面包屑导航
            if (currentPath.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onDirClick("") }) { Text("根目录") }
                        currentPath.split("/").filter { it.isNotEmpty() }.forEachIndexed { index, part ->
                            Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val subPath = currentPath.split("/").filter { it.isNotEmpty() }
                                .take(index + 1).joinToString("/")
                            TextButton(onClick = { onDirClick(subPath) }) { Text(part) }
                        }
                    }
                }
            }

            // 文件列表
            items(contents, key = { it.sha + it.path }) { item ->
                FileItem(
                    content = item,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = selectedFiles[item.path] == true,
                    onClick = {
                        if (isMultiSelectMode) {
                            // 多选模式下点击切换选中状态
                            if (item.type == "file") {
                                selectedFiles[item.path] = !(selectedFiles[item.path] == true)
                            }
                        } else if (item.type == "dir") {
                            onDirClick(item.path)
                        } else {
                            onFileClick(item)
                        }
                    },
                    onDownload = { onFileDownload(item) },
                    onLongClick = {
                        if (isMultiSelectMode) {
                            // 多选模式下长按退出多选
                            selectedFiles.clear()
                            isMultiSelectMode = false
                        } else {
                            // 文件和文件夹都可以长按弹出菜单
                            longPressContent = item
                        }
                    }
                )
            }

            // README（仅在根目录显示）
            if (currentPath.isEmpty() && !readme.isNullOrBlank()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = GitHubBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "README",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            MarkdownText(
                                markdown = readme,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (contents.isEmpty() && readme.isNullOrBlank()) {
                item { EmptyState(message = "空仓库") }
            }
        }
    }

    // 长按弹出菜单：根据文件/文件夹显示不同选项
    longPressContent?.let { content ->
        DropdownMenu(
            expanded = true,
            onDismissRequest = { longPressContent = null }
        ) {
            if (content.type == "dir") {
                // 文件夹：直接删除整个文件夹
                DropdownMenuItem(
                    text = { Text("删除此文件夹", color = GitHubRed) },
                    onClick = {
                        longPressContent = null
                        onDeleteFolder(content.path)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = GitHubRed, modifier = Modifier.size(20.dp))
                    }
                )
            } else {
                // 文件：删除单个或进入多选
                DropdownMenuItem(
                    text = { Text("删除此文件", color = GitHubRed) },
                    onClick = {
                        longPressContent = null
                        onDeleteFile(content)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = GitHubRed, modifier = Modifier.size(20.dp))
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("多选删除") },
                    onClick = {
                        longPressContent = null
                        isMultiSelectMode = true
                        selectedFiles[content.path] = true
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    content: Content,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选模式下显示复选框
        if (isMultiSelectMode && content.type == "file") {
            Icon(
                imageVector = if (isSelected) Icons.Filled.Check else Icons.Filled.Circle,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = if (content.type == "dir") Icons.Filled.Folder
                else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (content.type == "dir") GitHubPurple
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = content.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (content.type != "dir" && !isMultiSelectMode) {
            Text(
                text = NumberUtils.formatFileSize(content.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (content.downloadUrl != null) {
                IconButton(onClick = onDownload, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ===== Releases Tab =====
@Composable
private fun ReleasesTab(
    releases: List<Release>,
    onCreateRelease: () -> Unit = {},
    onDownload: (url: String, name: String) -> Unit,
    onDeleteRelease: (Long) -> Unit = {}
) {
    // 创建发布按钮（始终显示在顶部）
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = onCreateRelease,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GitHubGreen),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("上传 APK / 创建发布", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    if (releases.isEmpty()) {
        EmptyState(message = "暂无发布版本")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(releases, key = { it.id }) { release ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 发布标题 + 删除按钮
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null,
                            tint = GitHubPurple, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            release.name ?: release.tagName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (release.prerelease) Color(0xFFD29922) else GitHubGreen
                        ) {
                            Text(
                                if (release.prerelease) "预发布" else "最新",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onDeleteRelease(release.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除发布",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "标签: ${release.tagName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!release.publishedAt.isNullOrEmpty()) {
                        Text(
                            "发布于 ${TimeUtils.formatRelativeTime(release.publishedAt!!)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 发布说明
                    release.body?.let { body ->
                        if (body.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stripMarkdown(body).take(500),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 下载资产
                    if (release.assets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "下载文件 (${release.assets.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        release.assets.forEach { asset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDownload(asset.browserDownloadUrl, asset.name)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(asset.name, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium)
                                    Text(
                                        "${NumberUtils.formatFileSize(asset.size.toInt())} · " +
                                            "下载 ${asset.downloadCount} 次",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 源码下载
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        release.zipballUrl?.let { url ->
                            TextButton(onClick = { onDownload(url, "${release.tagName}.zip") }) {
                                Text("下载源码 ZIP")
                            }
                        }
                        release.tarballUrl?.let { url ->
                            TextButton(onClick = { onDownload(url, "${release.tagName}.tar.gz") }) {
                                Text("下载源码 TAR")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== Issues Tab =====
@Composable
private fun IssuesTab(
    issues: List<Issue>, owner: String, repo: String,
    onIssueClick: (String, String, Int) -> Unit
) {
    if (issues.isEmpty()) { EmptyState(message = "暂无打开的 Issue"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(issues, key = { it.id }) { issue ->
            IssueCard(
                issue = issue,
                onClick = { onIssueClick(owner, repo, issue.number) }
            )
        }
    }
}

// ===== PRs Tab =====
@Composable
private fun PullRequestsTab(
    prs: List<PullRequest>, owner: String, repo: String,
    onIssueClick: (String, String, Int) -> Unit
) {
    if (prs.isEmpty()) { EmptyState(message = "暂无打开的 PR"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(prs, key = { it.id }) { pr ->
            PrCard(pr = pr, onClick = { onIssueClick(owner, repo, pr.number) })
        }
    }
}

@Composable
private fun PrCard(pr: PullRequest, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Filled.CallSplit, contentDescription = null, tint = GitHubPurple,
                modifier = Modifier.size(20.dp).align(Alignment.Top))
            Column(modifier = Modifier.weight(1f)) {
                Text(pr.title, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${pr.number}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("由 ${pr.user?.login ?: ""} 创建",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("更新于 ${TimeUtils.formatRelativeTime(pr.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ===== Branches Tab =====
@Composable
private fun BranchesTab(branches: List<Branch>) {
    if (branches.isEmpty()) { EmptyState(message = "暂无分支"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(branches, key = { it.name }) { branch ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Code, contentDescription = null,
                        tint = GitHubGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(branch.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
                    if (branch.protected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50)) {
                            Text("受保护", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

// ===== Cloud Build Entry Tab =====
@Composable
private fun CloudBuildEntryTab(
    owner: String,
    repo: String,
    onNavigateToCloudBuild: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ===== 构建卡片 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = GitHubBlue.copy(alpha = 0.1f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = GitHubBlue,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "GitHub Actions 云端构建",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "填写基本信息后点击构建，GitHub Actions 自动编译 APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(20.dp))

                // 构建步骤
                BuildStepItem(step = "1", title = "创建/检查工作流", desc = "自动创建 GitHub Actions 工作流文件")
                Spacer(modifier = Modifier.height(12.dp))
                BuildStepItem(step = "2", title = "触发构建", desc = "点击构建按钮，云端开始编译")
                Spacer(modifier = Modifier.height(12.dp))
                BuildStepItem(step = "3", title = "等待编译", desc = "Ubuntu + JDK 17 环境自动编译 APK")
                Spacer(modifier = Modifier.height(12.dp))
                BuildStepItem(step = "4", title = "下载 APK", desc = "构建完成后直接下载 APK 产物")

                Spacer(modifier = Modifier.height(24.dp))

                // 构建 APK 按钮
                Button(
                    onClick = { onNavigateToCloudBuild(owner, repo) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GitHubGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("构建 APK", fontWeight = FontWeight.SemiBold, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "进入构建页面查看详细配置和构建记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ===== 快速信息卡片 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = GitHubBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "构建说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildString {
                        appendLine("• 构建 APK 使用 GitHub Actions 云端编译")
                        appendLine("• 自动创建工作流文件 .github/workflows/build-apk.yml")
                        appendLine("• 编译环境: Ubuntu + JDK 17 + Android SDK")
                        appendLine("• 构建命令: ./gradlew assembleDebug")
                        appendLine("• 产物路径: app/build/outputs/apk/debug/app-debug.apk")
                        appendLine("• 构建完成后可直接下载 APK")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }
}

@Composable
private fun BuildStepItem(step: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = GitHubBlue.copy(alpha = 0.1f),
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    step,
                    style = MaterialTheme.typography.labelMedium,
                    color = GitHubBlue,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 辅助函数 =====
private fun stripMarkdown(md: String): String {
    return md
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        .replace(Regex("`([^`]+?)`"), "$1")
        .replace(Regex("!\\[.*?]\\(.*?\\)"), "[图片]")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
}

private fun parseHex(hex: String): Long {
    return try {
        val clean = hex.removePrefix("#")
        (0xFF000000L or clean.toLong(16))
    } catch (e: Exception) { 0xFF6E7781L }
}

/** 从 Uri 查询文件显示名（OpenableColumns.DISPLAY_NAME）。 */
private fun queryFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
    }
    return name
}

/** 读取文件字节并以 Base64（NO_WRAP）编码，用于上传。 */
private fun readAndBase64Encode(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }.getOrNull()
}

/**
 * 解压 ZIP 文件，返回 (相对路径, base64内容) 列表。
 * 跳过目录条目和 Mac 元数据文件（__MACOSX, .DS_Store）。
 */
private fun extractZipEntries(
    context: Context,
    uri: Uri
): List<Pair<String, String>> {
    val entries = mutableListOf<Pair<String, String>>()
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zipStream = ZipInputStream(input)
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name
                // 跳过目录和系统隐藏文件
                if (!entry.isDirectory &&
                    !name.startsWith("__MACOSX") &&
                    !name.endsWith(".DS_Store") &&
                    !name.endsWith("/")) {

                    // 去掉 ZIP 内可能的顶层目录前缀
                    // 例如 "githup/app/src/..." -> "app/src/..."
                    val cleanPath = stripTopLevelDir(name)
                    if (cleanPath.isNotBlank()) {
                        val bytes = zipStream.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        entries.add(cleanPath to base64)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()
        }
    } catch (e: Exception) {
        // 解压失败
    }
    return entries
}

/**
 * 如果 ZIP 条目路径有统一的顶层目录（如 "githup/app/..."），则去掉顶层目录。
 * 如果顶层目录不统一或只有一个文件，保持原样。
 */
private fun stripTopLevelDir(path: String): String {
    val parts = path.split("/")
    // 如果只有一级路径，直接返回
    if (parts.size <= 1) return path
    // 返回去掉第一级后的路径
    return parts.drop(1).joinToString("/")
}
