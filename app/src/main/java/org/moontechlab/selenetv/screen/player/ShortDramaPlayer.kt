package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.service.DramaDetail
import org.moontechlab.selenetv.service.ShortDramaService

/** Short-drama-only OSD; leaves the movie/TV PlayerScreen untouched. */
@Composable
fun ShortDramaPlayer(detail: DramaDetail, initialEpisode: Int, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { PlayerFactory.create(context, null) }
    var episode by remember(detail.id) { mutableIntStateOf(initialEpisode) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    fun wake() { controls = true; interaction++ }
    BackHandler { onExit() }
    LaunchedEffect(Unit) { delay(180); runCatching { focus.requestFocus() } }
    LaunchedEffect(interaction, error) {
        if (error == null) {
            delay(5000)
            controls = false
        }
    }
    LaunchedEffect(detail.id, episode) {
        error = null
        try {
            val url = ShortDramaService.playUrl(detail.id, episode, detail.name, detail.episodes.getOrNull(episode)?.url.orEmpty())
            player.load(url, 0L)
            wake()
        } catch (e: Exception) { error = e.message ?: "解析播放地址失败" }
    }
    DisposableEffect(player) {
        player.onEnded = { if (episode + 1 < detail.episodeCount) episode++ }
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            player.syncProgress()
            delay(500)
        }
    }
    val duration = player.durationMs
    val position = player.positionMs
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!controls) wake()
                        else { player.seekTo((position - 10_000L).coerceAtLeast(0L)); wake() }
                        true
                    }
                    Key.DirectionRight -> {
                        if (!controls) wake()
                        else { player.seekTo((position + 10_000L).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)); wake() }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!controls) wake() else { player.togglePlay(); wake() }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> { wake(); true }
                    else -> false
                }
            }.focusable()
    ) {
        player.VideoSurface(Modifier.fillMaxSize())
        if (controls || error != null || player.error != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8000000))))
                    .padding(start = 56.dp, end = 56.dp, top = 58.dp, bottom = 26.dp)
            ) {
                Text(
                    "${detail.name} · ${detail.episodes.getOrNull(episode)?.title ?: "第${episode + 1}集"}",
                    color = Color.White, fontSize = 25.sp
                )
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(24.dp)) {
                    Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                    BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
                        Box(Modifier.width(maxWidth * fraction).height(4.dp).clip(CircleShape).background(Color.White))
                        Box(Modifier.offset(x = (maxWidth * fraction - 8.dp).coerceAtLeast(0.dp)).size(16.dp).align(Alignment.CenterStart).clip(CircleShape).background(Color.White))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortDramaTime(position), color = Color.White, fontSize = 13.sp)
                    Text(if (duration > 0) "剩余 -${shortDramaTime((duration - position).coerceAtLeast(0L))}" else "--:--", color = Color.LightGray, fontSize = 13.sp)
                }
                error?.let { Text(it, color = Color.Red) }
                player.error?.let { Text(it, color = Color.Red) }
            }
        }
    }
}

private fun shortDramaTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
