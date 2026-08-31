package com.githubclient.app

import android.app.Application
import com.githubclient.app.util.AppSettings
import com.githubclient.app.util.PluginManager
import com.githubclient.app.util.TranslationPlugin
import com.githubclient.app.util.UpdateChecker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GitHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        TranslationPlugin.init(this)
        PluginManager.init(this)
        // 应用启动时自动检查更新
        UpdateChecker.checkForUpdateAsync()
    }
}
