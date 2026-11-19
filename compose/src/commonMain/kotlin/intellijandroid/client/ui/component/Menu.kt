package intellijandroid.client.ui.component

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.MenuEntry
import intellijandroid.client.ui.MenuItem
import intellijandroid.client.ui.MenuSeparator
import intellijandroid.client.ui.SubMenu
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * IntelliJ's popup menu (`IdeaPopupMenuUI`; rows as `PopupListElementRenderer` draws them in
 * platform/platform-impl/src/com/intellij/ui/popup/list/), Jewel's `Menu`: [entries] in a popup
 * frame, submenus opening in place with a back row on top. [entries] returns null while the items
 * are still loading ("Loading…"). Rows are IntelliJ's 24 dp whether a mouse or a finger opened the
 * menu: IDEate keeps IntelliJ's sizes and adds no padding for touch. [close] runs after an item is
 * chosen. Measurements: docs/intellij-look.md, section 13.
 */
@Composable
fun Menu(
  entries: () -> List<MenuEntry>?,
  rowHeight: Dp = Menus.rowHeight,
  close: () -> Unit,
) {
  val fonts = LocalClientFonts.current
  // Open submenus, innermost last.
  var path by remember { mutableStateOf(emptyList<SubMenu>()) }
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
  rowHeight: Dp,
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

internal object Menus {
  val rowHeight = 24.dp // PopupMenu rows, the same for mouse and touch
  val border = IslandsDark.popupBorder // Separator.separatorColor = popup-border
  val selection = IslandsDark.selectionBackground // MenuItem.selectionBackground
  val shortcut = IslandsDark.textSecondary // MenuItem.acceleratorForeground = text-secondary
  val disabled = IslandsDark.textDisabled // MenuItem.disabledForeground
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
