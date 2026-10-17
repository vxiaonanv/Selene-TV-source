package org.moontechlab.selenetv.screen.tab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.DramaCategory
import org.moontechlab.selenetv.service.DramaDetail
import org.moontechlab.selenetv.service.DramaItem
import org.moontechlab.selenetv.service.ShortDramaService
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.screen.player.ShortDramaPlayer

private val preferred = listOf("女频恋爱", "反转爽剧", "古装仙侠", "年代穿越", "脑洞悬疑", "现代都市", "AI短剧")

@Composable
fun ShortDramaTab(focusRequester: FocusRequester = remember { FocusRequester() }) {
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

    LaunchedEffect(Unit) {
        try {
            val fetched = ShortDramaService.categories()
            categories = fetched.sortedWith(compareBy { cat ->
                preferred.indexOfFirst { cat.name.contains(it, ignoreCase = true) }.let { if (it == -1) 100 else it }
            })
            selected = categories.firstOrNull()
            if (categories.isEmpty()) error = "服务端未返回短剧分类，请检查短剧源"
        } catch (e: Exception) { error = e.message ?: "分类加载失败" }
    }

    LaunchedEffect(selected) {
        val cat = selected ?: return@LaunchedEffect
        dramas = emptyList()
        page = 1
        hasMore = false
        loading = true
        error = null
        try {
            val result = ShortDramaService.list(cat.id, 1)
            dramas = result.first
            hasMore = result.second
            if (dramas.isEmpty()) error = "该分类暂无内容"
        } catch (e: Exception) { error = e.message ?: "短剧列表加载失败" }
        finally { loading = false }
    }

    fun loadMore() {
        val cat = selected ?: return
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                val result = ShortDramaService.list(cat.id, page + 1)
                dramas = (dramas + result.first).distinctBy { it.id }
                page++
                hasMore = result.second
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 66.dp, start = 54.dp, end = 54.dp)) {
            Text("短剧", fontSize = 24.sp, color = Color.White)
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(categories.size) { index ->
                    val cat = categories[index]
                    Button(
                        onClick = { selected = cat },
                        modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier
                    ) { Text(cat.name) }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (error != null) Text(error ?: "", color = Color.White)
            if (loading && dramas.isEmpty()) Text("正在加载短剧…", color = Color.White)
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(dramas, key = { it.id }) { item ->
                    VideoCard(
                        videoInfo = VideoInfo(
                            id = item.id, source = "shortdrama", title = item.name,
                            sourceName = "短剧", year = "", cover = item.cover,
                            index = 0, totalEpisodes = item.episodeCount,
                            playTime = 0L, totalTime = 0L, saveTime = 0L,
                            searchTitle = item.name
                        ),
                        from = VideoCardSource.SOURCE,
                        onClick = {
                            scope.launch {
                                try { detail = ShortDramaService.detail(item.id) }
                                catch (e: Exception) { error = e.message ?: "详情加载失败" }
                            }
                        }
                    )
                }
                if (hasMore && !loading) {
                    item {
                        Button(onClick = { loadMore() }) { Text("加载更多") }
                    }
                }
            }
        }

        detail?.let { d ->
            BackHandler(enabled = !playing) { detail = null }
            Box(Modifier.fillMaxSize().background(Color(0xFF12121A))) {
                Column(Modifier.fillMaxSize().padding(55.dp)) {
                    Text(d.name, color = Color.White, fontSize = 27.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(d.description.replace(Regex("<[^>]+>"), ""), color = Color.LightGray, fontSize = 15.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("共 ${d.episodeCount} 集 · 选择集数播放", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(d.episodeCount) { index ->
                            Button(onClick = { episode = index; playing = true }) { Text("${index + 1}") }
                        }
                    }
                }
                if (playing) {
                    ShortDramaPlayer(d, episode, onExit = { playing = false })
                }
            }
        }
    }
}
