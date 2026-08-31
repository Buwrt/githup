package com.githubclient.app.ui.issue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.githubclient.app.data.remote.model.Comment
import com.githubclient.app.data.remote.model.Issue
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.theme.GitHubGreen
import com.githubclient.app.ui.theme.GitHubPurple
import com.githubclient.app.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(
    owner: String,
    repo: String,
    number: Int,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: IssueDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(owner, repo, number) {
        viewModel.loadIssue(owner, repo, number)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "#$number",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$owner/$repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    val issue = uiState.issue
                    if (issue != null) {
                        IconButton(onClick = { viewModel.toggleState() }) {
                            Icon(
                                imageVector = if (issue.state == "open") Icons.Filled.CheckCircle else Icons.Filled.Circle,
                                contentDescription = if (issue.state == "open") "关闭" else "重新打开",
                                tint = if (issue.state == "open") GitHubGreen else GitHubPurple
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> LoadingState(message = "加载 Issue...")
                uiState.error != null && uiState.issue == null ->
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.loadIssue(owner, repo, number) }
                    )
                uiState.issue != null ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Issue 主体
                            item {
                                IssueHeader(issue = uiState.issue!!)
                            }

                            // Issue 内容
                            item {
                                IssueBody(
                                    issue = uiState.issue!!,
                                    onUserClick = onUserClick
                                )
                            }

                            // 评论
                            if (uiState.comments.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "${uiState.comments.size} 条评论",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(uiState.comments, key = { it.id }) { comment ->
                                    CommentItem(
                                        comment = comment,
                                        onUserClick = onUserClick
                                    )
                                }
                            } else {
                                item {
                                    EmptyState(
                                        message = "暂无评论",
                                        icon = Icons.Filled.Comment,
                                        modifier = Modifier.height(150.dp)
                                    )
                                }
                            }
                        }

                        // 评论输入框
                        CommentInput(
                            text = uiState.commentText,
                            onTextChange = { viewModel.onCommentTextChange(it) },
                            onSubmit = { viewModel.submitComment() },
                            isSubmitting = uiState.isAddingComment
                        )
                    }
            }
        }
    }
}

@Composable
private fun IssueHeader(issue: Issue) {
    Column {
        Text(
            text = issue.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态标签
            Box(
                modifier = Modifier
                    .background(
                        color = if (issue.state == "open") GitHubGreen.copy(alpha = 0.15f) else GitHubPurple.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (issue.state == "open") Icons.Filled.Circle else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (issue.state == "open") GitHubGreen else GitHubPurple
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (issue.state == "open") "Open" else "Closed",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (issue.state == "open") GitHubGreen else GitHubPurple
                    )
                }
            }

            issue.user?.let { user ->
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(CircleShape)
                )
                Text(
                    text = user.login,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "· ${TimeUtils.formatRelativeTime(issue.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Labels
        if (issue.labels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                issue.labels.forEach { label ->
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(android.graphics.Color.parseColor("#${label.color}")),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLightColor(label.color)) Color.Black else Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun isLightColor(hex: String): Boolean {
    return try {
        val color = android.graphics.Color.parseColor("#$hex")
        val luminance = (0.299 * android.graphics.Color.red(color) +
                0.587 * android.graphics.Color.green(color) +
                0.114 * android.graphics.Color.blue(color)) / 255
        luminance > 0.5
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun IssueBody(issue: Issue, onUserClick: (String) -> Unit) {
    if (issue.body.isNullOrEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 作者信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                issue.user?.let { user ->
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = user.login,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.clickable { onUserClick(user.login) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = " commented ${TimeUtils.formatRelativeTime(issue.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 正文
            SelectionContainer {
                Text(
                    text = issue.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun CommentItem(comment: Comment, onUserClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                comment.user?.let { user ->
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = user.login,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.clickable { onUserClick(user.login) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = " commented ${TimeUtils.formatRelativeTime(comment.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SelectionContainer {
                Text(
                    text = comment.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun CommentInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isSubmitting: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("留下你的评论...") },
            shape = RoundedCornerShape(12.dp),
            minLines = 3,
            maxLines = 6
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onSubmit,
                enabled = text.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("评论", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
