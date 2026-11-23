package io.github.safeuq.ideate.client.ui.terminal

import io.github.safeuq.ideate.client.session.EditorScheme
import io.github.safeuq.ideate.client.session.terminal.TerminalPaint

/**
 * Turns the colors a program asked for into real ones (spec 2.4, IntelliJ's
 * `JBTerminalSchemeColorPalette`), from the editor [scheme]. Colors are ARGB.
 *
 * |Program asks                   |Color                                                                  |
 * |-------------------------------|-----------------------------------------------------------------------|
 * |nothing (`Default`)            |`CONSOLE_NORMAL_OUTPUT` foreground, `CONSOLE_BACKGROUND_KEY` background|
 * |`\e[31m` = `Indexed(1)`        |scheme key `CONSOLE_RED_OUTPUT`                                        |
 * |`\e[91m` = `Indexed(9)`        |`CONSOLE_RED_BRIGHT_OUTPUT`, else `CONSOLE_RED_OUTPUT`                 |
 * |`\e[38;5;196m` = `Indexed(196)`|the xterm cube: 0xFFFF0000                                             |
 * |`\e[38;2;1;2;3m` = `Rgb`       |as given                                                               |
 */
class TerminalPalette(scheme: EditorScheme) {
  val foreground: Long =
    scheme.style(listOf(listOf("CONSOLE_NORMAL_OUTPUT"))).foreground ?: scheme.foreground
  val background: Long = scheme.color("CONSOLE_BACKGROUND_KEY") ?: scheme.background

  private val ansi: List<Long> = ANSI_KEYS.map { keys ->
    keys.firstNotNullOfOrNull { key -> scheme.style(listOf(listOf(key))).foreground } ?: foreground
  }

  fun foreground(paint: TerminalPaint): Long =
    when (paint) {
      TerminalPaint.Default -> foreground
      is TerminalPaint.Indexed -> indexed(paint.index)
      is TerminalPaint.Rgb -> paint.argb
    }

  /** null for the default background: the view's own background shows. */
  fun background(paint: TerminalPaint): Long? =
    if (paint == TerminalPaint.Default) null else foreground(paint)

  private fun indexed(index: Int): Long = if (index < 16) ansi[index] else xtermColor(index)

  companion object {
    /** Per ANSI color 0–15: its scheme key, then the keys it falls back to (spec 2.4). */
    private val ANSI_KEYS: List<List<String>> =
      listOf("BLACK", "RED", "GREEN", "YELLOW", "BLUE", "MAGENTA", "CYAN", "GRAY").map { name ->
        listOf("CONSOLE_${name}_OUTPUT", "CONSOLE_NORMAL_OUTPUT")
      } +
        listOf(listOf("CONSOLE_DARKGRAY_OUTPUT", "CONSOLE_GRAY_OUTPUT", "CONSOLE_NORMAL_OUTPUT")) +
        listOf("RED", "GREEN", "YELLOW", "BLUE", "MAGENTA", "CYAN").map { name ->
          listOf(
            "CONSOLE_${name}_BRIGHT_OUTPUT",
            "CONSOLE_${name}_OUTPUT",
            "CONSOLE_NORMAL_OUTPUT",
          )
        } +
        listOf(listOf("CONSOLE_WHITE_OUTPUT", "CONSOLE_NORMAL_OUTPUT"))

    /**
     * xterm's colors 16–255: a 6 × 6 × 6 cube (16–231), whose steps are 0, 95, 135, 175, 215, 255,
     * then 24 greys (232–255) from 8 in steps of 10. Example: 196 is red 5, green 0, blue 0 =
     * `0xFFFF0000`; 244 is grey 8 + 12 × 10 = 128 = `0xFF808080`.
     */
    fun xtermColor(index: Int): Long {
      require(index in 16..255) { "xterm palette numbers 16 to 255 only, got $index" }
      if (index >= 232) {
        val level = 8 + (index - 232) * 10
        return rgb(level, level, level)
      }
      fun step(value: Int) = if (value == 0) 0 else 55 + value * 40
      val cube = index - 16
      return rgb(step(cube / 36), step(cube / 6 % 6), step(cube % 6))
    }

    private fun rgb(red: Int, green: Int, blue: Int): Long =
      0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
  }
}
