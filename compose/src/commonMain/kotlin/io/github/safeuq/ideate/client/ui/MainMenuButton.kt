package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.component.IconButton
import io.github.safeuq.ideate.client.ui.theme.Islands

/**
 * IntelliJ's main menu button (`MainMenuButton`, platform-impl
 * `openapi/wm/impl/customFrameDecorations/header/toolbar/MainMenuButton.kt`), shown on Windows and
 * Linux first in the main toolbar: the toolbar's icon button with
 * `AllIcons.General.WindowsMenu_20x20` at 20 dp (`burgerMenuButtonIconSize`). A click opens [menus]
 * in a popup under the button, as `MainMenuButton.showPopup` does: File, Edit, … as rows, each
 * submenu opening in place, at least 180 dp wide (`menuPopupMinWidth`, which is `Menus.minWidth`).
 * [open] and [onOpenChange] are held by the caller so a shortcut (Alt+\) can open it too.
 */
@Composable
fun MainMenuButton(
  menus: List<MenuEntry>,
  open: Boolean,
  onOpenChange: (Boolean) -> Unit,
  control: Modifier,
) {
  Box {
    IconButton(
      "expui/general/windowsMenu@20x20.svg",
      "Main Menu",
      onClick = { onOpenChange(!open) },
      modifier = control,
      size = Islands.toolbarButton,
      iconSize = BURGER_ICON_SIZE,
    )
    if (open)
      DropDownMenu(
        menus.ifEmpty { listOf(MenuItem("Loading…", enabled = false)) },
        beside = false,
      ) {
        onOpenChange(false)
      }
  }
}

/** `JBUI.CurrentTheme.Toolbar.burgerMenuButtonIconSize()`. */
private val BURGER_ICON_SIZE = 20.dp
