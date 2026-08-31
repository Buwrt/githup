package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

/**
 * PUT /repos/{owner}/{repo}/contents/{path} 的响应体
 * 注意：GitHub API 的 content 字段在创建/更新文件时返回的是对象，不是字符串
 */
data class CreateOrUpdateFileResponse(
    val content: Content? = null,
    val commit: CommitSummary? = null
)

data class CommitSummary(
    val sha: String = "",
    @SerializedName("node_id") val nodeId: String? = null,
    val url: String = "",
    val message: String? = null
)

data class Content(
    val type: String = "",
    val encoding: String? = null,
    val size: Int = 0,
    val name: String = "",
    val path: String = "",
    val content: String? = null,
    val sha: String = "",
    val url: String = "",
    @SerializedName("git_url") val gitUrl: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("_links") val links: ContentLinks? = null
)

data class ContentLinks(
    val git: String? = null,
    val self: String = "",
    val html: String? = null
)

data class Branch(
    val name: String = "",
    @SerializedName("commit") val commit: BranchCommit? = null,
    val protected: Boolean = false
)

data class BranchCommit(
    val sha: String = "",
    val url: String = ""
)

data class Comment(
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    val url: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    val body: String = "",
    val user: User? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("author_association") val authorAssociation: String = ""
)
