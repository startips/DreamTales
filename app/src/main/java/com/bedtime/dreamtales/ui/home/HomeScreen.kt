package com.bedtime.dreamtales.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.theme.WarmAlertRed
import com.bedtime.dreamtales.core.theme.WarmSuccessGreen
import com.bedtime.dreamtales.data.api.StoryCandidate
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.service.StoryPlaybackState
import com.bedtime.dreamtales.ui.settings.AboutDialog
import com.bedtime.dreamtales.ui.settings.ThemeSettingsDialog

/**
 * 遵循 Material Design 3 规范的主页文库。
 * 具备故事自由搜索（候选卡片二次确认）、主页精选 Top 5 排行与展开、红心收藏、右上角标准设置体系。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToRecord: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayStory: (Story, String) -> Unit
) {
    val stories by viewModel.stories.collectAsState()
    val defaultVoice by viewModel.defaultVoice.collectAsState()
    val allVoices by viewModel.allVoices.collectAsState()
    val completedCaches by viewModel.completedCaches.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val synthesisState by viewModel.synthesisState.collectAsState()
    val generationState by viewModel.generationState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var showVoiceSheet by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAllStories by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val focusManager = LocalFocusManager.current

    // 合成完成后准备就绪（待命模式，进入播放器不自动喷音）
    LaunchedEffect(synthesisState) {
        if (synthesisState is SynthesisUiState.ReadyToPlay) {
            val ready = synthesisState as SynthesisUiState.ReadyToPlay
            viewModel.resetSynthesisState()
            onPlayStory(ready.story, ready.localFilePath)
        }
    }

    // AI 生成故事成功后进入待命播放
    LaunchedEffect(generationState) {
        if (generationState is StoryGenerationUiState.Success) {
            val story = (generationState as StoryGenerationUiState.Success).story
            viewModel.resetGenerationState()
            searchQuery = ""
            viewModel.onStoryClicked(story, onNavigateToPlayer)
        }
    }

    // 筛选前 5 篇播放热度最高故事
    val top5Stories = remember(stories) {
        stories.sortedWith(compareByDescending<Story> { it.isFavorite }.thenByDescending { it.playCount }).take(5)
    }
    val remainingStories = remember(stories, top5Stories) {
        stories.filter { it.id !in top5Stories.map { t -> t.id } }
    }
    val displayStories = if (showAllStories) {
        stories.sortedWith(compareByDescending<Story> { it.isFavorite }.thenByDescending { it.playCount })
    } else {
        top5Stories
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // 顶部极简导航栏：Logo + 右上角标准更多菜单
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🌙", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DreamTales",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 右上角常规 APP 菜单体系
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多设置",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("🎨 界面色调设置") },
                                onClick = {
                                    showTopMenu = false
                                    showThemeDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("ℹ️ 关于 DreamTales") },
                                onClick = {
                                    showTopMenu = false
                                    showAboutDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 讲故事人轻量气泡卡片
                OutlinedCard(
                    onClick = { showVoiceSheet = true },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "讲故事人：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = defaultVoice?.name ?: "未设置音色",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "更换 ▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = playbackState.storyTitle.isNotBlank(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                BottomMiniPlayer(
                    playbackState = playbackState,
                    onCardClick = onNavigateToPlayer,
                    onTogglePlayPause = onTogglePlayPause
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 搜索栏与候选推荐区域
            item(key = "story_search_section") {
                ElevatedCard(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "搜童话（如：九色鹿、小红帽、勇敢小恐龙...）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            shape = CircleShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "搜索",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.searchStoryCandidates(searchQuery.trim())
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "检索候选",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    if (searchQuery.isNotBlank()) {
                                        viewModel.searchStoryCandidates(searchQuery.trim())
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 灵感快捷推荐气泡
                        val quickSuggestions = listOf("九色鹿", "小红帽", "神笔马良", "森林小兔子", "月亮上的秋千")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickSuggestions) { tag ->
                                SuggestionChip(
                                    onClick = {
                                        focusManager.clearFocus()
                                        searchQuery = tag
                                        viewModel.searchStoryCandidates(tag)
                                    },
                                    label = {
                                        Text(text = tag, style = MaterialTheme.typography.labelSmall)
                                    },
                                    shape = CircleShape,
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = SuggestionChipDefaults.suggestionChipBorder(
                                        enabled = true,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                            }
                        }

                        // 搜索中状态
                        if (generationState is StoryGenerationUiState.SearchingCandidates) {
                            val gen = generationState as StoryGenerationUiState.SearchingCandidates
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "✨ 正在为你检索《${gen.query}》相关童话...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // 生成正文中状态
                        if (generationState is StoryGenerationUiState.GeneratingStory) {
                            val gen = generationState as StoryGenerationUiState.GeneratingStory
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "📖 正在编织《${gen.title}》完整睡前正文...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // 搜索结果候选列表（二次点选下载机制）
                        if (generationState is StoryGenerationUiState.CandidatesReady) {
                            val candidatesState = generationState as StoryGenerationUiState.CandidatesReady
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "精选候选童话 (轻触收录下载)：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { viewModel.clearCandidates() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                candidatesState.candidates.forEach { candidate ->
                                    CandidateStoryCard(
                                        candidate = candidate,
                                        onSelect = { viewModel.confirmAndGenerateStory(candidate) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. 故事文库大标题（视觉降噪版）
            item(key = "story_list_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAllStories) "🌙 全部伴读文库" else "🌙 今夜最爱 · 伴读精选 (Top 5)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = if (showAllStories) "共 ${stories.size} 篇" else "精选 5 篇",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 3. 故事卡片列表（降噪 1 行摘要 + 卡片内原位进度 + 红心收藏）
            items(displayStories, key = { it.id }) { story ->
                val isCached = completedCaches.any { it.storyId == story.id && it.voiceProfileId == defaultVoice?.id }
                val isCurrentPlaying = (playbackState.storyId == story.id && playbackState.isPlaying)
                val isSynthesizing = (synthesisState is SynthesisUiState.Synthesizing && (synthesisState as SynthesisUiState.Synthesizing).storyId == story.id)
                val synthesisProgress = if (isSynthesizing) (synthesisState as SynthesisUiState.Synthesizing).progress else 0

                VerticalStoryCard(
                    story = story,
                    isCached = isCached,
                    isCurrentPlaying = isCurrentPlaying,
                    isSynthesizing = isSynthesizing,
                    synthesisProgress = synthesisProgress,
                    onPlayClick = {
                        viewModel.onStoryClicked(story, onNavigateToPlayer)
                    },
                    onToggleFavorite = {
                        viewModel.toggleFavorite(story)
                    }
                )
            }

            // 4. “浏览更多经典童话”折叠展开按键
            if (remainingStories.isNotEmpty()) {
                item(key = "toggle_more_stories_button") {
                    OutlinedButton(
                        onClick = { showAllStories = !showAllStories },
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Text(
                            text = if (showAllStories) "收起其余童话 ▴" else "浏览更多经典童话 (共 ${stories.size} 篇) ▾",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // 色调设置弹窗
    if (showThemeDialog) {
        ThemeSettingsDialog(
            currentMode = themeMode,
            onModeSelected = { mode ->
                viewModel.setThemeMode(mode)
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    // 关于弹窗
    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    // 音色选择 BottomSheet
    if (showVoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择讲故事人音色",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = {
                            showVoiceSheet = false
                            onNavigateToRecord()
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("录入新音色", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(allVoices, key = { it.id }) { voice ->
                        val isSelected = (voice.id == defaultVoice?.id)
                        OutlinedCard(
                            onClick = {
                                viewModel.selectVoiceProfile(voice.id)
                                showVoiceSheet = false
                            },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = voice.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val subtitle = when {
                                        voice.cloudVoiceId == "mimo:default" -> "小米原生温柔女声 · 经典伴读推荐"
                                        voice.cloudVoiceId == "mimo:clone" -> "家长专属复刻 · 高保真原声"
                                        else -> "专属伴读声音"
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 报错提示
    if (synthesisState is SynthesisUiState.Error) {
        val err = synthesisState as SynthesisUiState.Error
        AlertDialog(
            onDismissRequest = { viewModel.resetSynthesisState() },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "伴读提示",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = err.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resetSynthesisState() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("我知道了", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

/**
 * 候选故事小卡片（搜索结果二次点选机制）
 */
@Composable
private fun CandidateStoryCard(
    candidate: StoryCandidate,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(text = candidate.iconEmoji.ifBlank { "🌙" }, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = candidate.targetAge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = candidate.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onSelect,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("收录伴读", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 纵向故事卡片组件：1行治愈摘要、动态图标、红心收藏、原位进度光圈。
 */
@Composable
private fun VerticalStoryCard(
    story: Story,
    isCached: Boolean,
    isCurrentPlaying: Boolean,
    isSynthesizing: Boolean,
    synthesisProgress: Int,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    OutlinedCard(
        onClick = onPlayClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isCurrentPlaying || isSynthesizing) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            1.dp,
            if (isCurrentPlaying || isSynthesizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 左侧童话睡前插画/徽章
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            BorderStroke(
                                1.dp,
                                if (isCurrentPlaying || isSynthesizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            ),
                            MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getStoryEmoji(story),
                        fontSize = 24.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 2. 中间故事主体信息（精简降噪版）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary
                            else if (isSynthesizing) MaterialTheme.colorScheme.primaryContainer
                            else if (story.isAiGenerated) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = if (isCurrentPlaying) "伴读中"
                                else if (isSynthesizing) "调配中"
                                else if (story.dateLabel.isNotBlank()) story.dateLabel else "经典童话",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrentPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = story.targetAge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = story.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // 1 行治愈正文摘要（视觉极大清爽降噪）
                    Text(
                        text = story.content.lines().firstOrNull { it.isNotBlank() } ?: story.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 底部轻量信息行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSynthesizing) {
                            Text(
                                text = "🪄 正在生成 $synthesisProgress%...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "${story.durationSeconds / 60} 分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Icon(
                                imageVector = if (isCached) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (isCached) WarmSuccessGreen else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isCached) "已就绪" else "点播离线",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCached) WarmSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (story.playCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "听过${story.playCount}次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                // 3. 右侧红心收藏按钮
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (story.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (story.isFavorite) WarmAlertRed else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // 4. 右侧播放/原位环形进度光圈
                if (isSynthesizing) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { synthesisProgress / 100f },
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "$synthesisProgress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    FilledTonalIconButton(
                        onClick = onPlayClick,
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isCurrentPlaying || isCached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isCurrentPlaying || isCached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isCurrentPlaying) "暂停" else "播放",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 卡片最底沿极细进度线
            if (isSynthesizing) {
                LinearProgressIndicator(
                    progress = { synthesisProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

/**
 * 底部常驻迷你播放器卡片 (Mini Player)
 */
@Composable
private fun BottomMiniPlayer(
    playbackState: StoryPlaybackState,
    onCardClick: () -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val progress = if (playbackState.totalDurationMs > 0) {
        (playbackState.currentPositionMs.toFloat() / playbackState.totalDurationMs).coerceIn(0f, 1f)
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onCardClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🌙", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = playbackState.storyTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val timerText = if (playbackState.isTimerActive && playbackState.remainingTimerSeconds > 0) {
                        if (playbackState.isPlaying) {
                            "🌙 伴读倒计时 ${formatSeconds(playbackState.remainingTimerSeconds)} · 轻点展开"
                        } else {
                            "⏸️ 倒计时已暂停 (${formatSeconds(playbackState.remainingTimerSeconds)}) · 轻点展开"
                        }
                    } else if (playbackState.isPlaying) {
                        "正在伴读播放 · 轻点展开"
                    } else {
                        "伴读已就绪 · 轻点展开"
                    }
                    Text(
                        text = timerText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 依据故事题材语义精准匹配睡前插画 Emoji
 */
private fun getStoryEmoji(story: Story): String {
    if (story.iconEmoji.isNotBlank()) return story.iconEmoji
    val t = story.title + " " + story.content
    return when {
        t.contains("鹿") -> "🦌"
        t.contains("恐龙") || t.contains("霸王龙") -> "🦕"
        t.contains("马") -> "🐴"
        t.contains("蝌蚪") || t.contains("青蛙") -> "🐸"
        t.contains("兔") || t.contains("萝卜") -> "🥕"
        t.contains("猪") || t.contains("小猪") -> "🏡"
        t.contains("熊") -> "🐻"
        t.contains("狐狸") -> "🦊"
        t.contains("狮") -> "🦁"
        t.contains("象") -> "🐘"
        t.contains("猴") -> "🐵"
        t.contains("猫") -> "🐱"
        t.contains("狗") -> "🐶"
        t.contains("鸭") || t.contains("丑小鸭") -> "🦆"
        t.contains("天鹅") -> "🦢"
        t.contains("鸟") || t.contains("夜莺") -> "🎶"
        t.contains("鸽") || t.contains("燕") -> "🕊️"
        t.contains("鱼") || t.contains("海豚") || t.contains("鲸") -> "🐬"
        t.contains("龟") -> "🐢"
        t.contains("红帽") -> "👧"
        t.contains("姑娘") || t.contains("拇指") -> "🌸"
        t.contains("豌豆") || t.contains("公主") -> "👑"
        t.contains("王子") -> "🤴"
        t.contains("玫瑰") || t.contains("花") -> "🌹"
        t.contains("月亮") || t.contains("船") -> "🌙"
        t.contains("星") -> "⭐"
        t.contains("云") -> "☁️"
        t.contains("车") || t.contains("火车") -> "🚂"
        t.contains("火箭") || t.contains("飞船") -> "🚀"
        t.contains("气球") -> "🎈"
        t.contains("笔") || t.contains("画") -> "🎨"
        t.contains("树") || t.contains("森林") -> "🌲"
        else -> "🌙"
    }
}

private fun formatSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
