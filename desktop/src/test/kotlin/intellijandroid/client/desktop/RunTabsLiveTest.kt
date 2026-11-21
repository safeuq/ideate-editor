package intellijandroid.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.pngDecoder
import intellijandroid.client.ui.theme.LocalClientFonts
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Run and Build tool windows (plan phase 2) against a live backend, on the `smoke` project (plan
 * appendix B plus `Forever`, which sleeps) for `runTabs`, and on `gradle-smoke` for `buildTab`.
 * Skipped unless `DRIVER_LINK` is set; PNGs and logs go to `DRIVER_OUT`. `DRIVER_RERUN_X` /
 * `DRIVER_RERUN_Y`: where the panel's Rerun button is in the 1200 x 800 window.
 */
@OptIn(ExperimentalTestApi::class)
class RunTabsLiveTest {
  private fun drive(
    name: String,
    body: androidx.compose.ui.test.ComposeUiTest.(ClientSession, (String) -> Unit) -> Unit,
  ) {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    // As Main.kt does at startup: Lux icons are PNGs.
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
        val shot = { shotName: String ->
          // The panel is replaced when a tab changes: wait for its first frame.
          // (a window with no tab has no panel: then the shot is taken after the wait)
          runCatching {
            waitUntil(timeoutMillis = 4_000) {
              session.state.value.backendViews.values.any { it.panel != null }
            }
          }
          Thread.sleep(1_500)
          waitForIdle()
          // The screen, then each open dialog (its own root): name.png, name-1.png, ...
          val roots = onAllNodes(isRoot())
          repeat(roots.fetchSemanticsNodes().size) { index ->
            val file = if (index == 0) "$shotName.png" else "$shotName-$index.png"
            ImageIO.write(roots[index].captureToImage().toAwtImage(), "png", out.resolve(file))
          }
        }
        try {
          body(session, shot)
        } finally {
          val roots = onAllNodes(isRoot())
          out
            .resolve("$name-semantics.txt")
            .writeText(
              roots.fetchSemanticsNodes().indices.joinToString("\n") {
                roots[it].printToString(Int.MAX_VALUE)
              },
            )
        }
      }
    } finally {
      File(System.getenv("DRIVER_OUT") ?: "build/driver")
        .resolve("$name-log.txt")
        .writeText(session.state.value.log.joinToString("\n"))
      session.stop()
    }
  }

  /**
   * The tool window bar's button named [name]; the main toolbar has a Run and a Build button too.
   */
  private fun androidx.compose.ui.test.ComposeUiTest.stripeIndex(name: String) =
    onAllNodesWithContentDescription(name).fetchSemanticsNodes().indexOfFirst {
      it.positionInRoot.x < 100f
    }

  /** Opens a tool window with its stripe button, which shows once the backend lists the window. */
  private fun androidx.compose.ui.test.ComposeUiTest.openStripe(name: String) {
    // The stripe fills in as the backend lists windows; let it settle so the click isn't moved.
    waitUntil(timeoutMillis = 40_000) { stripeIndex(name) >= 0 && stripeIndex("Services") >= 0 }
    Thread.sleep(1_500)
    println(
      "[click] $name nodes " +
        onAllNodesWithContentDescription(name).fetchSemanticsNodes().map { it.boundsInRoot } +
        " index " +
        stripeIndex(name),
    )
    onAllNodesWithContentDescription(name)[stripeIndex(name)].performClick()
  }

  private fun ClientSession.consoleText(marker: String) =
    state.value.consoles.values.firstOrNull { it.text.contains(marker) }?.text

  @Test
  fun runTabs() =
    drive("run") { session, shot ->
      fun tabs() = session.state.value.backendViews["Run"]?.tabs.orEmpty()
      val finished = "Process finished with exit code 0"
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      session.runConfiguration("Application.Main")
      // The backend asks for the Run window when the run starts (state Active): no click.
      waitUntil(timeoutMillis = 60_000) { tabs() == listOf("Main") }
      waitUntil(timeoutMillis = 60_000) { session.consoleText(finished) != null }
      waitUntil(timeoutMillis = 30_000) {
        onAllNodesWithText("just a simple greeting", substring = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
      }
      shot("run-1-main")

      // The panel's own Rerun button (the first icon of its toolbar, at (431, 577) in this 1200 x
      // 800 window) runs Main again: a click on the backend-painted toolbar. A run makes a new
      // console.
      val before = session.state.value.consoles.keys.toSet()
      onRoot().performMouseInput { click(Offset(431f, 577f)) }
      waitUntil(timeoutMillis = 30_000) {
        (session.state.value.consoles.keys - before).isNotEmpty()
      }
      println("[rerun] new consoles ${session.state.value.consoles.keys - before}")
      shot("run-2-rerun")

      // A new run takes over a finished run's tab (the backend renames it), so start one that
      // keeps running; then a second run gets its own tab.
      session.runConfiguration("Application.Forever")
      waitUntil(timeoutMillis = 30_000) { session.consoleText("waiting forever") != null }
      println("[tabs after Forever] ${tabs()}")
      shot("run-3-forever")
      session.runConfiguration("Application.Slow")
      waitUntil(timeoutMillis = 30_000) { tabs().size >= 2 }
      println("[tabs] ${tabs()}")
      val slow = tabs().indexOf("Slow")
      assertTrue(slow >= 0, "no Slow tab in ${tabs()}")
      shot("run-4-two-tabs")
      onNodeWithText("Forever").performClick()
      waitUntil(timeoutMillis = 10_000) {
        session.state.value.backendViews["Run"]?.selectedTab == tabs().indexOf("Forever")
      }
      shot("run-5-forever-selected")
      onNodeWithText("Slow").performClick()
      waitUntil(timeoutMillis = 10_000) {
        session.state.value.backendViews["Run"]?.selectedTab == slow
      }
      waitUntil(timeoutMillis = 30_000) {
        session.consoleText("done with an error line")?.contains(finished) == true
      }
      shot("run-6-slow-selected")

      // Close the finished tab (Slow is selected, so its x shows).
      val tabsBefore = tabs()
      onNodeWithContentDescription("Close Slow").performClick()
      waitUntil(timeoutMillis = 15_000) { tabs() == tabsBefore - "Slow" }
      shot("run-7-closed")
      println("[tabs after close] ${tabs()}")
      // Forever still runs: close it and press Terminate so nothing is left behind.
      onNodeWithContentDescription("Close Forever").performClick()
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isNotEmpty() }
      Thread.sleep(1_500)
      onAllNodes(isRoot())[1].performMouseInput { click(Offset(739f, 451f)) }
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isEmpty() }
    }

  @Test
  fun closeRunningTab() =
    drive("run-running") { session, shot ->
      fun tabs() = session.state.value.backendViews["Run"]?.tabs.orEmpty()
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      session.runConfiguration("Application.Forever")
      // Does the backend ask for the Run window to show when a run starts? Wait, then look.
      Thread.sleep(12_000)
      println(
        "[activation] " + session.state.value.log.filter { it.contains("Tool window Run state") },
      )
      // The backend asks for the Run window when the run starts (state Active): no click.
      waitUntil(timeoutMillis = 60_000) { tabs() == listOf("Forever") }
      waitUntil(timeoutMillis = 30_000) { session.consoleText("waiting forever") != null }
      shot("running-1")
      onNodeWithContentDescription("Close Forever").performClick()
      // A running process: the backend asks first, in its own dialog.
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isNotEmpty() }
      Thread.sleep(1_500)
      println("[tabs while the dialog asks] ${tabs()}")
      shot("running-2-dialog")
      // Cancel (painted by the backend, at (488, 451) in the dialog's root): the tab and the
      // process stay.
      onAllNodes(isRoot())[1].performMouseInput { click(Offset(488f, 451f)) }
      waitUntil(timeoutMillis = 10_000) { session.state.value.luxDialogs.isEmpty() }
      Thread.sleep(3_000)
      println("[tabs after Cancel] ${tabs()}")
      shot("running-3-after-cancel")
      // Does hiding and showing the window bring the tab back?
      openStripe("Run")
      Thread.sleep(2_000)
      openStripe("Run")
      Thread.sleep(4_000)
      println("[tabs after hide and show] ${tabs()}")
      shot("running-4-after-hide-show")
    }

  @Test
  fun terminateRunningTab() =
    drive("run-terminate") { session, shot ->
      fun tabs() = session.state.value.backendViews["Run"]?.tabs.orEmpty()
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      session.runConfiguration("Application.Forever")
      // The backend asks for the Run window when the run starts (state Active): no click.
      waitUntil(timeoutMillis = 40_000) { tabs() == listOf("Forever") }
      waitUntil(timeoutMillis = 30_000) { session.consoleText("waiting forever") != null }
      onNodeWithContentDescription("Close Forever").performClick()
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isNotEmpty() }
      Thread.sleep(1_500)
      // Terminate, painted by the backend, at (739, 451) in the dialog's root.
      onAllNodes(isRoot())[1].performMouseInput { click(Offset(739f, 451f)) }
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isEmpty() }
      Thread.sleep(3_000)
      println("[tabs after Terminate] ${tabs()}")
      assertEquals(emptyList(), tabs())
      shot("terminate-1")
    }

  @Test
  fun buildTab() =
    drive("build") { session, shot ->
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      // Let Gradle import the project first; a build before that has nothing to compile.
      waitUntil(timeoutMillis = 40_000) {
        session.state.value.toolWindows.any { it.id == "Gradle" && it.available }
      }
      session.runProjectAction("CompileDirty")
      // Does the backend ask for the Build window itself? If not, open it with its button.
      val shownByBackend = runCatching {
        waitUntil(timeoutMillis = 40_000) {
          session.state.value.backendViews["Build"]?.panel != null
        }
      }
        .isSuccess
      println("[build shown by the backend] $shownByBackend")
      if (!shownByBackend) openStripe("Build")
      waitUntil(timeoutMillis = 40_000) { session.state.value.backendViews["Build"]?.panel != null }
      println("[build tabs] ${session.state.value.backendViews["Build"]?.tabs}")
      shot("build-1")
    }
}
