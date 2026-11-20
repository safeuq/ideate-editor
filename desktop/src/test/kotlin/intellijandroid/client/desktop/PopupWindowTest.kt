package intellijandroid.client.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import intellijandroid.client.screenInWindow
import intellijandroid.client.ui.component.LocalScreenBounds
import intellijandroid.client.ui.component.PopupPlacement
import intellijandroid.client.ui.component.popupProperties
import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Popups are their own windows and go past the main window's edge, as the desktop app sets them up
 * (docs/intellij-look.md section 24). Compose's test scene draws popups on one canvas, so this
 * opens a real window. Example: a 400 × 300 window at (100, 100) with a 200 × 200 popup under its
 * bottom edge: the popup window ends near y 600, past the main window's 400.
 */
class PopupWindowTest {
  @Test
  fun popupGoesPastTheWindowBottom() {
    if (GraphicsEnvironment.isHeadless()) return
    System.setProperty("compose.layers.type", "WINDOW")
    lateinit var frame: ComposeWindow
    // Opened once the window is on screen, as menus are: a popup in the window's first frame gets
    // a 1 × 1 window from Compose, before the main window has a size.
    val shown = mutableStateOf(false)
    SwingUtilities.invokeAndWait {
      frame = ComposeWindow()
      frame.setSize(400, 300)
      frame.setLocation(100, 100)
      frame.setContent {
        CompositionLocalProvider(LocalScreenBounds provides { screenInWindow(frame) }) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomStart).size(10.dp)) {
              val screen = LocalScreenBounds.current
              if (shown.value)
                Popup(
                  popupPositionProvider = PopupPlacement(IntOffset(0, 0), 0, screen),
                  properties = popupProperties(screen, focusable = false),
                ) {
                  Box(Modifier.size(200.dp).background(Color.Red))
                }
            }
          }
        }
      }
      frame.isVisible = true
    }
    try {
      Thread.sleep(500)
      SwingUtilities.invokeAndWait { shown.value = true }
      val popup = waitForPopup(frame)
      assertNotNull(popup, "no popup window opened")
      val popupBottom = popup.y + popup.height
      val frameBottom = frame.y + frame.height
      assertTrue(
        popupBottom > frameBottom + 100,
        "popup ${popup.bounds} should end well past the window's bottom $frameBottom",
      )
    } finally {
      SwingUtilities.invokeAndWait { frame.dispose() }
    }
  }

  /** The popup's window: a showing window other than [frame] that is at least 150 points tall. */
  private fun waitForPopup(frame: Window): Window? {
    repeat(50) {
      val found =
        Window.getWindows().firstOrNull { it !== frame && it.isShowing && it.height >= 150 }
      if (found != null) return found
      Thread.sleep(100)
    }
    return null
  }
}
