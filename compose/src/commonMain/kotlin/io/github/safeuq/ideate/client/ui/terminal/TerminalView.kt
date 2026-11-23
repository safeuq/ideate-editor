package io.github.safeuq.ideate.client.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.core.input.InputEvent
import com.jediterm.core.input.KeyEvent as TerminalKey
import io.github.safeuq.ideate.client.session.EditorScheme
import io.github.safeuq.ideate.client.session.terminal.TerminalCursorShape
import io.github.safeuq.ideate.client.session.terminal.TerminalKeyInput
import io.github.safeuq.ideate.client.session.terminal.TerminalRun
import io.github.safeuq.ideate.client.session.terminal.TerminalScreen
import io.github.safeuq.ideate.client.session.terminal.TerminalTabState
import io.github.safeuq.ideate.client.ui.ContextMenuArea
import io.github.safeuq.ideate.client.ui.EditorTextTarget
import io.github.safeuq.ideate.client.ui.IS_MAC
import io.github.safeuq.ideate.client.ui.LocalEditorTextInput
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.MenuSeparator
import io.github.safeuq.ideate.client.ui.shortcut
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** What a terminal view asks of the session. */
class TerminalActions(
  /** The grid is now this many columns and rows; the shell must be told. */
  val resize: (columns: Int, rows: Int) -> Unit,
  /** Paste this text, wrapped as the program asked for (bracketed paste, spec 2.6). */
  val paste: (String) -> Unit,
  val clearBuffer: () -> Unit,
)

/** A cell: [row] counts from the first history line, [column] from the left. */
private data class Cell(val row: Int, val column: Int) : Comparable<Cell> {
  override fun compareTo(other: Cell) = compareValuesBy(this, other, { it.row }, { it.column })
}

/**
 * One terminal (spec 2.3 to 2.7), drawn from [tab]'s snapshot with a small renderer of its own: a
 * cell grid in the editor font, 6 dp of space above the first line and below the last, 13 dp before
 * the text (see [LEFT_INSET]), lines cut at the grid's width (JediTerm already did that), no marks
 * at the cut. The columns and rows follow the view's size; each change goes to [actions] (`stty
 * size` then prints them).
 *
 * The cursor (spec 2.5): focused, a filled cell with the character under it in the background
 * color; not focused, an outline; underline and bar are 2 dp. It blinks, every 500 ms, while
 * focused and the program asked for a blinking one. ponytail: 500 ms is IntelliJ's default
 * `caretBlinkPeriod`; the editor setting isn't read yet. The same goes for the terminal font and
 * line spacing (settings under Tools | Terminal): the editor font at line spacing 1.0.
 *
 * Mouse: a drag selects text; a right click or a long press opens Copy, Paste, Select All and Clear
 * Buffer. ponytail: programs can't ask for mouse reports (vim's mouse, htop) yet.
 */
@Composable
fun TerminalView(
  tab: TerminalTabState,
  scheme: EditorScheme,
  input: TerminalKeyInput,
  actions: TerminalActions,
  focus: TerminalFocus,
  modifier: Modifier = Modifier,
) {
  val fonts = LocalClientFonts.current
  val density = LocalDensity.current
  val measurer = rememberTextMeasurer(cacheSize = 1024)
  val clipboard = LocalClipboardManager.current
  val palette = remember(scheme) { TerminalPalette(scheme) }
  val baseStyle =
    remember(fonts, palette) {
      TextStyle(
        fontFamily = fonts.editor,
        fontSize = IslandsDark.EDITOR_FONT_SIZE.sp,
        color = Color(palette.foreground),
      )
    }
  // IntelliJ's line height is the font's, rounded up to whole pixels; terminal line spacing is 1.0.
  val metrics =
    remember(baseStyle, density) {
      val sample = measurer.measure("0".repeat(100), baseStyle)
      CellMetrics(
        cellWidth = sample.size.width / 100f,
        lineHeight = ceil(sample.size.height.toFloat()),
        baseline = sample.firstBaseline,
      )
    }
  val inset = with(density) { VERTICAL_INSET.toPx() }
  val leftInset = with(density) { LEFT_INSET.toPx() }

  val screen = tab.screen
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  var focused by remember { mutableStateOf(false) }
  var blinkOn by remember { mutableStateOf(true) }
  var selection by remember { mutableStateOf<Pair<Cell, Cell>?>(null) }
  // How far the view is scrolled up from the end, in px; 0 follows the newest line.
  var distanceFromBottom by remember { mutableFloatStateOf(0f) }
  val focusRequester = remember { FocusRequester() }

  fun contentHeight() =
    screen.lines.size * metrics.lineHeight + if (screen.alternate) 0f else 2 * inset

  fun maxTop() = (contentHeight() - viewport.height).coerceAtLeast(0f)

  fun top() = if (screen.alternate) 0f else (maxTop() - distanceFromBottom).coerceAtLeast(0f)

  fun topInset() = if (screen.alternate) 0f else inset

  // New lines under a view scrolled up must not move what it shows.
  var knownLines by remember { mutableStateOf(screen.lines.size) }
  LaunchedEffect(screen.lines.size) {
    if (distanceFromBottom > 0f && screen.lines.size > knownLines)
      distanceFromBottom += (screen.lines.size - knownLines) * metrics.lineHeight
    knownLines = screen.lines.size
  }

  // The grid follows the size: width ÷ cell width columns, (height - the two insets) ÷ line height
  // rows. The alternate buffer has no insets in IntelliJ; here it keeps the same grid so a
  // full-screen program doesn't resize the shell on entering.
  val columns = terminalColumns(viewport.width.toFloat(), leftInset, metrics.cellWidth)
  val rows = ((viewport.height - 2 * inset) / metrics.lineHeight).toInt()
  LaunchedEffect(tab.id, columns, rows) {
    if (columns >= 2 && rows >= 1) actions.resize(columns, rows)
  }

  LaunchedEffect(tab.id) { focusRequester.requestFocus() }
  LaunchedEffect(focused, screen.cursor) {
    blinkOn = true
    while (focused && screen.cursor.blinking) {
      delay(BLINK_PERIOD_MS)
      blinkOn = !blinkOn
    }
  }

  fun cellAt(position: Offset): Cell {
    val row = ((position.y + top() - topInset()) / metrics.lineHeight).toInt()
    val column = ((position.x - leftInset) / metrics.cellWidth).toInt()
    return Cell(
      row.coerceIn(0, (screen.lines.size - 1).coerceAtLeast(0)),
      column.coerceIn(0, (screen.columns - 1).coerceAtLeast(0)),
    )
  }

  fun selectedText(): String {
    val (first, second) = selection ?: return ""
    val (start, end) = if (first <= second) first to second else second to first
    return buildString {
      for (row in start.row..end.row) {
        val text = screen.lines.getOrNull(row)?.text.orEmpty().padEnd(screen.columns)
        val from = if (row == start.row) start.column else 0
        val to = if (row == end.row) end.column + 1 else screen.columns
        append(
          text.substring(from.coerceAtMost(text.length), to.coerceAtMost(text.length)).trimEnd(),
        )
        // A line the terminal cut because it was too long continues in the next one.
        if (row < end.row && screen.lines.getOrNull(row + 1)?.wrapped != true) append('\n')
      }
    }
  }

  fun copy() {
    selectedText().takeIf { it.isNotEmpty() }?.let { clipboard.setText(AnnotatedString(it)) }
  }

  fun paste() {
    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(actions.paste)
  }

  fun selectAll() {
    selection =
      Cell(0, 0) to
        Cell((screen.lines.size - 1).coerceAtLeast(0), (screen.columns - 1).coerceAtLeast(0))
  }

  // Typing brings the view back to the end (spec 2.6).
  fun typed(send: () -> Unit) {
    distanceFromBottom = 0f
    selection = null
    send()
  }

  // The soft keyboard's side (Android): what it commits is typed as it is. The local functions are
  // reached through these, since the object's own members share their names.
  val copySelection = { copy() }
  val pasteFromClipboard = { paste() }
  val selectEverything = { selectAll() }
  val selectedTextNow = { selectedText() }
  // The ⌘ commands, for the hardware ⌘ key and the keyboard bar's sticky ⌘: C copy, V paste, A
  // select all, K clear buffer. False for any other letter.
  fun command(letter: Char): Boolean {
    when (letter.lowercaseChar()) {
      'c' -> copy()
      'v' -> typed(::paste)
      'a' -> selectAll()
      'k' -> actions.clearBuffer()
      else -> return false
    }
    return true
  }
  SideEffect { input.onCommand = ::command }

  val keyboardTarget =
    object : EditorTextTarget {
      // typed() would drop the selection before ⌘C could copy it.
      override fun type(character: Char) =
        if (input.meta) input.type(character) else typed { input.type(character) }

      override fun enter() = typed { input.key(TerminalKey.VK_ENTER) }

      override fun backspace() = typed { input.key(TerminalKey.VK_BACK_SPACE) }

      override fun delete() = typed { input.key(TerminalKey.VK_DELETE) }

      // A word or a clip the keyboard commits at once; not a paste the user asked for.
      override fun paste(text: String) = typed { input.text(text) }

      override fun pasteClipboard() = pasteFromClipboard()

      override fun copy() = copySelection()

      override fun cut() = copySelection()

      override fun selectAll() = selectEverything()

      // A shell has no text around the caret to read back; the keyboard just types.
      override fun textBeforeCaret(count: Int) = ""

      override fun textAfterCaret(count: Int) = ""

      override fun selectedText() = selectedTextNow()
    }

  fun onKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = event.key
    // Copy and paste: ⌘C / ⌘V on the Mac, where Ctrl+C stays the interrupt key; Ctrl+Shift+C / V
    // elsewhere (IntelliJ's terminal keymaps).
    val clipboardKey =
      if (IS_MAC) event.isMetaPressed else event.isCtrlPressed && event.isShiftPressed
    if (clipboardKey) {
      return when (key) {
        Key.C -> command('c')
        Key.V -> command('v')
        Key.A -> command('a')
        Key.K -> command('k')
        else -> false
      }
    }
    if (event.isMetaPressed) return false
    var modifiers = 0
    if (event.isShiftPressed) modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
    if (event.isCtrlPressed) modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
    if (event.isAltPressed) modifiers = modifiers or InputEvent.ALT_DOWN_MASK
    terminalKeyCode(key)?.let { keyCode ->
      typed { input.key(keyCode, modifiers) }
      return true
    }
    val codePoint = event.utf16CodePoint
    if (codePoint == 0) return false
    val character = String(Character.toChars(codePoint))
    when {
      event.isCtrlPressed ->
        TerminalKeyInput.controlCharacter(character[0])?.let { typed { input.text(it.toString()) } }
          ?: return false
      // Alt+b sends Esc then b (readline's "back a word").
      event.isAltPressed -> typed { input.text("\u001b$character") }
      codePoint >= ' '.code && codePoint != 127 -> typed { input.text(character) }
      else -> return false
    }
    return true
  }

  val fieldBackground = Color(palette.background)
  val selectionColor = Color(scheme.color("SELECTION_BACKGROUND") ?: DEFAULT_SELECTION)
  val scrollState = rememberScrollableState { delta ->
    val before = distanceFromBottom
    distanceFromBottom = (distanceFromBottom + delta).coerceIn(0f, maxTop())
    distanceFromBottom - before
  }

  ContextMenuArea(
    entries = {
      listOf(
        MenuItem(
          "Copy",
          shortcut("⌘C", "Ctrl+Shift+C"),
          enabled = selection != null,
          onClick = ::copy,
        ),
        MenuItem("Paste", shortcut("⌘V", "Ctrl+Shift+V"), onClick = ::paste),
        MenuItem("Select All", onClick = ::selectAll),
        MenuSeparator,
        MenuItem("Clear Buffer", onClick = actions.clearBuffer),
      )
    },
    modifier = modifier.fillMaxSize().background(fieldBackground).onSizeChanged { viewport = it },
  ) {
    Canvas(
      Modifier.fillMaxSize()
        .clipToBounds()
        .scrollable(scrollState, Orientation.Vertical, enabled = !screen.alternate)
        .focusRequester(focusRequester)
        .onFocusChanged {
          focused = it.hasFocus
          if (focused) focus.focused = input else if (focus.focused === input) focus.focused = null
        }
        .then(LocalEditorTextInput.current(keyboardTarget))
        .focusable()
        .onPreviewKeyEvent(::onKey)
        .pointerInput(tab.id) {
          detectTapGestures {
            focusRequester.requestFocus()
            selection = null
          }
        }
        .pointerInput(tab.id) {
          // Drag to select with a mouse only: on touch a drag scrolls.
          awaitEachGesture {
            val down = awaitFirstDown()
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            val anchor = cellAt(down.position)
            drag(down.id) { change ->
              change.consume()
              selection = anchor to cellAt(change.position)
            }
          }
        },
    ) {
      drawScreen(
        screen,
        palette,
        metrics,
        measurer,
        baseStyle,
        top = top(),
        topInset = topInset(),
        leftInset = leftInset,
        fieldBackground = fieldBackground,
        selection = selection,
        selectionColor = selectionColor,
        cursorShown = !focused || !screen.cursor.blinking || blinkOn,
        focused = focused,
        density = density.density,
      )
    }
  }
}

/** Cell size in px: [cellWidth] of one character, [lineHeight], [baseline] from a line's top. */
private class CellMetrics(val cellWidth: Float, val lineHeight: Float, val baseline: Float)

/** `TerminalUi.blockTopInset` and `blockBottomInset`: 6 above the first line, 6 below the last. */
private val VERTICAL_INSET = 6.dp
/**
 * The space before the text: IntelliJ's terminal output editor
 * (`TerminalUiUtils.createOutputEditor`) has no line numbers, icons or folding, but keeps the
 * line-marker area, so its new UI gutter is the empty annotation area 4 + the right free painters
 * 8 + the gap 1 = 13.
 */
private val LEFT_INSET = 13.dp

/** Columns that fit in [width] px after the [leftInset]. */
internal fun terminalColumns(width: Float, leftInset: Float, cellWidth: Float) =
  ((width - leftInset) / cellWidth).toInt()

private const val BLINK_PERIOD_MS = 500L
private const val DEFAULT_SELECTION = 0xFF214283L

private fun DrawScope.drawScreen(
  screen: TerminalScreen,
  palette: TerminalPalette,
  metrics: CellMetrics,
  measurer: androidx.compose.ui.text.TextMeasurer,
  baseStyle: TextStyle,
  top: Float,
  topInset: Float,
  leftInset: Float,
  fieldBackground: Color,
  selection: Pair<Cell, Cell>?,
  selectionColor: Color,
  cursorShown: Boolean,
  focused: Boolean,
  density: Float,
) {
  val first = ((top - topInset) / metrics.lineHeight).toInt().coerceAtLeast(0)
  val last =
    (((top + size.height - topInset) / metrics.lineHeight).toInt()).coerceAtMost(
      screen.lines.size - 1,
    )
  val selected = selection?.let { (a, b) -> if (a <= b) a to b else b to a }

  fun lineTop(row: Int) = topInset + row * metrics.lineHeight - top

  fun colors(run: TerminalRun): Pair<Color, Color?> {
    val foreground = Color(palette.foreground(run.foreground))
    val background = palette.background(run.background)?.let(::Color)
    return if (run.inverse) (background ?: fieldBackground) to foreground
    else foreground to background
  }

  for (row in first..last) {
    val y = lineTop(row)
    val line = screen.lines[row]
    for (run in line.runs) colors(run).second?.let { color ->
      drawRect(
        color,
        Offset(leftInset + run.column * metrics.cellWidth, y),
        Size(run.cells * metrics.cellWidth, metrics.lineHeight),
      )
    }
    if (selected != null && row in selected.first.row..selected.second.row) {
      val from = if (row == selected.first.row) selected.first.column else 0
      val to = if (row == selected.second.row) selected.second.column + 1 else screen.columns
      drawRect(
        selectionColor,
        Offset(leftInset + from * metrics.cellWidth, y),
        Size((to - from) * metrics.cellWidth, metrics.lineHeight),
      )
    }
    for (run in line.runs) {
      if (run.text.isBlank() && !run.underline) continue
      val style =
        baseStyle.copy(
          color = colors(run).first,
          fontWeight = if (run.bold) FontWeight.Bold else null,
          fontStyle = if (run.italic) FontStyle.Italic else null,
          textDecoration = if (run.underline) TextDecoration.Underline else null,
        )
      // The color goes in at draw time: the measurer's cache returns the layout of the same text in
      // an earlier color (color doesn't change the layout), e.g. a letter last measured under the
      // cursor in the background color, which then stays invisible.
      drawText(
        measurer.measure(run.text, style),
        color = style.color,
        topLeft = Offset(leftInset + run.column * metrics.cellWidth, y),
      )
    }
  }

  val cursor = screen.cursor
  val cursorRow = screen.screenTop + cursor.row
  if (!cursor.visible || !cursorShown || cursorRow !in first..last) return
  val x = leftInset + cursor.column * metrics.cellWidth
  val y = lineTop(cursorRow)
  val under =
    screen.lines[cursorRow].runs.firstOrNull {
      cursor.column in it.column until it.column + it.cells
    }
  val cursorColor = under?.let { colors(it).first } ?: Color(palette.foreground)
  val bar = 2f * density
  when {
    cursor.shape == TerminalCursorShape.UNDERLINE ->
      drawRect(cursorColor, Offset(x, y + metrics.lineHeight - bar), Size(metrics.cellWidth, bar))
    cursor.shape == TerminalCursorShape.BAR ->
      drawRect(cursorColor, Offset(x, y), Size(bar, metrics.lineHeight))
    !focused ->
      drawRect(
        cursorColor,
        Offset(x + 0.5f, y + 0.5f),
        Size(metrics.cellWidth - 1f, metrics.lineHeight - 1f),
        style = Stroke(width = 1f),
      )
    else -> {
      drawRect(cursorColor, Offset(x, y), Size(metrics.cellWidth, metrics.lineHeight))
      // The character under a filled block is drawn in the background color.
      if (under != null && under.text.length == under.cells) {
        val character = under.text[cursor.column - under.column].toString()
        if (character.isNotBlank())
          drawText(
            measurer.measure(
              character,
              baseStyle.copy(fontWeight = if (under.bold) FontWeight.Bold else null),
            ),
            color = fieldBackground,
            topLeft = Offset(x, y),
          )
      }
    }
  }
}

/** The AWT-numbered code (JediTerm's `KeyEvent.VK_*`) of a key with no character, else null. */
private fun terminalKeyCode(key: Key): Int? =
  when (key) {
    Key.Enter,
    Key.NumPadEnter -> TerminalKey.VK_ENTER
    Key.Backspace -> TerminalKey.VK_BACK_SPACE
    Key.Tab -> TerminalKey.VK_TAB
    Key.Escape -> TerminalKey.VK_ESCAPE
    Key.DirectionUp -> TerminalKey.VK_UP
    Key.DirectionDown -> TerminalKey.VK_DOWN
    Key.DirectionLeft -> TerminalKey.VK_LEFT
    Key.DirectionRight -> TerminalKey.VK_RIGHT
    Key.MoveHome -> TerminalKey.VK_HOME
    Key.MoveEnd -> TerminalKey.VK_END
    Key.PageUp -> TerminalKey.VK_PAGE_UP
    Key.PageDown -> TerminalKey.VK_PAGE_DOWN
    Key.Delete -> TerminalKey.VK_DELETE
    Key.Insert -> TerminalKey.VK_INSERT
    Key.F1 -> TerminalKey.VK_F1
    Key.F2 -> TerminalKey.VK_F2
    Key.F3 -> TerminalKey.VK_F3
    Key.F4 -> TerminalKey.VK_F4
    Key.F5 -> TerminalKey.VK_F5
    Key.F6 -> TerminalKey.VK_F6
    Key.F7 -> TerminalKey.VK_F7
    Key.F8 -> TerminalKey.VK_F8
    Key.F9 -> TerminalKey.VK_F9
    Key.F10 -> TerminalKey.VK_F10
    Key.F11 -> TerminalKey.VK_F11
    Key.F12 -> TerminalKey.VK_F12
    else -> null
  }
