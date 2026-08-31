package com.githubclient.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局主题管理器。
 * 管理深色/浅色模式，状态持久化到 SharedPreferences。
 */
object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_FOLLOW_SYSTEM = "follow_system"

    private var prefs: android.content.SharedPreferences? = null

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _followSystem = MutableStateFlow(true)
    val followSystem: StateFlow<Boolean> = _followSystem.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _followSystem.value = prefs?.getBoolean(KEY_FOLLOW_SYSTEM, true) ?: true
        _isDarkMode.value = prefs?.getBoolean(KEY_DARK_MODE, false) ?: false
    }

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        _followSystem.value = false
        prefs?.edit()?.apply {
            putBoolean(KEY_DARK_MODE, enabled)
            putBoolean(KEY_FOLLOW_SYSTEM, false)
        }?.apply()
    }

    fun setFollowSystem(enabled: Boolean) {
        _followSystem.value = enabled
        prefs?.edit()?.putBoolean(KEY_FOLLOW_SYSTEM, enabled)?.apply()
    }

    fun isDark(): Boolean = _isDarkMode.value
}
