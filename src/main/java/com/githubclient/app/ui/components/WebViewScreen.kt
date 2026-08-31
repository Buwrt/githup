package com.githubclient.app.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.githubclient.app.util.AppSettings
import com.githubclient.app.util.tr

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    title: String = "GitHub",
    onBack: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val isDark = AppSettings.isDarkTheme(false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.allowContentAccess = true
                        settings.allowFileAccess = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // 确保 WebView 可以滚动
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = true
                        requestFocus()

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // 注入深色模式 CSS
                                if (isDark) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var style = document.createElement('style');
                                            style.id = 'githup-dark-mode';
                                            style.textContent = `
                                                html, body { background-color: #0d1117 !important; color: #c9d1d9 !important; }
                                                * { background-color: transparent !important; }
                                                .Header, .header, nav, [class*="header"] { background-color: #161b22 !important; }
                                                a { color: #58a6ff !important; }
                                                input, textarea, select { background-color: #21262d !important; color: #c9d1d9 !important; border-color: #30363d !important; }
                                                button, .btn, [class*="button"] { background-color: #21262d !important; color: #c9d1d9 !important; }
                                                .Box, .color-bg-subtle, [class*="bg-subtle"] { background-color: #161b22 !important; }
                                                .border, [class*="border"] { border-color: #30363d !important; }
                                                .text-gray, .color-fg-muted, [class*="text-gray"] { color: #8b949e !important; }
                                            `;
                                            document.head.appendChild(style);
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(url)
                    }
                }
            )

            if (progress < 1f) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
