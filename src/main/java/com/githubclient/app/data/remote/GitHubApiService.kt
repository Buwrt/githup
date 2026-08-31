package com.githubclient.app.data.remote

import com.githubclient.app.data.remote.model.Branch
import com.githubclient.app.data.remote.model.BlobResponse
import com.githubclient.app.data.remote.model.Comment
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
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface GitHubApiService {

    // ===== Auth / User =====
    @GET("user")
    suspend fun getCurrentUser(): Response<User>

    @GET("users/{username}")
    suspend fun getUser(@Path("username") username: String): Response<User>

    // ===== Repositories =====
    @GET("user/repos")
    suspend fun getMyRepos(
        @Query("visibility") visibility: String = "all",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Repository>>

    @GET("users/{username}/repos")
    suspend fun getUserRepos(
        @Path("username") username: String,
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Repository>>

    @GET("user/starred")
    suspend fun getStarredRepos(
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Repository>>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Repository>

    @GET("repos/{owner}/{repo}/readme")
    suspend fun getReadme(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("ref") ref: String? = null
    ): Response<Content>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String = "",
        @Query("ref") ref: String? = null
    ): Response<List<Content>>

    @GET
    suspend fun getFileContent(@Url url: String): Response<Content>

    @GET("repos/{owner}/{repo}/branches")
    suspend fun getBranches(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Branch>>

    @GET("repos/{owner}/{repo}/languages")
    suspend fun getLanguages(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Map<String, Int>>

    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Release>>

    @POST("repos/{owner}/{repo}/releases")
    suspend fun createRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateReleaseRequest
    ): Response<Release>

    @DELETE("repos/{owner}/{repo}/releases/{releaseId}")
    suspend fun deleteRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("releaseId") releaseId: Long
    ): Response<Unit>
    @POST
    @Multipart
    suspend fun uploadAsset(
        @Url url: String,
        @Query("name") name: String,
        @Part file: MultipartBody.Part
    ): Response<ReleaseAsset>

    @PUT("user/starred/{owner}/{repo}")
    suspend fun starRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    @DELETE("user/starred/{owner}/{repo}")
    suspend fun unstarRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    @GET("user/starred/{owner}/{repo}")
    suspend fun checkStarred(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    // ===== Issues =====
    @GET("issues")
    suspend fun getMyIssues(
        @Query("state") state: String = "open",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Issue>>

    @GET("repos/{owner}/{repo}/issues")
    suspend fun getRepoIssues(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String = "open",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Issue>>

    @GET("repos/{owner}/{repo}/issues/{number}")
    suspend fun getIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int
    ): Response<Issue>

    @GET("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun getIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Comment>>

    @POST("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun createIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @retrofit2.http.Body body: Map<String, String>
    ): Response<Comment>

    @PATCH("repos/{owner}/{repo}/issues/{number}")
    suspend fun updateIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @retrofit2.http.Body body: Map<String, String>
    ): Response<Issue>

    // ===== Pull Requests =====
    @GET("repos/{owner}/{repo}/pulls")
    suspend fun getRepoPulls(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String = "open",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<PullRequest>>

    @GET("repos/{owner}/{repo}/pulls/{number}")
    suspend fun getPullRequest(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int
    ): Response<PullRequest>

    @GET("repos/{owner}/{repo}/pulls/{number}/comments")
    suspend fun getPullRequestComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Comment>>

    // ===== Events / Feed =====
    @GET("users/{username}/received_events")
    suspend fun getUserReceivedEvents(
        @Path("username") username: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Event>>

    @GET("users/{username}/events")
    suspend fun getUserEvents(
        @Path("username") username: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Event>>

    @GET("repos/{owner}/{repo}/events")
    suspend fun getRepoEvents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Event>>

    // ===== Notifications =====
    @GET("notifications")
    suspend fun getNotifications(
        @Query("all") all: Boolean = false,
        @Query("participating") participating: Boolean = false,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<Notification>>

    @PATCH("notifications/threads/{id}")
    suspend fun markNotificationRead(
        @Path("id") id: String
    ): Response<Unit>

    @PUT("notifications")
    suspend fun markAllNotificationsRead(): Response<Unit>

    @DELETE("notifications/threads/{id}")
    suspend fun deleteNotification(
        @Path("id") threadId: String
    ): Response<Unit>

    // ===== Search =====
    @GET("search/repositories")
    suspend fun searchRepos(
        @Query("q") query: String,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<SearchResponse<Repository>>

    @GET("search/users")
    suspend fun searchUsers(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<SearchResponse<User>>

    @GET("search/issues")
    suspend fun searchIssues(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<SearchResponse<Issue>>

    // ===== Followers / Following =====
    @GET("user/followers")
    suspend fun getMyFollowers(
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<User>>

    @GET("user/following")
    suspend fun getMyFollowing(
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<User>>

    @GET("users/{username}/followers")
    suspend fun getUserFollowers(
        @Path("username") username: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<User>>

    @GET("users/{username}/following")
    suspend fun getUserFollowing(
        @Path("username") username: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): Response<List<User>>

    @PUT("user/following/{username}")
    suspend fun followUser(@Path("username") username: String): Response<Unit>

    @DELETE("user/following/{username}")
    suspend fun unfollowUser(@Path("username") username: String): Response<Unit>

    @GET("user/following/{username}")
    suspend fun checkFollowing(@Path("username") username: String): Response<Unit>

    // ===== Trending (通过GitHub Search API模拟) =====
    @GET("search/repositories")
    suspend fun getTrendingRepos(
        @Query("q") query: String = "created:>2024-01-01",
        @Query("sort") sort: String = "stars",
        @Query("order") order: String = "desc",
        @Query("per_page") perPage: Int = 20
    ): Response<SearchResponse<Repository>>

    // ===== Create / Delete Repository =====
    @POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoRequest): Response<Repository>

    @DELETE("repos/{owner}/{repo}")
    suspend fun deleteRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Unit>

    @PATCH("repos/{owner}/{repo}")
    suspend fun updateRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Repository>

    // ===== File Upload / Create / Delete =====
    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Body body: UploadFileRequest
    ): Response<CreateOrUpdateFileResponse>

    @DELETE("repos/{owner}/{repo}/contents/{path}")
    suspend fun deleteFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("message") message: String,
        @Query("sha") sha: String
    ): Response<Unit>

    // ===== Fork Repository =====
    @POST("repos/{owner}/{repo}/forks")
    suspend fun forkRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Repository>

    // ===== Create Issue =====
    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Issue>

    // ===== GitHub Actions (Cloud Build) =====
    @GET("repos/{owner}/{repo}/actions/workflows")
    suspend fun listWorkflows(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<com.githubclient.app.data.remote.model.WorkflowsResponse>

    @POST("repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches")
    suspend fun triggerWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflow_id") workflowId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun getWorkflowRuns(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 10,
        @Query("page") page: Int = 1
    ): Response<WorkflowRunsResponse>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/artifacts")
    suspend fun getArtifacts(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<WorkflowArtifactsResponse>

    @DELETE("repos/{owner}/{repo}/actions/runs/{run_id}")
    suspend fun deleteWorkflowRun(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<Unit>

    @POST("repos/{owner}/{repo}/actions/runs/{run_id}/cancel")
    suspend fun cancelWorkflowRun(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<Unit>

    // ===== Get single file by path (for checking if workflow file exists) =====
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getSingleFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String? = null
    ): Response<Content>

    // ===== Git Database API (for initializing empty repos) =====
    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateBlobRequest
    ): Response<BlobResponse>

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateTreeRequest
    ): Response<TreeResponse>

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateCommitRequest
    ): Response<CommitResponse>

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateRefRequest
    ): Response<RefResponse>

    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): Response<RefResponse>

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateBranchRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<RefResponse>
}

// ===== 请求体数据类 =====
data class CreateRepoRequest(
    val name: String,
    val description: String? = null,
    val private: Boolean = false,
    @SerializedName("auto_init") val autoInit: Boolean = true,
    @SerializedName("gitignore_template") val gitignoreTemplate: String? = null,
    @SerializedName("license_template") val licenseTemplate: String? = null
)

data class UploadFileRequest(
    val message: String,
    val content: String,
    val branch: String? = null,
    val sha: String? = null
)

data class CreateReleaseRequest(
    val tag_name: String,
    @SerializedName("target_commitish") val target_commitish: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)
