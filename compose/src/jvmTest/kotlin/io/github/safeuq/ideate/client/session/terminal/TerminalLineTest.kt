package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jetbrains.codeWithMe.model.BeTerminalColor
import com.jetbrains.codeWithMe.model.BeTerminalEntryStyle
import com.jetbrains.codeWithMe.model.BeTerminalLine
import com.jetbrains.codeWithMe.model.BeTerminalLineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reconnect's screen state (spec 3.3) becomes JediTerm lines with their colors and options. */
class TerminalLineTest {
  @Test
  fun entriesBecomeOneLineWithTheirStyles() {
    val green =
      BeTerminalEntryStyle(BeTerminalColor(2, 0, 0, 0), null, 1 shl TextStyle.Option.BOLD.ordinal)
    val orange = BeTerminalEntryStyle(BeTerminalColor(-1, 255, 128, 0), null, 0)
    val line =
      terminalLines(
          BeTerminalLine(
            listOf(BeTerminalLineEntry("➜ ", green), BeTerminalLineEntry("echo", orange)),
          ),
          columns = 80,
        )
        .single()

    assertEquals("➜ echo", line.text)
    assertEquals(TerminalColor(2), checkNotNull(line.getStyleAt(0)).foreground)
    assertTrue(checkNotNull(line.getStyleAt(0)).hasOption(TextStyle.Option.BOLD))
    assertEquals(TerminalColor(255, 128, 0), checkNotNull(line.getStyleAt(2)).foreground)
    assertNull(checkNotNull(line.getStyleAt(2)).background)
  }

  @Test
  fun aLineLongerThanTheGridWrapsIntoRows() {
    val plain = BeTerminalEntryStyle(null, null, 0)
    val rows =
      terminalLines(
        BeTerminalLine(
          listOf(BeTerminalLineEntry("abc", plain), BeTerminalLineEntry("defg", plain)),
        ),
        columns = 3,
      )

    assertEquals(listOf("abc", "def", "g"), rows.map { row -> row.text })
    assertEquals(listOf(true, true, false), rows.map { row -> row.isWrapped })
  }
}
