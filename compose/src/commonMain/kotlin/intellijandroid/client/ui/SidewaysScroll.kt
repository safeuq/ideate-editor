package intellijandroid.client.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

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
