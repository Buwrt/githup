package com.githubclient.app.ui.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import javax.inject.Inject

data class SelectedFile(
    val uri: String,
    val name: String,
    val size: Long = 0L,
    val uploadStatus: UploadStatus = UploadStatus.Pending,
    val errorMsg: String? = null,
)

enum class UploadStatus { Pending, Uploading, Done, Failed }

data class CreateReleaseUiState(
    val tagName: String = "",
    val title: String = "",
    val body: String = "",
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val successMessage: String? = null,
)

@HiltViewModel
class CreateReleaseViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateReleaseUiState())
    val uiState: StateFlow<CreateReleaseUiState> = _uiState.asStateFlow()

    fun onTagNameChange(v: String) { _uiState.value = _uiState.value.copy(tagName = v) }
    fun onTitleChange(v: String) { _uiState.value = _uiState.value.copy(title = v) }
    fun onBodyChange(v: String) { _uiState.value = _uiState.value.copy(body = v) }

    fun onFilesSelected(uris: List<Uri>) {
        val newFiles = uris.map { uri ->
            val (name, size) = queryFileInfo(uri)
            SelectedFile(
                uri = uri.toString(),
                name = name ?: "file_${System.currentTimeMillis()}",
                size = size
            )
        }
        _uiState.value = _uiState.value.copy(
            selectedFiles = _uiState.value.selectedFiles + newFiles
        )
    }

    fun removeFile(uri: String) {
        _uiState.value = _uiState.value.copy(
            selectedFiles = _uiState.value.selectedFiles.filterNot { it.uri == uri }
        )
    }

    /** 发布成功后重置表单，允许用户继续发布新版本 */
    fun resetForNewRelease() {
        _uiState.value = CreateReleaseUiState()
    }

    fun publish(owner: String, repo: String) {
        val state = _uiState.value
        if (state.tagName.isBlank()) {
            _uiState.value = state.copy(error = "标签名称不能为空")
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, success = false, successMessage = null)

            // Step 1: create the release
            val createResult = gitHubRepository.createRelease(
                owner = owner,
                repo = repo,
                tagName = state.tagName.trim(),
                name = state.title.ifBlank { null },
                body = state.body.ifBlank { null }
            )

            createResult.onSuccess { release ->
                if (state.selectedFiles.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        success = true,
                        successMessage = "发布成功！"
                    )
                    return@launch
                }

                // Step 2: upload each file as a separate asset
                var successCount = 0
                var failCount = 0
                val uploadedNames = mutableListOf<String>()

                state.selectedFiles.forEachIndexed { index, file ->
                    // Update status to uploading
                    val updatedFiles = _uiState.value.selectedFiles.toMutableList()
                    updatedFiles[index] = file.copy(uploadStatus = UploadStatus.Uploading)
                    _uiState.value = _uiState.value.copy(selectedFiles = updatedFiles)

                    val uri = Uri.parse(file.uri)
                    val filePart = createFilePart(uri, file.name)
                    if (filePart == null) {
                        failCount++
                        val updated2 = _uiState.value.selectedFiles.toMutableList()
                        updated2[index] = file.copy(uploadStatus = UploadStatus.Failed, errorMsg = "无法读取文件")
                        _uiState.value = _uiState.value.copy(selectedFiles = updated2)
                        return@forEachIndexed
                    }

                    val uploadResult = gitHubRepository.uploadAsset(
                        uploadUrl = release.uploadUrl,
                        name = file.name,
                        file = filePart
                    )
                    uploadResult.onSuccess {
                        successCount++
                        uploadedNames.add(file.name)
                        val updated2 = _uiState.value.selectedFiles.toMutableList()
                        updated2[index] = file.copy(uploadStatus = UploadStatus.Done)
                        _uiState.value = _uiState.value.copy(selectedFiles = updated2)
                    }.onFailure { e ->
                        failCount++
                        val updated2 = _uiState.value.selectedFiles.toMutableList()
                        updated2[index] = file.copy(uploadStatus = UploadStatus.Failed, errorMsg = e.message)
                        _uiState.value = _uiState.value.copy(selectedFiles = updated2)
                    }
                }

                val msg = if (failCount == 0) {
                    "发布成功！已上传 $successCount 个文件"
                } else {
                    "发布已创建。$successCount 个文件上传成功，$failCount 个失败"
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    success = true,
                    successMessage = msg
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "创建发布失败"
                )
            }
        }
    }

    /** Query file name and size from a content [uri]. */
    private fun queryFileInfo(uri: Uri): Pair<String?, Long> {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && cursor.moveToFirst()) cursor.getLong(sizeIndex) else 0L
                name to size
            } ?: (null to 0L)
        } catch (e: Exception) {
            null to 0L
        }
    }

    /**
     * Build a [MultipartBody.Part] by streaming the file content from [uri].
     */
    private fun createFilePart(uri: Uri, fileName: String): MultipartBody.Part? {
        return try {
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestBody = object : RequestBody() {
                override fun contentType() = mediaType

                override fun writeTo(sink: BufferedSink) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            MultipartBody.Part.createFormData("file", fileName, requestBody)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateReleaseScreen(
    owner: String,
    repo: String,
    onBack: () -> Unit,
    viewModel: CreateReleaseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesSelected(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建发布", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 仓库信息
            Text(
                "$owner / $repo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 标签名称
            OutlinedTextField(
                value = uiState.tagName,
                onValueChange = viewModel::onTagNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标签名称 *") },
                placeholder = { Text("v1.0.0") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 发布标题
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("发布标题") },
                placeholder = { Text("发布标题（可选）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 发布说明
            OutlinedTextField(
                value = uiState.body,
                onValueChange = viewModel::onBodyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("发布说明") },
                placeholder = { Text("描述本次发布的内容...") },
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 文件上传区域 - 拖拽风格
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = null,
                        tint = GitHubBlue,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击选择文件",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GitHubBlue
                    )
                    Text(
                        text = "支持 APK、ZIP 等多文件上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = !uiState.isLoading,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择文件")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 已选文件列表 - 类似第二张截图的文件列表风格
            if (uiState.selectedFiles.isNotEmpty()) {
                Text(
                    "已选文件 (${uiState.selectedFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                uiState.selectedFiles.forEach { file ->
                    FileListItem(
                        file = file,
                        onRemove = { viewModel.removeFile(file.uri) },
                        enabled = !uiState.isLoading
                    )
                }
            }

            Text(
                "可选：上传 APK、ZIP 等文件作为发布附件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 发布按钮
            Button(
                onClick = { viewModel.publish(owner, repo) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !uiState.isLoading && uiState.tagName.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GitHubGreen,
                    contentColor = Color.White
                )
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("发布中...", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("发布", fontWeight = FontWeight.SemiBold)
                }
            }

            // 成功信息
            val successMessage = uiState.successMessage
            if (uiState.success && successMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = GitHubGreen.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = GitHubGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = successMessage,
                            color = GitHubGreen,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("返回仓库")
                    }
                    Button(
                        onClick = { viewModel.resetForNewRelease() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GitHubGreen,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发布新版本", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 错误信息
            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 单个文件列表项 - 类似 GitHub 文件浏览的列表风格
 */
@Composable
private fun FileListItem(
    file: SelectedFile,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    val isApk = file.name.endsWith(".apk", ignoreCase = true)
    val isZip = file.name.endsWith(".zip", ignoreCase = true)

    // 文件图标和颜色
    val icon = if (isApk) Icons.Filled.Description else Icons.Filled.Description
    val iconTint = when {
        isApk -> GitHubGreen
        isZip -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 上传状态颜色
    val statusText = when (file.uploadStatus) {
        UploadStatus.Pending -> "等待上传"
        UploadStatus.Uploading -> "上传中..."
        UploadStatus.Done -> "已上传"
        UploadStatus.Failed -> "失败: ${file.errorMsg ?: "未知错误"}"
    }
    val statusColor = when (file.uploadStatus) {
        UploadStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        UploadStatus.Uploading -> MaterialTheme.colorScheme.primary
        UploadStatus.Done -> GitHubGreen
        UploadStatus.Failed -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件图标
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            // 文件名和状态
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (file.size > 0) {
                        Text(
                            text = formatFileSize(file.size) + " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }

            // 上传中显示进度指示器
            if (file.uploadStatus == UploadStatus.Uploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (file.uploadStatus == UploadStatus.Done) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = GitHubGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 删除按钮（非上传中时显示）
            if (enabled && file.uploadStatus != UploadStatus.Uploading) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
