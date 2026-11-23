package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.lux.PngDecoder
import io.github.safeuq.ideate.client.ui.lux.pngDecoder
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Backend popups with no dialog open (a tooltip, a menu, the Git branches list) against a live
 * backend, as a window of their own (docs/lux-protocol.md, addendum 2026-11-21). Skipped unless
 * `DRIVER_LINK` is set; PNGs go to `DRIVER_OUT`. `smoke` for the first, a git project for the
 * others. The backend has one-time join links: start it again for each test.
 *
 * Rerun is at (431, 577) in the 1200 x 800 window.
 */
@OptIn(ExperimentalTestApi::class)
class LuxPopupLiveTest {
  private fun drive(
    body: androidx.compose.ui.test.ComposeUiTest.(ClientSession, (String) -> Unit) -> Unit,
  ) {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    pngDecoder = PngDecoder { bytes ->
      runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    try {
      runDesktopComposeUiTest(width = 1200, height = 800, testTimeout = 5.minutes) {
        val icons = DesktopResources.JarIcons(clientLib, Density(1f))
        setContent {
          CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
            val state by session.state.collectAsState()
            ClientScreen(state, session)
          }
        }
        val shot = { name: String ->
          waitForIdle()
          val roots = onAllNodes(isRoot())
          repeat(roots.fetchSemanticsNodes().size) { index ->
            val file = if (index == 0) "$name.png" else "$name-$index.png"
            ImageIO.write(roots[index].captureToImage().toAwtImage(), "png", out.resolve(file))
          }
        }
        try {
          body(session, shot)
        } finally {
          out.resolve("popup-log.txt").writeText(session.state.value.log.joinToString("\n"))
        }
      }
    } finally {
      session.stop()
    }
  }

  private fun ClientSession.popupIds() = state.value.luxDialogs.filter { it.popup }.map { it.id }

  // The tooltip (hover Rerun) must open once and stay: that check runs in a real window
  // (WindowPopupHost); this in-window harness blocks hover under a focusable popup.

  /**
   * A list popup with no dialog open (the Git branches list, on a git project) still closes on a
   * click outside it. The Run toolbar's More menu is not a Lux popup (it is a backend list popup).
   */
  @Test
  fun branchesCloseOnOutsideClick() = drive { session, shot ->
    waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
    Thread.sleep(15_000)
    session.runProjectAction("Git.Branches")
    waitUntil(timeoutMillis = 30_000) { session.popupIds().isNotEmpty() }
    Thread.sleep(1_500)
    shot("popup-fix-menu")
    onAllNodes(isRoot())[0].performMouseInput { click(Offset(1100f, 700f)) }
    waitUntil(timeoutMillis = 15_000) { session.popupIds().isEmpty() }
  }

  /** The Git branches popup is focusable, so typed keys reach it and its list filters. */
  @Test
  fun branchesSearchTakesText() = drive { session, shot ->
    waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
    Thread.sleep(15_000)
    session.runProjectAction("Git.Branches")
    waitUntil(timeoutMillis = 30_000) { session.popupIds().isNotEmpty() }
    shot("popup-fix-branches-before")
    val popupRoot = {
      val roots = onAllNodes(isRoot())
      roots[roots.fetchSemanticsNodes().size - 1]
    }
    val before = popupRoot().captureToImage().toAwtImage()
    popupRoot().performKeyInput { for (key in listOf(Key.T, Key.W, Key.O)) pressKey(key) }
    // "two" leaves feature-two: the list below the search field is mostly empty (the popup keeps
    // its size), so a large part of the popup's pixels change. A caret blink changes a few.
    waitUntil(timeoutMillis = 15_000) {
      val after = popupRoot().captureToImage().toAwtImage()
      var changed = 0
      for (x in 0 until minOf(before.width, after.width)) for (y in
        0 until minOf(before.height, after.height)) if (before.getRGB(x, y) != after.getRGB(x, y))
        changed++
      changed > 5_000
    }
    shot("popup-fix-branches-after")
  }
}
