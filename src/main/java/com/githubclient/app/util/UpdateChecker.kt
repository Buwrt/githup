package com.githubclient.app.util

import com.githubclient.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查 githup 软件更新。
 * 从 https://github.com/Buwrt/githup 的 Releases 获取最新版本。
 *
 * 更新策略：
 * - 小数点后第一位（minor）变化 → 强制更新（必须升级才能继续使用）
 * - 小数点后第二位或第三位（patch）变化 → 选择性更新（可跳过）
 *
 * 例如版本 1.2.3：
 * - 1.3.0 → minor 2→3，强制更新
 * - 1.2.4 → patch 3→4，可选更新
 * - 1.2.5 → patch 3→5，可选更新
 */
object UpdateChecker {

    private const val REPO_OWNER = "Buwrt"
    private const val REPO_NAME = "githup"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val releasePageUrl: String,
        val hasUpdate: Boolean,
        val isForceUpdate: Boolean,    // 是否强制更新
        val currentVersion: String,
    )

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /**
     * 检查更新（协程方式，启动时自动调用）
     * 受 AppSettings.autoCheckUpdate 开关控制
     */
    fun checkForUpdateAsync() {
        if (!AppSettings.autoCheckUpdate.value) return
        CoroutineScope(Dispatchers.IO).launch {
            checkForUpdate()
        }
    }

    /**
     * 检查更新
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        _checking.value = true
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "githup-android")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                val htmlUrl = json.optString("html_url", "https://github.com/$REPO_OWNER/$REPO_NAME/releases")
                val body = json.optString("body", "")

                // 获取 APK 下载链接
                var downloadUrl = htmlUrl
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i)
                        val name = asset?.optString("name", "") ?: ""
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", htmlUrl)
                            break
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val hasUpdate = compareVersions(tagName, currentVersion) > 0

                // 判断是否强制更新
                // 小数点后第一位（minor，即版本号第二段）不同 → 强制更新
                // 仅小数点后第二位或第三位（patch，即版本号第三段及以后）不同 → 可选更新
                val isForceUpdate = if (hasUpdate) {
                    isForceUpdateRequired(currentVersion, tagName)
                } else false

                val info = UpdateInfo(
                    latestVersion = tagName,
                    downloadUrl = downloadUrl,
                    releaseNotes = body,
                    releasePageUrl = htmlUrl,
                    hasUpdate = hasUpdate,
                    isForceUpdate = isForceUpdate,
                    currentVersion = currentVersion,
                )
                _updateInfo.value = info
                info
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            _checking.value = false
        }
    }

    /**
     * 判断是否需要强制更新。
     * 版本号格式：major.minor.patch（如 1.2.3）
     * - minor（小数点后第一位）变化 → 强制更新
     * - 仅 patch（小数点后第二位或第三位）变化 → 可选更新
     */
    private fun isForceUpdateRequired(currentVersion: String, latestVersion: String): Boolean {
        val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latestVersion.split(".").map { it.toIntOrNull() ?: 0 }

        // 比较 major（第一段）：如果 major 不同，也是强制更新
        val curMajor = currentParts.getOrElse(0) { 0 }
        val latestMajor = latestParts.getOrElse(0) { 0 }
        if (curMajor != latestMajor) return true

        // 比较 minor（小数点后第一位 = 第二段）：如果 minor 不同，强制更新
        val curMinor = currentParts.getOrElse(1) { 0 }
        val latestMinor = latestParts.getOrElse(1) { 0 }
        if (curMinor != latestMinor) return true

        // 仅 patch（小数点后第二位/第三位 = 第三段及以后）不同 → 可选更新
        return false
    }

    /**
     * 比较版本号，返回 >0 表示 v1 > v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
