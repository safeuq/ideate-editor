package intellijandroid.client.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** xterm's colors 16 to 255 (spec 2.4): the cube's corners and steps, and the grey ramp's ends. */
class TerminalPaletteTest {
  @Test
  fun xtermCubeAndGreys() {
    assertEquals(0xFF000000L, TerminalPalette.xtermColor(16))
    assertEquals(0xFF0000FFL, TerminalPalette.xtermColor(21))
    assertEquals(0xFFFF0000L, TerminalPalette.xtermColor(196))
    assertEquals(0xFF5F87AFL, TerminalPalette.xtermColor(16 + 36 * 1 + 6 * 2 + 3)) // 95, 135, 175
    assertEquals(0xFFFFFFFFL, TerminalPalette.xtermColor(231))
    assertEquals(0xFF080808L, TerminalPalette.xtermColor(232))
    assertEquals(0xFF808080L, TerminalPalette.xtermColor(244))
    assertEquals(0xFFEEEEEEL, TerminalPalette.xtermColor(255))
  }
}
