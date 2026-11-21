package intellijandroid.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.terminal.TerminalKeyInput
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.terminal.TerminalKeyBar
import intellijandroid.client.ui.theme.LocalClientFonts
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The Terminal tool window on screen, against a live backend: opens it from its bar button, runs
 * `ls -la` with colors, and writes PNGs to `DRIVER_OUT` (default `build/driver`). Skipped unless
 * `DRIVER_LINK=<join link>` is set. `DRIVER_WIDTH`/`DRIVER_HEIGHT`/`DRIVER_DENSITY` set the screen
 * (default 1200 × 800 at 1; a phone is 822 × 1720 at 2).
 */
@OptIn(ExperimentalTestApi::class)
class TerminalUiTest {
  @Test
  fun terminalToolWindow() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val width = System.getenv("DRIVER_WIDTH")?.toInt() ?: 1200
    val height = System.getenv("DRIVER_HEIGHT")?.toInt() ?: 800
    val scale = System.getenv("DRIVER_DENSITY")?.toFloat() ?: 1f
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    try {
      runDesktopComposeUiTest(width = width, height = height) {
        val density = Density(scale)
        val icons = DesktopResources.JarIcons(clientLib, density)
        setContent {
          CompositionLocalProvider(
            LocalClientFonts provides fonts,
            LocalIcons provides icons,
            LocalDensity provides density,
          ) {
            val state by session.state.collectAsState()
            ClientScreen(state, session)
          }
        }
        fun shot(name: String) {
          ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
        }
        fun has(description: String) =
          onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        fun tab() = session.state.value.terminals.firstOrNull()

        waitUntil(timeoutMillis = 60_000) { has("Terminal") }
        shot("terminal-0-before-open")
        onNodeWithContentDescription("Terminal").performClick()
        waitUntil(timeoutMillis = 30_000) { tab() != null }
        // The login shell prints a banner and a prompt, and drops what is typed before they end.
        var last = emptyList<String>()
        var since = System.currentTimeMillis()
        waitUntil(timeoutMillis = 60_000) {
          val lines = tab()?.screen?.lines?.map { it.text }.orEmpty()
          if (lines != last || lines.none { it.isNotBlank() }) {
            last = lines
            since = System.currentTimeMillis()
          }
          System.currentTimeMillis() - since > 2_000
        }
        waitForIdle()
        shot("terminal-1-prompt")

        val id = requireNotNull(tab()).id
        session.terminalText(
          id,
          "cd ${System.getProperty("user.home")} && CLICOLOR_FORCE=1 ls -laG\r",
        )
        waitUntil(timeoutMillis = 30_000) {
          tab()?.screen?.lines?.any { line ->
            line.runs.any { it.foreground.toString() != "Default" }
          } == true
        }
        Thread.sleep(1_000)
        waitForIdle()
        shot("terminal-2-ls-colors")
        out
          .resolve("terminal-screen.txt")
          .writeText(requireNotNull(tab()).screen.lines.joinToString("\n") { it.text })

        // A long output, then a drag down over the terminal: the view scrolls up into the history.
        session.terminalText(id, "seq 1 150\r")
        waitUntil(timeoutMillis = 30_000) {
          tab()?.screen?.lines?.any { it.text == "150" } == true
        }
        waitForIdle()
        shot("terminal-3-seq-end")
        onRoot().performTouchInput {
          swipe(
            start = androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.78f),
            end = androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.97f),
            durationMillis = 300,
          )
        }
        waitForIdle()
        shot("terminal-4-scrolled-up")
        // Scrolled all the way up: the 6 dp of space above the first history line shows.
        repeat(40) {
          onRoot().performTouchInput {
            swipe(
              start = androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.78f),
              end = androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.97f),
              durationMillis = 100,
            )
          }
        }
        waitForIdle()
        shot("terminal-7-scrolled-to-top")
        // A mouse drag selects text.
        onRoot().performMouseInput {
          moveTo(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.85f))
          press()
          moveTo(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.9f))
          release()
        }
        waitForIdle()
        shot("terminal-5-selection")

        // What `exit` does to the tab (spec 3.4): does the backend close the session?
        session.terminalText(id, "exit\r")
        Thread.sleep(5_000)
        println("RESULT: after exit the client has ${session.state.value.terminals.size} tabs")
        waitForIdle()
        shot("terminal-6-after-exit")
      }
    } finally {
      session.stop()
    }
  }

  /** The keyboard bar by itself, Ctrl on (no soft keyboard exists on the desktop to show it). */
  @Test
  fun keyboardBar() {
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    runDesktopComposeUiTest(width = 411, height = 32) {
      val density = Density(2f)
      val icons = DesktopResources.JarIcons(clientLib, density)
      val keys = TerminalKeyInput(sendText = {}, sendKey = { _, _ -> })
      keys.toggleCtrl()
      setContent {
        CompositionLocalProvider(
          LocalClientFonts provides fonts,
          LocalIcons provides icons,
          LocalDensity provides density,
        ) {
          TerminalKeyBar(keys)
        }
      }
      waitForIdle()
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("keyboard-bar.png"))
    }
  }
}
