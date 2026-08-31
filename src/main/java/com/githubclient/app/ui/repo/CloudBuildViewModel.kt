package com.githubclient.app.ui.repo

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubclient.app.data.remote.model.WorkflowArtifact
import com.githubclient.app.data.remote.model.WorkflowInfo
import com.githubclient.app.data.remote.model.WorkflowRun
import com.githubclient.app.data.repository.GitHubRepository
import com.githubclient.app.util.BuildStatusTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudBuildUiState(
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val isTriggering: Boolean = false,
    val workflowExists: Boolean = false,       // 仓库已有工作流
    val workflowCreated: Boolean = false,       // 本次创建了工作流
    val runs: List<WorkflowRun> = emptyList(),
    val artifacts: Map<Long, List<WorkflowArtifact>> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CloudBuildViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudBuildUiState())
    val uiState: StateFlow<CloudBuildUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repoName: String = ""
    private var workflowInfo: WorkflowInfo? = null  // 仓库中已有的工作流信息

    companion object {
        private const val WORKFLOW_PATH = ".github/workflows/build-apk.yml"
        private const val WORKFLOW_FILENAME = "build-apk.yml"

        private val WORKFLOW_YAML = """
name: Build APK
on:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
      - name: Build APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: APK
          path: app/build/outputs/apk/debug/app-debug.apk
""".trimIndent()
    }

    fun init(owner: String, repo: String) {
        this.owner = owner
        this.repoName = repo
        // 注册取消构建回调，供首页 BuildStatusTracker 调用
        BuildStatusTracker.setCancelCallback { runId ->
            cancelRun(runId)
        }
        checkWorkflowStatus()
    }

    /**
     * 检查仓库中是否已有工作流，同时加载构建记录。
     */
    private fun checkWorkflowStatus() {
        viewModelScope.launch {
            try {
                // 1. 列出仓库中的所有工作流
                val workflowsResult = gitHubRepository.listWorkflows(owner, repoName)
                workflowsResult.onSuccess { response ->
                    // 查找我们的构建工作流
                    val found = response.workflows.find { it.path.contains(WORKFLOW_FILENAME) }
                    workflowInfo = found
                    _uiState.value = _uiState.value.copy(
                        workflowExists = found != null,
                        workflowCreated = found != null,
                    )
                }
                // 2. 加载构建记录
                loadRuns()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载工作流状态失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 自动创建工作流并触发构建（一步到位）。
     *
     * 流程：
     * 1. 如果仓库已有工作流 → 直接触发构建
     * 2. 如果没有 → 创建工作流 → 自动触发构建
     * 3. 创建时处理 422（空仓库）→ 用 Git Database API 初始化
     */
    fun createAndTriggerBuild() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, error = null, message = null)
            try {
                val base64Content = Base64.encodeToString(
                    WORKFLOW_YAML.toByteArray(), Base64.NO_WRAP
                )

                // 如果已有工作流，直接触发
                if (workflowInfo != null || _uiState.value.workflowExists) {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        workflowCreated = true,
                        message = "工作流已存在，正在触发构建..."
                    )
                    triggerBuildInternal()
                    return@launch
                }

                // 尝试创建工作流文件
                val createResult = gitHubRepository.createOrUpdateFile(
                    owner, repoName, WORKFLOW_PATH,
                    "Add build workflow", base64Content
                )

                createResult.onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        workflowCreated = true,
                        workflowExists = true,
                        message = "工作流已创建！正在自动触发构建..."
                    )
                    // 等待 GitHub 处理
                    delay(3000)
                    // 重新获取工作流列表
                    refreshWorkflowInfo()
                    // 自动触发构建
                    triggerBuildInternal()
                }.onFailure { e ->
                    val errorMsg = e.message ?: ""
                    if (errorMsg.contains("422")) {
                        // 422 可能是仓库为空或文件已存在
                        tryInitEmptyRepo(base64Content)
                    } else if (errorMsg.contains("409") || errorMsg.contains("sha")) {
                        // 文件已存在，获取 SHA 后更新
                        _uiState.value = _uiState.value.copy(
                            workflowCreated = true,
                            workflowExists = true,
                            message = "工作流已存在，正在触发构建..."
                        )
                        refreshWorkflowInfo()
                        triggerBuildInternal()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isCreating = false,
                            error = "创建失败: $errorMsg\n" +
                                "请检查 Token 是否有 repo 和 workflow 权限"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    isTriggering = false,
                    error = "构建异常: ${e.message}"
                )
            }
        }
    }

    /**
     * 仓库为空时，使用 Git Database API 初始化仓库并创建工作流。
     */
    private suspend fun tryInitEmptyRepo(base64Content: String) {
        _uiState.value = _uiState.value.copy(
            message = "检测到仓库为空，正在自动初始化..."
        )

        val repoInfo = gitHubRepository.getRepoInfo(owner, repoName)
        val defaultBranch = repoInfo.getOrNull()?.defaultBranch ?: "main"

        val initResult = gitHubRepository.initRepoWithFile(
            owner, repoName, defaultBranch,
            WORKFLOW_PATH, base64Content,
            "Initialize repo with build workflow"
        )

        initResult.onSuccess {
            _uiState.value = _uiState.value.copy(
                isCreating = false,
                workflowCreated = true,
                workflowExists = true,
                message = "仓库已初始化，工作流已创建！正在自动触发构建..."
            )
            delay(3000)
            refreshWorkflowInfo()
            triggerBuildInternal()
        }.onFailure { e2 ->
            _uiState.value = _uiState.value.copy(
                isCreating = false,
                error = "无法自动创建工作流。\n" +
                    "可能原因：\n" +
                    "1. Token 缺少 repo 写入权限\n" +
                    "2. 仓库不存在或无访问权限\n" +
                    "3. 网络连接问题\n" +
                    "错误: ${e2.message}"
            )
        }
    }

    /**
     * 刷新工作流信息（获取最新工作流列表）。
     */
    private suspend fun refreshWorkflowInfo() {
        val result = gitHubRepository.listWorkflows(owner, repoName)
        result.onSuccess { response ->
            val found = response.workflows.find { it.path.contains(WORKFLOW_FILENAME) }
            workflowInfo = found
            _uiState.value = _uiState.value.copy(
                workflowExists = found != null,
                workflowCreated = found != null,
            )
        }
    }

    /**
     * 触发构建（内部方法，使用正确的工作流 ID/文件名）。
     */
    private suspend fun triggerBuildInternal() {
        _uiState.value = _uiState.value.copy(isTriggering = true, error = null)

        // 获取最新工作流列表以确定正确的工作流标识符
        if (workflowInfo == null) {
            refreshWorkflowInfo()
        }

        // GitHub API 支持用文件名（如 build-apk.yml）或 workflow ID 触发
        val workflowId = workflowInfo?.path?.substringAfterLast("/") ?: WORKFLOW_FILENAME

        val result = gitHubRepository.triggerWorkflow(
            owner, repoName, workflowId
        )

        result.onSuccess {
            _uiState.value = _uiState.value.copy(
                isTriggering = false,
                message = "构建已触发！正在等待云端编译..."
            )
            delay(5000)
            loadRuns()
        }.onFailure { e ->
            // 如果用文件名失败，尝试用 workflow ID
            if (workflowInfo != null && workflowId != workflowInfo?.id?.toString()) {
                val result2 = gitHubRepository.triggerWorkflow(
                    owner, repoName, workflowInfo!!.id.toString()
                )
                result2.onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isTriggering = false,
                        message = "构建已触发！正在等待云端编译..."
                    )
                    delay(5000)
                    loadRuns()
                }.onFailure { e2 ->
                    _uiState.value = _uiState.value.copy(
                        isTriggering = false,
                        error = "触发失败: ${e2.message}"
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isTriggering = false,
                    error = "触发失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 手动触发构建（从 UI 按钮调用）。
     */
    fun triggerBuild() {
        viewModelScope.launch {
            triggerBuildInternal()
        }
    }

    /** 加载构建历史 */
    fun loadRuns() {
        if (owner.isBlank() || repoName.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = gitHubRepository.getWorkflowRuns(owner, repoName)
                result.onSuccess { response ->
                    val runs = response.workflowRuns
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        runs = runs,
                    )
                    // 注册活跃构建到全局 Tracker（首页显示）
                    runs.forEach { run ->
                        if (run.status == "queued" || run.status == "in_progress") {
                            BuildStatusTracker.addBuild(owner, repoName, run.id, run.name ?: "Build APK")
                            BuildStatusTracker.updateStatus(run.id, run.status)
                        } else if (run.status == "completed") {
                            BuildStatusTracker.updateStatus(run.id, run.conclusion ?: "completed")
                        }
                    }
                    // 为已完成的构建加载产物
                    runs.filter { it.status == "completed" }.forEach { run ->
                        loadArtifacts(run.id)
                    }
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "加载失败: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载异常: ${e.message}"
                )
            }
        }
    }

    /** 加载构建产物 */
    private fun loadArtifacts(runId: Long) {
        viewModelScope.launch {
            try {
                val result = gitHubRepository.getArtifacts(owner, repoName, runId)
                result.onSuccess { response ->
                    val current = _uiState.value.artifacts.toMutableMap()
                    current[runId] = response.artifacts
                    _uiState.value = _uiState.value.copy(artifacts = current)
                }
            } catch (e: Exception) {
                // 静默忽略产物加载失败，不影响主流程
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /** 取消正在进行的构建 */
    fun cancelRun(runId: Long) {
        viewModelScope.launch {
            val result = gitHubRepository.cancelWorkflowRun(owner, repoName, runId)
            result.onSuccess {
                BuildStatusTracker.updateStatus(runId, "cancelled")
                _uiState.value = _uiState.value.copy(
                    message = "构建 #$runId 已取消"
                )
                delay(2000)
                loadRuns()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = "取消失败: ${e.message}"
                )
            }
        }
    }

    /** 删除选中的构建记录 */
    fun deleteRuns(runIds: List<Long>) {
        if (runIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            var successCount = 0
            var failCount = 0
            val errors = mutableListOf<String>()
            for (runId in runIds) {
                val result = gitHubRepository.deleteWorkflowRun(owner, repoName, runId)
                if (result.isSuccess) {
                    successCount++
                } else {
                    failCount++
                    val errMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    errors.add("#$runId: $errMsg")
                }
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                message = if (failCount == 0) "已删除 $successCount 条构建记录"
                          else "删除完成：成功 $successCount 条，失败 $failCount 条\n" +
                               "失败原因: ${errors.first()}\n" +
                               "可能需要 Token 有 admin 权限"
            )
            loadRuns()
        }
    }
}
