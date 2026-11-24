package io.github.safeuq.ideate.client.desktop

import androidx.compose.ui.graphics.toArgb
import io.github.safeuq.ideate.client.ui.theme.Islands

/**
 * IntelliJ's `MacFullScreenControlsManager` (new UI, macOS): in full screen the traffic lights stay
 * in the main toolbar, and macOS's own title bar no longer slides down at the top edge of the
 * screen. JetBrains Runtime reads these properties when a window enters full screen
 * (`AWTWindow.windowDidEnterFullScreen` → `setWindowFullScreenControls`), so [configureEnable] runs
 * once, before any window opens. Other runtimes ignore them.
 *
 * Left out: IntelliJ's registry switch (always on here), distraction-free and presentation mode,
 * and re-sending the colours on a theme change (Ideate has one theme).
 */
object MacFullScreenControlsManager {
  fun configureEnable() {
    if (!System.getProperty("os.name").startsWith("Mac")) return
    System.setProperty("apple.awt.newFullScreenControls", "true")
    configureColors()
  }

  /**
   * JBR's `AWTButtonsView` fills the three button circles with this colour when the pointer isn't
   * over them, and the miniaturize circle always (a full-screen window can't be minimized). The
   * value is ARGB, as `Color.getRGB()` gives it: without the alpha byte the circles are
   * transparent. IntelliJ also sets the misspelt `newFullScreeControls` key, for older JBRs.
   */
  private fun configureColors() {
    val rgb = Islands.fullScreenControlBackground.toArgb().toString()
    System.setProperty("apple.awt.newFullScreeControls.background", rgb)
    System.setProperty("apple.awt.newFullScreenControls.background", rgb)
  }
}
