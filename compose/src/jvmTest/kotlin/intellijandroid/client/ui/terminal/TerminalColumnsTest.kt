package intellijandroid.client.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** The terminal's column count after its left inset. */
class TerminalColumnsTest {
  @Test
  fun leavesOutTheLeftInset() {
    // 800 px wide, 8 px cells: 100 columns without an inset, 98 with 13 px (787 / 8).
    assertEquals(100, terminalColumns(800f, 0f, 8f))
    assertEquals(98, terminalColumns(800f, 13f, 8f))
  }
}
