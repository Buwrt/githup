package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class PullRequest(
    val url: String = "",
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("diff_url") val diffUrl: String = "",
    @SerializedName("patch_url") val patchUrl: String = "",
    @SerializedName("issue_url") val issueUrl: String = "",
    val number: Int = 0,
    val state: String = "open",
    val locked: Boolean = false,
    val title: String = "",
    val user: User? = null,
    val body: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("closed_at") val closedAt: String? = null,
    @SerializedName("merged_at") val mergedAt: String? = null,
    @SerializedName("merge_commit_sha") val mergeCommitSha: String? = null,
    val assignee: User? = null,
    val assignees: List<User> = emptyList(),
    @SerializedName("requested_reviewers") val requestedReviewers: List<User> = emptyList(),
    val labels: List<Label> = emptyList(),
    val milestone: Milestone? = null,
    val head: PRBranch? = null,
    val base: PRBranch? = null,
    @SerializedName("author_association") val authorAssociation: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    @SerializedName("rebaseable") val rebaseable: Boolean? = null,
    @SerializedName("mergeable_state") val mergeableState: String? = null,
    val comments: Int = 0,
    @SerializedName("review_comments") val reviewComments: Int = 0,
    @SerializedName("maintainer_can_modify") val maintainerCanModify: Boolean = false,
    val commits: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerializedName("changed_files") val changedFiles: Int = 0
)

data class PRBranch(
    val label: String = "",
    val ref: String = "",
    val sha: String = "",
    val user: User? = null,
    val repo: Repository? = null
)
