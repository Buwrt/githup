package com.githubclient.app.ui.explore

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.githubclient.app.ui.components.EmptyState
import com.githubclient.app.ui.components.ErrorState
import com.githubclient.app.ui.components.IssueCard
import com.githubclient.app.ui.components.LoadingMoreState
import com.githubclient.app.ui.components.LoadingState
import com.githubclient.app.ui.components.RepoCard
import com.githubclient.app.ui.components.UserCard
import com.githubclient.app.util.NumberUtils
import com.githubclient.app.util.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onRepoClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null) {
                    val currentList = when (uiState.selectedTab) {
                        SearchTab.REPOS -> uiState.repos
                        SearchTab.USERS -> uiState.users
                        SearchTab.ISSUES -> uiState.issues
                    }
                    if (lastIndex >= currentList.size - 3) {
                        viewModel.loadMore()
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tr("探索"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索框 - GitHub 风格
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = null
            ) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            tr("搜索仓库、用户、Issue..."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Tab + 排序按钮
            if (uiState.query.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabRow(
                        selectedTabIndex = uiState.selectedTab.ordinal,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.background,
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab.ordinal]),
                                color = MaterialTheme.colorScheme.primary,
                                height = 3.dp
                            )
                        },
                        divider = {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        }
                    ) {
                        SearchTab.entries.forEach { tab ->
                            Tab(
                                selected = uiState.selectedTab == tab,
                                onClick = { viewModel.onTabChange(tab) },
                                text = {
                                    Text(
                                        text = when (tab) {
                                            SearchTab.REPOS -> tr("仓库")
                                            SearchTab.USERS -> tr("用户")
                                            SearchTab.ISSUES -> "Issue"
                                        },
                                        fontWeight = if (uiState.selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 排序按钮
                    if (uiState.selectedTab == SearchTab.REPOS) {
                        Box {
                            TextButton(
                                onClick = { sortMenuExpanded = true }
                            ) {
                                Icon(
                                    Icons.Filled.Sort,
                                    contentDescription = tr("排序方式"),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    uiState.sortOption.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                Text(
                                    tr("排序方式"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (option == uiState.sortOption) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Text(option.displayName)
                                            }
                                        },
                                        onClick = {
                                            viewModel.onSortChange(option)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 内容
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.query.isBlank() -> TrendingSection(
                        trendingRepos = uiState.trendingRepos,
                        onRepoClick = onRepoClick
                    )
                    uiState.isLoading -> LoadingState(message = tr("搜索中"))
                    uiState.error != null -> ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.onQueryChange(uiState.query) }
                    )
                    else -> {
                        val currentList = when (uiState.selectedTab) {
                            SearchTab.REPOS -> uiState.repos
                            SearchTab.USERS -> uiState.users
                            SearchTab.ISSUES -> uiState.issues
                        }

                        if (currentList.isEmpty()) {
                            EmptyState(message = tr("没有找到相关结果"))
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                when (uiState.selectedTab) {
                                    SearchTab.REPOS -> items(uiState.repos, key = { it.id }) { repo ->
                                        RepoCard(
                                            repo = repo,
                                            onClick = { onRepoClick(repo.owner?.login ?: "", repo.name) }
                                        )
                                    }
                                    SearchTab.USERS -> items(uiState.users, key = { it.id }) { user ->
                                        UserCard(
                                            user = user,
                                            onClick = { onUserClick(user.login) }
                                        )
                                    }
                                    SearchTab.ISSUES -> items(uiState.issues, key = { it.id }) { issue ->
                                        IssueCard(
                                            issue = issue,
                                            onClick = {
                                                val repoUrl = issue.repositoryUrl
                                                val parts = repoUrl.split("/")
                                                if (parts.size >= 2) {
                                                    onRepoClick(parts[parts.size - 2], parts[parts.size - 1])
                                                }
                                            }
                                        )
                                    }
                                }
                                if (uiState.isLoadingMore) {
                                    item { LoadingMoreState() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingSection(
    trendingRepos: List<com.githubclient.app.data.remote.model.Repository>,
    onRepoClick: (String, String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 热门仓库标题 - 带渐变背景和火焰图标
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF238636).copy(alpha = 0.12f),
                                    Color(0xFF0969DA).copy(alpha = 0.08f),
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = Color(0xFFE8590C),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("热门仓库"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${trendingRepos.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(trendingRepos, key = { it.id }) { repo ->
            RepoCard(
                repo = repo,
                onClick = { onRepoClick(repo.owner?.login ?: "", repo.name) }
            )
        }
    }
}
