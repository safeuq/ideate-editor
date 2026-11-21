package intellijandroid.client.session.terminal

import com.jediterm.core.input.KeyEvent
import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TerminalScreenTest {
  private val styleState = StyleState()
  private val buffer = TerminalTextBuffer(20, 4, styleState, TERMINAL_HISTORY_LINES)
  private val terminal =
    JediTerminal(
      object : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) = Unit

        override fun setCursorShape(shape: CursorShape?) = Unit

        override fun beep() = Unit

        override fun scrollArea(top: Int, height: Int, dy: Int) = Unit

        override fun setCursorVisible(visible: Boolean) = Unit

        override fun useAlternateScreenBuffer(enabled: Boolean) = Unit

        override fun getWindowTitle() = ""

        override fun setWindowTitle(windowTitle: String) = Unit

        override fun getSelection(): TerminalSelection? = null

        override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit

        override fun setMouseFormat(mouseFormat: MouseFormat) = Unit

        override fun ambiguousCharsAreDoubleWidth() = false
      },
      buffer,
      styleState,
    )

  private fun feed(text: String) {
    val emulator = JediEmulator(ArrayTerminalDataStream(text.toCharArray()), terminal)
    while (emulator.hasNext()) emulator.next()
  }

  private fun screen() =
    TerminalScreenReader(buffer)
      .read(TerminalCursor(0, 0, true, TerminalCursorShape.BLOCK, true), alternate = false)

  @Test
  fun aRedWordAndAPlainWordAreTwoRuns() {
    feed("\u001b[31mred\u001b[0m plain\r\n")
    val runs = screen().lines.first().runs.filter { it.text.isNotBlank() }
    assertEquals(
      listOf(
        TerminalRun(0, 3, "red", foreground = TerminalPaint.Indexed(1)),
        TerminalRun(3, 6, " plain"),
      ),
      runs.map { it.copy(bold = false) },
    )
    assertEquals("red plain", screen().lines.first().text)
  }

  @Test
  fun historyKeepsLinesThatScrolledOff() {
    feed((1..6).joinToString("\r\n") { "line$it" })
    val screen = screen()
    // 4 rows: line1 and line2 are in the history, line3 to line6 on the screen.
    assertEquals(
      listOf("line1", "line2", "line3", "line4", "line5", "line6"),
      screen.lines.map { it.text },
    )
    assertEquals(2, screen.screenTop)
  }

  @Test
  fun upArrowFollowsTheProgramsCursorKeyMode() {
    assertContentEquals("\u001b[A".toByteArray(), terminal.getCodeForKey(KeyEvent.VK_UP, 0))
    feed("\u001b[?1h") // the program turns on application cursor keys
    assertContentEquals("\u001bOA".toByteArray(), terminal.getCodeForKey(KeyEvent.VK_UP, 0))
  }
}
