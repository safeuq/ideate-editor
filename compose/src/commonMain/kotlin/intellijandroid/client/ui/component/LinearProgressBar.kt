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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp

/**
 * IntelliJ's indeterminate `JProgressBar`, painted as `DarculaProgressBarUI.paintIndeterminate`
 * does (not simplified): the whole 4 dp rounded bar filled with a mirrored gradient from
 * `ProgressBar.indeterminateStartColor` to `indeterminateEndColor` that moves right in steps.
 *
 * Values: 16 frames (`cycleTime` 800 ms / `repaintInterval` 50 ms), `step` 6 dp; the gradient runs
 * from `frame * 12` dp to `16 * 6 + frame * 12` dp, mirrored (`GradientPaint(…, cyclic = true)`),
 * so it moves 12 dp every 50 ms and one 800 ms cycle moves it one full period (192 dp), seamless.
 * Fills the width it is given, e.g. `IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth())`.
 */
@Composable
fun IndeterminateHorizontalProgressBar(modifier: Modifier = Modifier) {
  val time by
    rememberInfiniteTransition(label = "progress")
      .animateFloat(
        initialValue = 0f,
        targetValue = ProgressBarLook.frames.toFloat(),
        animationSpec =
          infiniteRepeatable(tween(ProgressBarLook.cycleMillis, easing = LinearEasing)),
        label = "frame",
      )
  Canvas(modifier.height(ProgressBarLook.height)) {
    // Swing repaints every 50 ms, so the gradient jumps a frame at a time rather than gliding.
    val frame = time.toInt().coerceAtMost(ProgressBarLook.frames - 1)
    val step = ProgressBarLook.step.toPx()
    val start = frame * step * 2
    drawRoundRect(
      Brush.linearGradient(
        listOf(ProgressBarLook.indeterminateStart, ProgressBarLook.indeterminateEnd),
        start = Offset(start, 0f),
        end = Offset(start + ProgressBarLook.frames * step, 0f),
        tileMode = TileMode.Mirror,
      ),
      cornerRadius = CornerRadius(size.height / 2),
    )
  }
}

/**
 * IntelliJ's determinate `JProgressBar` (`DarculaProgressBarUI.paintDeterminate`): the 4 dp rounded
 * track in `ProgressBar.trackColor`, and [fraction] of it (0 to 1) from the left in
 * `ProgressBar.progressColor`. E.g. a background task at 0.4 fills two fifths.
 */
@Composable
fun DeterminateHorizontalProgressBar(fraction: Float, modifier: Modifier = Modifier) {
  Canvas(modifier.height(ProgressBarLook.height)) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(ProgressBarLook.track, cornerRadius = radius)
    val filled = size.width * fraction.coerceIn(0f, 1f)
    if (filled > 0f)
      drawRoundRect(
        ProgressBarLook.progress,
        size = androidx.compose.ui.geometry.Size(filled, size.height),
        cornerRadius = radius,
      )
  }
}

private object ProgressBarLook {
  val track = Color(0xFF40434A) // ProgressBar.trackColor = control-bg-small = gray-50
  val progress = Color(0xFF3871E1) // ProgressBar.progressColor = control-brand-bg = blue-80
  val height = 4.dp // DarculaProgressBarUI.DEFAULT_WIDTH
  // ProgressBar.indeterminateStartColor = blue-110 (Islands Dark)
  val indeterminateStart = Color(0xFF92B7FF)
  // Islands Dark spells it "indeterminateendcolor"; UIManager keys match case, so the parent
  // Experimental Dark's indeterminateEndColor = Blue5 applies.
  val indeterminateEnd = Color(0xFF366ACE)
  const val cycleMillis = 800 // CYCLE_TIME_DEFAULT
  const val frames = 16 // cycleMillis / REPAINT_INTERVAL_DEFAULT (50)
  val step = 6.dp
}
