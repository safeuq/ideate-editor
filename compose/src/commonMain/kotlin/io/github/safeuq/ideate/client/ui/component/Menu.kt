package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.MenuEntry
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.MenuSeparator
import io.github.safeuq.ideate.client.ui.SubMenu
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label
import kotlinx.coroutines.delay

/**
 * IntelliJ's popup menu (`IdeaPopupMenuUI`; rows as `PopupListElementRenderer` draws them in
 * platform/platform-impl/src/com/intellij/ui/popup/list/), Jewel's `Menu`: [entries] in a popup
 * frame. Submenus open in place with a back row on top, or with [cascade] beside their parent as
 * IntelliJ's `ListPopupImpl.showNextStepPopup` does (a window with room for it; the main menu
 * plan's addendum of 2026-11-26). [entries] returns null while the items are still loading
 * ("Loading…"). Rows are IntelliJ's 24 dp whether a mouse or a finger opened the menu: Ideate keeps
 * IntelliJ's sizes and adds no padding for touch. [close] runs after an item is chosen.
 * Measurements: docs/intellij-look.md, section 13.
 */
@Composable
fun Menu(
  entries: () -> List<MenuEntry>?,
  rowHeight: Dp = Menus.rowHeight,
  cascade: Boolean = false,
  close: () -> Unit,
) {
  val fonts = LocalClientFonts.current
  // Open submenus, innermost last.
  var path by remember { mutableStateOf(emptyList<SubMenu>()) }
  // With [cascade]: the submenu open beside this menu (one at a time, as WizardPopup's myChild).
  var child by remember { mutableStateOf<SubMenu?>(null) }
  val text = fonts.label()
  Column(
    Modifier.width(IntrinsicSize.Max)
      .widthIn(min = Menus.minWidth, max = Menus.maxWidth)
      .heightIn(max = Menus.maxHeight)
      .popupContainer(Menus.radius)
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
            // A toggle that is on shows IntelliJ's check mark in the icon column (ActionMenuItem
            // with Toggleable); one that is off keeps the column empty.
            entry.iconPath ?: CHECKED_ICON.takeIf { entry.checked == true },
            false,
            rowHeight,
            text,
            // Resting on a plain item closes the open submenu, as ListPopupImpl's disposeChildren.
            onHoverRest = if (cascade) ({ child = null }) else null,
          ) {
            close()
            entry.onClick()
          }
        is SubMenu ->
          if (cascade)
            // The Box spans the menu's width at the row's top, the child's anchor.
            Box(Modifier.fillMaxWidth()) {
              MenuRow(
                entry.label,
                null,
                entry.enabled,
                null,
                true,
                rowHeight,
                text,
                selected = child == entry,
                onHoverRest = { child = entry },
              ) {
                child = entry
              }
              if (child == entry) {
                val position =
                  with(LocalDensity.current) {
                    SubmenuPosition(STEP_X_PADDING.roundToPx(), Menus.verticalInset.roundToPx())
                  }
                // Escape in the submenu, or a click on this menu, closes the submenu only.
                AppPopup(position, onDismissRequest = { child = null }) {
                  Menu({ entry.entries }, rowHeight, cascade = true, close = close)
                }
              }
            }
          else
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
  rowHeight: Dp,
  text: TextStyle,
  selected: Boolean = false,
  onHoverRest: (() -> Unit)? = null,
  onClick: () -> Unit,
) {
  val icons = LocalIcons.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  // ListPopupImpl's submenu timer: the pointer resting 250 ms on a row.
  // ponytail: no "moving towards the submenu" test (ListPopupImpl.isMovingToSubmenu); the 250 ms
  // delay already covers a straight move into the submenu. Add it if a diagonal move closes it.
  if (onHoverRest != null)
    LaunchedEffect(hovered) {
      if (hovered) {
        delay(SUBMENU_HOVER_DELAY_MILLIS)
        onHoverRest()
      }
    }
  Box(
    Modifier.fillMaxWidth()
      .height(rowHeight)
      .padding(horizontal = Menus.selectionInset, vertical = 1.dp)
      .hoverable(interaction, enabled)
      .then(
        if ((hovered || selected) && enabled)
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
        swingText(label, colored = enabled),
        style = text.copy(color = if (enabled) Islands.text else Menus.disabled),
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

internal object Menus {
  val rowHeight = 24.dp // PopupMenu rows, the same for mouse and touch
  val border
    get() = Islands.popupBorder // Separator.separatorColor = popup-border

  val selection
    get() = Islands.selectionBackground // MenuItem.selectionBackground

  val shortcut
    get() = Islands.textSecondary // MenuItem.acceleratorForeground = text-secondary

  val disabled
    get() = Islands.textDisabled // MenuItem.disabledForeground

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

private const val CHECKED_ICON = "expui/actions/checked.svg"

private const val SUBMENU_HOVER_DELAY_MILLIS = 250L

private val STEP_X_PADDING = 2.dp // WizardPopup.STEP_X_PADDING

/**
 * A submenu beside the row that opened it ([anchorBounds]: the row across the whole menu), as
 * `ListPopupImpl.showNextStepPopup` and `WizardPopup.show` place it: right of the menu, overlapping
 * it by [stepPadding] px, top raised by [verticalInset] so its first row is level with the row;
 * with no room on the right, left of the menu; then kept inside the window ([keep]).
 */
internal class SubmenuPosition(private val stepPadding: Int, private val verticalInset: Int) :
  PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    var x = anchorBounds.right - stepPadding
    if (x + popupContentSize.width > windowSize.width)
      x = anchorBounds.left - popupContentSize.width + stepPadding
    return windowSize.keep(x, anchorBounds.top - verticalInset, popupContentSize)
  }
}
