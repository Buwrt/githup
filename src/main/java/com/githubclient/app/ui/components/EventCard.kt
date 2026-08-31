package com.githubclient.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.githubclient.app.data.remote.model.Event
import com.githubclient.app.ui.theme.GitHubBlue
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubPurple
import com.githubclient.app.ui.theme.GitHubRed
import com.githubclient.app.ui.theme.GitHubTextSecondary
import com.githubclient.app.ui.theme.GitHubYellow
import com.githubclient.app.util.TimeUtils

@Composable
fun EventCard(
    event: Event,
    onRepoClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val eventInfo = getEventInfo(event)

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 事件图标
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = eventInfo.iconColor.copy(alpha = 0.15f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = eventInfo.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = eventInfo.iconColor
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                // 用户和动作
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AsyncImage(
                        model = event.actor?.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { event.actor?.login?.let(onUserClick) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = event.actor?.displayLogin ?: event.actor?.login ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.clickable { event.actor?.login?.let(onUserClick) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = eventInfo.action,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GitHubTextSecondary
                    )
                }

                // 仓库名
                Spacer(modifier = Modifier.height(4.dp))
                event.repo?.name?.let { repoName ->
                    Text(
                        text = repoName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            val parts = repoName.split("/")
                            if (parts.size == 2) onRepoClick(parts[0], parts[1])
                        }
                    )
                }

                // 附加信息（commit message / issue title等）
                eventInfo.description?.let { desc ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 时间
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = TimeUtils.formatRelativeTime(event.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class EventInfo(
    val icon: ImageVector,
    val iconColor: Color,
    val action: String,
    val description: String? = null
)

private fun getEventInfo(event: Event): EventInfo {
    return when (event.type) {
        "PushEvent" -> EventInfo(
            icon = Icons.Filled.CallSplit,
            iconColor = GitHubGreen,
            action = "推送了 ${event.payload?.size ?: 0} 个提交",
            description = event.payload?.commits?.firstOrNull()?.message
        )
        "CreateEvent" -> EventInfo(
            icon = Icons.Filled.Add,
            iconColor = GitHubGreen,
            action = "创建了 ${event.payload?.refType ?: "仓库"}",
            description = event.payload?.description
        )
        "DeleteEvent" -> EventInfo(
            icon = Icons.Filled.Delete,
            iconColor = GitHubRed,
            action = "删除了 ${event.payload?.refType ?: ""}"
        )
        "WatchEvent" -> EventInfo(
            icon = Icons.Filled.Star,
            iconColor = GitHubYellow,
            action = "收藏了仓库"
        )
        "ForkEvent" -> EventInfo(
            icon = Icons.Filled.ForkRight,
            iconColor = GitHubBlue,
            action = "Fork了仓库"
        )
        "IssuesEvent" -> EventInfo(
            icon = Icons.Filled.Warning,
            iconColor = if (event.payload?.action == "closed") GitHubPurple else GitHubGreen,
            action = "${getActionText(event.payload?.action)}了 Issue",
            description = event.payload?.issue?.title
        )
        "IssueCommentEvent" -> EventInfo(
            icon = Icons.Filled.Comment,
            iconColor = GitHubBlue,
            action = "评论了 Issue",
            description = event.payload?.issue?.title
        )
        "PullRequestEvent" -> EventInfo(
            icon = Icons.Filled.Code,
            iconColor = if (event.payload?.action == "closed") GitHubPurple else GitHubGreen,
            action = "${getActionText(event.payload?.action)}了 Pull Request",
            description = event.payload?.pullRequest?.title
        )
        "PullRequestReviewEvent" -> EventInfo(
            icon = Icons.Filled.Edit,
            iconColor = GitHubPurple,
            action = "审核了 Pull Request",
            description = event.payload?.pullRequest?.title
        )
        "PullRequestReviewCommentEvent" -> EventInfo(
            icon = Icons.Filled.Comment,
            iconColor = GitHubBlue,
            action = "评论了 Pull Request",
            description = event.payload?.pullRequest?.title
        )
        "ReleaseEvent" -> EventInfo(
            icon = Icons.Filled.Bookmark,
            iconColor = GitHubGreen,
            action = "${getActionText(event.payload?.action)}了 Release",
            description = event.payload?.release?.name ?: event.payload?.release?.tagName
        )
        "PublicEvent" -> EventInfo(
            icon = Icons.Filled.Public,
            iconColor = GitHubGreen,
            action = "开源了仓库"
        )
        "MemberEvent" -> EventInfo(
            icon = Icons.Filled.PersonAdd,
            iconColor = GitHubPurple,
            action = "${getActionText(event.payload?.action)}了协作者"
        )
        "GollumEvent" -> EventInfo(
            icon = Icons.Filled.Create,
            iconColor = GitHubBlue,
            action = "更新了 Wiki"
        )
        else -> EventInfo(
            icon = Icons.Filled.Public,
            iconColor = GitHubTextSecondary,
            action = event.type.removeSuffix("Event")
        )
    }
}

private fun getActionText(action: String?): String {
    return when (action) {
        "opened" -> "创建"
        "closed" -> "关闭"
        "reopened" -> "重新打开"
        "created" -> "创建"
        "published" -> "发布"
        "edited" -> "编辑"
        "added" -> "添加"
        else -> action ?: ""
    }
}
