package com.githubclient.app.data.repository

import com.githubclient.app.data.remote.CreateRepoRequest
import com.githubclient.app.data.remote.CreateReleaseRequest
import com.githubclient.app.data.remote.GitHubApiService
import com.githubclient.app.data.remote.UploadFileRequest
import com.githubclient.app.data.remote.model.Branch
import com.githubclient.app.data.remote.model.BlobResponse
import com.githubclient.app.data.remote.model.CommitResponse
import com.githubclient.app.data.remote.model.Content
import com.githubclient.app.data.remote.model.CreateBlobRequest
import com.githubclient.app.data.remote.model.CreateCommitRequest
import com.githubclient.app.data.remote.model.CreateOrUpdateFileResponse
import com.githubclient.app.data.remote.model.CreateRefRequest
import com.githubclient.app.data.remote.model.CreateTreeRequest
import com.githubclient.app.data.remote.model.Event
import com.githubclient.app.data.remote.model.Issue
import com.githubclient.app.data.remote.model.Notification
import com.githubclient.app.data.remote.model.PullRequest
import com.githubclient.app.data.remote.model.RefResponse
import com.githubclient.app.data.remote.model.Release
import com.githubclient.app.data.remote.model.ReleaseAsset
import com.githubclient.app.data.remote.model.Repository
import com.githubclient.app.data.remote.model.SearchResponse
import com.githubclient.app.data.remote.model.TreeResponse
import com.githubclient.app.data.remote.model.User
import com.githubclient.app.data.remote.model.WorkflowArtifactsResponse
import com.githubclient.app.data.remote.model.WorkflowRunsResponse
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubRepository @Inject constructor(
    private val api: GitHubApiService
) {
    // ===== User =====
    suspend fun getUser(username: String): Result<User> = safeCall { api.getUser(username) }

    suspend fun getUserRepos(username: String, page: Int = 1): Result<List<Repository>> =
        safeCall { api.getUserRepos(username, page = page) }

    suspend fun getUserFollowers(username: String, page: Int = 1): Result<List<User>> =
        safeCall { api.getUserFollowers(username, page = page) }

    suspend fun getUserFollowing(username: String, page: Int = 1): Result<List<User>> =
        safeCall { api.getUserFollowing(username, page = page) }

    suspend fun getUserEvents(username: String, page: Int = 1): Result<List<Event>> =
        safeCall { api.getUserEvents(username, page = page) }

    suspend fun getUserReceivedEvents(username: String, page: Int = 1): Result<List<Event>> =
        safeCall { api.getUserReceivedEvents(username, page = page) }

    // ===== My Stuff =====
    suspend fun getMyRepos(page: Int = 1): Result<List<Repository>> =
        safeCall { api.getMyRepos(page = page) }

    suspend fun getStarredRepos(page: Int = 1): Result<List<Repository>> =
        safeCall { api.getStarredRepos(page = page) }

    suspend fun getMyIssues(page: Int = 1): Result<List<Issue>> =
        safeCall { api.getMyIssues(page = page) }

    suspend fun getMyNotifications(page: Int = 1): Result<List<Notification>> =
        safeCall { api.getNotifications(page = page) }

    suspend fun getMyFollowers(page: Int = 1): Result<List<User>> =
        safeCall { api.getMyFollowers(page = page) }

    suspend fun getMyFollowing(page: Int = 1): Result<List<User>> =
        safeCall { api.getMyFollowing(page = page) }

    // ===== Repo =====
    suspend fun getRepo(owner: String, repo: String): Result<Repository> =
        safeCall { api.getRepo(owner, repo) }

    suspend fun getRepoContents(owner: String, repo: String, path: String, ref: String? = null): Result<List<Content>> =
        safeCall { api.getContents(owner, repo, path, ref) }

    suspend fun getReadme(owner: String, repo: String, ref: String? = null): Result<Content> =
        safeCall { api.getReadme(owner, repo, ref) }

    /** 获取单个文件的内容（用于 App 内查看代码） */
    suspend fun getFileContent(url: String): Result<Content> =
        safeCall { api.getFileContent(url) }

    suspend fun getBranches(owner: String, repo: String, page: Int = 1): Result<List<Branch>> =
        safeCall { api.getBranches(owner, repo, page = page) }

    suspend fun getReleases(owner: String, repo: String, page: Int = 1): Result<List<Release>> =
        safeCall { api.getReleases(owner, repo, page = page) }

    // ===== Create Release & Upload Asset =====

    suspend fun createRelease(
        owner: String,
        repo: String,
        tagName: String,
        name: String?,
        body: String?,
        targetCommitish: String? = null
    ): Result<Release> = safeCall {
        api.createRelease(
            owner, repo,
            CreateReleaseRequest(
                tag_name = tagName,
                target_commitish = targetCommitish,
                name = name,
                body = body
            )
        )
    }

    /** 删除指定 Release */
    suspend fun deleteRelease(
        owner: String,
        repo: String,
        releaseId: Long
    ): Result<Unit> = safeCall {
        api.deleteRelease(owner, repo, releaseId)
    }

    /**
     * Upload a binary asset to a release.
     *
     * [uploadUrl] should be the `upload_url` returned by createRelease; it has a URI template
     * suffix like `{?name,name,label}` which is stripped here, then the asset name is passed
     * as a query parameter and the file as a multipart part.
     */
    suspend fun uploadAsset(
        uploadUrl: String,
        name: String,
        file: MultipartBody.Part
    ): Result<ReleaseAsset> {
        val baseUrl = uploadUrl.substringBefore("{")
        return safeCall { api.uploadAsset(baseUrl, name, file) }
    }

    suspend fun getLanguages(owner: String, repo: String): Result<Map<String, Int>> =
        safeCall { api.getLanguages(owner, repo) }

    suspend fun getRepoIssues(owner: String, repo: String, state: String = "open", page: Int = 1): Result<List<Issue>> =
        safeCall { api.getRepoIssues(owner, repo, state = state, page = page) }

    suspend fun getRepoPulls(owner: String, repo: String, state: String = "open", page: Int = 1): Result<List<PullRequest>> =
        safeCall { api.getRepoPulls(owner, repo, state = state, page = page) }

    suspend fun getRepoEvents(owner: String, repo: String, page: Int = 1): Result<List<Event>> =
        safeCall { api.getRepoEvents(owner, repo, page = page) }

    suspend fun starRepo(owner: String, repo: String): Result<Unit> =
        safeCall { api.starRepo(owner, repo) }

    suspend fun unstarRepo(owner: String, repo: String): Result<Unit> =
        safeCall { api.unstarRepo(owner, repo) }

    suspend fun checkStarred(owner: String, repo: String): Result<Boolean> {
        return try {
            val response = api.checkStarred(owner, repo)
            Result.success(response.isSuccessful && response.code() == 204)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Issue / PR Detail =====
    suspend fun getIssue(owner: String, repo: String, number: Int): Result<Issue> =
        safeCall { api.getIssue(owner, repo, number) }

    suspend fun getIssueComments(owner: String, repo: String, number: Int, page: Int = 1): Result<List<com.githubclient.app.data.remote.model.Comment>> =
        safeCall { api.getIssueComments(owner, repo, number, page = page) }

    suspend fun updateIssue(owner: String, repo: String, number: Int, body: Map<String, String>): Result<Issue> =
        safeCall { api.updateIssue(owner, repo, number, body) }

    suspend fun getPullRequest(owner: String, repo: String, number: Int): Result<PullRequest> =
        safeCall { api.getPullRequest(owner, repo, number) }

    suspend fun addIssueComment(owner: String, repo: String, number: Int, body: String): Result<com.githubclient.app.data.remote.model.Comment> =
        safeCall { api.createIssueComment(owner, repo, number, mapOf("body" to body)) }

    // ===== Notifications =====
    suspend fun markNotificationRead(id: String): Result<Unit> =
        safeCall { api.markNotificationRead(id) }

    suspend fun markAllNotificationsRead(): Result<Unit> = safeCall { api.markAllNotificationsRead() }

    suspend fun deleteNotification(threadId: String): Result<Unit> = safeCall { api.deleteNotification(threadId) }

    // ===== Search =====
    suspend fun searchRepos(
        query: String, page: Int = 1, sort: String? = null, order: String? = null
    ): Result<SearchResponse<Repository>> =
        safeCall { api.searchRepos(query, sort = sort, order = order, page = page) }

    suspend fun searchUsers(query: String, page: Int = 1): Result<SearchResponse<User>> =
        safeCall { api.searchUsers(query, page = page) }

    suspend fun searchIssues(query: String, page: Int = 1): Result<SearchResponse<Issue>> =
        safeCall { api.searchIssues(query, page = page) }

    // ===== Follow =====
    suspend fun followUser(username: String): Result<Unit> =
        safeCall { api.followUser(username) }

    suspend fun unfollowUser(username: String): Result<Unit> =
        safeCall { api.unfollowUser(username) }

    suspend fun checkFollowing(username: String): Result<Boolean> {
        return try {
            val response = api.checkFollowing(username)
            Result.success(response.isSuccessful && response.code() == 204)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Create / Delete / Fork Repo =====
    suspend fun createRepo(name: String, description: String?, isPrivate: Boolean, autoInit: Boolean): Result<Repository> =
        safeCall { api.createRepo(CreateRepoRequest(name, description, isPrivate, autoInit)) }

    suspend fun deleteRepo(owner: String, repo: String): Result<Unit> {
        return try {
            val response = api.deleteRepo(owner, repo)
            if (response.isSuccessful || response.code() == 204) Result.success(Unit)
            else Result.failure(Exception("删除失败: ${response.code()} ${response.message()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun forkRepo(owner: String, repo: String): Result<Repository> =
        safeCall { api.forkRepo(owner, repo) }

    // ===== File Upload / Create / Delete =====
    suspend fun createOrUpdateFile(
        owner: String, repo: String, path: String,
        message: String, content: String, branch: String? = null, sha: String? = null
    ): Result<CreateOrUpdateFileResponse> =
        safeCall { api.createOrUpdateFile(owner, repo, path, UploadFileRequest(message, content, branch, sha)) }

    suspend fun deleteFile(
        owner: String, repo: String, path: String, message: String, sha: String
    ): Result<Unit> {
        return try {
            val response = api.deleteFile(owner, repo, path, message, sha)
            if (response.isSuccessful || response.code() == 200) Result.success(Unit)
            else Result.failure(Exception("删除失败: ${response.code()} ${response.message()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ===== Create Issue =====
    suspend fun createIssue(owner: String, repo: String, title: String, body: String): Result<Issue> =
        safeCall { api.createIssue(owner, repo, mapOf("title" to title, "body" to body)) }

    // ===== GitHub Actions (Cloud Build) =====

    suspend fun listWorkflows(owner: String, repo: String): Result<com.githubclient.app.data.remote.model.WorkflowsResponse> =
        safeCall { api.listWorkflows(owner, repo) }

    suspend fun triggerWorkflow(owner: String, repo: String, workflowId: String, ref: String = "main"): Result<Unit> {
        return try {
            val response = api.triggerWorkflow(owner, repo, workflowId, mapOf("ref" to ref))
            if (response.isSuccessful || response.code() == 204) Result.success(Unit)
            else Result.failure(Exception("触发失败: ${response.code()} ${response.message()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getWorkflowRuns(owner: String, repo: String, page: Int = 1): Result<WorkflowRunsResponse> =
        safeCall { api.getWorkflowRuns(owner, repo, page = page) }

    suspend fun getArtifacts(owner: String, repo: String, runId: Long): Result<WorkflowArtifactsResponse> =
        safeCall { api.getArtifacts(owner, repo, runId) }

    suspend fun deleteWorkflowRun(owner: String, repo: String, runId: Long): Result<Unit> {
        return try {
            val response = api.deleteWorkflowRun(owner, repo, runId)
            if (response.isSuccessful || response.code() == 204) Result.success(Unit)
            else {
                val errorBody = response.errorBody()?.string() ?: ""
                val errorMsg = when (response.code()) {
                    403 -> "权限不足(403)：Token 需要 admin 权限"
                    404 -> "构建记录不存在(404)"
                    else -> "删除失败(${response.code()})：${errorBody.take(200)}"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 取消正在进行的构建 */
    suspend fun cancelWorkflowRun(owner: String, repo: String, runId: Long): Result<Unit> {
        return try {
            val response = api.cancelWorkflowRun(owner, repo, runId)
            if (response.isSuccessful || response.code() == 204) Result.success(Unit)
            else {
                val errorMsg = when (response.code()) {
                    403 -> "权限不足(403)：Token 需要 admin 权限"
                    404 -> "构建记录不存在(404)"
                    409 -> "构建已完成，无法取消(409)"
                    else -> "取消失败(${response.code()})"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ===== Get single file (for checking if workflow file exists) =====
    suspend fun getSingleFile(owner: String, repo: String, path: String, ref: String? = null): Result<Content> =
        safeCall { api.getSingleFile(owner, repo, path, ref) }

    // ===== Git Database API (for initializing empty repos) =====

    /**
     * Initialize an empty repository by creating the first commit via Git Database API.
     * Creates a blob, tree, commit, and branch ref.
     * Returns the commit SHA on success.
     */
    suspend fun initRepoWithFile(
        owner: String, repo: String, branch: String,
        filePath: String, fileContentBase64: String, commitMessage: String
    ): Result<String> {
        return try {
            // Step 1: Create a blob
            val blobResponse = api.createBlob(owner, repo, CreateBlobRequest(fileContentBase64))
            if (!blobResponse.isSuccessful || blobResponse.body() == null) {
                return Result.failure(Exception("创建 Blob 失败: ${blobResponse.code()}"))
            }
            val blobSha = blobResponse.body()!!.sha

            // Step 2: Create a tree with the file
            val treeResponse = api.createTree(owner, repo, CreateTreeRequest(
                baseTree = null,
                tree = listOf(
                    com.githubclient.app.data.remote.model.TreeEntry(
                        path = filePath,
                        mode = "100644",
                        type = "blob",
                        sha = blobSha
                    )
                )
            ))
            if (!treeResponse.isSuccessful || treeResponse.body() == null) {
                return Result.failure(Exception("创建 Tree 失败: ${treeResponse.code()}"))
            }
            val treeSha = treeResponse.body()!!.sha

            // Step 3: Create a commit pointing to the tree
            val commitResponse = api.createCommit(owner, repo, CreateCommitRequest(
                message = commitMessage,
                tree = treeSha,
                parents = emptyList()
            ))
            if (!commitResponse.isSuccessful || commitResponse.body() == null) {
                return Result.failure(Exception("创建 Commit 失败: ${commitResponse.code()}"))
            }
            val commitSha = commitResponse.body()!!.sha

            // Step 4: Create the branch ref pointing to the commit
            val refResponse = api.createRef(owner, repo, CreateRefRequest(
                ref = "refs/heads/$branch",
                sha = commitSha
            ))
            if (!refResponse.isSuccessful) {
                // If ref already exists, try to update it
                val updateResponse = api.updateBranchRef(owner, repo, branch, mapOf("sha" to commitSha))
                if (!updateResponse.isSuccessful) {
                    return Result.failure(Exception("创建分支失败: ${refResponse.code()}"))
                }
            }

            Result.success(commitSha)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update an existing branch ref to point to a new commit (for repos with existing commits).
     */
    suspend fun getBranchRef(owner: String, repo: String, branch: String): Result<RefResponse> =
        safeCall { api.getBranchRef(owner, repo, branch) }

    /**
     * Get the repository's default branch name.
     */
    suspend fun getRepoInfo(owner: String, repo: String): Result<Repository> =
        safeCall { api.getRepo(owner, repo) }

    private suspend fun <T> safeCall(call: suspend () -> retrofit2.Response<T>): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("请求失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
