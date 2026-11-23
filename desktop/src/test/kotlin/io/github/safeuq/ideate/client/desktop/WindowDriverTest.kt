package io.github.safeuq.ideate.client.desktop

import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Self-test of [WindowDriver] on the `smoke` project. Skipped unless `DRIVER_LINK` is set.
 * `DRIVER_RERUN_X` / `DRIVER_RERUN_Y`: the Run panel's Rerun button in the 1200 × 800 window.
 */
class WindowDriverTest {
  @Test
  fun hoverClickAndType() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    val driver = WindowDriver.open(session)
    try {
      driver.waitUntil(60.seconds, "the project to open") {
        session.state.value.projectName != null
      }
      session.runConfiguration("Application.Main")
      driver.waitUntil(60.seconds, "Main's console") { session.state.value.consoles.isNotEmpty() }
      // The panel's buttons change when the run ends, and the backend hides a tooltip then: wait
      // until the Run panel has not changed for 2 s.
      var panel = session.state.value.backendViews["Run"]?.panel
      var changedAt = System.nanoTime()
      driver.waitUntil(30.seconds, "the Run panel to settle") {
        val now = session.state.value.backendViews["Run"]?.panel
        if (now != panel) {
          panel = now
          changedAt = System.nanoTime()
        }
        System.nanoTime() - changedAt > 2.seconds.inWholeNanoseconds
      }
      driver.screenshot("before-hover")

      val rerunX = System.getenv("DRIVER_RERUN_X")?.toInt() ?: 431
      val rerunY = System.getenv("DRIVER_RERUN_Y")?.toInt() ?: 577
      driver.move(rerunX - 30, rerunY)
      driver.move(rerunX, rerunY)
      // The Rerun tooltip is a popup window. It must stay: one Lux dialog with the same id for
      // 3 s (before the fix it was replaced about every 0.75 s).
      driver.waitUntil(10.seconds, "the tooltip's Lux dialog and window") {
        session.state.value.luxDialogs.isNotEmpty() && driver.popupWindows().isNotEmpty()
      }
      val tooltipIds = session.state.value.luxDialogs.map { it.id }
      assertEquals(1, tooltipIds.size, "one tooltip dialog, got $tooltipIds")
      driver.screenshot("hover")
      val start = System.nanoTime()
      val stable = start + 3.seconds.inWholeNanoseconds
      val timeline = mutableListOf<String>()
      var last = tooltipIds
      while (System.nanoTime() < stable) {
        val now = session.state.value.luxDialogs.map { it.id }
        if (now != last)
          timeline +=
            "${(System.nanoTime() - start) / 1_000_000}ms $now windows=${driver.popupWindows().size}"
        last = now
        Thread.sleep(20)
      }
      assertTrue(timeline.isEmpty(), "the tooltip changed from $tooltipIds: $timeline")
      assertTrue(driver.popupWindows().isNotEmpty(), "the tooltip's window closed")

      // A menu goes through WindowPopupHost too. The Project tool window's title.
      driver.move(75, 47)
      driver.waitUntil(10.seconds, "the tooltip to go") {
        session.state.value.luxDialogs.isEmpty() && driver.popupWindows().isEmpty()
      }
      driver.click(75, 47)
      driver.waitUntil(10.seconds, "the menu window") { driver.popupWindows().isNotEmpty() }
      driver.screenshot("menu")
      driver.dismissPopups()
      driver.waitUntil(10.seconds, "the menu window to close") { driver.popupWindows().isEmpty() }

      // Keys while the window is not focused: open a file, click into it, type.
      val projectFolder =
        session.state.value.tree.nodes.values
          .first { it.parentId == null && it.path != null }
          .path!!
      val file = "$projectFolder/src/demo/Main.java"
      session.open(file)
      driver.waitUntil(10.seconds, "Main.java in an editor") {
        session.state.value.editors.any { it.path == file }
      }
      val before = session.state.value.editors.first { it.path == file }.text
      driver.click(800, 300)
      driver.type("Q")
      driver.waitUntil(10.seconds, "the typed character in the document") {
        session.state.value.editors.first { it.path == file }.text.length == before.length + 1
      }
      driver.screenshot("typed")

      // A tool window button: the Terminal stripe button opens a terminal tab.
      driver.click(17, 660)
      driver.waitUntil(30.seconds, "a terminal tab") { session.state.value.terminals.isNotEmpty() }
      driver.screenshot("terminal")
    } finally {
      driver.close()
      session.stop()
    }
  }
}
