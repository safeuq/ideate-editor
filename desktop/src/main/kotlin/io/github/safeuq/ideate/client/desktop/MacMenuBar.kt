package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeWindow
import io.github.safeuq.ideate.client.ui.MenuEntry
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.MenuSeparator
import io.github.safeuq.ideate.client.ui.SubMenu
import io.github.safeuq.ideate.client.ui.component.swingText
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.event.MenuEvent
import javax.swing.event.MenuListener

/**
 * The main menu in the macOS system menu bar, as IntelliJ's `MacMenuBar.kt` and
 * `MacNativeActionMenu.kt` (platform-impl `platform/ide/menu/`) fill it: one menu per top-level
 * group of [menus] (File, Edit, …), each item with its enabled state, check mark and accelerator. A
 * Swing `JMenuBar` on [window] goes to the system bar because the app runs with
 * `compose.application.configure.swing.globals=true` (`apple.laf.useScreenMenuBar`). Nothing on
 * other systems.
 *
 * [onOpen] (a refresh) is called when a menu opens and when the window gains the focus. While a
 * menu is open the bar is not rebuilt (`PeerBasedIdeMenuBarHelper.isUpdateForbidden`); the newest
 * tree is applied when it closes.
 */
@Composable
fun MacMenuBar(window: ComposeWindow, menus: List<MenuEntry>, onOpen: () -> Unit) {
  if (!System.getProperty("os.name").startsWith("Mac")) return
  val currentOnOpen by rememberUpdatedState(onOpen)
  val bar = remember(window) { MainMenuBar { currentOnOpen() } }
  DisposableEffect(window) {
    window.jMenuBar = bar.menuBar
    val focus =
      object : WindowAdapter() {
        override fun windowGainedFocus(event: WindowEvent) = currentOnOpen()
      }
    window.addWindowFocusListener(focus)
    onDispose {
      window.removeWindowFocusListener(focus)
      window.jMenuBar = null
    }
  }
  SideEffect { bar.show(menus) }
}

private class MainMenuBar(private val onOpen: () -> Unit) {
  val menuBar = JMenuBar()
  private var shown: List<MenuEntry>? = null
  /** A tree that came in while a menu was open. */
  private var waiting: List<MenuEntry>? = null
  private var open = false

  fun show(menus: List<MenuEntry>) {
    if (menus == shown) return
    if (open) {
      waiting = menus
      return
    }
    shown = menus
    menuBar.removeAll()
    for (entry in menus) if (entry is SubMenu) menuBar.add(topLevel(entry))
    menuBar.revalidate()
    menuBar.repaint()
  }

  private fun topLevel(entry: SubMenu): JMenu =
    menu(entry).apply {
      addMenuListener(
        object : MenuListener {
          override fun menuSelected(event: MenuEvent) {
            open = true
            onOpen()
          }

          override fun menuDeselected(event: MenuEvent) = closed()

          override fun menuCanceled(event: MenuEvent) = closed()
        },
      )
    }

  private fun closed() {
    open = false
    // macOS may close the menu before it hands the chosen item's action to Swing; the newest tree
    // waits a moment so the chosen item is not removed first. ponytail: a fixed 500 ms.
    javax.swing
      .Timer(APPLY_DELAY_MILLISECONDS) {
        if (open) return@Timer
        waiting?.let {
          waiting = null
          show(it)
        }
      }
      .apply { isRepeats = false }
      .start()
  }

  private fun menu(entry: SubMenu): JMenu =
    JMenu(plain(entry.label)).apply {
      isEnabled = entry.enabled
      fill(entry.entries)
    }

  private fun JMenu.fill(entries: List<MenuEntry>) {
    for (entry in entries) {
      when (entry) {
        is SubMenu -> add(menu(entry))
        is MenuItem -> add(item(entry))
        MenuSeparator -> addSeparator()
      }
    }
  }

  private fun item(entry: MenuItem): JMenuItem {
    val label = plain(entry.label)
    val item = entry.checked?.let { JCheckBoxMenuItem(label, it) } ?: JMenuItem(label)
    item.isEnabled = entry.enabled
    entry.keyStroke?.let { stroke ->
      item.accelerator = KeyStroke.getKeyStroke(stroke.keyCode, stroke.modifiers)
    }
    item.addActionListener { entry.onClick() }
    return item
  }

  private companion object {
    const val APPLY_DELAY_MILLISECONDS = 500
  }

  /** The native bar shows no HTML: `<html>` labels as their plain text. */
  private fun plain(label: String) = swingText(label).text
}
