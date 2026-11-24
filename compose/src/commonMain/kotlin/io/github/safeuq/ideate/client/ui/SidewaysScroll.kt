package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A panel's sideways scroll position in px: the editor's text area (IntelliJ keeps the gutter in
 * place and scrolls the text) and the tool window trees. Hoisted so the phone's swipe pager can
 * ask, when a sideways swipe starts, whether the page it is on can still scroll that way
 * (ClientScreen).
 */
@Stable
class SidewaysScroll {
  var x by mutableFloatStateOf(0f)
    internal set

  /**
   * Where the panel is, in root coordinates, so the pager asks only the panel under the finger: on
   * a split page (Project over Structure) a long name in one used to keep the swipe from the other.
   */
  internal var bounds = Rect.Zero

  /** How far the content reaches past the panel's right edge; 0 when it fits. */
  internal var max by mutableFloatStateOf(0f)

  internal val scrollable = ScrollableState { delta ->
    val target = (x + delta).coerceIn(0f, max)
    val consumed = target - x
    x = target
    consumed
  }

  /** Whether a sideways drag of [dragX] px (positive: the finger moves right) scrolls the panel. */
  fun canScroll(dragX: Float) = if (dragX > 0) x > 0f else x < max

  /** Sets [max] and keeps [x] inside it. */
  internal fun fit(contentWidth: Float, viewportWidth: Float) {
    max = maxOf(0f, contentWidth - viewportWidth)
    x = x.coerceIn(0f, max)
  }
}

/**
 * A row's selection or hover band in a panel that scrolls sideways, as IntelliJ's trees and lists
 * paint it: a rounded rectangle [start] px in from the content's left edge and [end] px in from its
 * right edge, the content being as wide as the wider of the panel and its widest row. It scrolls
 * with the content, so the panel's edge cuts it square on the side where the content goes on.
 *
 * E.g. a 300 px panel, content 500, start 9, end 12: unscrolled the band runs from 9 (rounded) past
 * the panel's right edge (cut square); scrolled to the end (x = 200) it runs from -191 (cut square)
 * to 288 (rounded). Content that fits: 9 to 288, both rounded. Draw it in a clipped row.
 */
fun DrawScope.drawRowBand(
  color: Color,
  scroll: SidewaysScroll,
  start: Float,
  end: Float,
  radius: Float,
) {
  val left = start - scroll.x
  val right = size.width + scroll.max - end - scroll.x
  drawRoundRect(
    color,
    topLeft = Offset(left, 0f),
    size = Size(right - left, size.height),
    cornerRadius = CornerRadius(radius, radius),
  )
}

/**
 * How much more one axis must have moved than the other, when a drag passes the touch slop, for the
 * drag to count as along that axis only: 2 is about 27° either side of it. The phone's page swiper
 * uses the same rule for a sideways swipe (ClientScreen).
 */
internal const val ONE_AXIS_RATIO = 2f

/**
 * Which axes a touch drag moves, from how far it [travelled] by the time it passed the touch slop:
 * (1, 0) sideways only, (0, 1) up and down only, (1, 1) both. A drag of (30, 4) stays sideways, (4,
 * 30) stays vertical and (20, 15) moves both ways at once.
 */
internal fun pannedAxes(travelled: Offset): Offset {
  val x = abs(travelled.x)
  val y = abs(travelled.y)
  return when {
    x >= ONE_AXIS_RATIO * y -> Offset(1f, 0f)
    y >= ONE_AXIS_RATIO * x -> Offset(0f, 1f)
    else -> Offset(1f, 1f)
  }
}

/**
 * A finger drag that scrolls [lines] (the list's up and down) and [sideways] together, so a
 * diagonal drag moves diagonally; the drag keeps to one axis when it starts close to it
 * ([pannedAxes]). Each scroller on its own takes the whole drag once the finger has moved past the
 * slop along its axis, so the two of them could only move one way at a time. Touch only: the mouse
 * wheel and the trackpad keep the scrollers' own handling. Placed before the list's own scrollers
 * (the initial pass), it takes the drag from them; a drag the page swiper took first is left alone.
 */
@Composable
internal fun Modifier.touchPan(lines: ScrollableState, sideways: SidewaysScroll): Modifier {
  val fling = ScrollableDefaults.flingBehavior()
  val scope = rememberCoroutineScope()
  return pointerInput(lines, sideways, fling) {
    val maxSpeed = viewConfiguration.maximumFlingVelocity
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (down.type != PointerType.Touch) return@awaitEachGesture
      var travelled = Offset.Zero
      var axes: Offset? = null
      val velocity = VelocityTracker()
      // Each axis scrolls inside its own scroll { }, as the scrollers do themselves: a new drag
      // stops a fling, and isScrollInProgress is true while the finger moves.
      val deltas = listOf(lines, sideways.scrollable).map { Channel<Float>(Channel.UNLIMITED) }
      val releaseSpeeds = FloatArray(2)
      try {
        while (true) {
          val change =
            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
              ?: break
          if (!change.pressed) break
          if (axes == null) {
            if (change.isConsumed) return@awaitEachGesture
            travelled += change.positionChange()
            if (travelled.getDistance() < viewConfiguration.touchSlop) continue
            axes = pannedAxes(travelled)
            listOf(lines, sideways.scrollable).forEachIndexed { index, state ->
              if ((if (index == 0) axes.y else axes.x) == 0f) return@forEachIndexed
              scope.launch {
                state.scroll(MutatePriority.UserInput) {
                  for (delta in deltas[index]) scrollBy(delta)
                  with(fling) { performFling(releaseSpeeds[index]) }
                }
              }
            }
          }
          // Read before consume(): a consumed change reports no movement.
          val delta = change.positionChange()
          change.consume()
          velocity.addPosition(change.uptimeMillis, change.position)
          // The content moves against the finger: up shows later lines, left shows the line's end.
          deltas[0].trySend(-delta.y * axes.y)
          deltas[1].trySend(-delta.x * axes.x)
        }
        if (axes != null) {
          val speed = velocity.calculateVelocity()
          releaseSpeeds[0] = (-speed.y * axes.y).coerceIn(-maxSpeed, maxSpeed)
          releaseSpeeds[1] = (-speed.x * axes.x).coerceIn(-maxSpeed, maxSpeed)
        }
      } finally {
        deltas.forEach { it.close() }
      }
    }
  }
}
