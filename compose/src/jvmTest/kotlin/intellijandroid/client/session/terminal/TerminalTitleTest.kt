package intellijandroid.client.session.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** The four rows of the tab title table (spec 2.2), plus the cut of a long program title. */
class TerminalTitleTest {
  @Test
  fun followsTheTableOfSpecSection2_2() {
    assertEquals("Local", terminalTabTitle(null, null, null))
    assertEquals("Remote", terminalTabTitle(null, null, "Remote"))
    assertEquals(
      "vim build.gradle.kts",
      terminalTabTitle(null, "vim build.gradle.kts", "Remote"),
    )
    assertEquals("Server", terminalTabTitle("Server", "vim build.gradle.kts", "Remote"))
  }

  @Test
  fun cutsALongProgramTitleInTheMiddleTo30Characters() {
    val title = terminalTabTitle(null, "user@host:~/a/very/long/path/to/some/project/folder", null)
    assertEquals("user@host:~/a/…/project/folder", title)
    assertEquals(30, title.length)
  }
}
