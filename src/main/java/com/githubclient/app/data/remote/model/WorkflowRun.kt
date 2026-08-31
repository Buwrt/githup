package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class WorkflowRun(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("head_branch") val headBranch: String? = null,
    @SerializedName("run_number") val runNumber: Int = 0,
    val status: String = "",        // queued, in_progress, completed
    val conclusion: String? = null, // success, failure, cancelled, null if running
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("display_title") val displayTitle: String = "",
    val event: String = "",
    @SerializedName("workflow_id") val workflowId: Long = 0,
)

data class WorkflowArtifact(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("size_in_bytes") val sizeInBytes: Long = 0,
    @SerializedName("archive_download_url") val archiveDownloadUrl: String = "",
    val expired: Boolean = false,
    @SerializedName("created_at") val createdAt: String = "",
)

data class WorkflowRunsResponse(
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList()
)

data class WorkflowArtifactsResponse(
    @SerializedName("total_count") val totalCount: Int = 0,
    val artifacts: List<WorkflowArtifact> = emptyList()
)

data class WorkflowInfo(
    val id: Long = 0,
    val name: String = "",
    val path: String = "",
    val state: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
)

data class WorkflowsResponse(
    @SerializedName("total_count") val totalCount: Int = 0,
    val workflows: List<WorkflowInfo> = emptyList()
)
