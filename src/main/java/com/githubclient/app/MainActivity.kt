package com.githubclient.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.githubclient.app.ui.navigation.NavGraph
import com.githubclient.app.ui.theme.GitHubClientTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // 注册存储权限请求（Android 9 及以下需要运行时请求）
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 9 及以下：请求 WRITE_EXTERNAL_STORAGE 权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            GitHubClientTheme {
                NavGraph()
            }
        }
    }
}
