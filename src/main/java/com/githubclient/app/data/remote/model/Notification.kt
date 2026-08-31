package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class Notification(
    val id: String = "",
    val unread: Boolean = true,
    val reason: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("last_read_at") val lastReadAt: String? = null,
    val subject: NotificationSubject? = null,
    val repository: Repository? = null,
    val url: String = "",
    @SerializedName("subscription_url") val subscriptionUrl: String = ""
)

data class NotificationSubject(
    val title: String = "",
    val url: String? = null,
    @SerializedName("latest_comment_url") val latestCommentUrl: String? = null,
    val type: String = ""
)
