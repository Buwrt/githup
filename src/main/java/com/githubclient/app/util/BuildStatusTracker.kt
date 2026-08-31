package com.githubclient.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局构建状态追踪器，用于在首页显示活跃构建状态。
 */
object BuildStatusTracker {

    data class BuildInfo(
        val owner: String,
        val repo: String,
        val runId: Long,
        val buildName: String,
        val status: String,        // queued, in_progress, completed, etc.
        val triggeredAt: Long,     // timestamp
    )

    private val _activeBuilds = MutableStateFlow<List<BuildInfo>>(emptyList())
    val activeBuilds: StateFlow<List<BuildInfo>> = _activeBuilds.asStateFlow()

    // 取消构建的回调（由 CloudBuildViewModel 设置）
    private var cancelCallback: ((Long) -> Unit)? = null

    /**
     * 设置取消构建的回调
     */
    fun setCancelCallback(callback: (Long) -> Unit) {
        cancelCallback = callback
    }

    /**
     * 注册一个新构建（当触发构建时调用）
     */
    fun addBuild(owner: String, repo: String, runId: Long, buildName: String) {
        val info = BuildInfo(
            owner = owner,
            repo = repo,
            runId = runId,
            buildName = buildName,
            status = "queued",
            triggeredAt = System.currentTimeMillis()
        )
        _activeBuilds.value = _activeBuilds.value + info
    }

    /**
     * 更新构建状态
     */
    fun updateStatus(runId: Long, status: String) {
        _activeBuilds.value = _activeBuilds.value.map {
            if (it.runId == runId) it.copy(status = status) else it
        }
    }

    /**
     * 移除构建记录
     */
    fun removeBuild(runId: Long) {
        _activeBuilds.value = _activeBuilds.value.filterNot { it.runId == runId }
    }

    /**
     * 清除已完成/失败的构建
     */
    fun clearCompleted() {
        _activeBuilds.value = _activeBuilds.value.filter {
            it.status != "completed" && it.status != "cancelled" && it.status != "failed"
        }
    }

    /**
     * 取消构建（调用回调通知 ViewModel 执行 API 调用）
     */
    fun cancelBuild(runId: Long) {
        cancelCallback?.invoke(runId)
        updateStatus(runId, "cancelled")
    }

    /**
     * 是否有活跃构建
     */
    fun hasActiveBuilds(): Boolean =
        _activeBuilds.value.any {
            it.status == "queued" || it.status == "in_progress"
        }
}
