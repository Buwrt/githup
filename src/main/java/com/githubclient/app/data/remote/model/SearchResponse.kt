package com.githubclient.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class SearchResponse<T>(
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("incomplete_results") val incompleteResults: Boolean = false,
    val items: List<T> = emptyList()
)

data class TrendingRepo(
    val author: String = "",
    val name: String = "",
    val avatar: String = "",
    val url: String = "",
    val description: String = "",
    val language: String? = null,
    val languageColor: String? = null,
    val stars: Int = 0,
    val forks: Int = 0,
    val currentPeriodStars: Int = 0,
    val builtBy: List<BuiltBy> = emptyList()
)

data class BuiltBy(
    val username: String = "",
    val href: String = "",
    val avatar: String = ""
)
