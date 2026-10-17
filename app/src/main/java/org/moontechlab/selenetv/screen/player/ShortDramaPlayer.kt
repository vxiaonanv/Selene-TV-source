package org.moontechlab.selenetv.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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

/** Short-drama-only player UI. Movie/TV PlayerScreen remains unchanged. */
@Composable
fun ShortDramaPlayer(detail: DramaDetail, initialEpisode: Int, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { PlayerFactory.create(context, null) }
    var episode by remember(detail.id) { mutableIntStateOf(initialEpisode) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var aspectOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var controlsRowFocused by remember { mutableStateOf(false) }
    var scrubberFocused by remember { mutableStateOf(false) }
    val scrubberFocus = remember { FocusRequester() }
    val aspectFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }

    fun wake() { controls = true; interaction++ }
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { delay(2200); exitArmed = false } }
    BackHandler(enabled = !aspectOpen && !speedOpen) {
        when {
            controls -> { controls = false; exitArmed = false }
            exitArmed -> onExit()
            else -> exitArmed = true
        }
    }
    LaunchedEffect(resizeMode) { player.resizeMode = resizeMode }
    LaunchedEffect(playbackSpeed) { player.speed = playbackSpeed }
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(70)
            runCatching { scrubberFocus.requestFocus() }
        }
    }
    LaunchedEffect(controls, player.isPlaying, interaction, aspectOpen, speedOpen, controlsRowFocused) {
        if (controls && player.isPlaying && !aspectOpen && !speedOpen && !controlsRowFocused) {
            delay(5000)
            controls = false
            runCatching { scrubberFocus.requestFocus() }
        }
    }
    LaunchedEffect(detail.id, episode) {
        error = null
        try {
            val url = ShortDramaService.playUrl(
                detail.id, episode, detail.name,
                detail.episodes.getOrNull(episode)?.url.orEmpty()
            )
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
    val popupOpen = aspectOpen || speedOpen

    Box(Modifier.fillMaxSize().background(Color.Black)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || popupOpen) return@onPreviewKeyEvent false
            if (event.key == Key.Back) return@onPreviewKeyEvent false
            // Keep scrubber mounted and focused even when its OSD fades out.
            if (!controls) {
                wake()
                runCatching { scrubberFocus.requestFocus() }
                return@onPreviewKeyEvent true
            }
            // Pill focus belongs to the existing TV widgets; do not steal their OK/arrow keys.
            if (controlsRowFocused) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> {
                    player.seekTo((position - 10_000L).coerceAtLeast(0L)); wake(); true
                }
                Key.DirectionRight -> {
                    player.seekTo((position + 10_000L).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)); wake(); true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    player.togglePlay(); wake(); true
                }
                Key.DirectionUp -> {
                    runCatching { speedFocus.requestFocus() }; wake(); true
                }
                else -> false
            }
        }
    ) {
        player.VideoSurface(Modifier.fillMaxSize())

        // The focusable scrubber remains in the composition while the OSD is hidden.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 56.dp)
                .height(28.dp)
                .focusRequester(scrubberFocus)
                .onFocusChanged { scrubberFocused = it.isFocused }
                .focusProperties { up = speedFocus }
                .focusable()
        )

        AnimatedVisibility(
            visible = controls || error != null || player.error != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8000000))))
                    .padding(start = 56.dp, end = 56.dp, top = 56.dp, bottom = 26.dp)
            ) {
                Row(Modifier.fillMaxWidth().onFocusChanged { controlsRowFocused = it.hasFocus }, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${detail.name} · ${detail.episodes.getOrNull(episode)?.title ?: "第${episode + 1}集"}",
                        color = Color.White, fontSize = 25.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        AspectRatioPill(
                            currentMode = resizeMode,
                            onClick = { speedOpen = false; aspectOpen = true; wake() },
                            focusRequester = aspectFocus,
                            scrubberFocus = scrubberFocus,
                            rightTarget = speedFocus
                        )
                        AspectRatioPopup(
                            open = aspectOpen, currentMode = resizeMode,
                            onSelect = { resizeMode = it; aspectOpen = false; wake(); runCatching { aspectFocus.requestFocus() } },
                            onDismiss = { aspectOpen = false; wake() }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        SpeedPill(
                            currentSpeed = playbackSpeed,
                            onClick = { aspectOpen = false; speedOpen = true; wake() },
                            focusRequester = speedFocus,
                            scrubberFocus = scrubberFocus,
                            leftTarget = aspectFocus
                        )
                        SpeedPopup(
                            open = speedOpen, currentSpeed = playbackSpeed,
                            onSelect = { playbackSpeed = it; speedOpen = false; wake(); runCatching { speedFocus.requestFocus() } },
                            onDismiss = { speedOpen = false; wake() }
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                // All track layers use the SAME center alignment and measured width.
                BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
                    val trackWidth = maxWidth
                    Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)
                        .clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
                    Box(Modifier.width(trackWidth * fraction).height(4.dp).align(Alignment.CenterStart)
                        .offset(y = 14.dp).clip(CircleShape).background(Color.White))
                    if (scrubberFocused) {
                        Box(
                            Modifier.align(Alignment.CenterStart)
                                .offset(x = (trackWidth * fraction - 26.dp).coerceIn(0.dp, (trackWidth - 52.dp).coerceAtLeast(0.dp)), y = (-24).dp)
                                .clip(RoundedCornerShape(7.dp)).background(Color.White)
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(shortDramaTime(position), color = Color.Black, fontSize = 12.sp)
                        }
                    }
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset(x = (trackWidth * fraction - 9.dp).coerceIn(0.dp, (trackWidth - 18.dp).coerceAtLeast(0.dp)))
                        .size(18.dp).clip(CircleShape).background(Color.White))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortDramaTime(position), color = Color.White, fontSize = 14.sp)
                    val remaining = (duration - position).coerceAtLeast(0L)
                    Text(if (duration > 0) "剩余 -${shortDramaTime(remaining)}" else "--:--", color = Color.LightGray, fontSize = 14.sp)
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
