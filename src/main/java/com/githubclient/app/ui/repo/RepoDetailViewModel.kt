package com.githubclient.app.ui.repo

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.remote.model.Branch
import com.githubclient.app.data.remote.model.Content
import com.githubclient.app.data.remote.model.Issue
import com.githubclient.app.data.remote.model.PullRequest
import com.githubclient.app.data.remote.model.Release
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.repository.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoDetailUiState(
    val isLoading: Boolean = false,
    val repo: Repository? = null,
    val readme: String? = null,
    val contents: List<Content> = emptyList(),
    val currentPath: String = "",
    val issues: List<Issue> = emptyList(),
    val pullRequests: List<PullRequest> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val releases: List<Release> = emptyList(),
    val isStarred: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val isUploadingZip: Boolean = false,
    val uploadProgress: String? = null,
    // 文件查看
    val viewingFile: Content? = null,
    val fileContent: String? = null,
    val isLoadingFile: Boolean = false,
)

@HiltViewModel
class RepoDetailViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepoDetailUiState())
    val uiState: StateFlow<RepoDetailUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repoName: String = ""

    fun load(owner: String, repo: String) {
        this.owner = owner
        this.repoName = repo
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val repoResult = gitHubRepository.getRepo(owner, repo)
                repoResult.onSuccess { r ->
                    try {
                        val readmeResult = gitHubRepository.getReadme(owner, repo)
                        val decoded = readmeResult.getOrNull()?.let { decodeBase64(it.content, it.encoding) }
                        // 防止超长 README 导致内存溢出，限制最大长度
                        val safeReadme = if (decoded != null && decoded.length > 50000) {
                            decoded.substring(0, 50000) + "\n\n... (内容过长，已截断)"
                        } else {
                            decoded
                        }
                        val contents = gitHubRepository.getRepoContents(owner, repo, "").getOrNull().orEmpty()
                        val issues = gitHubRepository.getRepoIssues(owner, repo, state = "open").getOrNull().orEmpty()
                        val prs = gitHubRepository.getRepoPulls(owner, repo, state = "open").getOrNull().orEmpty()
                        val branches = gitHubRepository.getBranches(owner, repo).getOrNull().orEmpty()
                        val releases = gitHubRepository.getReleases(owner, repo).getOrNull().orEmpty()
                        val starredResult = gitHubRepository.checkStarred(owner, repo)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            repo = r,
                            readme = safeReadme,
                            contents = contents.sortedBy { it.type != "dir" },
                            issues = issues,
                            pullRequests = prs,
                            branches = branches,
                            releases = releases,
                            isStarred = starredResult.getOrNull() ?: false,
                        )
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "加载部分数据失败: ${e.message}"
                        )
                    }
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = e.message ?: "加载仓库失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, error = "加载失败: ${e.message}"
                )
            }
        }
    }

    /** 删除 Release */
    fun deleteRelease(releaseId: Long) {
        viewModelScope.launch {
            val result = gitHubRepository.deleteRelease(owner, repoName, releaseId)
            result.onSuccess {
                // 从列表中移除已删除的 release
                _uiState.value = _uiState.value.copy(
                    releases = _uiState.value.releases.filter { it.id != releaseId },
                    message = "发布已删除"
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = "删除失败: ${e.message}"
                )
            }
        }
    }

    /** 在 App 内查看文件内容 */
    fun loadFileContent(content: Content) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                viewingFile = content,
                isLoadingFile = true,
                fileContent = null
            )
            try {
                val result = gitHubRepository.getFileContent(content.url)
                result.onSuccess { fileContent ->
                    val decoded = if (fileContent.encoding == "base64") {
                        decodeBase64(fileContent.content ?: "", "base64")
                    } else {
                        fileContent.content
                    }
                    // 防止超大文件导致内存溢出
                    val safeContent = if (decoded != null && decoded.length > 100000) {
                        decoded.substring(0, 100000) + "\n\n... (文件过大，已截断显示)"
                    } else {
                        decoded
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoadingFile = false,
                        fileContent = safeContent
                    )
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingFile = false,
                        error = "加载文件失败: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingFile = false,
                    error = "加载文件异常: ${e.message}"
                )
            }
        }
    }

    /** 关闭文件查看器 */
    fun closeFileViewer() {
        _uiState.value = _uiState.value.copy(
            viewingFile = null,
            fileContent = null,
            isLoadingFile = false
        )
    }

    /** 浏览子目录 */
    fun loadPath(path: String) {
        viewModelScope.launch {
            val result = gitHubRepository.getRepoContents(owner, repoName, path)
            result.onSuccess { items ->
                _uiState.value = _uiState.value.copy(
                    contents = items.sortedBy { it.type != "dir" },
                    currentPath = path
                )
            }
        }
    }

    /** Star / Unstar */
    fun toggleStar() {
        viewModelScope.launch {
            if (_uiState.value.isStarred) {
                gitHubRepository.unstarRepo(owner, repoName)
            } else {
                gitHubRepository.starRepo(owner, repoName)
            }
            _uiState.value = _uiState.value.copy(
                isStarred = !_uiState.value.isStarred,
                message = if (_uiState.value.isStarred) "已取消星标" else "已星标"
            )
        }
    }

    /** Fork */
    fun forkRepo(onForked: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "正在 Fork...")
            val result = gitHubRepository.forkRepo(owner, repoName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(message = "Fork 成功!")
                onForked()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = "Fork 失败: ${e.message}")
            }
        }
    }

    /** 删除仓库 */
    fun deleteRepo(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val result = gitHubRepository.deleteRepo(owner, repoName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(message = "仓库已删除")
                onDeleted()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "删除失败: ${e.message}")
            }
        }
    }

    /** 上传文件（base64 内容） */
    fun uploadFile(fileName: String, base64Content: String, commitMessage: String) {
        viewModelScope.launch {
            val path = if (_uiState.value.currentPath.isEmpty()) fileName
                else "${_uiState.value.currentPath}/$fileName"
            val result = gitHubRepository.createOrUpdateFile(
                owner, repoName, path, commitMessage, base64Content
            )
            result.onSuccess {
                _uiState.value = _uiState.value.copy(message = "上传成功: $fileName")
                loadPath(_uiState.value.currentPath)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "上传失败: ${e.message}")
            }
        }
    }

    /**
     * 上传 ZIP 文件内容：解压后逐个文件上传到仓库，
     * 使仓库显示完整的目录结构（而非一个单独的 zip 文件）。
     */
    fun uploadZipFiles(
        entries: List<Pair<String, String>>,
        zipFileName: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploadingZip = true,
                uploadProgress = "正在上传 ZIP 内容... 0/${entries.size}",
                error = null,
                message = null
            )

            var success = 0
            var failed = 0
            val failedFiles = mutableListOf<String>()
            val basePath = _uiState.value.currentPath

            for ((index, entry) in entries.withIndex()) {
                val (filePath, base64Content) = entry
                val fullPath = if (basePath.isEmpty()) filePath else "$basePath/$filePath"
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "正在上传: $filePath (${index + 1}/${entries.size})"
                )
                val result = gitHubRepository.createOrUpdateFile(
                    owner, repoName,
                    fullPath,
                    "Upload $filePath from $zipFileName",
                    base64Content
                )
                if (result.isSuccess) {
                    success++
                } else {
                    failed++
                    val errMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    failedFiles.add("$filePath (错误: $errMsg)")
                }
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "上传进度: ${success + failed}/${entries.size}（成功 $success, 失败 $failed）\n" +
                        if (failedFiles.isNotEmpty()) "失败文件:\n${failedFiles.joinToString("\n")}" else ""
                )
            }

            val finalMessage = "ZIP 上传完成: $success 个文件成功" +
                if (failed > 0) "，$failed 个失败:\n${failedFiles.joinToString("\n")}" else ""
            _uiState.value = _uiState.value.copy(
                isUploadingZip = false,
                uploadProgress = null,
                message = finalMessage
            )
            loadPath(_uiState.value.currentPath)
        }
    }

    /** 创建 Issue */
    fun createIssue(title: String, body: String, onCreated: () -> Unit) {
        viewModelScope.launch {
            val result = gitHubRepository.createIssue(owner, repoName, title, body)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(message = "Issue 已创建")
                load(owner, repoName)
                onCreated()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "创建失败: ${e.message}")
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /** 长按删除文件（直接删除，无确认对话框） */
    fun deleteFile(path: String, sha: String) {
        viewModelScope.launch {
            val result = gitHubRepository.deleteFile(
                owner, repoName, path, "Delete $path", sha
            )
            result.onSuccess {
                loadPath(_uiState.value.currentPath)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "删除失败: ${e.message}")
            }
        }
    }

    /** 批量删除多个文件 */
    fun deleteFiles(files: List<Pair<String, String>>) {
        viewModelScope.launch {
            var success = 0
            var failed = 0
            val failedFiles = mutableListOf<String>()
            _uiState.value = _uiState.value.copy(
                isUploadingZip = true,
                uploadProgress = "正在删除文件... 0/${files.size}"
            )
            for ((index, file) in files.withIndex()) {
                val (path, sha) = file
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "正在删除: $path (${index + 1}/${files.size})"
                )
                val result = gitHubRepository.deleteFile(
                    owner, repoName, path, "Delete $path", sha
                )
                if (result.isSuccess) {
                    success++
                } else {
                    failed++
                    val errMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    failedFiles.add("$path (错误: $errMsg)")
                }
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "删除进度: ${index + 1}/${files.size}（成功 $success, 失败 $failed）\n" +
                        if (failedFiles.isNotEmpty()) "失败文件:\n${failedFiles.joinToString("\n")}" else ""
                )
            }
            val finalMessage = "删除完成: $success 个成功" +
                if (failed > 0) "，$failed 个失败:\n${failedFiles.joinToString("\n")}" else ""
            _uiState.value = _uiState.value.copy(
                isUploadingZip = false,
                uploadProgress = null,
                message = finalMessage
            )
            loadPath(_uiState.value.currentPath)
        }
    }

    /** 删除文件夹（递归获取所有文件并逐个删除） */
    fun deleteFolder(folderPath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploadingZip = true,
                uploadProgress = "正在扫描文件夹: $folderPath"
            )
            val allFiles = mutableListOf<Pair<String, String>>()
            val failedFiles = mutableListOf<String>()

            // 递归获取文件夹内所有文件
            suspend fun collectFiles(path: String) {
                val result = gitHubRepository.getRepoContents(owner, repoName, path)
                result.onSuccess { contents ->
                    contents.forEach { content ->
                        if (content.type == "file") {
                            allFiles.add(content.path to content.sha)
                        } else if (content.type == "dir") {
                            collectFiles(content.path)
                        }
                    }
                }.onFailure { e ->
                    failedFiles.add("$path (扫描失败: ${e.message})")
                }
            }
            collectFiles(folderPath)

            if (allFiles.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isUploadingZip = false,
                    uploadProgress = null,
                    message = if (failedFiles.isEmpty()) "文件夹为空，无需删除"
                    else "扫描失败:\n${failedFiles.joinToString("\n")}"
                )
                return@launch
            }

            var success = 0
            var failed = 0
            for ((index, filePair) in allFiles.withIndex()) {
                val (path, sha) = filePair
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "正在删除: $path (${index + 1}/${allFiles.size})"
                )
                val result = gitHubRepository.deleteFile(owner, repoName, path, "Delete $path", sha)
                if (result.isSuccess) {
                    success++
                } else {
                    failed++
                    val errMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    failedFiles.add("$path (错误: $errMsg)")
                }
                _uiState.value = _uiState.value.copy(
                    uploadProgress = "删除进度: ${index + 1}/${allFiles.size}（成功 $success, 失败 $failed）\n" +
                        if (failedFiles.isNotEmpty()) "失败文件:\n${failedFiles.joinToString("\n")}" else ""
                )
            }

            val finalMessage = "文件夹删除完成: $success 个文件成功删除" +
                if (failed > 0) "，$failed 个失败:\n${failedFiles.joinToString("\n")}" else ""
            _uiState.value = _uiState.value.copy(
                isUploadingZip = false,
                uploadProgress = null,
                message = finalMessage
            )
            loadPath(_uiState.value.currentPath)
        }
    }

    private fun decodeBase64(content: String?, encoding: String?): String {
        if (content.isNullOrBlank()) return ""
        if (encoding.equals("base64", ignoreCase = true)) {
            return try {
                val cleaned = content.replace("\n", "")
                String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                content
            }
        }
        return content
    }
}
