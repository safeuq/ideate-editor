package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
        MenuPanel(entries, rowHeight = if (menu.byTouch) TOUCH_ROW else POINTER_ROW) { open = null }
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
 * IntelliJ's popup menu, Islands Dark (docs/intellij-look.md, section 13): background `#26282C`, a
 * 1 px `#33353B` border, radius 8, 6 above and below the items; the hovered row a band inset 7 from
 * the sides with radius 4.
 */
@Composable
private fun MenuPanel(
  entries: () -> List<MenuEntry>?,
  rowHeight: androidx.compose.ui.unit.Dp,
  close: () -> Unit,
) {
  val fonts = LocalClientFonts.current
  // Open submenus, innermost last.
  var path by remember { mutableStateOf(emptyList<SubMenu>()) }
  val shape = RoundedCornerShape(Menus.radius)
  val text = TextStyle(fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)
  Column(
    Modifier.width(IntrinsicSize.Max)
      .widthIn(min = Menus.minWidth, max = Menus.maxWidth)
      .heightIn(max = Menus.maxHeight)
      .background(Menus.background, shape)
      .border(1.dp, Menus.border, shape)
      .padding(vertical = Menus.verticalInset)
      .verticalScroll(rememberScrollState()),
  ) {
    path.lastOrNull()?.let { submenu ->
      MenuRow(
        label = submenu.label,
        shortcut = null,
        enabled = true,
        iconPath = "expui/general/chevronLeft.svg",
        trailingChevron = false,
        rowHeight = rowHeight,
        text = text,
      ) {
        path = path.dropLast(1)
      }
      Separator()
    }
    val current = path.lastOrNull()?.entries ?: entries()
    if (current == null) {
      Box(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = Menus.textInset)) {
        BasicText(
          "Loading…",
          style = text.copy(color = Menus.disabled),
          modifier = Modifier.align(Alignment.CenterStart),
        )
      }
      return@Column
    }
    for (entry in current) {
      when (entry) {
        MenuSeparator -> Separator()
        is MenuItem ->
          MenuRow(
            entry.label,
            entry.shortcut,
            entry.enabled,
            entry.iconPath,
            false,
            rowHeight,
            text,
          ) {
            close()
            entry.onClick()
          }
        is SubMenu ->
          MenuRow(entry.label, null, entry.enabled, null, true, rowHeight, text) {
            path = path + entry
          }
      }
    }
  }
}

@Composable
private fun MenuRow(
  label: String,
  shortcut: String?,
  enabled: Boolean,
  iconPath: String?,
  trailingChevron: Boolean,
  rowHeight: androidx.compose.ui.unit.Dp,
  text: TextStyle,
  onClick: () -> Unit,
) {
  val icons = LocalIcons.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Box(
    Modifier.fillMaxWidth()
      .height(rowHeight)
      .padding(horizontal = Menus.selectionInset, vertical = 1.dp)
      .hoverable(interaction, enabled)
      .then(
        if (hovered && enabled)
          Modifier.background(Menus.selection, RoundedCornerShape(Menus.selectionRadius))
        else Modifier,
      )
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      ),
  ) {
    // The label takes the room the menu has (its width comes from the widest row,
    // IntrinsicSize.Max);
    // the shortcut and the submenu chevron sit at the right end.
    Row(
      Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(horizontal = Menus.itemInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // IntelliJ keeps an icon column (Menu.maxGutterIconWidth 18) so labels line up.
      Box(Modifier.width(Menus.iconColumn), contentAlignment = Alignment.CenterStart) {
        iconPath?.let(icons::painter)?.let { Image(it, null, Modifier.size(16.dp)) }
      }
      BasicText(
        label,
        style = text.copy(color = if (enabled) IslandsDark.text else Menus.disabled),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      shortcut?.let {
        BasicText(
          it,
          modifier = Modifier.padding(start = Menus.shortcutGap),
          style = text.copy(color = if (enabled) Menus.shortcut else Menus.disabled),
          maxLines = 1,
        )
      }
      if (trailingChevron)
        icons.painter("expui/general/chevronRight.svg")?.let {
          Image(it, null, Modifier.size(16.dp))
        }
    }
  }
}

@Composable
private fun Separator() {
  // PopupMenuSeparator: 9 high, a 1 px line 10 from the sides.
  Box(Modifier.fillMaxWidth().height(9.dp).padding(horizontal = Menus.separatorInset)) {
    Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.Center).background(Menus.border))
  }
}

private object Menus {
  val background = Color(0xFF26282C) // popup-bg
  val border = Color(0xFF33353B) // popup-border; also Separator.separatorColor
  val selection = Color(0xFF2E436E) // hovered row, as Gateway's selected sidebar row (not measured)
  val shortcut = Color(0xFF73767C) // text-secondary
  val disabled = Color(0xFF777777) // MenuItem.disabledForeground
  val radius = 8.dp // PopupMenu.borderCornerRadius
  val verticalInset = 6.dp // PopupMenu.borderInsets 6,1,6,1
  val selectionInset = 7.dp // PopupMenu.Selection.outerInsets 1,7
  val selectionRadius = 4.dp // PopupMenu.Selection.arc 8
  val itemInset = 6.dp // PopupMenu.Selection.innerInsets 0,6
  val textInset = 13.dp
  val iconColumn = 22.dp // 18 + a gap
  val separatorInset = 10.dp
  val shortcutGap = 24.dp
  val minWidth = 180.dp
  val maxWidth = 360.dp
  val maxHeight = 560.dp
}

/**
 * A shortcut as IntelliJ's default keymap shows it: macOS symbols on a Mac (`⌘C`), `Ctrl+C`
 * elsewhere. [mac] and [other] are the two spellings, e.g. `shortcut("⌘C", "Ctrl+C")`.
 */
fun shortcut(mac: String, other: String): String = if (IS_MAC) mac else other

private val IS_MAC = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
