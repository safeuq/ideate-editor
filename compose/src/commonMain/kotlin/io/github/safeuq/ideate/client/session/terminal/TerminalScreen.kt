package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TextStyle.Option
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.util.CharUtils
import kotlinx.serialization.Serializable

/**
 * A color as the program asked for it: [Default] (no color set), a palette number from 0 to 255
 * (`\e[31m` is `Indexed(1)`), or an exact color (`\e[38;2;255;0;0m` is `Rgb(0xFFFF0000)`). The view
 * turns the first two into real colors with the editor scheme (spec 2.4), so a snapshot doesn't
 * change when the theme does.
 */
@Serializable
sealed interface TerminalPaint {
  @Serializable data object Default : TerminalPaint

  @Serializable data class Indexed(val index: Int) : TerminalPaint

  /** ARGB. */
  @Serializable data class Rgb(val argb: Long) : TerminalPaint
}

/**
 * A stretch of one line drawn with one style, starting at [column] (0-based). [text] has no NUL and
 * no double-width filler characters; [cells] is how many columns the run covers, which is larger
 * than `text.length` when it holds wide characters (e.g. 2 columns for `日`).
 */
@Serializable
data class TerminalRun(
  val column: Int,
  val cells: Int,
  val text: String,
  val foreground: TerminalPaint = TerminalPaint.Default,
  val background: TerminalPaint = TerminalPaint.Default,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val inverse: Boolean = false,
)

/**
 * [wrapped]: this line continues the one before it (a long line the terminal cut), so copying joins
 * them.
 */
@Serializable
data class TerminalScreenLine(val runs: List<TerminalRun>, val wrapped: Boolean) {
  /** The line as plain text with columns kept, trailing spaces cut: `"red plain"`. */
  val text: String
    get() = buildString {
      for (run in runs) append(" ".repeat(maxOf(0, run.column - length))).append(run.text)
    }
      .trimEnd()
}

@Serializable
enum class TerminalCursorShape {
  BLOCK,
  UNDERLINE,
  BAR,
}

/** [column] and [row] are 0-based; [row] counts from the first screen row, not from the history. */
@Serializable
data class TerminalCursor(
  val column: Int,
  val row: Int,
  val visible: Boolean,
  val shape: TerminalCursorShape,
  val blinking: Boolean,
)

/**
 * What the terminal shows right now, copied out of JediTerm so the UI never touches its buffer:
 * [lines] are the history followed by the screen (the last [rows] lines). In the alternate buffer
 * (vim, less, top) there is no history, so [lines] is the screen only.
 */
@Serializable
data class TerminalScreen(
  val lines: List<TerminalScreenLine>,
  val columns: Int,
  val rows: Int,
  val cursor: TerminalCursor,
  val alternate: Boolean,
) {
  /** Where the screen starts in [lines]; the cursor's row is counted from here. */
  val screenTop: Int
    get() = lines.size - rows

  companion object {
    val EMPTY =
      TerminalScreen(
        emptyList(),
        0,
        0,
        TerminalCursor(0, 0, false, TerminalCursorShape.BLOCK, false),
        false,
      )
  }
}

/** How many old lines JediTerm keeps (the plan's value; IntelliJ's own default is 5,000 too). */
internal const val TERMINAL_HISTORY_LINES = 5000

/**
 * Copies JediTerm's text buffer into [TerminalScreen]s. History lines never change once they are in
 * the history, so only the ones added since the last copy are converted. The copy starts over when
 * the history shrinks, when the width changes (JediTerm re-wraps old lines then), and while the
 * history is full (every new line pushes the oldest out, so the cached lines shift).
 */
internal class TerminalScreenReader(private val buffer: TerminalTextBuffer) {
  private var history = emptyList<TerminalScreenLine>()
  private var historyWidth = 0

  /** Call from any thread; it takes the buffer's lock. */
  fun read(cursor: TerminalCursor, alternate: Boolean): TerminalScreen {
    buffer.lock()
    try {
      val rows = buffer.height
      val screen = (0 until rows).map { convert(buffer.getLine(it)) }
      val historyLines =
        if (alternate) emptyList()
        else {
          val count = buffer.historyLinesCount
          if (
            count < history.size || buffer.width != historyWidth || count >= TERMINAL_HISTORY_LINES
          ) {
            history = emptyList()
          }
          historyWidth = buffer.width
          if (count > history.size)
            history =
              history + (history.size until count).map { convert(buffer.getLine(it - count)) }
          history
        }
      return TerminalScreen(historyLines + screen, buffer.width, rows, cursor, alternate)
    } finally {
      buffer.unlock()
    }
  }

  private fun convert(line: TerminalLine): TerminalScreenLine {
    val runs = mutableListOf<TerminalRun>()
    var column = 0
    line.forEachEntry { entry ->
      val raw = entry.text.toString()
      val style = entry.style
      // An empty cell is NUL; a wide character is followed by a filler. Both take a column.
      val text = raw.replace(CharUtils.NUL_CHAR, ' ').replace(CharUtils.DWC.toString(), "")
      if (text.isNotEmpty())
        runs +=
          TerminalRun(
            column = column,
            cells = raw.length,
            text = text,
            foreground = style.foreground.toPaint(),
            background = style.background.toPaint(),
            bold = style.hasOption(Option.BOLD),
            italic = style.hasOption(Option.ITALIC),
            underline = style.hasOption(Option.UNDERLINED),
            inverse = style.hasOption(Option.INVERSE),
          )
      column += raw.length
    }
    return TerminalScreenLine(runs, line.isWrapped)
  }
}

private fun com.jediterm.terminal.TerminalColor?.toPaint(): TerminalPaint =
  when {
    this == null -> TerminalPaint.Default
    isIndexed -> TerminalPaint.Indexed(colorIndex)
    else -> TerminalPaint.Rgb((toColor().rgb.toLong() and 0xFFFFFFL) or 0xFF000000L)
  }

internal fun CursorShape.toShape() =
  when (this) {
    CursorShape.BLINK_BLOCK,
    CursorShape.STEADY_BLOCK -> TerminalCursorShape.BLOCK
    CursorShape.BLINK_UNDERLINE,
    CursorShape.STEADY_UNDERLINE -> TerminalCursorShape.UNDERLINE
    CursorShape.BLINK_VERTICAL_BAR,
    CursorShape.STEADY_VERTICAL_BAR -> TerminalCursorShape.BAR
  }
