package com.githubclient.app.ui.login

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.githubclient.app.util.Constants

private enum class LoginStep {
    LOGIN_PAGE,      // 用户在 GitHub 登录页（WebView 可见）
    PROCESSING,      // 登录成功，正在后台创建令牌（全屏遮罩）
    EXCHANGING,      // 已提取令牌，正在验证（全屏遮罩）
    NEED_MANUAL,     // 自动创建失败，需要用户手动操作（WebView 可见）
    DONE,            // 成功
    ERROR            // 失败
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthLoginScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var step by remember { mutableStateOf(LoginStep.LOGIN_PAGE) }
    var statusMessage by remember { mutableStateOf("请在 GitHub 页面输入账号密码登录") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pollCount by remember { mutableIntStateOf(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub 账号登录") },
                navigationIcon = {
                    if (step == LoginStep.LOGIN_PAGE || step == LoginStep.NEED_MANUAL || step == LoginStep.ERROR) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ===== WebView（始终在 DOM 中） =====
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
                        // 让 GitHub 移动端正常显示
                        settings.userAgentString = settings.userAgentString.replace("wv", "")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentUrl = url ?: ""

                                // ===== 检测登录成功 =====
                                if (step == LoginStep.LOGIN_PAGE && isLoginSuccess(currentUrl)) {
                                    step = LoginStep.PROCESSING
                                    statusMessage = "登录成功，正在自动创建授权令牌..."
                                    // 跳转到令牌创建页
                                    view?.loadUrl(Constants.GITHUB_NEW_TOKEN_URL)
                                    return
                                }

                                // ===== 令牌页加载完成，自动填表 + 提交 =====
                                if (step == LoginStep.PROCESSING && isTokenPage(currentUrl)) {
                                    // 延迟 1 秒等页面渲染完
                                    handler.postDelayed({
                                        // 第一步：填表
                                        view?.evaluateJavascript(JsScripts.fillForm) { _ ->
                                            // 延迟 0.5 秒确认填表成功
                                            handler.postDelayed({
                                                view?.evaluateJavascript(JsScripts.checkFilled) { checkResult ->
                                                    if (checkResult?.contains("true") == true) {
                                                        // 填表成功，提交
                                                        view.evaluateJavascript(JsScripts.submitForm) { _ ->
                                                            startTokenPolling(view, handler) { token ->
                                                                handleTokenExtracted(token, viewModel, stepSetter = { newStep ->
                                                                    step = newStep
                                                                }, statusSetter = { msg ->
                                                                    statusMessage = msg
                                                                }, onSuccess = onSuccess)
                                                            }
                                                        }
                                                    } else {
                                                        // 填表可能失败，等待页面稳定后重试
                                                        handler.postDelayed({
                                                            view.evaluateJavascript(JsScripts.fillForm) { _ ->
                                                                handler.postDelayed({
                                                                    view.evaluateJavascript(JsScripts.submitForm) { _ ->
                                                                        startTokenPolling(view, handler) { token ->
                                                                            handleTokenExtracted(token, viewModel, stepSetter = { newStep ->
                                                                                step = newStep
                                                                            }, statusSetter = { msg ->
                                                                                statusMessage = msg
                                                                            }, onSuccess = onSuccess)
                                                                        }
                                                                    }
                                                                }, 500)
                                                            }
                                                        }, 1000)
                                                    }
                                                }
                                            }, 500)
                                        }
                                    }, 1000)
                                }

                                // ===== 轮询提取令牌（在令牌列表页） =====
                                if (step == LoginStep.PROCESSING || step == LoginStep.NEED_MANUAL) {
                                    view?.evaluateJavascript(JsScripts.extractToken) { result ->
                                        val token = cleanJsResult(result)
                                        if (token.isNotEmpty() && (token.startsWith("ghp_") || token.startsWith("github_pat_"))) {
                                            step = LoginStep.EXCHANGING
                                            statusMessage = "已获取令牌，正在登录..."
                                            viewModel.exchangeTokenFromWeb(token) { success ->
                                                if (success) {
                                                    step = LoginStep.DONE
                                                    onSuccess()
                                                } else {
                                                    step = LoginStep.ERROR
                                                    statusMessage = "令牌验证失败，请重试"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        webChromeClient = WebChromeClient()
                        webViewRef = this
                        loadUrl(Constants.GITHUB_LOGIN_URL)
                    }
                }
            )

            // ===== 处理中 / 交换令牌的遮罩层 =====
            if (step == LoginStep.PROCESSING || step == LoginStep.EXCHANGING) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ===== 需要手动操作 =====
            if (step == LoginStep.NEED_MANUAL) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "登录成功！",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "请在下方页面填写 Note 并点击 \"Generate token\" 创建令牌，APP 会自动检测并登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                step = LoginStep.PROCESSING
                                statusMessage = "请稍候..."
                                webViewRef?.loadUrl(Constants.GITHUB_NEW_TOKEN_URL)
                            }
                        ) {
                            Text("重新自动创建")
                        }
                    }
                }
            }

            // ===== 错误状态 =====
            if (step == LoginStep.ERROR) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                step = LoginStep.LOGIN_PAGE
                                statusMessage = "请在 GitHub 页面输入账号密码登录"
                                webViewRef?.loadUrl(Constants.GITHUB_LOGIN_URL)
                            }
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            step = LoginStep.NEED_MANUAL
                            webViewRef?.loadUrl(Constants.GITHUB_NEW_TOKEN_URL)
                        }) {
                            Text("手动创建令牌")
                        }
                    }
                }
            }
        }
    }
}

// ===== 轮询提取令牌 =====
private fun startTokenPolling(
    webView: WebView?,
    handler: Handler,
    onToken: (String) -> Unit
) {
    val maxAttempts = 20
    var attempts = 0

    val pollRunnable = object : Runnable {
        override fun run() {
            attempts++
            webView?.evaluateJavascript(JsScripts.extractToken) { result ->
                val token = cleanJsResult(result)
                if (token.isNotEmpty() && (token.startsWith("ghp_") || token.startsWith("github_pat_"))) {
                    onToken(token)
                } else if (attempts < maxAttempts) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }
    handler.postDelayed(pollRunnable, 2000)
}

// ===== 处理提取到的令牌 =====
private fun handleTokenExtracted(
    token: String,
    viewModel: LoginViewModel,
    stepSetter: (LoginStep) -> Unit,
    statusSetter: (String) -> Unit,
    onSuccess: () -> Unit
) {
    stepSetter(LoginStep.EXCHANGING)
    statusSetter("已获取令牌，正在登录...")
    viewModel.exchangeTokenFromWeb(token) { success ->
        if (success) {
            stepSetter(LoginStep.DONE)
            onSuccess()
        } else {
            stepSetter(LoginStep.ERROR)
            statusSetter("令牌验证失败，请重试")
        }
    }
}

// ===== 辅助函数 =====

private fun isLoginSuccess(url: String): Boolean {
    return (url.startsWith("https://github.com/") && !url.contains("/login"))
            && !url.contains("/signup")
            && !url.contains("/password")
            && !url.contains("/sessions")
            && !url.contains("/two-factor")
            && !url.contains("/2fa")
}

private fun isTokenPage(url: String): Boolean {
    return url.contains("settings/tokens")
}

private fun cleanJsResult(result: String?): String {
    if (result == null || result == "null") return ""
    var s = result.trim()
    if (s.startsWith("\"") && s.endsWith("\"")) {
        s = s.substring(1, s.length - 1)
    }
    return s
}

// ===== JavaScript 脚本 =====
private object JsScripts {

    // 填写令牌创建表单（使用多种选择器确保兼容）
    val fillForm = """
        (function() {
            // 填写 Note/Description 字段
            var selectors = [
                '#access_token_description',
                'input[name="access_token[description]"]',
                'input[name*="description"]',
                'input[name*="note"]',
                'textarea[name="access_token[description]"]',
                'textarea[name*="description"]',
                'input[placeholder*="escription"]',
                'input[placeholder*="note"]',
                'input[placeholder*="Note"]',
                'input[autocomplete*="description"]',
                'input[type="text"]'
            ];
            var filled = false;
            for (var i = 0; i < selectors.length; i++) {
                var el = document.querySelector(selectors[i]);
                if (el) {
                    el.value = 'githup App Token';
                    el.dispatchEvent(new Event('input', {bubbles: true}));
                    el.dispatchEvent(new Event('change', {bubbles: true}));
                    el.dispatchEvent(new Event('blur', {bubbles: true}));
                    filled = true;
                    break;
                }
            }
            // 勾选所需权限
            var needed = ['repo', 'user', 'notifications', 'read:org'];
            var checkboxes = document.querySelectorAll('input[type="checkbox"]');
            checkboxes.forEach(function(cb) {
                if (needed.indexOf(cb.value) >= 0 || needed.indexOf(cb.id) >= 0) {
                    if (!cb.checked) {
                        cb.click();
                    }
                }
            });
            return filled ? 'true' : 'false';
        })();
    """.trimIndent()

    // 检查 Note 字段是否已填充
    val checkFilled = """
        (function() {
            var selectors = [
                '#access_token_description',
                'input[name="access_token[description]"]',
                'input[name*="description"]',
                'input[name*="note"]',
                'textarea[name="access_token[description]"]',
                'textarea[name*="description"]',
                'input[placeholder*="escription"]',
                'input[placeholder*="note"]',
                'input[placeholder*="Note"]',
                'input[type="text"]'
            ];
            for (var i = 0; i < selectors.length; i++) {
                var el = document.querySelector(selectors[i]);
                if (el && el.value && el.value.length > 0) {
                    return 'true';
                }
            }
            return 'false';
        })();
    """.trimIndent()

    // 提交表单
    val submitForm = """
        (function() {
            var buttons = document.querySelectorAll('button, input[type="submit"]');
            for (var i = 0; i < buttons.length; i++) {
                var text = (buttons[i].textContent || '') + ' ' + (buttons[i].value || '');
                if (text.indexOf('Generate') >= 0 || text.indexOf('生成') >= 0 || text.indexOf('Create') >= 0 || text.indexOf('创建') >= 0) {
                    buttons[i].click();
                    return 'submitted';
                }
            }
            var form = document.querySelector('form');
            if (form) {
                form.submit();
                return 'submitted';
            }
            return 'no_button';
        })();
    """.trimIndent()

    // 提取令牌（从多种位置查找）
    val extractToken = """
        (function() {
            var prefixes = ['ghp_', 'github_pat_'];
            function isToken(s) {
                if (!s) return false;
                for (var i = 0; i < prefixes.length; i++) {
                    if (s.indexOf(prefixes[i]) === 0) return true;
                }
                return false;
            }
            var elements = document.querySelectorAll(
                'code, [data-clipboard-copy-value], .flash-success code, .text-amber, [id*="token"], [class*="token"]'
            );
            for (var i = 0; i < elements.length; i++) {
                var text = (elements[i].textContent || '').trim();
                if (isToken(text)) return text;
                var val = elements[i].getAttribute('data-clipboard-copy-value');
                if (val && isToken(val)) return val;
                var val2 = elements[i].getAttribute('value');
                if (val2 && isToken(val2)) return val2;
            }
            var inputs = document.querySelectorAll('input[type="text"]');
            for (var i = 0; i < inputs.length; i++) {
                var val = inputs[i].value || '';
                if (isToken(val)) return val;
            }
            return '';
        })();
    """.trimIndent()
}
