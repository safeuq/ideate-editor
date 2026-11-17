package intellijandroid.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import intellijandroid.client.session.EditorFile
import intellijandroid.client.session.EditorKey
import intellijandroid.client.session.EditorScheme
import intellijandroid.client.session.Highlight
import intellijandroid.client.session.IndentGuide
import intellijandroid.client.session.Lookup
import intellijandroid.client.session.LookupKey
import intellijandroid.client.session.TextEffect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where each line starts; line i is text[starts[i] until end(i)], without its line break. */
private class Lines(val text: String) {
  val starts: IntArray = buildList {
    add(0)
    text.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
  }
    .toIntArray()
  val count
    get() = starts.size

  fun end(line: Int) = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length

  fun lineOf(offset: Int): Int =
    starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }.coerceIn(0, count - 1)

  fun text(line: Int) = text.substring(starts[line], end(line))
}

/** Editor geometry in px, following IntelliJ's `EditorView` and `EditorGutterLayout` (New UI). */
private class EditorMetrics(
  val lineHeight: Float,
  val baseline: Float,
  val caretTop: Float,
  val caretHeight: Float,
  val charWidth: Float,
  val gutterWidth: Float,
  val lineNumbersRight: Float,
  val iconAreaLeft: Float,
)

/**
 * What the editor asks the session to do; the session applies it locally first and syncs it with
 * the backend.
 */
class EditorActions(
  val type: (Char) -> Unit,
  val key: (EditorKey) -> Unit,
  /** New caret offset and selection anchor (equal to the offset when nothing is selected). */
  val moveCaret: (Int, Int) -> Unit,
  val undo: () -> Unit,
  val redo: () -> Unit,
  /** Ctrl+Space. */
  val complete: () -> Unit = {},
  /** Keys for the completion popup while it is open. */
  val lookupKey: (LookupKey) -> Unit = {},
  /** A click or tap on a completion popup row, by item id. */
  val chooseLookupItem: (String) -> Unit = {},
  /** Paste this text (the system clipboard's) through the backend's paste. */
  val paste: (String) -> Unit = {},
  /** The same through the backend's Paste as Plain Text (`EditorPasteSimple`). */
  val pasteSimple: (String) -> Unit = {},
  /** Text IDEate just copied, for the backend's clipboard. */
  val copied: (String) -> Unit = {},
)

@Composable
fun EditorView(
  file: EditorFile,
  scheme: EditorScheme,
  actions: EditorActions,
  modifier: Modifier = Modifier,
  lookup: Lookup? = null,
  scroll: SidewaysScroll = remember { SidewaysScroll() },
) {
  val fonts = LocalClientFonts.current
  val density = LocalDensity.current
  val measurer = rememberTextMeasurer(cacheSize = 512)
  val lines = remember(file.text) { Lines(file.text) }
  val textStyle =
    remember(fonts, scheme) {
      TextStyle(
        fontFamily = fonts.editor,
        fontSize = IslandsDark.EDITOR_FONT_SIZE.sp,
        color = Color(scheme.foreground),
      )
    }
  val lineNumberStyle =
    remember(fonts) {
      TextStyle(fontFamily = fonts.editor, fontSize = IslandsDark.LINE_NUMBER_FONT_SIZE.sp)
    }

  val metrics =
    remember(textStyle, lines.count, density) {
      with(density) {
        // IntelliJ: lineHeight = ceil(fontMetricsHeight × lineSpacing); the extra space is split
        // above and below.
        val sample = measurer.measure("0".repeat(100), textStyle)
        val fontHeight = ceil(sample.size.height.toFloat() / density.density) * density.density
        val lineHeight =
          ceil(fontHeight / density.density * IslandsDark.LINE_SPACING) * density.density
        val extra = ((lineHeight - fontHeight) / density.density / 2).toInt() * density.density
        val lineNumberWidth =
          max(
            14.dp.toPx(),
            ceil(
              measurer
                .measure("9".repeat(lines.count.toString().length), lineNumberStyle)
                .size
                .width
                .toFloat(),
            ),
          )
        // Gutter as measured on the real client (Islands Dark, no VCS markers): numbers, 4, icons
        // 17, 4,
        // folding 11, 6. With two-digit line numbers that is 56 px, text starting right after.
        val gutterWidth =
          lineNumberWidth + 4.dp.toPx() + 17.dp.toPx() + 4.dp.toPx() + 11.dp.toPx() + 6.dp.toPx()
        EditorMetrics(
          lineHeight = lineHeight,
          baseline = sample.firstBaseline + extra,
          caretTop = extra,
          caretHeight = fontHeight,
          charWidth = sample.size.width / 100f,
          gutterWidth = gutterWidth,
          lineNumbersRight = lineNumberWidth,
          // Icons area (17 px) after the line numbers and their 4 px gap.
          iconAreaLeft = lineNumberWidth + 4.dp.toPx(),
        )
      }
    }
  // Keyed on the lists, not the file: a caret move must not rebuild them (every line would redraw).
  // A line keeps its previous list when its highlights didn't change, so only lines whose
  // highlighting changed recompose and measure their text again.
  val previousBuckets = remember(lines) { HashMap<Int, List<Highlight>>() }
  val highlightsByLine =
    remember(file.highlights, lines) {
      bucketByLine(file.highlights, lines)
        .mapValues { (line, highlights) ->
          previousBuckets[line]?.takeIf { it == highlights } ?: highlights
        }
        .also {
          previousBuckets.clear()
          previousBuckets.putAll(it)
        }
    }
  val gutterIconsByLine =
    remember(file.gutterIcons, lines) { file.gutterIcons.groupBy { lines.lineOf(it.offset) } }
  // IndentGuideRenderer: a guide is drawn on the lines after its start line and before its end
  // line.
  val guidesByLine =
    remember(file.indentGuides, lines) {
      val result = HashMap<Int, MutableList<IndentGuide>>()
      for (guide in file.indentGuides) {
        for (line in guide.startLine + 1 until minOf(guide.endLine, lines.count)) {
          result.getOrPut(line) { mutableListOf() } += guide
        }
      }
      result
    }
  val longestLine =
    remember(lines) { (0 until lines.count).maxOfOrNull { lines.end(it) - lines.starts[it] } ?: 0 }

  // Caret and selection belong to the session (they are synced with the backend); only the column
  // to keep when
  // moving up and down, and the drag start, live here.
  val caret = file.caret
  val anchor =
    if (file.selectionStart == file.selectionEnd) null
    else if (file.selectionStart == caret) file.selectionEnd else file.selectionStart
  var preferredColumn by remember(file.path) { mutableIntStateOf(0) }
  var dragAnchor by remember(file.path) { mutableIntStateOf(0) }
  var focused by remember { mutableStateOf(false) }
  var caretOn by remember { mutableStateOf(true) }
  val focusRequester = remember { FocusRequester() }
  val listState = rememberLazyListState()
  val stickyScroll = rememberCoroutineScope()
  // The pointer handlers below live across recompositions; they must read the current text, not the
  // first one.
  val currentLines by rememberUpdatedState(lines)
  val currentFile by rememberUpdatedState(file)

  LaunchedEffect(caret, focused) {
    caretOn = true
    while (focused) {
      delay(500)
      caretOn = !caretOn
    }
  }

  // Keep the caret's line on screen: after typing or arrows past an edge, and when the view gets
  // shorter
  // (on Android the soft keyboard takes the lower half of the screen).
  val caretLine = lines.lineOf(caret)
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  val viewportHeight = viewport.height
  LaunchedEffect(caretLine, viewportHeight, focused) {
    if (!focused || viewportHeight == 0) return@LaunchedEffect
    val layout = listState.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == caretLine }
    val lineHeight = layout.visibleItemsInfo.firstOrNull()?.size ?: 0
    when {
      item == null && caretLine < listState.firstVisibleItemIndex ->
        listState.scrollToItem(caretLine)
      item == null -> {
        listState.scrollToItem(caretLine)
        listState.scrollBy(
          -(layout.viewportEndOffset - layout.viewportStartOffset - lineHeight).toFloat(),
        )
      }
      item.offset < layout.viewportStartOffset ->
        listState.scrollBy((item.offset - layout.viewportStartOffset).toFloat())
      item.offset + item.size > layout.viewportEndOffset ->
        listState.scrollBy((item.offset + item.size - layout.viewportEndOffset).toFloat())
    }
  }

  LaunchedEffect(longestLine, viewport.width, metrics) {
    // Room for the widest line plus two columns, like IntelliJ's scrolling past the longest line.
    scroll.max =
      maxOf(0f, (longestLine + 2) * metrics.charWidth - (viewport.width - metrics.gutterWidth))
    scroll.x = scroll.x.coerceIn(0f, scroll.max)
  }
  LaunchedEffect(file.path) { scroll.x = 0f }
  LaunchedEffect(caret, viewport.width, focused) {
    if (!focused || viewport.width == 0) return@LaunchedEffect
    val caretX = (caret - lines.starts[caretLine]) * metrics.charWidth
    val textWidth = viewport.width - metrics.gutterWidth
    scroll.x =
      when {
        caretX < scroll.x -> caretX
        caretX > scroll.x + textWidth - 2 * metrics.charWidth ->
          caretX - textWidth + 2 * metrics.charWidth
        else -> scroll.x
      }.coerceIn(0f, scroll.max)
  }

  // IntelliJ's IndentsModel.getCaretIndentGuide plus IndentGuideRenderer.isCaretOnGuide: the guide
  // at the caret's column whose block holds the caret is drawn in the selected color.
  val caretGuide =
    remember(file.indentGuides, caret, lines) {
      val column = caret - lines.starts[caretLine]
      file.indentGuides.firstOrNull { guide ->
        val firstNonSpace =
          (lines.starts[guide.startLine] until lines.end(guide.startLine)).firstOrNull {
            file.text[it] != ' ' && file.text[it] != '\t'
          } ?: lines.starts[guide.startLine]
        val end = if (guide.endLine < lines.count) lines.starts[guide.endLine] else file.text.length
        column == guide.indentLevel && caret >= firstNonSpace && caret < end
      }
    }

  fun moveTo(offset: Int, extend: Boolean, keepColumn: Boolean = false) {
    val now = currentFile
    val target = offset.coerceIn(0, now.text.length)
    val currentAnchor =
      if (now.selectionStart == now.selectionEnd) null
      else if (now.selectionStart == now.caret) now.selectionEnd else now.selectionStart
    actions.moveCaret(target, if (extend) (currentAnchor ?: now.caret) else target)
    if (!keepColumn) preferredColumn = target - currentLines.starts[currentLines.lineOf(target)]
  }

  /**
   * Typed characters. ponytail: key events, no IME; Android needs an InputConnection for its soft
   * keyboard.
   */
  fun typeCharacter(event: KeyEvent): Boolean {
    val character = event.utf16CodePoint
    // Arrow and function keys report private-use or non-character codes (e.g. U+FFFF); those aren't
    // text.
    val isText =
      !Character.isISOControl(character) &&
        character != 0xFFFF &&
        Character.getType(character) != Character.PRIVATE_USE.toInt()
    if (!file.editable || event.isMetaPressed || event.isCtrlPressed || !isText) return false
    actions.type(character.toChar())
    return true
  }

  val clipboard = LocalClipboardManager.current

  /** The selection, or the caret's whole line with its line break: what IntelliJ copies. */
  fun copyRange(): Pair<Int, Int> {
    val current = currentFile
    if (current.selectionStart != current.selectionEnd)
      return minOf(current.selectionStart, current.selectionEnd) to
        maxOf(current.selectionStart, current.selectionEnd)
    val line = currentLines.lineOf(current.caret)
    val end =
      if (line + 1 < currentLines.count) currentLines.starts[line + 1] else current.text.length
    return currentLines.starts[line] to end
  }

  fun copy() {
    val (start, end) = copyRange()
    val copiedText = currentFile.text.substring(start, end)
    clipboard.setText(AnnotatedString(copiedText))
    actions.copied(copiedText)
  }

  fun paste() {
    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(actions.paste)
  }

  fun pasteSimple() {
    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(actions.pasteSimple)
  }

  fun cut() {
    val (start, end) = copyRange()
    if (start == end) return
    copy()
    actions.moveCaret(end, start)
    actions.key(EditorKey.BACKSPACE)
  }

  fun onKey(event: KeyEvent): Boolean {
    val key = event.key
    val shift = event.isShiftPressed
    val meta = event.isMetaPressed
    val line = lines.lineOf(caret)
    if (lookup != null) {
      when (key) {
        Key.DirectionUp -> {
          actions.lookupKey(LookupKey.UP)
          return true
        }
        Key.DirectionDown -> {
          actions.lookupKey(LookupKey.DOWN)
          return true
        }
        Key.Enter,
        Key.Tab -> {
          actions.lookupKey(LookupKey.ACCEPT)
          return true
        }
        Key.Escape -> {
          actions.lookupKey(LookupKey.CANCEL)
          return true
        }
      }
    }
    if ((meta || event.isCtrlPressed) && key == Key.C) {
      copy()
      return true
    }
    if (file.editable && event.isCtrlPressed && key == Key.Spacebar) {
      actions.complete()
      return true
    }
    if (file.editable) {
      when {
        key == Key.Enter -> {
          actions.key(EditorKey.ENTER)
          return true
        }
        key == Key.Backspace -> {
          actions.key(EditorKey.BACKSPACE)
          return true
        }
        key == Key.Delete -> {
          actions.key(EditorKey.DELETE)
          return true
        }
        (meta || event.isCtrlPressed) && key == Key.V -> {
          paste()
          return true
        }
        (meta || event.isCtrlPressed) && key == Key.X -> {
          cut()
          return true
        }
        meta && key == Key.Z -> {
          if (shift) actions.redo() else actions.undo()
          return true
        }
      }
    }
    fun lineOffset(targetLine: Int) =
      lines.starts[targetLine] +
        minOf(preferredColumn, lines.end(targetLine) - lines.starts[targetLine])
    when (key) {
      Key.DirectionLeft -> moveTo(if (meta) lines.starts[line] else caret - 1, shift)
      Key.DirectionRight -> moveTo(if (meta) lines.end(line) else caret + 1, shift)
      Key.DirectionUp ->
        if (line > 0) moveTo(lineOffset(line - 1), shift, keepColumn = true) else moveTo(0, shift)
      Key.DirectionDown ->
        if (line + 1 < lines.count) moveTo(lineOffset(line + 1), shift, keepColumn = true)
        else moveTo(file.text.length, shift)
      Key.MoveHome -> {
        // IntelliJ's smart Home: the first non-space character, then column 0 when already there.
        val start = lines.starts[line]
        val firstNonSpace =
          (start until lines.end(line)).firstOrNull { !file.text[it].isWhitespace() } ?: start
        moveTo(if (file.caret == firstNonSpace) start else firstNonSpace, shift)
      }
      Key.MoveEnd -> moveTo(lines.end(line), shift)
      Key.A -> if (meta) actions.moveCaret(file.text.length, 0) else return typeCharacter(event)
      else -> return typeCharacter(event)
    }
    return true
  }

  fun offsetAt(position: Offset): Int {
    val visible = listState.layoutInfo.visibleItemsInfo
    val item = visible.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
    val line = item?.index ?: if (position.y < 0) 0 else currentLines.count - 1
    val column =
      ((position.x - metrics.gutterWidth + scroll.x) / metrics.charWidth)
        .roundToInt()
        .coerceAtLeast(0)
    val offset = minOf(currentLines.starts[line] + column, currentLines.end(line))
    if (System.getenv("CLIENT_EDIT_DEBUG") != null)
      println(
        "[edit] pointer $position -> line ${line + 1} column $column offset $offset (first visible item offset ${visible.firstOrNull()?.offset})",
      )
    return offset
  }

  // IntelliJ's editor context menu (EditorPopupMenu), what IDEate can do enabled, the rest greyed
  // out (docs/intellij-look.md, section 13).
  fun menu(): List<MenuEntry> {
    val current = currentFile
    val hasSelection = current.selectionStart != current.selectionEnd
    return listOf(
      MenuItem(
        "Show Context Actions",
        shortcut("⌥⏎", "Alt+Enter"),
        enabled = false,
        iconPath = "expui/codeInsight/intentionBulb.svg",
      ),
      SubMenu(
        "Copy / Paste Special",
        listOf(
          MenuItem("Copy Absolute Path", shortcut("⇧⌘C", "Ctrl+Shift+C")) {
            clipboard.setText(AnnotatedString(current.path))
          },
          MenuItem("Copy File Name") { clipboard.setText(AnnotatedString(current.name)) },
          MenuItem("Copy Reference", shortcut("⌥⇧⌘C", "Ctrl+Alt+Shift+C"), enabled = false),
          MenuSeparator,
          MenuItem("Copy as Plain Text", enabled = hasSelection) { copy() },
          MenuItem("Copy as Rich Text", enabled = false),
          MenuSeparator,
          MenuItem("Paste from History…", shortcut("⇧⌘V", "Ctrl+Shift+V"), enabled = false),
          MenuItem(
            "Paste as Plain Text",
            shortcut("⌥⇧⌘V", "Ctrl+Alt+Shift+V"),
            enabled = current.editable,
            onClick = ::pasteSimple,
          ),
        ),
      ),
      MenuItem(
        "Cut",
        shortcut("⌘X", "Ctrl+X"),
        enabled = current.editable,
        iconPath = "expui/general/cut.svg",
        onClick = ::cut,
      ),
      MenuItem(
        "Copy",
        shortcut("⌘C", "Ctrl+C"),
        iconPath = "expui/general/copy.svg",
        onClick = ::copy,
      ),
      MenuItem(
        "Paste",
        shortcut("⌘V", "Ctrl+V"),
        enabled = current.editable,
        iconPath = "expui/general/paste.svg",
        onClick = ::paste,
      ),
      MenuItem("Column Selection Mode", shortcut("⇧⌘8", "Alt+Shift+Insert"), enabled = false),
      MenuSeparator,
      MenuItem("Find Usages", shortcut("⌥F7", "Alt+F7"), enabled = false),
      SubMenu("Go To", emptyList(), enabled = false),
      SubMenu("Folding", emptyList(), enabled = false),
      SubMenu("Refactor", emptyList(), enabled = false),
      MenuItem("Generate…", shortcut("⌘N", "Alt+Insert"), enabled = false),
      MenuSeparator,
      MenuItem(
        "Run…",
        shortcut("⌃⇧R", "Ctrl+Shift+F10"),
        enabled = false,
        iconPath = "expui/run/run.svg",
      ),
      MenuItem(
        "Debug…",
        shortcut("⌃⇧D", "Ctrl+Shift+F9"),
        enabled = false,
        iconPath = "expui/run/debug.svg",
      ),
      SubMenu("More Run/Debug", emptyList(), enabled = false),
      MenuSeparator,
      SubMenu("Open In", emptyList(), enabled = false),
      SubMenu("Local History", emptyList(), enabled = false),
      SubMenu("Git", emptyList(), enabled = false),
      MenuSeparator,
      MenuItem("Compare with Clipboard", enabled = false),
    )
  }

  ContextMenuArea(
    entries = ::menu,
    modifier = modifier.onSizeChanged { viewport = it },
    // Like IntelliJ: a right click outside the selection moves the caret there first.
    onOpen = { position ->
      val offset = offsetAt(position)
      val current = currentFile
      val start = minOf(current.selectionStart, current.selectionEnd)
      val end = maxOf(current.selectionStart, current.selectionEnd)
      if (start == end || offset !in start..end) moveTo(offset, extend = false)
    },
  ) {
    LazyColumn(
      state = listState,
      modifier =
        Modifier.fillMaxSize()
          .scrollable(scroll.scrollable, Orientation.Horizontal, reverseDirection = true)
          .background(Color(scheme.background))
          .drawWithContent {
            drawContent()
            // The gutter's border line, the editor's full height (New UI,
            // EditorGutterComponentImpl.paint): 1 px in the scheme's INDENT_GUIDE color at
            // getWhitespaceSeparatorOffset() = gutter width - 3, over the caret row.
            scheme.color("INDENT_GUIDE")?.let { separatorColor ->
              val separatorX = metrics.gutterWidth - 3.dp.toPx() + 0.5.dp.toPx()
              drawLine(
                Color(separatorColor),
                Offset(separatorX, 0f),
                Offset(separatorX, size.height),
                strokeWidth = 1.dp.toPx(),
              )
            }
          }
          .focusRequester(focusRequester)
          .onFocusChanged { focused = it.hasFocus }
          .then(LocalEditorTextInput.current)
          .focusable()
          .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event) }
          .pointerInput(file.path, metrics) {
            // On tap, not on press: a swipe that starts on the editor (phone portrait) must not
            // focus it and open the keyboard. A tap that only stops a fling doesn't move the caret.
            var stoppedScroll = false
            detectTapGestures(
              onPress = {
                stoppedScroll = listState.isScrollInProgress || scroll.scrollable.isScrollInProgress
              },
              onTap = { position ->
                if (stoppedScroll) return@detectTapGestures
                focusRequester.requestFocus()
                moveTo(offsetAt(position), extend = false)
              },
            )
          }
          .pointerInput(file.path, metrics) {
            // Drag to select with a mouse only: on touch a drag scrolls, and selecting there moved
            // the caret to where a scroll started.
            awaitEachGesture {
              val down = awaitFirstDown()
              if (down.type != PointerType.Mouse) return@awaitEachGesture
              dragAnchor = offsetAt(down.position)
              drag(down.id) { change ->
                change.consume()
                actions.moveCaret(offsetAt(change.position), dragAnchor)
              }
            }
          },
    ) {
      items(lines.count) { line ->
        // Plain values, and only for the lines they concern: lines whose parameters don't change
        // are skipped when the caret blinks, moves or the selection changes.
        val selectionStart = anchor?.let { minOf(it, caret) } ?: -1
        val selectionEnd = anchor?.let { maxOf(it, caret) } ?: -1
        val inSelection =
          selectionStart >= 0 &&
            selectionStart <= lines.end(line) &&
            selectionEnd >= lines.starts[line]
        val lineGuides = guidesByLine[line].orEmpty()
        EditorLine(
          line = line,
          lines = lines,
          highlights = highlightsByLine[line].orEmpty(),
          guides = lineGuides,
          selectedGuideLevel = caretGuide?.takeIf { it in lineGuides }?.indentLevel ?: -1,
          gutterIconPath = gutterIconsByLine[line]?.firstOrNull()?.iconPaths?.firstOrNull(),
          scheme = scheme,
          metrics = metrics,
          measurer = measurer,
          textStyle = textStyle,
          lineNumberStyle = lineNumberStyle,
          caretColumn = if (lines.lineOf(caret) == line) caret - lines.starts[line] else null,
          caretVisible = focused && caretOn && line == caretLine,
          selectionStart = if (inSelection) selectionStart else -1,
          selectionEnd = if (inSelection) selectionEnd else -1,
          scroll = scroll,
        )
      }
    }
    // Sticky lines (IntelliJ's StickyLinesPanel): the start lines of the scopes the top of the view
    // is in, pinned over the text, with a border line and a shadow under them.
    val stickyScopes =
      remember(file.stickyRanges, lines) {
        file.stickyRanges.map {
          StickyScope(lines.lineOf(it.start), lines.lineOf(it.end), it.start)
        }
      }
    val sticky by
      remember(stickyScopes, metrics, viewportHeight) {
        derivedStateOf {
          stickyPanel(
            stickyScopes,
            listState.firstVisibleItemIndex * metrics.lineHeight +
              listState.firstVisibleItemScrollOffset,
            metrics.lineHeight,
            viewportHeight.toFloat(),
          )
        }
      }
    if (sticky.lines.isNotEmpty()) {
      val stickyBackground = Color(scheme.color("STICKY_LINES_BACKGROUND") ?: scheme.background)
      // StickyLineColors: STICKY_LINES_BORDER_COLOR, else RIGHT_MARGIN_COLOR, else the background.
      val borderColor =
        Color(
          scheme.color("STICKY_LINES_BORDER_COLOR")
            ?: scheme.color("RIGHT_MARGIN_COLOR")
            ?: scheme.background,
        )
      // Dark schemes: a shadow of 0.25 line heights, black at alpha 32 fading out.
      val shadowHeight = (metrics.lineHeight * 0.25f).toInt().toFloat()
      with(density) {
        Box(Modifier.fillMaxWidth().height(sticky.height.toDp()).clipToBounds()) {
          sticky.lines.forEachIndexed { row, pinned ->
            val line = pinned.scope.primaryLine
            Box(
              Modifier.offset(y = pinned.y.toDp())
                .fillMaxWidth()
                .height(metrics.lineHeight.toDp())
                .background(stickyBackground)
                .pointerInput(pinned) {
                  // StickyLineComponent: a click puts the caret at the scope's name and scrolls
                  // it into view, here just under the pinned lines above it.
                  detectTapGestures {
                    val target = pinned.scope.navigateOffset
                    actions.moveCaret(target, target)
                    stickyScroll.launch {
                      listState.scrollToItem(maxOf(0, line - row))
                      // Focus only once the session has the new caret: gaining focus scrolls to
                      // the caret, which would still be the old one.
                      withTimeoutOrNull(1_000) {
                        snapshotFlow { currentFile.caret }.first { it == target }
                      }
                      focusRequester.requestFocus()
                    }
                  }
                },
            ) {
              EditorLine(
                line = line,
                lines = lines,
                highlights = highlightsByLine[line].orEmpty(),
                guides = guidesByLine[line].orEmpty(),
                selectedGuideLevel = -1,
                gutterIconPath = gutterIconsByLine[line]?.firstOrNull()?.iconPaths?.firstOrNull(),
                scheme = scheme,
                metrics = metrics,
                measurer = measurer,
                textStyle = textStyle,
                lineNumberStyle = lineNumberStyle,
                caretColumn = null,
                caretVisible = false,
                selectionStart = -1,
                selectionEnd = -1,
                scroll = scroll,
              )
            }
          }
        }
        Box(
          Modifier.offset(y = sticky.height.toDp())
            .fillMaxWidth()
            .height(1.dp)
            .background(borderColor),
        )
        Box(
          Modifier.offset(y = sticky.height.toDp() + 1.dp)
            .fillMaxWidth()
            .height(shadowHeight.toDp())
            .background(
              Brush.verticalGradient(listOf(Color(0, 0, 0, 32), Color(0, 0, 0, 0))),
            ),
        )
      }
    }
    // The completion popup sits under the caret line, its item text lined up with the first letter
    // of the prefix.
    lookup
      ?.takeIf { it.items.isNotEmpty() }
      ?.let { current ->
        val caretItem =
          listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == caretLine } ?: return@let
        val prefixColumn = caret - lines.starts[caretLine] - current.prefix.length
        val textOffset = with(density) { LOOKUP_PREFIX_INSET.toPx() }
        val x =
          (metrics.gutterWidth + prefixColumn * metrics.charWidth - scroll.x - textOffset)
            .roundToInt()
            .coerceAtLeast(0)
        Popup(offset = IntOffset(x, caretItem.offset + caretItem.size)) {
          LookupPopup(current, actions.chooseLookupItem)
        }
      }
  }
}

@Composable
private fun EditorLine(
  line: Int,
  lines: Lines,
  highlights: List<Highlight>,
  guides: List<IndentGuide>,
  selectedGuideLevel: Int,
  gutterIconPath: String?,
  scheme: EditorScheme,
  metrics: EditorMetrics,
  measurer: TextMeasurer,
  textStyle: TextStyle,
  lineNumberStyle: TextStyle,
  caretColumn: Int?,
  caretVisible: Boolean,
  selectionStart: Int,
  selectionEnd: Int,
  scroll: SidewaysScroll,
) {
  val density = LocalDensity.current
  val lineStart = lines.starts[line]
  val lineText = remember(lines, line) { lines.text(line) }
  val annotated = remember(lineText, highlights) { styledLine(lineText, lineStart, highlights) }
  val layout =
    remember(annotated, textStyle) { measurer.measure(annotated, textStyle, softWrap = false) }
  val lineNumber =
    remember(line, lineNumberStyle) {
      measurer.measure(AnnotatedString((line + 1).toString()), lineNumberStyle)
    }
  val height = with(density) { metrics.lineHeight.toDp() }
  val gutterIcon = gutterIconPath?.let(LocalIcons.current::painter)

  Canvas(Modifier.fillMaxWidth().height(height)) {
    val isCaretLine = caretColumn != null
    if (isCaretLine) scheme.color("CARET_ROW_COLOR")?.let { drawRect(Color(it)) }

    val textLeft = metrics.gutterWidth
    // Read here, in drawing: scrolling sideways redraws the visible lines without recomposing them.
    val scrollX = scroll.x
    fun x(column: Int) =
      textLeft +
        layout.getHorizontalPosition(
          column.coerceIn(0, lineText.length),
          usePrimaryDirection = true,
        )

    clipRect(left = textLeft) {
      translate(left = -scrollX) {
        // Selection: to the end of the text on the last line, to the right edge on lines it
        // continues
        // past.
        if (selectionStart in 0 until selectionEnd) {
          val from = maxOf(selectionStart, lineStart) - lineStart
          val lineEnd = lineStart + lineText.length
          if (selectionStart <= lineEnd && selectionEnd >= lineStart) {
            val to =
              if (selectionEnd > lineEnd) size.width + scrollX else x(selectionEnd - lineStart)
            scheme.color("SELECTION_BACKGROUND")?.let {
              drawRect(Color(it), Offset(x(from), 0f), Size(to - x(from), size.height))
            }
          }
        }
        // Highlight backgrounds (identifier under caret, search results, matched braces, …).
        for (highlight in highlights) {
          val background = highlight.style.background ?: continue
          val from = maxOf(highlight.start - lineStart, 0)
          val to = minOf(highlight.end - lineStart, lineText.length)
          if (to > from)
            drawRect(Color(background), Offset(x(from), 0f), Size(x(to) - x(from), size.height))
        }
        // Indent guides (IndentGuideRenderer): 1 px at the guide's column, moved left by
        // EditorPainter.getIndentGuideShift = -round(5 × fontSize / 13) / 2, which is 2 px at size
        // 12.
        for (guide in guides) {
          val key =
            if (guide.indentLevel == selectedGuideLevel) "SELECTED_INDENT_GUIDE" else "INDENT_GUIDE"
          val guideColor = scheme.color(key) ?: continue
          val shift = (Math.round(5 * IslandsDark.EDITOR_FONT_SIZE / 13f) / 2).dp.toPx()
          val guideX = textLeft + guide.indentLevel * metrics.charWidth - shift
          drawLine(
            Color(guideColor),
            Offset(guideX, 0f),
            Offset(guideX, size.height),
            strokeWidth = 1.dp.toPx(),
          )
        }

        drawText(layout, topLeft = Offset(textLeft, metrics.baseline - layout.firstBaseline))
        for (highlight in highlights) drawEffect(
          highlight,
          lineStart,
          lineText.length,
          ::x,
          metrics,
        )

        if (caretColumn != null && caretVisible) {
          scheme.color("CARET_COLOR")?.let { caretColor ->
            drawRect(
              Color(caretColor),
              Offset(x(caretColumn), metrics.caretTop),
              Size(2.dp.toPx(), metrics.caretHeight),
            )
          }
        }
      }
    }

    // Gutter: line number right-aligned on the editor's baseline, brighter on the caret line.
    val numberColor =
      scheme
        .color(if (isCaretLine) "LINE_NUMBER_ON_CARET_ROW_COLOR" else "LINE_NUMBERS_COLOR")
        ?.let { Color(it) } ?: Color.Gray
    drawText(
      lineNumber,
      color = numberColor,
      topLeft =
        Offset(
          metrics.lineNumbersRight - lineNumber.size.width,
          metrics.baseline - lineNumber.firstBaseline,
        ),
    )
    // Gutter icon (e.g. the green run marker), 14 px, centered in the 17 px icons area.
    if (gutterIcon != null) {
      val iconSize = 14.dp.toPx()
      translate(
        metrics.iconAreaLeft + (17.dp.toPx() - iconSize) / 2,
        (size.height - iconSize) / 2,
      ) {
        with(gutterIcon) { draw(Size(iconSize, iconSize)) }
      }
    }
  }
}

private fun DrawScope.drawEffect(
  highlight: Highlight,
  lineStart: Int,
  lineLength: Int,
  x: (Int) -> Float,
  metrics: EditorMetrics,
) {
  val effect = highlight.style.effect ?: return
  val color = Color(highlight.style.effectColor ?: return)
  val from = maxOf(highlight.start - lineStart, 0)
  val to = minOf(highlight.end - lineStart, lineLength)
  if (to <= from) return
  val left = x(from)
  val right = x(to)
  val baseline = metrics.baseline
  when (effect) {
    // IntelliJ's EffectPainter2D: a 2 px period zig-zag 3 to 6 px below the baseline.
    TextEffect.WAVE_UNDERSCORE -> {
      val top = baseline + 3.dp.toPx()
      val bottom = baseline + 5.dp.toPx()
      val step = 1.dp.toPx()
      val path =
        Path().apply {
          moveTo(left, bottom)
          var position = left
          var up = true
          while (position < right) {
            position += step
            lineTo(position.coerceAtMost(right), if (up) top else bottom)
            up = !up
          }
        }
      drawPath(path, color, style = Stroke(width = 0.7.dp.toPx()))
    }
    TextEffect.LINE_UNDERSCORE,
    TextEffect.BOLD_LINE_UNDERSCORE -> {
      val y = baseline + 1.5.dp.toPx()
      drawLine(
        color,
        Offset(left, y),
        Offset(right, y),
        strokeWidth = (if (effect == TextEffect.BOLD_LINE_UNDERSCORE) 2 else 1).dp.toPx(),
      )
    }
    TextEffect.STRIKEOUT -> {
      val y = baseline - 3.5.dp.toPx()
      drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
    }
    TextEffect.BOXED ->
      drawRect(
        color,
        Offset(left, metrics.caretTop),
        Size(right - left, metrics.caretHeight),
        style = Stroke(1.dp.toPx()),
      )
    TextEffect.BOLD_DOTTED_LINE -> {
      val y = baseline + 1.5.dp.toPx()
      var position = left
      while (position < right) {
        drawLine(
          color,
          Offset(position, y),
          Offset(minOf(position + 2.dp.toPx(), right), y),
          strokeWidth = 2.dp.toPx(),
        )
        position += 4.dp.toPx()
      }
    }
  }
}

/**
 * The line's text with colors and font styles of the highlights over it; higher layers paint last
 * and win.
 */
private fun styledLine(
  lineText: String,
  lineStart: Int,
  highlights: List<Highlight>,
): AnnotatedString {
  val builder = AnnotatedString.Builder(lineText)
  for (highlight in highlights) {
    val style = highlight.style
    if (style.foreground == null && style.fontType == 0) continue
    val from = maxOf(highlight.start - lineStart, 0)
    val to = minOf(highlight.end - lineStart, lineText.length)
    if (to <= from) continue
    builder.addStyle(
      SpanStyle(
        color = style.foreground?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (style.isBold) FontWeight.Bold else null,
        fontStyle = if (style.isItalic) FontStyle.Italic else null,
      ),
      from,
      to,
    )
  }
  return builder.toAnnotatedString()
}

/**
 * Highlights per line, in layer order. ponytail: one pass over all highlights; fine for files of a
 * few thousand lines.
 */
private fun bucketByLine(highlights: List<Highlight>, lines: Lines): Map<Int, List<Highlight>> {
  val result = HashMap<Int, MutableList<Highlight>>()
  for (highlight in highlights) {
    if (highlight.end <= highlight.start) continue
    for (line in lines.lineOf(highlight.start)..lines.lineOf(highlight.end - 1)) result.getOrPut(
      line,
    ) {
      mutableListOf()
    } += highlight
  }
  return result
}
