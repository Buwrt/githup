package com.githubclient.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.githubclient.app.ui.components.WebViewScreen
import com.githubclient.app.ui.explore.ExploreScreen
import com.githubclient.app.ui.home.HomeScreen
import com.githubclient.app.ui.issue.IssueDetailScreen
import com.githubclient.app.ui.login.LoginScreen
import com.githubclient.app.ui.main.MainScreen
import com.githubclient.app.ui.plugin.PluginManagerScreen
import com.githubclient.app.ui.profile.ProfileScreen
import com.githubclient.app.ui.repo.CloudBuildScreen
import com.githubclient.app.ui.repo.CreateReleaseScreen
import com.githubclient.app.ui.repo.CreateRepoScreen
import com.githubclient.app.ui.repo.RepoDetailScreen
import com.githubclient.app.ui.repo.StarredReposScreen
import com.githubclient.app.ui.settings.SettingsScreen
import com.githubclient.app.ui.settings.TokenSettingsScreen
import com.githubclient.app.ui.tasks.TaskManagerScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val REPO = "repo/{owner}/{repo}"
    const val ISSUE = "issue/{owner}/{repo}/{number}"
    const val USER = "user/{username}"
    const val WEB = "web?url={url}&title={title}"
    const val CREATE_REPO = "create_repo"
    const val PLUGINS = "plugins"
    const val SETTINGS = "settings"
    const val TOKEN_SETTINGS = "token_settings"
    const val CLOUD_BUILD = "cloud_build/{owner}/{repo}"
    const val STARRED_REPOS = "starred_repos"
    const val CREATE_RELEASE = "create_release/{owner}/{repo}"
    const val TASK_MANAGER = "task_manager"

    fun repo(owner: String, repo: String) = "repo/$owner/$repo"
    fun cloudBuild(owner: String, repo: String) = "cloud_build/$owner/$repo"
    fun createRelease(owner: String, repo: String) = "create_release/$owner/$repo"
    fun issue(owner: String, repo: String, number: Int) = "issue/$owner/$repo/$number"
    fun user(username: String) = "user/$username"
    fun web(url: String, title: String): String {
        val e = URLEncoder.encode(url, "UTF-8")
        val t = URLEncoder.encode(title, "UTF-8")
        return "web?url=$e&title=$t"
    }
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val startViewModel: NavStartViewModel = hiltViewModel()
    val isLoggedIn by startViewModel.isLoggedIn.collectAsStateWithLifecycle()

    val startDestination = if (isLoggedIn) Routes.MAIN else Routes.LOGIN

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onOpenWeb = { url, title ->
                    navController.navigate(Routes.web(url, title))
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToRepo = { o, r -> navController.navigate(Routes.repo(o, r)) },
                onNavigateToIssue = { o, r, n -> navController.navigate(Routes.issue(o, r, n)) },
                onNavigateToUser = { u -> navController.navigate(Routes.user(u)) },
                onNavigateToCreateRepo = { navController.navigate(Routes.CREATE_REPO) },
                onNavigateToStarred = { navController.navigate(Routes.STARRED_REPOS) },
                onNavigateToPlugins = { navController.navigate(Routes.PLUGINS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToTaskManager = { navController.navigate(Routes.TASK_MANAGER) },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PLUGINS) {
            PluginManagerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TASK_MANAGER) {
            val context = androidx.compose.ui.platform.LocalContext.current
            TaskManagerScreen(
                context = context,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToTokenSettings = { navController.navigate(Routes.TOKEN_SETTINGS) },
                onNavigateToPlugins = { navController.navigate(Routes.PLUGINS) },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.TOKEN_SETTINGS) {
            TokenSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) },
                onTokenSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.CREATE_REPO) {
            CreateRepoScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() }
            )
        }

        composable(Routes.STARRED_REPOS) {
            StarredReposScreen(
                onBackClick = { navController.popBackStack() },
                onRepoClick = { o, r -> navController.navigate(Routes.repo(o, r)) }
            )
        }

        composable(
            Routes.CREATE_RELEASE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType }
            )
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            CreateReleaseScreen(
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.CLOUD_BUILD,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType }
            )
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            CloudBuildScreen(
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) }
            )
        }

        composable(
            Routes.REPO,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType }
            )
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            RepoDetailScreen(
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() },
                onIssueClick = { o, r, n -> navController.navigate(Routes.issue(o, r, n)) },
                onUserClick = { u -> navController.navigate(Routes.user(u)) },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) },
                onNavigateToCloudBuild = { o, r -> navController.navigate(Routes.cloudBuild(o, r)) },
                onNavigateToCreateRelease = { o, r -> navController.navigate(Routes.createRelease(o, r)) },
            )
        }

        composable(
            Routes.ISSUE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("number") { type = NavType.IntType }
            )
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            val number = entry.arguments?.getInt("number") ?: 0
            IssueDetailScreen(
                owner = owner,
                repo = repo,
                number = number,
                onBack = { navController.popBackStack() },
                onUserClick = { u -> navController.navigate(Routes.user(u)) },
            )
        }

        composable(
            Routes.USER,
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { entry ->
            val username = entry.arguments?.getString("username").orEmpty()
            ProfileScreen(
                username = username,
                onBack = { navController.popBackStack() },
                onRepoClick = { o, r -> navController.navigate(Routes.repo(o, r)) },
                onUserClick = { u -> navController.navigate(Routes.user(u)) },
                onLogout = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } },
                onOpenWeb = { url, title -> navController.navigate(Routes.web(url, title)) },
            )
        }

        composable(
            Routes.WEB,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType }
            )
        ) { entry ->
            val url = URLDecoder.decode(entry.arguments?.getString("url").orEmpty(), "UTF-8")
            val title = URLDecoder.decode(entry.arguments?.getString("title").orEmpty(), "UTF-8")
            WebViewScreen(
                url = url,
                title = title,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
