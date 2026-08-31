package com.githubclient.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth Web Flow。
 *
 * 流程：
 * 1. APP 打开 WebView 加载 GitHub 授权页（用户看到 GitHub 官方登录页）
 * 2. 用户在 GitHub 官方页面输入账号密码登录并授权
 * 3. GitHub 重定向到 redirect_uri?code=AUTH_CODE
 * 4. APP 拦截重定向，提取 code，向 GitHub 换取 access_token
 *
 * 前提：你需要在 https://github.com/settings/developers 注册一个 OAuth App，
 * 把其 Client ID 和 Client Secret 填入 [Constants.OAUTH_CLIENT_ID] 和 [Constants.OAUTH_CLIENT_SECRET]，
 * Authorization callback URL 填写：githubclient://oauth/callback
 */
object OAuthManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 获取 GitHub OAuth 授权页 URL。
     * 用户在 WebView 中打开此 URL 即可看到 GitHub 官方登录页。
     */
    fun getAuthorizeUrl(): String {
        return "${Constants.OAUTH_AUTHORIZE_URL}" +
                "?client_id=${Constants.OAUTH_CLIENT_ID}" +
                "&redirect_uri=${Constants.OAUTH_REDIRECT_URI}" +
                "&scope=${Constants.OAUTH_SCOPES.replace(",", "%20")}"
    }

    /**
     * 判断 URL 是否为 OAuth 回调（重定向到 redirect_uri）。
     */
    fun isOAuthCallback(url: String): Boolean {
        return url.startsWith(Constants.OAUTH_REDIRECT_URI)
    }

    /**
     * 从回调 URL 中提取授权码。
     * 回调 URL 格式：githubclient://oauth/callback?code=XXXX
     */
    fun extractCode(callbackUrl: String): String? {
        val queryPart = callbackUrl.substringAfter("?", "")
        return queryPart.split("&")
            .firstOrNull { it.startsWith("code=") }
            ?.substringAfter("code=")
    }

    /**
     * 用授权码换取 access_token。
     * 成功返回 token，失败返回 null。
     */
    suspend fun exchangeCodeForToken(code: String): String? {
        val form = FormBody.Builder()
            .add("client_id", Constants.OAUTH_CLIENT_ID)
            .add("client_secret", Constants.OAUTH_CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", Constants.OAUTH_REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(Constants.OAUTH_TOKEN_ENDPOINT)
            .header("Accept", "application/json")
            .post(form)
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val obj = gson.fromJson(body, JsonObject::class.java)
                obj.get("access_token")?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (e: Exception) {
            null
        }
    }
}
