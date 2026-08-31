package com.githubclient.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "token_store")

class TokenManager(private val context: Context) {

    private val dataStore: DataStore<Preferences> = context.dataStore

    @Volatile
    private var cachedToken: String? = null

    init {
        // 启动时同步读取一次，便于非挂起调用
        cachedToken = runBlocking { dataStore.data.first()[TOKEN_KEY] }
        staticToken = cachedToken
    }

    suspend fun saveToken(token: String) {
        cachedToken = token
        staticToken = token
        dataStore.edit { it[TOKEN_KEY] = token }
    }

    fun getToken(): String? = cachedToken

    suspend fun clearToken() {
        // 保存上次使用的 Token（不清除 last_token）
        cachedToken?.let { last ->
            dataStore.edit { it[LAST_TOKEN_KEY] = last }
        }
        cachedToken = null
        staticToken = null
        dataStore.edit { it.remove(TOKEN_KEY) }
    }

    /** 获取上次使用的 Token（退出登录后仍可读取） */
    suspend fun getLastToken(): String? {
        return dataStore.data.first()[LAST_TOKEN_KEY]
    }

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("github_token")
        private val LAST_TOKEN_KEY = stringPreferencesKey("last_token")

        @Volatile
        private var staticToken: String? = null

        /** 静态获取 Token（可能为 null，用于工具类等无法注入的场景） */
        fun getStaticToken(): String? = staticToken
    }
}
