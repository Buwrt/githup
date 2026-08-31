package com.githubclient.app.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局应用设置管理器。
 * 使用 SharedPreferences 持久化，确保设置不会丢失。
 */
object AppSettings {

    private var prefs: android.content.SharedPreferences? = null

    // ===== 深色模式 =====
    // 0=跟随系统, 1=浅色, 2=深色
    private val _darkMode = MutableStateFlow(0)
    val darkMode: StateFlow<Int> = _darkMode.asStateFlow()

    // ===== 语言 =====
    // 0=跟随系统, 1=简体中文, 2=English
    private val _language = MutableStateFlow(0)
    val language: StateFlow<Int> = _language.asStateFlow()

    // ===== 通知设置 =====
    private val _pushEnabled = MutableStateFlow(true)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()

    private val _emailEnabled = MutableStateFlow(false)
    val emailEnabled: StateFlow<Boolean> = _emailEnabled.asStateFlow()

    private val _repoActivityEnabled = MutableStateFlow(true)
    val repoActivityEnabled: StateFlow<Boolean> = _repoActivityEnabled.asStateFlow()

    // ===== 下载路径 =====
    private val _downloadPath = MutableStateFlow(Environment.DIRECTORY_DOWNLOADS + "/githup")
    val downloadPath: StateFlow<String> = _downloadPath.asStateFlow()

    // ===== 自动检查更新 =====
    private val _autoCheckUpdate = MutableStateFlow(true)
    val autoCheckUpdate: StateFlow<Boolean> = _autoCheckUpdate.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _darkMode.value = prefs?.getInt("dark_mode", 0) ?: 0
        _language.value = prefs?.getInt("language", 0) ?: 0
        _pushEnabled.value = prefs?.getBoolean("push_enabled", true) ?: true
        _emailEnabled.value = prefs?.getBoolean("email_enabled", false) ?: false
        _repoActivityEnabled.value = prefs?.getBoolean("repo_activity_enabled", true) ?: true
        _downloadPath.value = prefs?.getString("download_path", Environment.DIRECTORY_DOWNLOADS + "/githup")
            ?: (Environment.DIRECTORY_DOWNLOADS + "/githup")
        _autoCheckUpdate.value = prefs?.getBoolean("auto_check_update", true) ?: true

        // 应用已保存的语言设置
        applyLanguage()
    }

    // ===== 深色模式 =====
    fun setDarkMode(value: Int) {
        _darkMode.value = value
        prefs?.edit()?.putInt("dark_mode", value)?.apply()
        // 通知主题刷新
        _settingsChanged.value = System.currentTimeMillis()
    }

    /** 判断是否应该使用深色主题 */
    fun isDarkTheme(systemDark: Boolean): Boolean {
        return when (_darkMode.value) {
            1 -> false
            2 -> true
            else -> systemDark
        }
    }

    // ===== 语言 =====
    fun setLanguage(value: Int) {
        _language.value = value
        prefs?.edit()?.putInt("language", value)?.apply()

        // 实际切换应用语言
        applyLanguage()

        // 语言切换联动翻译插件
        when (value) {
            1 -> TranslationPlugin.setEnabled(true)  // 简体中文 → 开启翻译
            2 -> TranslationPlugin.setEnabled(false) // English → 关闭翻译
            else -> TranslationPlugin.setEnabled(true) // 跟随系统 → 默认开启翻译
        }

        // 通知界面刷新
        _settingsChanged.value = System.currentTimeMillis()
    }

    /** 实际切换 Android 系统语言 */
    private fun applyLanguage() {
        when (_language.value) {
            1 -> AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags("zh-CN")
            )
            2 -> AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags("en")
            )
            else -> AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.getEmptyLocaleList()
            )
        }
    }

    /** 重新创建 Activity 以应用语言和主题变更 */
    fun recreateActivity(activity: Activity) {
        activity.recreate()
    }

    // ===== 通知 =====
    fun setPushEnabled(value: Boolean) {
        _pushEnabled.value = value
        prefs?.edit()?.putBoolean("push_enabled", value)?.apply()
    }

    fun setEmailEnabled(value: Boolean) {
        _emailEnabled.value = value
        prefs?.edit()?.putBoolean("email_enabled", value)?.apply()
        _settingsChanged.value = System.currentTimeMillis()
    }

    fun setRepoActivityEnabled(value: Boolean) {
        _repoActivityEnabled.value = value
        prefs?.edit()?.putBoolean("repo_activity_enabled", value)?.apply()
    }

    // ===== 下载路径 =====
    fun setDownloadPath(path: String) {
        _downloadPath.value = path
        prefs?.edit()?.putString("download_path", path)?.apply()
    }

    // ===== 自动检查更新 =====
    fun setAutoCheckUpdate(value: Boolean) {
        _autoCheckUpdate.value = value
        prefs?.edit()?.putBoolean("auto_check_update", value)?.apply()
    }

    // ===== 设置变更通知（用于触发界面刷新） =====
    private val _settingsChanged = MutableStateFlow(0L)
    val settingsChanged: StateFlow<Long> = _settingsChanged.asStateFlow()

    // ===== 语言显示名称 =====
    val languageOptions = listOf("跟随系统", "简体中文", "English")
    val darkModeOptions = listOf("跟随系统", "浅色模式", "深色模式")

    // 下载路径预设选项
    val downloadPathOptions = listOf(
        "Download/githup" to Environment.DIRECTORY_DOWNLOADS + "/githup",
        "Download" to Environment.DIRECTORY_DOWNLOADS,
        "Documents/githup" to "Documents/githup",
        "Movies/githup" to Environment.DIRECTORY_MOVIES + "/githup",
    )

    fun getLanguageName(): String = languageOptions.getOrElse(_language.value) { "跟随系统" }
    fun getDarkModeName(): String = darkModeOptions.getOrElse(_darkMode.value) { "跟随系统" }
}
