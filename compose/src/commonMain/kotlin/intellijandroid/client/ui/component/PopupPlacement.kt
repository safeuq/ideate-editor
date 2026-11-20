package intellijandroid.client.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Where the screen is, in the window's px, so a popup can go past the window's edge as IntelliJ's
 * do (docs/intellij-look.md section 24). Example: a window at (200, 100) points on a 1512 × 982
 * point screen at 2x, menu bar 33 points: `IntRect(-400, -134, 2624, 1764)`. The desktop provides
 * it and starts with `compose.layers.type=WINDOW`, so each popup is its own window. Null (Android,
 * tests): popups stay inside the window, as before.
 */
val LocalScreenBounds = staticCompositionLocalOf<() -> IntRect?> { { null } }

/**
 * [PopupProperties] for a popup placed by [PopupPlacement]. Compose's own clamp keeps a popup
 * inside the window, so it is off when the screen is the limit.
 */
fun popupProperties(screen: () -> IntRect?, focusable: Boolean = true) =
  PopupProperties(focusable = focusable, clippingEnabled = screen() == null)

/**
 * The area a popup must stay in: the screen when known, else the window. Example: window 1200 ×
 * 800, no screen → `IntRect(0, 0, 1200, 800)`.
 */
internal fun limitOf(screen: () -> IntRect?, windowSize: IntSize): IntRect =
  screen() ?: IntRect(0, 0, windowSize.width, windowSize.height)

/** [x], [y] moved so a popup of [size] stays inside [limit] (its top left wins if it can't fit). */
internal fun IntRect.keep(x: Int, y: Int, size: IntSize) =
  IntOffset(
    x.coerceIn(left, maxOf(left, right - size.width)),
    y.coerceIn(top, maxOf(top, bottom - size.height)),
  )

/**
 * A popup at [offset] from its parent's top left (e.g. under the caret line). With no room below on
 * the screen it goes above, [flipBy] px higher than its own top (the caret line's height), as
 * IntelliJ's editor popups do.
 */
class PopupPlacement(
  private val offset: IntOffset,
  private val flipBy: Int,
  private val screen: () -> IntRect?,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val limit = limitOf(screen, windowSize)
    val x = anchorBounds.left + offset.x
    var y = anchorBounds.top + offset.y
    if (y + popupContentSize.height > limit.bottom) y -= flipBy + popupContentSize.height
    return limit.keep(x, y, popupContentSize)
  }
}
