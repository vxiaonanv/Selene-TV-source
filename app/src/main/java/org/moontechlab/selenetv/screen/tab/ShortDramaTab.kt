package org.moontechlab.selenetv.screen.tab

import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.data.Repositories
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.DramaCategory
import org.moontechlab.selenetv.service.DramaDetail
import org.moontechlab.selenetv.service.DramaItem
import org.moontechlab.selenetv.service.ShortDramaService
import org.moontechlab.selenetv.ui.FilterSelector
import org.moontechlab.selenetv.ui.FilterOption
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.screen.player.PlayerScreen
import org.moontechlab.selenetv.screen.player.PlayerLaunch
import org.moontechlab.selenetv.screen.detail.HeroInfo
import org.moontechlab.selenetv.screen.detail.UnifiedDetailBackground
import org.moontechlab.selenetv.screen.detail.UnifiedDetailScrollColumn
import org.moontechlab.selenetv.screen.detail.DetailHero
import org.moontechlab.selenetv.screen.detail.DetailActionButtons
import org.moontechlab.selenetv.screen.detail.DetailSourcesSection
import org.moontechlab.selenetv.screen.detail.EpisodePickerDialog
import org.moontechlab.selenetv.screen.detail.sourceKey
import org.moontechlab.selenetv.screen.DetailEntry
import org.moontechlab.selenetv.model.SearchResult

@Composable
fun ShortDramaTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    openDramaId: String? = null,
    onOpenDramaConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<DramaCategory>>(emptyList()) }
    var selected by remember { mutableStateOf<DramaCategory?>(null) }
    var dramas by remember { mutableStateOf<List<DramaItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DramaDetail?>(null) }
    var playing by remember { mutableStateOf(false) }
    var episode by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val cache = remember { mutableStateMapOf<String, Pair<List<DramaItem>, Boolean>>() }
    val cachedPages = remember { mutableStateMapOf<String, Int>() }
    var posterId by remember { mutableStateOf<String?>(null) }
    val posterFocus = remember { FocusRequester() }
    var returnToPoster by remember { mutableStateOf(false) }
    LaunchedEffect(detail) {
        if (detail == null && returnToPoster) {
            delay(160)
            runCatching { posterFocus.requestFocus() }
            returnToPoster = false
        }
    }

    LaunchedEffect(openDramaId) {
        val id = openDramaId ?: return@LaunchedEffect
        try { detail = ShortDramaService.detail(id) }
        catch (e: Exception) { error = e.message ?: "短剧详情加载失败" }
        finally { onOpenDramaConsumed() }
    }

    LaunchedEffect(Unit) {
        try {
            val fetched = ShortDramaService.categories()
            categories = fetched
            selected = categories.firstOrNull()
            if (categories.isEmpty()) error = "红果短剧分类暂不可用"
        } catch (e: Exception) { error = e.message ?: "分类加载失败" }
    }

    LaunchedEffect(selected?.id) {
        val cat = selected ?: return@LaunchedEffect
        val key = cat.id.toString()
        error = null
        val saved = cache[key]
        if (saved != null) {
            dramas = saved.first
            hasMore = saved.second
            page = cachedPages[key] ?: 1
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val result = ShortDramaService.list(cat.id, 1)
            cache[key] = result
            cachedPages[key] = 1
            dramas = result.first
            page = 1
            hasMore = result.second
            if (result.first.isEmpty()) error = "该分类暂无内容"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "短剧列表加载失败"
        } finally { loading = false }
    }

    fun loadMore() {
        val cat = selected ?: return
        val key = cat.id.toString()
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                val next = (cachedPages[key] ?: 1) + 1
                val result = ShortDramaService.list(cat.id, next)
                val merged = (cache[key]?.first.orEmpty() + result.first).distinctBy { it.id }
                cache[key] = merged to result.second
                cachedPages[key] = next
                if (selected?.id == cat.id) {
                    dramas = merged
                    page = next
                    hasMore = result.second
                }
            } catch (e: Exception) {
                if (selected?.id == cat.id) error = e.message
            } finally { loading = false }
        }
    }

    // 与电影列表一致：滚动到末尾前自动获取下一页，无需聚焦“加载更多”按钮。
    LaunchedEffect(selected?.id) {
        snapshotFlow {
            Triple(
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                dramas.size,
                loading || !hasMore
            )
        }.collect { (lastVisible, count, unavailable) ->
            if (!unavailable && count > 0 && lastVisible >= count - 6) {
                loadMore()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 对齐电影频道分类行：56dp 顶部预留 + 6dp 筛选区顶部间距。
        Column(Modifier.fillMaxSize().padding(top = 62.dp, start = 58.dp, end = 58.dp)) {
            if (categories.isNotEmpty()) {
                FilterSelector(
                    options = categories.map { FilterOption(it.id.toString(), it.name) },
                    selectedKey = selected?.id?.toString().orEmpty(),
                    onSelect = { key ->
                        val next = categories.firstOrNull { it.id.toString() == key }
                        if (next?.id != selected?.id) {
                            selected = next
                            posterId = null
                            scope.launch { gridState.scrollToItem(0) }
                        }
                    },
                    focusRequester = focusRequester,
                    directFocusToSelected = true
                    // 这里不拦截向下键：让 Compose 在下一次独立按键时
                    // 自然从分类按钮移动到下方封面，避免同一按键被连续转发。
                )
            }
            Spacer(Modifier.height(20.dp))
            if (error != null) Text(error ?: "", color = Color.White)
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(22.dp),
                // 为首排封面 1.1 倍聚焦动画预留顶部空间，防止被 LazyGrid 裁剪。
                contentPadding = PaddingValues(top = 18.dp, bottom = 90.dp)
            ) {
                itemsIndexed(dramas, key = { _, item -> item.id }) { index, item ->
                    VideoCard(
                        videoInfo = VideoInfo(
                            id = item.id, source = "shortdrama", title = item.name,
                            sourceName = "短剧", year = "", cover = item.cover,
                            index = 0, totalEpisodes = item.episodeCount,
                            playTime = 0L, totalTime = 0L, saveTime = 0L,
                            searchTitle = item.name
                        ),
                        from = VideoCardSource.SOURCE,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (index < 6 && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                focusRequester.requestFocus()
                                true
                            } else false
                        }.then(if (item.id == (posterId ?: dramas.firstOrNull()?.id)) Modifier.focusRequester(posterFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) posterId = item.id },
                        onClick = {
                            posterId = item.id
                            scope.launch {
                                try { detail = ShortDramaService.detail(item.id) }
                                catch (e: Exception) { error = e.message ?: "详情加载失败" }
                            }
                        }
                    )
                }
            }
        }

        detail?.let { d ->
            Dialog(
                onDismissRequest = { if (!playing) detail = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                ShortDramaDetailPage(
                    detail = d,
                    playing = playing,
                    episode = episode,
                    onEpisode = { index -> episode = index; playing = true },
                    onBack = { returnToPoster = true; detail = null },
                    onPlayerExit = { playing = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortDramaDetailPage(
    detail: DramaDetail,
    playing: Boolean,
    episode: Int,
    onEpisode: (Int) -> Unit,
    onBack: () -> Unit,
    onPlayerExit: () -> Unit
) {
    val playFocus = remember(detail.id) { FocusRequester() }
    var pickerOpen by remember(detail.id) { mutableStateOf(false) }
    val detailScroll = rememberScrollState()
    val detailScope = rememberCoroutineScope()
    var favorite by remember(detail.id) { mutableStateOf(false) }
    var playRecord by remember(detail.id) { mutableStateOf<PlayRecord?>(null) }
    LaunchedEffect(detail.id, playing) {
        if (!playing) {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val repository = Repositories.userData()
                    Pair(repository.getFavorites().any { it.source == "shortdrama" && it.id == detail.id },
                         repository.getPlayRecords()["shortdrama+" + detail.id])
                }
                favorite = saved.first
                playRecord = saved.second
            } catch (_: Exception) {}
        }
    }
    fun toggleFavorite() {
        val previous = favorite
        favorite = !previous
        detailScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (previous) Repositories.userData().unfavorite("shortdrama", detail.id)
                    else Repositories.userData().addFavorite("shortdrama", detail.id, mapOf<String, Any?>(
                        "title" to detail.name, "source_name" to "短剧", "year" to "",
                        "cover" to detail.cover, "total_episodes" to detail.episodeCount,
                        "save_time" to System.currentTimeMillis(), "origin" to ""
                    ))
                }
            } catch (_: Exception) { favorite = previous }
        }
    }
    val detailSource = remember(detail) {
        SearchResult(id = detail.id, title = detail.name, poster = detail.cover,
            source = "shortdrama", sourceName = "短剧", episodes = detail.episodes.map { it.url },
            episodesTitles = detail.episodes.map { it.title }, desc = detail.description, typeName = "短剧")
    }
    LaunchedEffect(detail.id, playing) {
        if (!playing) {
            delay(120)
            runCatching { playFocus.requestFocus() }
            detailScroll.scrollTo(0)
        }
    }
    // 与电影详情页一致：禁止焦点自动把 Hero 推出屏幕顶部。
    val noVerticalAutoScroll = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }
    BackHandler(enabled = !playing && !pickerOpen) { onBack() }
    Box(
        Modifier.fillMaxSize()
            .background(UnifiedDetailBackground)
            .focusGroup()
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScroll) {
        UnifiedDetailScrollColumn(modifier = Modifier.fillMaxSize(), scrollState = detailScroll) {
            DetailHero(
                entry = DetailEntry.History(
                    source = "shortdrama", id = detail.id, searchTitle = detail.name,
                    title = detail.name, poster = detail.cover, year = ""
                ),
                info = HeroInfo(
                    title = detail.name,
                    poster = detail.cover,
                    genres = listOf("短剧"),
                    subLine = "共 ${detail.episodeCount} 集",
                    summary = detail.description.replace(Regex("<[^>]+>"), "").ifBlank { "暂无简介" }
                ),
                actionsRow = {
                    // 与电影详情页一致：焦点从播放源/测速区回到操作按钮时，页面滚回顶部。
                    Box(Modifier.onFocusChanged {
                        if (it.hasFocus) detailScope.launch { detailScroll.animateScrollTo(0) }
                    }) {
                    DetailActionButtons(
                        showContinue = playRecord != null, showPlay = true, showDelete = playRecord != null,
                        showFavorite = true, isFavorite = favorite, sourceName = "短剧",
                        onContinue = { onEpisode(((playRecord?.index ?: 1) - 1).coerceIn(0, (detail.episodeCount - 1).coerceAtLeast(0))) },
                        onPlay = { if (playRecord != null) onEpisode(0) else pickerOpen = true },
                        onDelete = {
                            playRecord = null
                            detailScope.launch {
                                try { withContext(Dispatchers.IO) { Repositories.userData().deletePlayRecord("shortdrama", detail.id) } } catch (_: Exception) {}
                            }
                        },
                        onToggleFavorite = { toggleFavorite() },
                        primaryFocusRequester = playFocus
                    )
                    }
                }
            )
            Box(Modifier.onFocusChanged { if (it.hasFocus) detailScope.launch { detailScroll.animateScrollTo(detailScroll.maxValue) } }) {
            DetailSourcesSection(
                sources = listOf(detailSource),
                selectedSourceKey = sourceKey(detailSource),
                playRecords = playRecord?.let { mapOf(("shortdrama+" + detail.id) to it) } ?: emptyMap(),
                progress = null,
                complete = true,
                onSelect = { pickerOpen = true },
                onPlaySelected = { pickerOpen = true },
                onSearch = { pickerOpen = true }
            )
            }
            EpisodePickerDialog(
                visible = pickerOpen,
                sourceName = detail.name,
                episodes = detailSource.episodes,
                episodesTitles = detailSource.episodesTitles,
                onSelect = { idx -> pickerOpen = false; onEpisode(idx) },
                onDismiss = { pickerOpen = false }
            )
        }
        }
        if (playing) {
            var playableUrls by remember(detail.id) { mutableStateOf<List<String>?>(null) }
            var playError by remember(detail.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(detail.id, playing) {
                if (!playing) return@LaunchedEffect
                playableUrls = null
                playError = null
                try {
                    playableUrls = (0 until detail.episodeCount).map { idx ->
                        ShortDramaService.playUrl(detail.id, idx, detail.name, detail.episodes.getOrNull(idx)?.url.orEmpty())
                    }
                } catch (e: Exception) { playError = e.message ?: "播放地址解析失败" }
            }
            val source = remember(detail, playableUrls) {
                SearchResult(
                    id = detail.id,
                    title = detail.name,
                    poster = detail.cover,
                    source = "shortdrama",
                    sourceName = "短剧",
                    episodes = playableUrls.orEmpty(),
                    episodesTitles = detail.episodes.map { it.title },
                    desc = detail.description,
                    typeName = "短剧"
                )
            }
            if (playableUrls != null) PlayerScreen(
                launch = PlayerLaunch(
                    title = detail.name,
                    heroInfo = HeroInfo(
                        title = detail.name, poster = detail.cover,
                        genres = listOf("短剧"), summary = detail.description
                    ),
                    sources = listOf(source),
                    selectedSourceKey = sourceKey(source),
                    episodeIndex = episode,
                    startPositionMs = if (playRecord?.index == episode + 1) (playRecord?.playTime ?: 0L) * 1000L else 0L
                ),
                onExit = onPlayerExit
            ) else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(playError ?: "正在获取播放地址…", color = Color.White)
            }
        }
    }
}
