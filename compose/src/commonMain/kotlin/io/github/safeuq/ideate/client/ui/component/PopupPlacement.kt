package io.github.safeuq.ideate.client.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Shows the app's menus and popups (docs/intellij-look.md section 24). The default is Compose's
 * [Popup], inside the window (Android, tests). The desktop gives each popup its own window, as
 * IntelliJ does, so a menu can go past the window's edge; [PopupPositionProvider]s then get the
 * screen as their `windowSize`, so "keep inside the window" means "keep inside the screen" there.
 */
interface PopupHost {
  @Composable
  fun Show(
    position: PopupPositionProvider,
    onDismissRequest: (() -> Unit)?,
    focusable: Boolean,
    content: @Composable () -> Unit,
  )
}

/** Compose's own popup, drawn inside the window. */
object WindowLayerPopupHost : PopupHost {
  @Composable
  override fun Show(
    position: PopupPositionProvider,
    onDismissRequest: (() -> Unit)?,
    focusable: Boolean,
    content: @Composable () -> Unit,
  ) = Popup(position, onDismissRequest, PopupProperties(focusable = focusable), content)
}

val LocalPopupHost = staticCompositionLocalOf<PopupHost> { WindowLayerPopupHost }

/** A popup through [LocalPopupHost]; use it instead of [Popup] for the app's menus and popups. */
@Composable
fun AppPopup(
  position: PopupPositionProvider,
  onDismissRequest: (() -> Unit)? = null,
  focusable: Boolean = true,
  content: @Composable () -> Unit,
) = LocalPopupHost.current.Show(position, onDismissRequest, focusable, content)

/**
 * [x], [y] moved so a popup of [size] stays inside this area (its top left wins if it can't fit).
 */
internal fun IntSize.keep(x: Int, y: Int, size: IntSize) =
  IntOffset(
    x.coerceIn(0, maxOf(0, width - size.width)),
    y.coerceIn(0, maxOf(0, height - size.height)),
  )

/**
 * A popup at [offset] from its parent's top left (e.g. under the caret line). With no room below it
 * goes above, [flipBy] px higher than its own top (the caret line's height), as IntelliJ's editor
 * popups do.
 */
class PopupPlacement(private val offset: IntOffset, private val flipBy: Int) :
  PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = anchorBounds.left + offset.x
    var y = anchorBounds.top + offset.y
    if (y + popupContentSize.height > windowSize.height) y -= flipBy + popupContentSize.height
    return windowSize.keep(x, y, popupContentSize)
  }
}
