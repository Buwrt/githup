package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class Issue(
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    val url: String = "",
    @SerializedName("repository_url") val repositoryUrl: String = "",
    @SerializedName("labels_url") val labelsUrl: String = "",
    @SerializedName("comments_url") val commentsUrl: String = "",
    @SerializedName("events_url") val eventsUrl: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    val number: Int = 0,
    val state: String = "open",
    val title: String = "",
    val body: String? = null,
    val user: User? = null,
    val labels: List<Label> = emptyList(),
    val assignee: User? = null,
    val assignees: List<User> = emptyList(),
    val milestone: Milestone? = null,
    val locked: Boolean = false,
    @SerializedName("active_lock_reason") val activeLockReason: String? = null,
    val comments: Int = 0,
    @SerializedName("pull_request") val pullRequest: PullRequestRef? = null,
    @SerializedName("closed_at") val closedAt: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("author_association") val authorAssociation: String = "",
    @SerializedName("state_reason") val stateReason: String? = null
)

data class Label(
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    val url: String = "",
    val name: String = "",
    val description: String? = null,
    val color: String = "",
    val default: Boolean = false
)

data class Milestone(
    val url: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("labels_url") val labelsUrl: String = "",
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    val number: Int = 0,
    val state: String = "",
    val title: String = "",
    val description: String? = null,
    val creator: User? = null,
    @SerializedName("open_issues") val openIssues: Int = 0,
    @SerializedName("closed_issues") val closedIssues: Int = 0,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("due_on") val dueOn: String? = null,
    @SerializedName("closed_at") val closedAt: String? = null
)

data class PullRequestRef(
    val url: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    @SerializedName("diff_url") val diffUrl: String? = null,
    @SerializedName("patch_url") val patchUrl: String? = null,
    @SerializedName("merged_at") val mergedAt: String? = null
)
