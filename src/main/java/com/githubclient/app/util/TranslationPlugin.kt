package com.githubclient.app.util

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 翻译插件：将 GitHub 中的英文自动翻译为中文。
 * 支持全局开关，持久化到 SharedPreferences。
 */
object TranslationPlugin {

    private var prefs: android.content.SharedPreferences? = null

    private val _enabled = MutableStateFlow(true)  // 默认开启
    val enabled: StateFlow<Boolean> = _enabled

    // ===== 词典 =====
    val dictionary: Map<String, String> = mapOf(
        // 导航
        "Home" to "首页", "Explore" to "探索", "Notifications" to "通知",
        "Profile" to "我的", "Dashboard" to "仪表板", "Feed" to "动态",
        // 仓库操作
        "Repository" to "仓库", "Repositories" to "仓库", "repo" to "仓库",
        "Star" to "星标", "Unstar" to "取消星标", "stars" to "星标",
        "Fork" to "复刻", "forks" to "复刻", "Forked" to "已复刻",
        "Watch" to "关注", "watching" to "关注中", "Unwatch" to "取消关注",
        "Clone" to "克隆", "clone" to "克隆", "Download" to "下载",
        // 仓库结构
        "Code" to "代码", "code" to "代码",
        "Issues" to "议题", "Issue" to "议题", "issue" to "议题",
        "Pull Requests" to "拉取请求", "Pull request" to "拉取请求",
        "Pull requests" to "拉取请求", "pull request" to "拉取请求",
        "Pulls" to "拉取请求", "PR" to "拉取请求",
        "Branches" to "分支", "Branch" to "分支", "branch" to "分支",
        "Commits" to "提交", "Commit" to "提交", "commit" to "提交",
        "Releases" to "发布", "Release" to "发布", "release" to "发布",
        "Tags" to "标签", "Tag" to "标签", "tag" to "标签",
        "Contributors" to "贡献者", "Contributor" to "贡献者",
        "Compare" to "比较", "compare" to "比较",
        // 文件
        "Files" to "文件", "File" to "文件", "file" to "文件",
        "README" to "README", "Wiki" to "Wiki",
        "Discussions" to "讨论", "Discussion" to "讨论",
        "Projects" to "项目", "Project" to "项目",
        "Actions" to "工作流", "Insights" to "洞察",
        "Security" to "安全", "Settings" to "设置",
        "About" to "关于", "Description" to "描述",
        "Topics" to "主题", "License" to "许可证",
        "Language" to "语言", "Languages" to "语言",
        "Activity" to "活动", "Pulse" to "动态概览",
        "Graphs" to "图表", "Network" to "网络",
        "Forks" to "复刻", "Stargazers" to "星标者",
        "Watchers" to "关注者",
        // 状态
        "Open" to "开放", "open" to "开放",
        "Closed" to "已关闭", "closed" to "已关闭",
        "Merged" to "已合并", "merged" to "已合并",
        "Draft" to "草稿", "draft" to "草稿",
        "Pending" to "等待中", "pending" to "等待中",
        // 用户
        "Followers" to "关注者", "Following" to "正在关注",
        "Follow" to "关注", "Unfollow" to "取消关注",
        "Bio" to "简介", "Company" to "公司",
        "Location" to "位置", "Blog" to "博客",
        "Email" to "邮箱", "Website" to "网站",
        "Joined" to "加入于", "Repositories" to "仓库",
        "Contribution" to "贡献", "Contributions" to "贡献",
        "Organizations" to "组织", "Organization" to "组织",
        // 操作
        "Comment" to "评论", "Comments" to "评论",
        "comment" to "评论", "comments" to "评论",
        "Review" to "审查", "Reviews" to "审查",
        "Approve" to "批准", "Approved" to "已批准",
        "Request changes" to "请求修改",
        "Changes requested" to "需要修改",
        "Merge" to "合并", "merge" to "合并",
        "Close" to "关闭", "Reopen" to "重新打开",
        "Lock" to "锁定", "Unlock" to "解锁",
        "Pin" to "置顶", "Unpin" to "取消置顶",
        "Assignee" to "指派人", "Assignees" to "指派人",
        "Label" to "标签", "Labels" to "标签",
        "Milestone" to "里程碑", "Milestones" to "里程碑",
        "Subscribe" to "订阅", "Unsubscribe" to "取消订阅",
        "Mute" to "静音", "Unmute" to "取消静音",
        // 通知
        "Mark as read" to "标记为已读",
        "Mark all as read" to "全部标记为已读",
        "Unread" to "未读",
        "mentioned" to "提及了你",
        "assigned" to "指派给你",
        "author" to "你创建的",
        "comment" to "你评论过",
        "review_requested" to "请求你审查",
        "ci_activity" to "CI 活动",
        "manual" to "已订阅",
        "team_mention" to "团队提及",
        "state_change" to "状态变更",
        "approval" to "已批准",
        // 通用
        "Search" to "搜索", "search" to "搜索",
        "Login" to "登录", "Sign in" to "登录",
        "Sign up" to "注册", "Logout" to "退出登录", "Log out" to "退出登录",
        "Cancel" to "取消", "Confirm" to "确认",
        "Delete" to "删除", "Edit" to "编辑",
        "Save" to "保存", "Update" to "更新",
        "Add" to "添加", "Remove" to "移除",
        "Loading" to "加载中", "Error" to "错误",
        "Success" to "成功", "Retry" to "重试",
        "Back" to "返回", "Next" to "下一步",
        "Done" to "完成", "All" to "全部",
        "None" to "无", "Yes" to "是", "No" to "否",
        "New" to "新建", "Create" to "创建",
        "Created" to "创建", "Updated" to "更新",
        "Deleted" to "删除", "Published" to "发布",
        "Author" to "作者", "Owner" to "所有者",
        "Private" to "私有", "Public" to "公开",
        "Active" to "活跃", "Inactive" to "不活跃",
        "Archived" to "已归档", "Disabled" to "已禁用",
        "Template" to "模板",
        "Default" to "默认", "default" to "默认",
        "View" to "查看", "view" to "查看",
        "Copy" to "复制", "Share" to "分享",
        "Report" to "举报", "Block" to "屏蔽",
        "More" to "更多", "Filter" to "筛选",
        "Sort" to "排序", "Order" to "排序",
        "Latest" to "最新", "Oldest" to "最早",
        "Newest" to "最新", "Most stars" to "最多星标",
        "Fewest stars" to "最少星标",
        "Most forks" to "最多复刻",
        "Recently updated" to "最近更新",
        "Least recently updated" to "最近未更新",
        // 事件类型
        "PushEvent" to "推送",
        "WatchEvent" to "关注",
        "StarEvent" to "星标",
        "ForkEvent" to "复刻",
        "CreateEvent" to "创建",
        "DeleteEvent" to "删除",
        "IssueCommentEvent" to "议题评论",
        "IssuesEvent" to "议题",
        "PullRequestEvent" to "拉取请求",
        "PullRequestReviewEvent" to "拉取请求审查",
        "PullRequestReviewCommentEvent" to "拉取请求审查评论",
        "CommitCommentEvent" to "提交评论",
        "ReleaseEvent" to "发布",
        "MemberEvent" to "成员",
        "PublicEvent" to "公开",
        "ForkApplyEvent" to "复刻应用",
        "GollumEvent" to "Wiki",
        // 杂项
        "Trending" to "热门", "trending" to "热门",
        "Popular" to "热门", "popular" to "热门",
        "Overview" to "概览", "overview" to "概览",
        "Pinned" to "已置顶", "pinned" to "已置顶",
        "Contribution settings" to "贡献设置",
        "Achievements" to "成就", "achievements" to "成就",
        "Contribution graph" to "贡献图",
        "No contributions" to "暂无贡献",
        "Learn how we count contributions" to "了解我们如何计算贡献",
    )

    // 按长度降序排列，确保先匹配长词组
    private val sortedEntries = dictionary.entries.sortedByDescending { it.key.length }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("translation_prefs", Context.MODE_PRIVATE)
        // 迁移：旧版本默认 false，新版本强制设为 true 一次
        if (!prefs!!.contains("v2_migrated")) {
            prefs!!.edit().putBoolean("enabled", true).putBoolean("v2_migrated", true).apply()
        }
        _enabled.value = prefs?.getBoolean("enabled", true) ?: true  // 默认开启
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs?.edit()?.putBoolean("enabled", value)?.apply()
    }

    fun toggle() {
        setEnabled(!_enabled.value)
    }

    /** 翻译单个词/短语（精确匹配，优先使用插件词典） */
    fun translateWord(text: String): String {
        val pluginDict = PluginManager.getTranslationDictionary()
        return pluginDict[text] ?: dictionary[text] ?: text
    }

    /** 翻译句子（替换所有匹配的词组，合并插件词典） */
    fun translateSentence(text: String): String {
        if (!isEnabled()) return text
        val pluginDict = PluginManager.getTranslationDictionary()
        val merged = (dictionary + pluginDict)
        val sorted = merged.entries.sortedByDescending { it.key.length }
        var result = text
        for ((eng, chn) in sorted) {
            result = result.replace(eng, chn, ignoreCase = false)
        }
        return result
    }

    /** 翻译（自动判断使用哪种方式） */
    fun translate(text: String): String {
        if (!isEnabled()) return text
        if (text.isBlank()) return text
        // 先尝试精确匹配
        val exact = dictionary[text]
        if (exact != null) return exact
        // 再尝试句子翻译
        return translateSentence(text)
    }

    fun isEnabled(): Boolean = _enabled.value

    /** 通知词典已更新，需要重新合并插件词典 */
    fun reloadDictionary() {
        // 强制重新读取插件词典（StateFlow 会自动通知更新）
        // 翻译方法每次调用都会读取最新词典，无需额外操作
    }
}
