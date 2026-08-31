package com.githubclient.app.util

object Constants {
    const val BASE_URL = "https://api.github.com/"

    // GitHub 官网页面（在 APP 内置浏览器打开）
    const val GITHUB_TOKEN_SETTINGS_URL = "https://github.com/settings/tokens"
    const val GITHUB_NEW_TOKEN_URL = "https://github.com/settings/tokens/new"
    const val GITHUB_SIGNUP_URL = "https://github.com/signup"
    const val GITHUB_LOGIN_URL = "https://github.com/login"
    const val GITHUB_ACCOUNT_SETTINGS_URL = "https://github.com/settings/profile"
    const val GITHUB_SECURITY_URL = "https://github.com/settings/security"

    // OAuth Web Flow
    const val OAUTH_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
    const val OAUTH_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
    const val OAUTH_REDIRECT_URI = "githubclient://oauth/callback"

    // OAuth App 配置：请替换为你自己在 github.com 注册的 OAuth App 的 Client ID 和 Secret。
    // 注册地址：https://github.com/settings/developers -> New OAuth App
    // Authorization callback URL 填写：githubclient://oauth/callback
    const val OAUTH_CLIENT_ID = "REPLACE_WITH_YOUR_OAUTH_CLIENT_ID"
    const val OAUTH_CLIENT_SECRET = "REPLACE_WITH_YOUR_OAUTH_CLIENT_SECRET"
    const val OAUTH_SCOPES = "repo,user,notifications,read:org"

    private val LANGUAGE_COLORS = mapOf(
        "Kotlin" to "#A97BFF", "Java" to "#B07219", "JavaScript" to "#F1E05A",
        "TypeScript" to "#3178C6", "Python" to "#3572A5", "C" to "#555555",
        "C++" to "#F34B7D", "C#" to "#178600", "Go" to "#00ADD8", "Rust" to "#DEA584",
        "Ruby" to "#701516", "PHP" to "#4F5D95", "Swift" to "#F05138", "Dart" to "#00B4AB",
        "HTML" to "#E34C26", "CSS" to "#563D7C", "Shell" to "#89E051", "Scala" to "#C22D40",
        "Vue" to "#41B883", "Lua" to "#000080", "Perl" to "#0298C3", "Haskell" to "#5E5086",
        "Elixir" to "#6E4A7E", "Clojure" to "#DB5855", "Zig" to "#EC915C", "Solidity" to "#AA6746",
        "Jupyter Notebook" to "#DA5B0B", "Dockerfile" to "#384D54", "Makefile" to "#427819",
        "PowerShell" to "#012456", "Vue" to "#41B883", "Svelte" to "#FF3E00", "Astro" to "#FF5A03",
    )

    fun getLanguageColor(language: String?): String {
        if (language.isNullOrBlank()) return "#6E7781"
        return LANGUAGE_COLORS[language] ?: "#6E7781"
    }
}
