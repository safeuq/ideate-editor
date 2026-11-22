package intellijandroid.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The line under a tool window header, by IntelliJ's `ToolWindowHeader.paintComponent` rule. */
class HeaderLineTest {
  @Test
  fun followsIntellij() {
    // (bottom, tabCount, toolbar, portrait) to whether the line shows
    val expected =
      mapOf(
        listOf(false, 0, false, false) to false, // Project on the left: no line
        listOf(true, 0, false, false) to true, // Problems at the bottom
        listOf(false, 2, false, false) to true, // two tabs in the header
        listOf(false, 1, false, false) to false, // one tab is not tabbed
        listOf(false, 0, true, false) to true, // Gradle's toolbar under the header
        listOf(false, 0, false, true) to true, // phone upright
      )
    assertEquals(
      expected,
      expected.mapValues { (input, _) ->
        headerLineShown(
          input[0] as Boolean,
          input[1] as Int,
          input[2] as Boolean,
          input[3] as Boolean,
        )
      },
    )
  }
}
