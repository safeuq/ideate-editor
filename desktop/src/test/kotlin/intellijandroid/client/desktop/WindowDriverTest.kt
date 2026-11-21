package intellijandroid.client.desktop

import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import kotlin.test.Test
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
      driver.screenshot("before-hover")

      val rerunX = System.getenv("DRIVER_RERUN_X")?.toInt() ?: 431
      val rerunY = System.getenv("DRIVER_RERUN_Y")?.toInt() ?: 577
      driver.move(rerunX - 30, rerunY)
      driver.move(rerunX, rerunY)
      // The Rerun tooltip is a Lux popup drawn inside the window (not an owned window). It blinks
      // (a known bug), so remember that it was seen instead of checking once.
      var sawLux = false
      driver.waitUntil(10.seconds, "a Lux popup") {
        sawLux = sawLux || session.state.value.luxDialogs.isNotEmpty()
        sawLux
      }
      driver.screenshot("hover")

      // A menu goes through WindowPopupHost: its own window. The Project tool window's title.
      driver.move(75, 47)
      driver.waitUntil(10.seconds, "the tooltip to go") { session.state.value.luxDialogs.isEmpty() }
      // The Rerun tooltip's blinking can swallow the first click, so click again if nothing opens.
      repeat(3) {
        if (driver.popupWindows().isEmpty()) {
          driver.click(75, 47)
          runCatching { driver.waitUntil(2.seconds) { driver.popupWindows().isNotEmpty() } }
        }
      }
      driver.waitUntil(1.seconds, "a popup window") { driver.popupWindows().isNotEmpty() }
      driver.screenshot("menu")
      driver.dismissPopups()
      driver.waitUntil(10.seconds, "the popup window to close") { driver.popupWindows().isEmpty() }

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
