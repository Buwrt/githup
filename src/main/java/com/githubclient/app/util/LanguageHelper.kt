package com.githubclient.app.util

/**
 * 运行时语言翻译助手。
 *
 * 当用户在设置中选择 English 时，将 UI 中文文本翻译为英文。
 * 当选择简体中文或跟随系统（中文环境）时，返回原始中文文本。
 *
 * 用法：Text(tr("设置")) 替代 Text("设置")
 */
object LanguageHelper {

    // 中文 → 英文 词典（按长度降序匹配，避免短词覆盖长词）
    private val zhToEn: Map<String, String> = mapOf(
        // ===== 导航与标签 =====
        "首页" to "Home",
        "探索" to "Explore",
        "通知" to "Notifications",
        "我的" to "Profile",
        "插件管理" to "Plugins",
        "设置" to "Settings",
        "返回" to "Back",
        "搜索" to "Search",
        "登录" to "Sign in",
        "退出登录" to "Log out",
        "注册" to "Sign up",

        // ===== 设置页面 =====
        "账户设置" to "Account",
        "外观设置" to "Appearance",
        "通知设置" to "Notifications",
        "下载设置" to "Downloads",
        "安全设置" to "Security",
        "Token 设置" to "Token Settings",
        "关于" to "About",
        "个人资料" to "Profile",
        "管理 GitHub 账户信息" to "Manage your GitHub account",
        "已登录，点击退出" to "Logged in, tap to log out",
        "当前未登录" to "Not logged in",
        "深色模式" to "Dark mode",
        "切换深色 / 浅色 / 跟随系统" to "Switch dark / light / system",
        "跟随系统" to "Follow system",
        "浅色模式" to "Light",
        "深色模式" to "Dark",
        "语言" to "Language",
        "应用界面语言" to "App interface language",
        "简体中文" to "Simplified Chinese",
        "推送通知" to "Push notifications",
        "接收 GitHub 通知推送" to "Receive GitHub push notifications",
        "邮件通知" to "Email notifications",
        "通过邮件接收通知" to "Receive notifications via email",
        "仓库动态" to "Repository activity",
        "关注仓库的动态提醒" to "Activity alerts for watched repos",
        "下载路径" to "Download path",
        "APK 文件下载到手机的保存位置" to "Where APK files are saved on your phone",
        "文件保存位置" to "File save location",
        "选择操作" to "Choose action",
        "创建新仓库" to "Create new repository",
        "创建一个全新的 GitHub 仓库" to "Create a brand new GitHub repository",
        "从 URL 复制仓库到你的账号下" to "Fork a repo from URL to your account",
        "安全与隐私" to "Security and privacy",
        "两步验证、密码与安全检查" to "2FA, passwords and security checks",
        "访问令牌" to "Access token",
        "查看与更新 Personal Access Token" to "View and update your Personal Access Token",
        "翻译插件等扩展功能" to "Translation plugins and extensions",
        "版本" to "Version",
        "基于 GitHub API 构建的第三方客户端，提供仓库、议题、通知等管理能力。" to "A third-party client built on the GitHub API, providing repository, issue and notification management.",
        "访问 GitHub 官网" to "Visit GitHub.com",
        "检查更新" to "Check for updates",
        "点击下方按钮下载最新版本" to "Tap below to download the latest version",
        "下载" to "Download",
        "选择下载路径" to "Select download path",
        "选择文件下载的保存位置：" to "Choose where downloaded files are saved:",
        "自定义路径：" to "Custom path:",
        "输入自定义路径" to "Enter custom path",
        "路径相对于内部存储根目录（如 Download/githup）" to "Path is relative to internal storage root (e.g. Download/githup)",
        "确定" to "OK",
        "取消" to "Cancel",

        // ===== 主页 =====
        "动态" to "Activity",
        "克隆仓库" to "Clone repository",
        "输入 GitHub 仓库地址，将 1:1 复制到你的账号下" to "Enter a GitHub repo URL to fork it to your account",
        "正在克隆..." to "Cloning...",
        "克隆" to "Clone",
        "暂无动态" to "No activity",
        "下拉刷新或创建新仓库" to "Pull to refresh or create a new repo",

        // ===== 仓库详情 =====
        "代码" to "Code",
        "议题" to "Issues",
        "拉取请求" to "Pull requests",
        "发布" to "Releases",
        "星标" to "Star",
        "取消星标" to "Unstar",
        "复刻" to "Fork",
        "关注" to "Watch",
        "分支" to "Branches",
        "提交" to "Commits",
        "标签" to "Tags",
        "贡献者" to "Contributors",
        "描述" to "Description",
        "主题" to "Topics",
        "许可证" to "License",
        "私有" to "Private",
        "公开" to "Public",
        "已归档" to "Archived",
        "下载 ZIP" to "Download ZIP",
        "克隆或下载" to "Clone or download",

        // ===== 探索页面 =====
        "排序方式" to "Sort by",
        "最多星标" to "Most stars",
        "最少星标" to "Fewest stars",
        "最近更新" to "Recently updated",
        "最近未更新" to "Least recently updated",
        "最多复刻" to "Most forks",
        "仓库" to "Repositories",
        "用户" to "Users",
        "搜索中" to "Searching",
        "没有找到相关结果" to "No results found",
        "搜索仓库、用户、Issue..." to "Search repos, users, issues...",
        "热门仓库" to "Trending Repositories",
        "个" to "items",

        // ===== 通知 =====
        "未读" to "Unread",
        "全部标记为已读" to "Mark all as read",
        "标记为已读" to "Mark as read",
        "提及了你" to "mentioned you",
        "指派给你" to "assigned to you",
        "请求你审查" to "review requested",
        "暂无通知" to "No notifications",

        // ===== 个人资料 =====
        "关注者" to "Followers",
        "正在关注" to "Following",
        "简介" to "Bio",
        "公司" to "Company",
        "位置" to "Location",
        "博客" to "Blog",
        "邮箱" to "Email",
        "加入于" to "Joined",
        "贡献" to "Contributions",
        "组织" to "Organizations",
        "星标仓库" to "Starred repositories",
        "我的仓库" to "My repositories",

        // ===== 通用操作 =====
        "删除" to "Delete",
        "编辑" to "Edit",
        "保存" to "Save",
        "更新" to "Update",
        "添加" to "Add",
        "移除" to "Remove",
        "加载中" to "Loading",
        "加载失败" to "Failed to load",
        "重试" to "Retry",
        "下一步" to "Next",
        "完成" to "Done",
        "全部" to "All",
        "新建" to "New",
        "创建" to "Create",
        "查看" to "View",
        "复制" to "Copy",
        "分享" to "Share",
        "更多" to "More",
        "筛选" to "Filter",
        "确认删除" to "Confirm delete",
        "此操作不可撤销。" to "This action cannot be undone.",
        "确定要删除选中的" to "Are you sure you want to delete the selected",
        "条构建记录吗？" to "build records?",

        // ===== 登录 =====
        "使用 Personal Access Token 登录" to "Sign in with Personal Access Token",
        "请输入你的 GitHub Token" to "Please enter your GitHub Token",
        "获取 Token" to "Get Token",
        "登录中..." to "Signing in...",
        "登录成功" to "Sign in successful",
        "Token 不能为空" to "Token cannot be empty",
        "没有账号？注册 GitHub" to "No account? Sign up for GitHub",
        "输入 Personal Access Token 即可登录" to "Enter your Personal Access Token to sign in",

        // ===== WebView 页面 =====
        "安全设置" to "Security Settings",
        "创建 Token" to "Create Token",

        // ===== 插件管理 =====
        "内置插件" to "Built-in Plugins",
        "英译中翻译" to "English to Chinese Translation",
        "将 GitHub 界面英文自动翻译为中文" to "Automatically translate GitHub UI from English to Chinese",
        "用户插件" to "User Plugins",
        "还没有安装用户插件" to "No user plugins installed",
        "点击右下角按钮上传插件 JSON 文件" to "Tap the button below to upload a plugin JSON file",
        "点击右上角图标查看插件格式说明" to "Tap the icon in the top right for plugin format guide",
        "安装插件" to "Install Plugin",
        "插件安装成功，词典已合并生效" to "Plugin installed, dictionary merged successfully",
        "插件安装失败，请检查 JSON 格式是否正确" to "Plugin installation failed, please check the JSON format",
        "插件制作指南" to "Plugin Creation Guide",
        "卸载" to "Uninstall",
        "已启用" to "Enabled",
        "已禁用" to "Disabled",
        "词典" to "dictionary",
        "条词典" to "entries",

        // ===== 云构建 =====
        "云构建" to "Cloud Build",
        "构建列表" to "Build List",
        "全选" to "Select All",
        "工作流" to "Workflows",
        "触发构建" to "Trigger Build",
        "构建状态" to "Build Status",
        "构建详情" to "Build Details",
        "下载产物" to "Download Artifact",
        "查看详情" to "View Details",
        "进行中" to "In Progress",
        "成功" to "Success",
        "失败" to "Failed",
        "已取消" to "Cancelled",
        "排队中" to "Queued",

        // ===== Release =====
        "创建发布" to "Create Release",
        "发布标题" to "Release Title",
        "发布说明" to "Release Notes",
        "上传 APK 文件" to "Upload APK File",
        "标签名" to "Tag Name",
        "发布" to "Publish",

        // ===== 仓库创建 =====
        "创建新仓库" to "Create New Repository",
        "仓库名称" to "Repository Name",
        "仓库描述" to "Repository Description",
        "公开仓库" to "Public Repository",
        "私有仓库" to "Private Repository",
        "初始化 README" to "Initialize README",
        "添加 .gitignore" to "Add .gitignore",
        "选择许可证" to "Choose License",

        // ===== 星标页面 =====
        "我的星标" to "My Stars",
        "暂无星标仓库" to "No starred repositories",
        "下拉刷新" to "Pull to refresh",

        // ===== 空状态 =====
        "暂无数据" to "No data",
        "暂无内容" to "No content",
        "出错了" to "Something went wrong",
        "请稍后重试" to "Please try again later",
    )

    // 按长度降序排序
    private val sortedEntries = zhToEn.entries.sortedByDescending { it.key.length }

    /**
     * 翻译中文文本为英文（当语言设置为 English 时）。
     * 支持句子级别的翻译：先尝试精确匹配，再尝试词组替换。
     */
    fun tr(text: String): String {
        if (text.isBlank()) return text
        val lang = AppSettings.language.value
        // 0=跟随系统, 1=简体中文, 2=English
        if (lang != 2) return text  // 非英文模式，返回原始中文

        // 精确匹配
        zhToEn[text]?.let { return it }

        // 句子级替换
        var result = text
        for ((chn, eng) in sortedEntries) {
            if (result.contains(chn)) {
                result = result.replace(chn, eng)
            }
        }
        return result
    }
}

/**
 * 全局翻译函数，在 Composable 中使用 tr("中文") 即可根据语言设置自动翻译。
 */
fun tr(text: String): String = LanguageHelper.tr(text)
