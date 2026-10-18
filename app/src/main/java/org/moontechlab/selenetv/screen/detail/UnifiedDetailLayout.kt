package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 电影/电视剧/短剧共享的详情页背景。 */
val UnifiedDetailBackground: Brush = Brush.verticalGradient(
    0.0f to Color(0xFF0A0A0A),
    0.4f to Color(0xFF1A1A1A),
    0.7f to Color(0xFF0F0F0F),
    1.0f to Color(0xFF050505)
)

/** 统一详情页的滚动容器。焦点域及弹窗退出策略由调用者通过 modifier 提供。 */
@Composable
fun UnifiedDetailScrollColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(modifier = modifier.verticalScroll(scrollState), content = content)
}
