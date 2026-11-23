package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.lux.PngDecoder
import io.github.safeuq.ideate.client.ui.lux.pngDecoder
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.skia.Image

/**
 * Ideate's real desktop window (`IdeateLocals`, as `Main.kt`), driven with synthetic AWT events by
 * [ComposeWindowInput]. The window is not focusable, so the user's focus and cursor stay theirs.
 */
class WindowDriver private constructor(private val outputFolder: File) {
  lateinit var window: ComposeWindow
    private set

  private lateinit var input: ComposeWindowInput

  fun popupWindows() = input.popupWindows()

  fun move(x: Int, y: Int, inside: Window = window) = input.move(x, y, inside)

  fun click(
    x: Int,
    y: Int,
    inside: Window = window,
    button: Int = MouseEvent.BUTTON1,
    clicks: Int = 1,
  ) = input.click(x, y, inside, button, clicks)

  fun doubleClick(x: Int, y: Int, inside: Window = window) = input.doubleClick(x, y, inside)

  fun rightClick(x: Int, y: Int, inside: Window = window) = input.rightClick(x, y, inside)

  fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, inside: Window = window) =
    input.drag(fromX, fromY, toX, toY, inside)

  fun scroll(x: Int, y: Int, notches: Int, inside: Window = window) =
    input.scroll(x, y, notches, inside)

  fun type(text: String, inside: Window = window) = input.type(text, inside)

  fun press(keyCode: Int, modifiers: Int = 0, inside: Window = window) =
    input.press(keyCode, modifiers, inside)

  /** The main window and each popup window, as `<name>-0.png`, `<name>-1.png`, ... */
  fun screenshot(name: String): List<File> =
    input.screenshots().mapIndexed { index, png ->
      File(outputFolder, "$name-$index.png").also { it.writeBytes(png) }
    }

  /**
   * Closes every popup window the way a click outside does: a popup closes when its window loses
   * focus (`WindowPopupHost`). An unfocusable test window never gives a popup focus, so neither
   * Escape nor a click outside reaches it; this sends the focus-lost event itself.
   */
  fun dismissPopups() = input.onEventThread {
    for (popup in popupWindows()) {
      popup.window.dispatchEvent(WindowEvent(popup.window, WindowEvent.WINDOW_LOST_FOCUS))
    }
  }

  /** Polls every 20 ms; no fixed sleeps. */
  fun waitUntil(
    timeout: Duration = 10.seconds,
    description: String = "the condition",
    condition: () -> Boolean,
  ) {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (!condition()) {
      check(System.nanoTime() < deadline) { "Timed out after $timeout waiting for $description" }
      Thread.sleep(20)
    }
  }

  fun close() {
    input.onEventThread { window.dispose() }
  }

  companion object {
    /**
     * Opens the window as `Main.kt` does, 1200 × 800 at (40, 40), unfocusable. Pictures go to
     * `DRIVER_OUT` (default `build/driver`).
     */
    fun open(session: ClientSession): WindowDriver {
      val clientLib =
        File(
          requireNotNull(System.getProperty("jetbrains.client.lib")) {
            "System property jetbrains.client.lib is not set (the Gradle test task sets it)"
          },
        )
      val fonts = DesktopResources.fonts(clientLib)
      IslandsDark.density = DesktopResources.uiDensity()
      pngDecoder = PngDecoder { bytes ->
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
      }
      val driver =
        WindowDriver(File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() })
      SwingUtilities.invokeAndWait {
        val window = ComposeWindow()
        // Never take the user's focus.
        window.isAutoRequestFocus = false
        window.focusableWindowState = false
        window.setSize(1200, 800)
        window.setLocation(40, 40)
        window.setContent {
          IdeateLocals(window, fullScreen = false, clientLib = clientLib, fonts = fonts) {
            val state by session.state.collectAsState()
            ClientScreen(state, session)
          }
        }
        window.isVisible = true
        driver.window = window
        driver.input = ComposeWindowInput(window)
      }
      return driver
    }
  }
}
