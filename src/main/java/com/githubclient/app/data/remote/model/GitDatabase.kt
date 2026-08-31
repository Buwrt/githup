package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

// ===== Git Database API 数据模型 =====

data class CreateBlobRequest(
    val content: String,
    val encoding: String = "base64"
)

data class BlobResponse(
    val sha: String = "",
    val url: String = ""
)

data class TreeEntry(
    val path: String,
    val mode: String = "100644",  // 100644=file, 100755=executable, 040000=dir, 120000=symlink
    val type: String = "blob",     // blob, tree, commit
    val sha: String
)

data class CreateTreeRequest(
    @SerializedName("base_tree") val baseTree: String? = null,
    val tree: List<TreeEntry>
)

data class TreeResponse(
    val sha: String = "",
    val url: String = ""
)

data class CreateCommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String> = emptyList()
)

data class CommitResponse(
    val sha: String = "",
    val url: String = ""
)

data class CreateRefRequest(
    val ref: String,   // e.g. "refs/heads/main"
    val sha: String
)

data class RefResponse(
    val ref: String = "",
    @SerializedName("node_id") val nodeId: String = "",
    val url: String = "",
    val `object`: GitObject? = null
)

data class GitObject(
    val sha: String = "",
    val type: String = "",
    val url: String = ""
)

// ===== Repo info for default branch =====
data class RepoInfo(
    @SerializedName("default_branch") val defaultBranch: String = "main",
    @SerializedName("size") val size: Int = 0
)
