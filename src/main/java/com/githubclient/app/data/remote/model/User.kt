package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class User(
    val login: String = "",
    val id: Long = 0,
    @SerializedName("node_id") val nodeId: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    @SerializedName("gravatar_id") val gravatarId: String = "",
    val url: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("followers_url") val followersUrl: String = "",
    @SerializedName("following_url") val followingUrl: String = "",
    @SerializedName("gists_url") val gistsUrl: String = "",
    @SerializedName("starred_url") val starredUrl: String = "",
    @SerializedName("subscriptions_url") val subscriptionsUrl: String = "",
    @SerializedName("organizations_url") val organizationsUrl: String = "",
    @SerializedName("repos_url") val reposUrl: String = "",
    @SerializedName("events_url") val eventsUrl: String = "",
    @SerializedName("received_events_url") val receivedEventsUrl: String = "",
    val type: String = "User",
    @SerializedName("site_admin") val siteAdmin: Boolean = false,
    val name: String? = null,
    val company: String? = null,
    val blog: String? = null,
    val location: String? = null,
    val email: String? = null,
    val hireable: Boolean? = null,
    val bio: String? = null,
    @SerializedName("twitter_username") val twitterUsername: String? = null,
    @SerializedName("public_repos") val publicRepos: Int = 0,
    @SerializedName("public_gists") val publicGists: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = ""
)
