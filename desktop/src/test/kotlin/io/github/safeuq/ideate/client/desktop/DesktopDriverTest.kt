package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Drives the real IDE screen against a live backend inside this JVM, with Compose's UI test layer:
 * nodes are found by their text or content description, clicked through Compose's own input, and
 * rendered to PNG. No window, no OS accessibility, no pixel clicks. Skipped unless
 * `DRIVER_LINK=<join link>` is set; PNGs and the semantics tree go to `DRIVER_OUT` (default
 * `build/driver`).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopDriverTest {
  @Test
  fun gradleToolWindowAndToolbar() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    try {
      runDesktopComposeUiTest(width = 1200, height = 800) {
        val icons = DesktopResources.JarIcons(clientLib, Density(1f))
        setContent {
          CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
            val state by session.state.collectAsState()
            ClientScreen(state, session)
          }
        }
        fun shot(name: String, node: SemanticsNodeInteraction = onRoot()) {
          ImageIO.write(node.captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
        }
        fun waitFor(description: String) =
          waitUntil(timeoutMillis = 60_000) {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
          }

        // The bar's Gradle button: a stripe button whose icon says "Gradle".
        waitFor("Gradle")
        shot("1-connected")
        onNodeWithContentDescription("Gradle").performClick()
        // The toolbar's buttons are named after their actions.
        waitFor("Expand All")
        shot("2-gradle")
        onNodeWithContentDescription("Expand All").performClick()
        waitUntil(timeoutMillis = 30_000) {
          onAllNodesWithContentDescription("Expand All").fetchSemanticsNodes().isNotEmpty() &&
            runCatching { onRoot().printToString().contains("assemble") }.getOrDefault(false)
        }
        shot("3-expanded")
        out.resolve("semantics.txt").writeText(onRoot().printToString())
      }
    } finally {
      session.stop()
    }
  }

  /**
   * The status bar (docs/status-bar.md) with `DRIVER_FILE=<file on the backend>` open: its
   * breadcrumbs and widgets, a breadcrumb's list, and a Gradle sync's task with the Processes
   * popup. `DRIVER_WIDTH`/`DRIVER_HEIGHT` (default 1200 × 800) set the screen; 411 × 860 is a phone
   * held upright.
   */
  @Test
  fun statusBar() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val width = System.getenv("DRIVER_WIDTH")?.toInt() ?: 1200
    val height = System.getenv("DRIVER_HEIGHT")?.toInt() ?: 800
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    val session =
      ClientSession(
        JoinLink.parse(requireNotNull(link)),
        initialFile = System.getenv("DRIVER_FILE"),
      )
    session.start()
    try {
      runDesktopComposeUiTest(width = width, height = height) {
        val icons = DesktopResources.JarIcons(clientLib, Density(1f))
        setContent {
          CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
            val state by session.state.collectAsState()
            ClientScreen(state, session)
          }
        }
        // The screen, then each open popup (its own root): name.png, name-1.png, …
        fun shot(name: String) {
          val roots = onAllNodes(isRoot())
          repeat(roots.fetchSemanticsNodes().size) { index ->
            val file = if (index == 0) "$name.png" else "$name-$index.png"
            ImageIO.write(roots[index].captureToImage().toAwtImage(), "png", out.resolve(file))
          }
        }
        fun has(description: String) =
          onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        // The widgets come once the file's editor is there; Position is named after its tooltip.
        waitUntil(timeoutMillis = 60_000) { has("Go to Line") }
        waitUntil(timeoutMillis = 10_000) { session.state.value.breadcrumbs.isNotEmpty() }
        waitForIdle()
        shot("status-1-bar")
        // A breadcrumb's list: the file's folder (the crumb before the file) lists its children.
        val crumbs = session.state.value.breadcrumbs
        val folder = crumbs.getOrNull(crumbs.size - 2) ?: crumbs.first()
        onAllNodesWithText(folder.text).onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { session.state.value.crumbMenu != null }
        waitForIdle()
        shot("status-2-crumb-list")
        session.closeCrumbMenu()
        // A background task: Gradle's Sync All Gradle Projects from its toolbar.
        if (has("Gradle")) {
          onNodeWithContentDescription("Gradle").performClick()
          waitUntil(timeoutMillis = 60_000) { has("Sync All Gradle Projects") }
          onNodeWithContentDescription("Sync All Gradle Projects").performClick()
          waitUntil(timeoutMillis = 60_000) { has("Show all processes") }
          shot("status-3-task")
          onNodeWithContentDescription("Show all processes").performClick()
          waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Processes").fetchSemanticsNodes().isNotEmpty()
          }
          shot("status-4-processes")
        }
        out
          .resolve("status-semantics.txt")
          .writeText(
            onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { it.toString() },
          )
      }
    } finally {
      session.stop()
    }
  }
}
