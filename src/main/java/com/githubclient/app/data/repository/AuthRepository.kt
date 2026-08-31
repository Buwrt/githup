package com.githubclient.app.data.repository

import com.githubclient.app.data.remote.GitHubApiService
import com.githubclient.app.data.remote.model.User
import com.githubclient.app.util.TokenManager
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: GitHubApiService,
    private val tokenManager: TokenManager
) {
    suspend fun login(token: String): Result<User> {
        return try {
            tokenManager.saveToken(token)
            val response = api.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                tokenManager.clearToken()
                Result.failure(Exception("登录失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            tokenManager.clearToken()
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): Result<User> {
        return try {
            val response = api.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取用户信息失败: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Boolean = tokenManager.getToken() != null

    fun logout() {
        runBlocking { tokenManager.clearToken() }
    }

    fun getToken(): String? = tokenManager.getToken()

    suspend fun getLastToken(): String? = tokenManager.getLastToken()

    fun saveToken(token: String) {
        runBlocking { tokenManager.saveToken(token) }
    }

    fun clearToken() {
        runBlocking { tokenManager.clearToken() }
    }
}
