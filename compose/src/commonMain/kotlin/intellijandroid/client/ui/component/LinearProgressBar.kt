package intellijandroid.client.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.theme.IslandsDark

/**
 * IntelliJ's indeterminate `JProgressBar` (`DarculaProgressBarUI`), Jewel's
 * `IndeterminateHorizontalProgressBar`: a 4 dp rounded track with a segment a third of its width
 * sliding across, one pass every 1.6 s. Fills the width it is given, e.g.
 * `IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth())`.
 *
 * IntelliJ paints the segment as a gradient from `ProgressBar.indeterminateStartColor` (blue-110)
 * to `indeterminateEndColor` (control-brand-bg); this draws it in one color, as Gateway's start
 * popup looked when measured.
 */
@Composable
fun IndeterminateHorizontalProgressBar(modifier: Modifier = Modifier) {
  val segment by
    rememberInfiniteTransition(label = "progress")
      .animateFloat(
        initialValue = -ProgressBarLook.segment,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "segment",
      )
  Canvas(modifier.height(ProgressBarLook.height)) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(ProgressBarLook.track, cornerRadius = radius)
    val start = (segment * size.width).coerceAtLeast(0f)
    val end = ((segment + ProgressBarLook.segment) * size.width).coerceAtMost(size.width)
    if (end > start)
      drawRoundRect(ProgressBarLook.fill, Offset(start, 0f), Size(end - start, size.height), radius)
  }
}

private object ProgressBarLook {
  val height = 4.dp // DarculaProgressBarUI.DEFAULT_WIDTH
  val track = Color(0xFF40434A) // ProgressBar.trackColor = control-bg-small = gray-50
  val fill = IslandsDark.controlBrandBackground // ProgressBar.progressColor = control-brand-bg
  /** Part of the bar the moving segment covers. */
  const val segment = 0.3f
}
