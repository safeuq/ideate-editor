package intellijandroid.client.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.ui.component.Menu
import kotlin.math.roundToInt

/** One line of a context menu: plain data, so items can also come from the backend later. */
sealed interface MenuEntry

/**
 * An action. [shortcut]: IntelliJ's text for it, e.g. `⌘C`. [enabled] false shows it greyed out
 * (IntelliJ's disabled text), for actions IDEate can't do yet. [iconPath]: in the icons jar.
 */
data class MenuItem(
  val label: String,
  val shortcut: String? = null,
  val enabled: Boolean = true,
  val iconPath: String? = null,
  /** The backend's action id for items from the backend, e.g. `$Copy`; null for local items. */
  val id: String? = null,
  val onClick: () -> Unit = {},
) : MenuEntry

/** A submenu; it opens in place of the menu, with a row to go back. */
data class SubMenu(val label: String, val entries: List<MenuEntry>, val enabled: Boolean = true) :
  MenuEntry

data object MenuSeparator : MenuEntry

/**
 * [content] with a context menu: a right click opens it at the pointer (desktop, Android with a
 * mouse), a long press with a finger opens it in the middle of the screen. On a phone (smallest
 * screen side under 600 dp, Android's tablet line) a right click opens it in the middle too, as
 * there is little room.
 *
 * [entries] is read while the menu shows, so a caller backed by Compose state can fill it in later;
 * null shows "Loading…". [onOpen] gets the press position in [content]'s coordinates first (the
 * editor moves its caret there, as IntelliJ does on a right click).
 */
@Composable
fun ContextMenuArea(
  entries: () -> List<MenuEntry>?,
  modifier: Modifier = Modifier,
  onOpen: (Offset) -> Unit = {},
  content: @Composable BoxScope.() -> Unit,
) {
  var open by remember { mutableStateOf<OpenMenu?>(null) }
  var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
  val density = LocalDensity.current

  Box(
    modifier
      .onGloballyPositioned { coordinates = it }
      .pointerInput(Unit) {
        fun show(position: Offset, byTouch: Boolean) {
          onOpen(position)
          val window = coordinates?.localToWindow(position) ?: position
          open = OpenMenu(window, byTouch)
        }
        awaitEachGesture {
          // The Initial pass sees the press before [content] does, so a menu gesture can be kept
          // from turning into a tap or a drag there.
          val down =
            awaitPointerEvent(PointerEventPass.Initial).let { event ->
              event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
            }
          if (currentEvent.buttons.isSecondaryPressed) {
            down.consume()
            show(down.position, byTouch = false)
            consumeUntilUp()
            return@awaitEachGesture
          }
          if (down.type != PointerType.Touch) return@awaitEachGesture
          // A long press: held for the long-press time without moving past the touch slop.
          var released = false
          val timedOut =
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
              while (!released) {
                val change =
                  awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                    it.id == down.id
                  }
                released =
                  change == null ||
                    !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
              }
            } == null
          if (!timedOut) return@awaitEachGesture
          show(down.position, byTouch = true)
          consumeUntilUp()
        }
      },
  ) {
    content()
    open?.let { menu ->
      val windowSize = coordinates?.let { windowSizeOf(it) } ?: IntSize.Zero
      val phone =
        with(density) { minOf(windowSize.width, windowSize.height).toDp() } < PHONE_SMALLEST_SIDE
      val centered = menu.byTouch || phone
      Popup(
        popupPositionProvider = MenuPosition(menu.anchor, centered),
        onDismissRequest = { open = null },
        properties = PopupProperties(focusable = true),
      ) {
        Menu(entries, rowHeight = if (menu.byTouch) TOUCH_ROW else POINTER_ROW) { open = null }
      }
    }
  }
}

private data class OpenMenu(val anchor: Offset, val byTouch: Boolean)

private val PHONE_SMALLEST_SIDE = 600.dp
// IntelliJ's menu rows are 24 high; a finger needs more.
private val POINTER_ROW = 24.dp
private val TOUCH_ROW = 40.dp

private fun windowSizeOf(coordinates: LayoutCoordinates): IntSize {
  var root = coordinates
  while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
  return root.size
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilUp() {
  while (true) {
    val event = awaitPointerEvent(PointerEventPass.Initial)
    event.changes.forEach { it.consume() }
    if (event.changes.none { it.pressed }) return
  }
}

/** At the pointer, moved to stay inside the window; or in the middle of the window. */
private class MenuPosition(private val anchor: Offset, private val centered: Boolean) :
  PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    if (centered)
      return IntOffset(
        (windowSize.width - popupContentSize.width) / 2,
        (windowSize.height - popupContentSize.height) / 2,
      )
    var x = anchor.x.roundToInt()
    var y = anchor.y.roundToInt()
    // Like a desktop menu: open to the left or upwards when there is no room right or below.
    if (x + popupContentSize.width > windowSize.width) x -= popupContentSize.width
    if (y + popupContentSize.height > windowSize.height) y -= popupContentSize.height
    return IntOffset(
      x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
      y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
    )
  }
}

/**
 * A shortcut as IntelliJ's default keymap shows it: macOS symbols on a Mac (`⌘C`), `Ctrl+C`
 * elsewhere. [mac] and [other] are the two spellings, e.g. `shortcut("⌘C", "Ctrl+C")`.
 */
fun shortcut(mac: String, other: String): String = if (IS_MAC) mac else other

internal val IS_MAC = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
