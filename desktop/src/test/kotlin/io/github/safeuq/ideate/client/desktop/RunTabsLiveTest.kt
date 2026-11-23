package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.PanelConsole
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.lux.PngDecoder
import io.github.safeuq.ideate.client.ui.lux.pngDecoder
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
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
      runDesktopComposeUiTest(
        width = System.getenv("DRIVER_WIDTH")?.toInt() ?: 1200,
        height = System.getenv("DRIVER_HEIGHT")?.toInt() ?: 800,
        testTimeout = 5.minutes,
      ) {
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
      it.positionInRoot.x < 100f ||
        it.positionInRoot.y > 700f // the left bar, or a phone's bottom bar
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

  /**
   * Answers the backend's "Process 'x' Is Running" dialog with Terminate, its default button
   * (Enter): the dialog is painted by the backend, so there is no button to find by text, and a
   * click at fixed pixels broke whenever the dialog's size changed.
   */
  private fun androidx.compose.ui.test.ComposeUiTest.pressTerminate() {
    onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.Enter) }
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
      // Forever still runs: close it and press Terminate so nothing is left behind. (The backend
      // only asks while its setting processCloseConfirmation is the default; the "Do not ask
      // again" choice, TERMINATE in options/ide.general.xml, closes the tab without a dialog.)
      onNodeWithContentDescription("Close Forever").performClick()
      waitUntil(timeoutMillis = 15_000) { session.state.value.luxDialogs.isNotEmpty() }
      Thread.sleep(1_500)
      pressTerminate()
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
      // Terminate is the dialog's default button: Enter.
      pressTerminate()
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

  /**
   * The Build tree (plan phase 3) in the Run window of a Gradle task, on `gradle-smoke`: the rows
   * with their icons and durations, and a tap on a step changes the console. The toolbar's eye
   * button is at (513, 576) and its "Show Successful Steps" at (592, 634) in the 1200 x 800 window.
   */
  @Test
  fun buildTree() =
    drive("build-tree") { session, shot ->
      fun tree() = session.state.value.buildTrees.values.firstOrNull()
      fun rows() =
        tree()?.visibleRows()?.map { (node, depth) ->
          "$depth ${node.fragments.joinToString("") { it.text }} | ${node.iconPaths} | ${node.trailing}"
        }
      // The console on screen: the backend shows another console (another editor id) per step.
      fun consoleHas(text: String) =
        session.state.value.backendViews["Run"]?.panel?.locals.orEmpty().any { local ->
          local is PanelConsole &&
            session.state.value.consoles[local.kernelEditorId]?.text?.contains(text) == true
        }
      // The conditions read the session's state, not the semantics tree.
      fun poll(timeoutMillis: Long, condition: () -> Boolean) =
        waitUntil(timeoutMillis = timeoutMillis) { condition() }
      poll(60_000) { session.state.value.projectName != null }
      session.runConfiguration("Gradle.hello")
      poll(60_000) { session.state.value.backendViews["Run"]?.panel != null }
      poll(60_000) { rows()?.any { it.contains("hello: successful") } == true }
      println("[rows] ${rows()}")
      val root = tree()!!.visibleRows().first { it.first.fragments.first().text == "hello: " }.first
      assertEquals(listOf("expui/run/testPassed.svg"), root.iconPaths)
      assertTrue(
        root.trailing!!.contains("ms") || root.trailing!!.contains("sec"),
        "${root.trailing}",
      )
      // The whole run's output is shown while the root is selected.
      poll(30_000) { consoleHas("Executing ':app:hello'") }
      shot("build-tree-1")

      // A step's own output.
      onNodeWithText("New Minor Gradle Version Available", substring = true).performClick()
      poll(30_000) { consoleHas("is not the latest minor version") }
      assertEquals(2, tree()!!.selected)
      poll(10_000) { !consoleHas("Executing ':app:hello'") }
      shot("build-tree-2-warning")

      // Successful steps are hidden; the eye button's "Show Successful Steps" shows them.
      assertTrue(rows()!!.none { it.contains(":app:hello") }, "${rows()}")
      onRoot().performMouseInput { click(Offset(513f, 576f)) }
      Thread.sleep(2_000)
      onRoot().performMouseInput { click(Offset(592f, 634f)) }
      poll(20_000) { rows()?.any { it.contains(":app:hello") } == true }
      println("[rows with successful steps] ${rows()}")
      onNodeWithText(":app:hello", substring = true).performClick()
      poll(15_000) { tree()?.selected == 3 && consoleHas("> Task :app:hello") }
      Thread.sleep(1_000)
      println("[after task click] selected=${tree()?.selected}")
      assertEquals(3, tree()!!.selected)
      // Its own output is the task header and `hello`: the run's first line is gone.
      assertTrue(consoleHas("> Task :app:hello"))
      assertTrue(!consoleHas("Executing ':app:hello'") && !consoleHas("Possible solutions"))
      shot("build-tree-3-task")

      // Back to the whole run.
      onNodeWithText("hello: successful", substring = true).performClick()
      poll(15_000) { consoleHas("Executing ':app:hello'") }
    }

  /** The Build tree in the Build window, after Build Project on `gradle-smoke`. */
  @Test
  fun buildWindowTree() =
    drive("build-window-tree") { session, shot ->
      fun rows() =
        session.state.value.buildTrees.values.firstOrNull()?.visibleRows()?.map { (node, depth) ->
          "$depth ${node.fragments.joinToString("") { it.text }} | ${node.iconPaths} | ${node.trailing}"
        }
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      waitUntil(timeoutMillis = 40_000) {
        session.state.value.toolWindows.any { it.id == "Gradle" && it.available }
      }
      session.runProjectAction("CompileDirty")
      val shownByBackend = runCatching {
        waitUntil(timeoutMillis = 40_000) {
          session.state.value.backendViews["Build"]?.panel != null
        }
      }
        .isSuccess
      if (!shownByBackend) openStripe("Build")
      waitUntil(timeoutMillis = 40_000) { !rows().isNullOrEmpty() }
      Thread.sleep(3_000)
      println("[rows] ${rows()}")
      shot("build-window-1")
    }

  /**
   * The Build window on a phone upright (`DRIVER_WIDTH=411 DRIVER_HEIGHT=891`; any window taller
   * than wide is the portrait layout): the console above the tree, the panel only its two toolbar
   * strips, and clicks on the strip still reach the backend: the eye button's menu opens, and "Show
   * Successful Steps" shows the build's task rows in the tree. Click points are for 411 x 891.
   */
  @Test
  fun buildPortrait() =
    drive("build-portrait") { session, shot ->
      fun panel() = session.state.value.backendViews["Build"]?.panel
      fun consoleTexts() =
        panel()?.locals.orEmpty().filterIsInstance<PanelConsole>().map {
          session.state.value.consoles[it.kernelEditorId]?.text.orEmpty()
        }
      fun rowCount() = session.state.value.buildTrees.values.firstOrNull()?.visibleRows()?.size ?: 0
      waitUntil(timeoutMillis = 60_000) { session.state.value.projectName != null }
      waitUntil(timeoutMillis = 40_000) {
        session.state.value.toolWindows.any { it.id == "Gradle" && it.available }
      }
      session.runProjectAction("CompileDirty")
      openStripe("Build")
      waitUntil(timeoutMillis = 40_000) { consoleTexts().isNotEmpty() }
      waitUntil(timeoutMillis = 20_000) {
        onAllNodesWithText("Build gradle-smoke", substring = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
      }
      Thread.sleep(1_500)
      println("[panel] ${panel()!!.width} x ${panel()!!.height}")
      shot("build-portrait-1")
      val console = onNodeWithText("Execution finished", substring = true).fetchSemanticsNode()
      val row =
        onAllNodesWithText("Build gradle-smoke", substring = true).fetchSemanticsNodes().first()
      println("[bounds] console ${console.boundsInRoot} row ${row.boundsInRoot}")
      assertEquals(73, panel()!!.width)
      assertTrue(console.boundsInRoot.bottom <= row.boundsInRoot.top, "console above the tree")
      // The toolbars are two rows of cells cut out of the panel, each with a name.
      for (name in
        listOf("Rerun", "Stop", "Pin", "Filter", "Soft-Wrap", "Scroll to the End", "Clear All")) {
        println(
          "[cell] $name ${onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot}",
        )
      }
      // The Filter button opens its menu; its second item is Show Successful Steps.
      val rowsBefore = rowCount()
      onNodeWithContentDescription("Filter").performClick()
      Thread.sleep(2_000)
      shot("build-portrait-2-menu")
      // The menu opens under the button; its second item, Show Successful Steps, is at (180, 653).
      onRoot().performMouseInput { click(Offset(180f, 653f)) }
      waitUntil(timeoutMillis = 20_000) { rowCount() > rowsBefore }
      Thread.sleep(1_500)
      println("[rows] $rowsBefore -> ${rowCount()}")
      shot("build-portrait-3-successful")
      // A step in the tree switches the console to that step's own.
      // (the whole run's console names the task too: the row is the one lowest on the screen)
      val steps = onAllNodesWithText(":app:testClasses", substring = true)
      steps[
          steps.fetchSemanticsNodes().indices.maxBy {
            steps.fetchSemanticsNodes()[it].boundsInRoot.top
          }]
        .performClick()
      waitUntil(timeoutMillis = 20_000) {
        consoleTexts().let { texts ->
          texts.any { it.contains("Task :app:testClasses") } &&
            texts.none { it.contains("Task :app:compileJava") }
        }
      }
      shot("build-portrait-4-step")
      // The console's buttons: Soft-Wrap wraps the long lines, Scroll to the End and Clear All
      // empty the console shown.
      onNodeWithContentDescription("Soft-Wrap").performClick()
      Thread.sleep(1_500)
      shot("build-portrait-5-soft-wrap")
      onNodeWithContentDescription("Scroll to the End").performClick()
      Thread.sleep(1_000)
      shot("build-portrait-6-scroll-end")
      onNodeWithContentDescription("Clear All").performClick()
      // It empties the whole build's console (the selected step keeps its own), so look at the
      // build's console again: the row at the top.
      Thread.sleep(1_000)
      onAllNodesWithText("Build gradle-smoke", substring = true).onFirst().performClick()
      waitUntil(timeoutMillis = 20_000) { consoleTexts().all { it.isBlank() } }
      shot("build-portrait-7-cleared")
      // Rerun starts a new build: a console with text again.
      onNodeWithContentDescription("Rerun").performClick()
      waitUntil(timeoutMillis = 40_000) { consoleTexts().any { it.contains("Execution finished") } }
      shot("build-portrait-8-rerun")
      // Each hover is sent to the panel at the cell's own place: the backend hovers that button.
      for (name in
        listOf("Rerun", "Stop", "Pin", "Filter", "Soft-Wrap", "Scroll to the End", "Clear All")) {
        onNodeWithContentDescription(name).performMouseInput { moveTo(center) }
        // (the pointer's Enter reaches the cell on the next frame)
        waitForIdle()
        Thread.sleep(1_000)
        shot("build-portrait-hover-" + name.lowercase().replace(" ", "-"))
      }
      onRoot().performMouseInput { moveTo(Offset(300f, 500f)) }
    }
}
