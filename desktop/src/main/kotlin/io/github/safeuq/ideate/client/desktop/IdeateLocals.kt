package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.LocalTitleBarArea
import io.github.safeuq.ideate.client.ui.component.LocalPopupHost
import io.github.safeuq.ideate.client.ui.component.LocalResizePointers
import io.github.safeuq.ideate.client.ui.component.ResizePointers
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import java.io.File

/**
 * What every Ideate window provides to its content: popups in their own windows, fonts, icons, the
 * macOS title bar and IntelliJ's splitter cursors. Shared by `Main.kt` and the tests'
 * `WindowDriver` so a test runs the same window setup as the app.
 */
@Composable
fun IdeateLocals(
  window: ComposeWindow,
  clientLib: File,
  fonts: ClientFonts,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val icons = remember(density) { DesktopResources.JarIcons(clientLib, density) }
  // On macOS the app draws in the title bar's place, as in IntelliJ: the main toolbar in the
  // editor window, an empty strip in the connections window.
  val titleBar = rememberMacTitleBar(window)
  // Menus and popups in their own windows, so they can go past this window's edge.
  val popups = remember(window) { WindowPopupHost(window) }
  CompositionLocalProvider(
    LocalPopupHost provides popups,
    LocalClientFonts provides fonts,
    LocalIcons provides icons,
    LocalTitleBarArea provides titleBar,
    // IntelliJ's Splitter cursors.
    LocalResizePointers provides
      ResizePointers(
        sideBySide =
          PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.W_RESIZE_CURSOR)),
        stacked = PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)),
      ),
    content = content,
  )
}
