package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class Event(
    val id: String = "",
    val type: String = "",
    val actor: Actor? = null,
    val repo: EventRepo? = null,
    val payload: Payload? = null,
    @SerializedName("public") val isPublic: Boolean = true,
    @SerializedName("created_at") val createdAt: String = ""
)

data class Actor(
    val id: Long = 0,
    val login: String = "",
    @SerializedName("display_login") val displayLogin: String = "",
    @SerializedName("gravatar_id") val gravatarId: String = "",
    val url: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = ""
)

data class EventRepo(
    val id: Long = 0,
    val name: String = "",
    val url: String = ""
)

data class Payload(
    val action: String? = null,
    @SerializedName("push_id") val pushId: Long? = null,
    val size: Int? = null,
    @SerializedName("distinct_size") val distinctSize: Int? = null,
    val ref: String? = null,
    val head: String? = null,
    val before: String? = null,
    val commits: List<Commit>? = null,
    val issue: Issue? = null,
    @SerializedName("pull_request") val pullRequest: PullRequest? = null,
    val comment: Comment? = null,
    @SerializedName("release") val release: Release? = null,
    @SerializedName("forkee") val forkee: Repository? = null,
    @SerializedName("created") val created: Boolean? = null,
    @SerializedName("deleted") val deleted: Boolean? = null,
    @SerializedName("ref_type") val refType: String? = null,
    @SerializedName("master_branch") val masterBranch: String? = null,
    val description: String? = null,
    @SerializedName("pusher_type") val pusherType: String? = null,
    @SerializedName("review") val review: Review? = null,
    @SerializedName("number") val number: Int? = null
)

data class Commit(
    val sha: String = "",
    @SerializedName("author") val author: CommitAuthor? = null,
    val message: String = "",
    val distinct: Boolean = true,
    val url: String = ""
)

data class CommitAuthor(
    val email: String = "",
    val name: String = ""
)

data class Release(
    val url: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    val id: Long = 0,
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("target_commitish") val targetCommitish: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("published_at") val publishedAt: String? = null,
    val author: User? = null,
    val assets: List<ReleaseAsset> = emptyList(),
    @SerializedName("tarball_url") val tarballUrl: String? = null,
    @SerializedName("zipball_url") val zipballUrl: String? = null,
    @SerializedName("upload_url") val uploadUrl: String = ""
)

data class ReleaseAsset(
    val id: Long = 0,
    val name: String = "",
    val label: String? = null,
    @SerializedName("content_type") val contentType: String = "",
    val state: String = "",
    val size: Long = 0,
    @SerializedName("download_count") val downloadCount: Long = 0,
    @SerializedName("browser_download_url") val browserDownloadUrl: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = ""
)

data class Review(
    val id: Long = 0,
    val user: User? = null,
    val body: String? = null,
    val state: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("submitted_at") val submittedAt: String? = null
)
