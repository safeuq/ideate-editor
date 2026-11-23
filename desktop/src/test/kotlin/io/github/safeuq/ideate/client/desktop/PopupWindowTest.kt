package io.github.safeuq.ideate.client.desktop

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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import io.github.safeuq.ideate.client.ui.component.AppPopup
import io.github.safeuq.ideate.client.ui.component.LocalPopupHost
import io.github.safeuq.ideate.client.ui.component.PopupPlacement
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [WindowPopupHost] gives a popup its own window that can go past the main window's edge and is
 * measured against the screen, not the window (docs/intellij-look.md section 24). Compose's test
 * scene draws popups on one canvas, so this opens real windows.
 */
class PopupWindowTest {
  /** A 200 dp popup above a 400 × 300 window at (100, 400): all of it above the window's top. */
  @Test
  fun popupAboveTheWindowIsWholeAndDrawn() {
    val popup = showPopup(Rectangle(100, 400, 400, 300), Above) ?: return
    assertTrue(
      popup.bounds.y + popup.bounds.height <= 400 + 30,
      "popup ${popup.bounds} above y 400",
    )
    assertTrue(popup.bounds.height >= 195, "popup ${popup.bounds} should be 200 points tall")
    popup.drawnColor?.let { assertTrue(it.red > 200 && it.green < 60, "popup drawn $it, not red") }
  }

  /** The same popup under a 160 × 120 window: 200 points tall, not cut to the window. */
  @Test
  fun popupLargerThanTheWindowIsNotCut() {
    val popup =
      showPopup(Rectangle(100, 100, 160, 120), PopupPlacement(IntOffset(0, 0), 0)) ?: return
    assertTrue(popup.bounds.height >= 195, "popup ${popup.bounds} should be 200 points tall")
    assertTrue(popup.bounds.y >= 100 + 120 - 30, "popup ${popup.bounds} under the window")
    popup.drawnColor?.let { assertTrue(it.red > 200 && it.green < 60, "popup drawn $it, not red") }
  }

  private class Shown(val bounds: Rectangle, val drawnColor: java.awt.Color?)

  /**
   * Opens [frameBounds] with a red 200 dp popup anchored to a 10 dp box at its bottom left, placed
   * by [position]; the popup's window bounds and the colour at its centre (null when the screen
   * can't be read). Null when there is no display.
   */
  private fun showPopup(frameBounds: Rectangle, position: PopupPositionProvider): Shown? {
    if (GraphicsEnvironment.isHeadless()) return null
    lateinit var frame: ComposeWindow
    // Opened once the window is on screen, as menus are.
    val shown = mutableStateOf(false)
    SwingUtilities.invokeAndWait {
      frame = ComposeWindow()
      frame.bounds = frameBounds
      frame.setContent {
        CompositionLocalProvider(LocalPopupHost provides WindowPopupHost(frame)) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomStart).size(10.dp)) {
              if (shown.value)
                AppPopup(position, focusable = false) {
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
      Thread.sleep(300)
      val bounds = popup.bounds
      val color = runCatching {
        Robot().getPixelColor(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
      }
        .getOrNull()
      println("POPUP $bounds window ${frame.bounds} centre colour $color")
      return Shown(bounds, color)
    } finally {
      SwingUtilities.invokeAndWait { frame.dispose() }
    }
  }

  /** The popup's window: a showing window owned by [frame] that is at least 150 points tall. */
  private fun waitForPopup(frame: Window): Window? {
    repeat(50) {
      val found = frame.ownedWindows.firstOrNull { it.isShowing && it.height >= 150 }
      if (found != null) return found
      Thread.sleep(100)
    }
    return null
  }

  /** Right above the anchor's top edge. */
  private object Above : PopupPositionProvider {
    override fun calculatePosition(
      anchorBounds: IntRect,
      windowSize: IntSize,
      layoutDirection: LayoutDirection,
      popupContentSize: IntSize,
    ) = IntOffset(anchorBounds.left, anchorBounds.top - 300 * 2 - popupContentSize.height)
  }
}
