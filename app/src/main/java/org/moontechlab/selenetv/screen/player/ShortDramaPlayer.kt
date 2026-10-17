package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.service.DramaDetail
import org.moontechlab.selenetv.service.ShortDramaService

@Composable
fun ShortDramaPlayer(detail: DramaDetail, initialEpisode: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val player = remember { PlayerFactory.create(context, null) }
    var episode by remember(detail.id) { mutableIntStateOf(initialEpisode) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }

    BackHandler { onExit() }
    LaunchedEffect(detail.id, episode) {
        error = null
        try {
            val url = ShortDramaService.playUrl(detail.id, episode, detail.name)
            player.load(url, 0L)
        } catch (e: Exception) {
            error = e.message ?: "解析播放地址失败"
        }
    }
    DisposableEffect(player) {
        player.onEnded = {
            if (episode + 1 < detail.episodeCount) episode++
        }
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            player.syncProgress()
            delay(500)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        player.VideoSurface(Modifier.fillMaxSize())
        if (controls || error != null || player.error != null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC111111)).padding(20.dp)) {
                Text("${detail.name} · 第${episode + 1}集 / ${detail.episodeCount}集", color = Color.White)
                error?.let { Text(it, color = Color.Red) }
                player.error?.let { Text(it, color = Color.Red) }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = { if (episode > 0) episode-- }) { Text("上一集") }
                    Button(onClick = { player.togglePlay() }) { Text(if (player.isPlaying) "暂停" else "播放") }
                    Button(onClick = { if (episode + 1 < detail.episodeCount) episode++ }) { Text("下一集") }
                    Button(onClick = onExit) { Text("返回详情") }
                }
            }
        }
    }
}
