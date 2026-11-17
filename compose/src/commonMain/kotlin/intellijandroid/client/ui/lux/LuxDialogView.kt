package intellijandroid.client.ui.lux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jetbrains.rd.ide.model.LxEvent
import com.jetbrains.rd.ide.model.LxInputMethodEvent
import com.jetbrains.rd.ide.model.LxInputMethodEventType
import com.jetbrains.rd.ide.model.LxKeyEvent
import com.jetbrains.rd.ide.model.LxKeyEventType
import com.jetbrains.rd.ide.model.LxMouseEvent
import com.jetbrains.rd.ide.model.LxMouseEventType
import com.jetbrains.rd.ide.model.LxWindowEvent
import com.jetbrains.rd.ide.model.LxWindowEventType
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.LuxDialog
import intellijandroid.client.ui.EditorTextTarget
import intellijandroid.client.ui.LocalEditorTextInput
import intellijandroid.client.ui.dialog.DialogLook
import intellijandroid.client.ui.dialog.dialogFrame
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The backend's open windows: each dialog in its own window, oldest below, and the popups (a
 * combo's list, …) over the top dialog at the place the backend put them, e.g. Rename's Scope list
 * just under the Scope combo. A popup with no dialog open gets a window of its own.
 */
@Composable
fun LuxDialogs(dialogs: List<LuxDialog>, session: ClientSession) {
  val windows = dialogs.filterNot { it.popup }
  val popups = dialogs.filter { it.popup }
  val top = windows.lastOrNull()
  for (dialog in windows) key(dialog.id) {
    LuxDialogWindow(dialog, session, if (dialog === top) popups else emptyList())
  }
  if (top == null)
    for (popup in popups) key(popup.id) { LuxDialogWindow(popup, session, emptyList()) }
}

/**
 * One backend dialog: IntelliJ's title row (macOS draws it in the title bar; the backend paints
 * only the content), then the content the backend paints, replayed onto a bitmap, with [popups]
 * over it. A backend pixel is a dp; a dialog wider than the screen is scaled down to fit, and input
 * scaled back.
 */
@Composable
private fun LuxDialogWindow(dialog: LuxDialog, session: ClientSession, popups: List<LuxDialog>) {
  val fonts = LocalClientFonts.current
  fun send(event: LxEvent) = session.luxEvent(dialog.id, event)
  Dialog(
    onDismissRequest = { closeWindow(::send) },
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnClickOutside = dialog.popup || !dialog.modal,
      ),
  ) {
    BoxWithConstraints {
      val fit = if (dialog.width == 0) 1f else min(1f, (maxWidth.value - 16f) / dialog.width)
      // As wide as the backend laid the dialog out; the title row mustn't widen it.
      Column(Modifier.width((dialog.width * fit).dp).dialogFrame()) {
        if (!dialog.popup && !dialog.undecorated)
          Box(
            Modifier.fillMaxWidth()
              .height(DialogLook.titleHeight)
              .padding(start = DialogLook.sideInset),
            contentAlignment = Alignment.CenterStart,
          ) {
            BasicText(dialog.title, style = fonts.label().copy(fontWeight = FontWeight.SemiBold))
          }
        Box {
          LuxSurface(dialog, session, fit, ::send, softKeyboard = !dialog.popup)
          for (popup in popups) key(popup.id) { LuxPopup(popup, dialog, fit, session) }
        }
      }
    }
  }
}

/**
 * [popup] over its [owner] dialog at the backend's offset between the two, scaled as the dialog is.
 * Example: Rename at (282, 242) and its Scope list at (290, 467) put the list 8 dp right of and 225
 * dp below the content's top left. It takes no soft keyboard: the combo keeps the focus.
 */
@Composable
private fun LuxPopup(popup: LuxDialog, owner: LuxDialog, fit: Float, session: ClientSession) {
  val density = LocalDensity.current.density
  fun send(event: LxEvent) = session.luxEvent(popup.id, event)
  Popup(
    offset =
      IntOffset(
        ((popup.x - owner.x) * fit * density).roundToInt(),
        ((popup.y - owner.y) * fit * density).roundToInt(),
      ),
    onDismissRequest = { closeWindow(::send) },
    properties = PopupProperties(focusable = true),
  ) {
    Box(Modifier.dialogFrame()) { LuxSurface(popup, session, fit, ::send, softKeyboard = false) }
  }
}

/** The window's close button: Swing gets WINDOW_CLOSING and the dialog its Cancel; Escape too. */
private fun closeWindow(send: (LxEvent) -> Unit) {
  send(LxWindowEvent(LxWindowEventType.Closing, null))
  sendKey(send, KEY_ESCAPE, '\u001b', 0)
}

/**
 * [dialog]'s content: the backend's draw operations replayed onto a bitmap, and input sent back.
 */
@Composable
private fun LuxSurface(
  dialog: LuxDialog,
  session: ClientSession,
  fit: Float,
  send: (LxEvent) -> Unit,
  softKeyboard: Boolean,
) {
  val images = session.luxImages ?: return
  val density = LocalDensity.current
  val fonts = LocalClientFonts.current
  val fontResolver = LocalFontFamilyResolver.current
  val painter = remember(dialog.id) { LuxPainter(images, fonts, fontResolver, density) }
  val pixelSize =
    IntSize(
      (dialog.width * density.density).roundToInt().coerceAtLeast(1),
      (dialog.height * density.density).roundToInt().coerceAtLeast(1),
    )
  val bitmap = remember(dialog.id, pixelSize) { ImageBitmap(pixelSize.width, pixelSize.height) }
  var painted by remember { mutableIntStateOf(0) }
  LaunchedEffect(dialog.version, bitmap) {
    val canvas = GraphicsCanvas(bitmap)
    while (true) {
      val batch = dialog.nextBatch() ?: break
      painter.paint(canvas, density.density, batch)
    }
    painted++
  }
  LuxContent(dialog, bitmap, painted, fit, send, softKeyboard)
}

@Composable
private fun LuxContent(
  dialog: LuxDialog,
  bitmap: ImageBitmap,
  painted: Int,
  fit: Float,
  send: (LxEvent) -> Unit,
  softKeyboard: Boolean,
) {
  val clipboard = LocalClipboardManager.current
  val keyboard = remember(dialog.id) { LuxKeyboard(send) { clipboard.getText()?.text } }
  val textInput = LocalEditorTextInput.current
  // The soft keyboard only while a text field has the focus: the backend then reports where its
  // caret is (`inputLocation`), as JetBrains Client turns input methods on and off with it. A new
  // focus node each time, since the keyboard connects when the node gains focus.
  val wantsKeyboard = softKeyboard && dialog.acceptsText
  key(wantsKeyboard) {
    LuxCanvas(
      dialog,
      bitmap,
      painted,
      fit,
      send,
      keyboard,
      if (wantsKeyboard) textInput(keyboard) else Modifier,
    )
  }
}

@Composable
private fun LuxCanvas(
  dialog: LuxDialog,
  bitmap: ImageBitmap,
  painted: Int,
  fit: Float,
  send: (LxEvent) -> Unit,
  keyboard: LuxKeyboard,
  textInput: Modifier,
) {
  val density = LocalDensity.current
  val focus = remember { FocusRequester() }
  Canvas(
    Modifier.size((dialog.width * fit).dp, (dialog.height * fit).dp)
      .focusRequester(focus)
      .then(textInput)
      .focusable()
      .onKeyEvent { event -> keyboard.key(event) }
      .pointerInput(dialog.id, fit) {
        val toBackend = 1f / (density.density * fit)
        var lastPress = 0L
        var lastPressAt = Offset.Zero
        var clicks = 0
        var downAt = Offset.Zero
        var inside = false
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val x = (change.position.x * toBackend).roundToInt()
            val y = (change.position.y * toBackend).roundToInt()
            val touch = change.type == PointerType.Touch
            // xAbs/yAbs: the point on the screen the backend was told about.
            fun mouse(type: LxMouseEventType, count: Int, modifiers: Int, atX: Int = x) =
              send(
                LxMouseEvent(
                  type,
                  atX,
                  y,
                  dialog.x + atX,
                  dialog.y + y,
                  count,
                  touch,
                  BUTTON1,
                  false,
                  modifiers,
                ),
              )
            when (event.type) {
              PointerEventType.Move ->
                mouse(LxMouseEventType.Moved, 0, if (change.pressed) BUTTON1_DOWN else 0)
              PointerEventType.Enter -> {
                inside = true
                mouse(LxMouseEventType.Entered, 0, 0)
              }
              PointerEventType.Exit -> {
                inside = false
                mouse(LxMouseEventType.Exited, 0, 0)
              }
              PointerEventType.Press -> {
                runCatching { focus.requestFocus() }
                // A finger has no hover: enter the window and move to the point first, as a mouse
                // would. Swing ignores a press in a window the pointer never entered, and
                // IntelliJ's
                // list popups (`ListPopupImpl.isMouseMoved`) take the first move only as a start,
                // so the move comes twice, 1 px apart along the row: e.g. (52, 140) then (53, 140)
                // selects the Scope list's item under the finger before the press.
                if (touch) {
                  if (!inside) mouse(LxMouseEventType.Entered, 0, 0)
                  inside = true
                  mouse(LxMouseEventType.Moved, 0, 0, atX = if (x > 0) x - 1 else x + 1)
                  mouse(LxMouseEventType.Moved, 0, 0)
                }
                val near = (change.position - lastPressAt).getDistance() < 8 * density.density
                clicks =
                  if (near && change.uptimeMillis - lastPress < DOUBLE_CLICK_MILLIS) clicks + 1
                  else 1
                lastPress = change.uptimeMillis
                lastPressAt = change.position
                downAt = change.position
                mouse(LxMouseEventType.Pressed, clicks, BUTTON1_DOWN)
              }
              PointerEventType.Release -> {
                mouse(LxMouseEventType.Released, clicks, 0)
                if ((change.position - downAt).getDistance() < 8 * density.density)
                  mouse(LxMouseEventType.Clicked, clicks, 0)
                // The finger leaves, so nothing stays hovered.
                if (touch) {
                  inside = false
                  mouse(LxMouseEventType.Exited, 0, 0)
                }
              }
              else -> Unit
            }
            event.changes.forEach { it.consume() }
          }
        }
      },
  ) {
    painted // read, so a painted batch redraws
    drawImage(bitmap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
  }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * Keys for a backend dialog: hardware keys as AWT key events (`LxKeyEvent`, as `EventsKt.toLx`
 * converts them), and a soft keyboard's text through [EditorTextTarget], each character as a key
 * press. Example: `x` is `Pressed(VK_X = 88, 'x')`, `Typed(0, 'x')`, `Released(88, 'x')`.
 *
 * Pasted text goes in as one input method commit (`LxInputMethodEvent` Text with all of it
 * committed), as JetBrains Client sends an input method's text: e.g. `"fooBar"` arrives whole, and
 * a tab or newline in it is text, not a key. The paste shortcut (⌘V on a Mac, Ctrl+V elsewhere) and
 * the keyboard's Paste take [clipboardText], the device's clipboard, so a backend on another
 * machine never pastes its own.
 */
private class LuxKeyboard(
  private val send: (LxEvent) -> Unit,
  private val clipboardText: () -> String?,
) : EditorTextTarget {
  fun key(event: KeyEvent): Boolean {
    val code = awtKeyCode(event.key) ?: return false
    val modifiers = modifiersOf(event)
    if (code == 'V'.code && modifiers == PASTE_MODIFIER) {
      if (event.type == KeyEventType.KeyDown) pasteClipboard()
      return true
    }
    val character = event.utf16CodePoint.takeIf { it in 32..0xFFFF && it != 127 }?.toChar()
    when (event.type) {
      KeyEventType.KeyDown -> {
        send(LxKeyEvent(LxKeyEventType.Pressed, code, character ?: UNDEFINED_CHAR, 1, modifiers))
        if (character != null && modifiers and (CTRL or META) == 0)
          send(LxKeyEvent(LxKeyEventType.Typed, 0, character, 0, modifiers))
      }
      KeyEventType.KeyUp ->
        send(LxKeyEvent(LxKeyEventType.Released, code, character ?: UNDEFINED_CHAR, 1, modifiers))
      else -> return false
    }
    return true
  }

  override fun type(character: Char) {
    if (character == '\n') return enter()
    sendKey(send, awtKeyCodeFor(character), character, 0)
  }

  override fun enter() = sendKey(send, KEY_ENTER, '\n', 0)

  override fun backspace() = sendKey(send, KEY_BACKSPACE, '\b', 0)

  override fun delete() = sendKey(send, KEY_DELETE, '\u007f', 0)

  override fun paste(text: String) {
    if (text.isEmpty()) return
    send(LxInputMethodEvent(LxInputMethodEventType.Text, text, text.length, null, false))
  }

  override fun pasteClipboard() {
    clipboardText()?.let(::paste)
  }

  override fun copy() = Unit

  override fun cut() = Unit

  // The dialog's text is pixels here: the keyboard gets no context around the caret.
  override fun textBeforeCaret(count: Int) = ""

  override fun textAfterCaret(count: Int) = ""

  override fun selectedText() = ""

  /** The keyboard's Select all: the platform's shortcut, as the backend's text field takes it. */
  override fun selectAll() = sendKey(send, 'A'.code, 'a', PASTE_MODIFIER)
}

/** Pressed, Typed and Released, as AWT sends one key stroke (Enter, Escape, Backspace type too). */
private fun sendKey(send: (LxEvent) -> Unit, code: Int, character: Char, modifiers: Int) {
  send(LxKeyEvent(LxKeyEventType.Pressed, code, character, 1, modifiers))
  // A shortcut (Ctrl or ⌘ held) types nothing, as in AWT.
  if (modifiers and (CTRL or META) == 0)
    send(LxKeyEvent(LxKeyEventType.Typed, 0, character, 0, modifiers))
  send(LxKeyEvent(LxKeyEventType.Released, code, character, 1, modifiers))
}

private fun modifiersOf(event: KeyEvent): Int {
  var modifiers = 0
  if (event.isShiftPressed) modifiers = modifiers or SHIFT
  if (event.isCtrlPressed) modifiers = modifiers or CTRL
  if (event.isMetaPressed) modifiers = modifiers or META
  if (event.isAltPressed) modifiers = modifiers or ALT
  return modifiers
}

/**
 * AWT's `VK_` code for [character]: letters and digits as their upper-case code, else undefined.
 */
private fun awtKeyCodeFor(character: Char): Int =
  when (val upper = character.uppercaseChar()) {
    in 'A'..'Z',
    in '0'..'9' -> upper.code
    ' ' -> 32
    else -> 0
  }

/** AWT's `VK_` code for a Compose key; null for keys a dialog doesn't need. */
private fun awtKeyCode(key: Key): Int? =
  when (key) {
    Key.Enter,
    Key.NumPadEnter -> KEY_ENTER
    Key.Escape -> KEY_ESCAPE
    Key.Tab -> 9
    Key.Backspace -> KEY_BACKSPACE
    Key.Delete -> KEY_DELETE
    Key.DirectionLeft -> 37
    Key.DirectionUp -> 38
    Key.DirectionRight -> 39
    Key.DirectionDown -> 40
    Key.MoveHome -> 36
    Key.MoveEnd -> 35
    Key.Spacebar -> 32
    Key.ShiftLeft,
    Key.ShiftRight -> 16
    Key.CtrlLeft,
    Key.CtrlRight -> 17
    Key.AltLeft,
    Key.AltRight -> 18
    Key.MetaLeft,
    Key.MetaRight -> 157
    else -> KEY_CODES[key]
  }

private val KEY_CODES: Map<Key, Int> =
  (listOf(
        Key.A,
        Key.B,
        Key.C,
        Key.D,
        Key.E,
        Key.F,
        Key.G,
        Key.H,
        Key.I,
        Key.J,
        Key.K,
        Key.L,
        Key.M,
        Key.N,
        Key.O,
        Key.P,
        Key.Q,
        Key.R,
        Key.S,
        Key.T,
        Key.U,
        Key.V,
        Key.W,
        Key.X,
        Key.Y,
        Key.Z,
      )
      .mapIndexed { index, key -> key to 'A'.code + index } +
      listOf(
          Key.Zero,
          Key.One,
          Key.Two,
          Key.Three,
          Key.Four,
          Key.Five,
          Key.Six,
          Key.Seven,
          Key.Eight,
          Key.Nine,
        )
        .mapIndexed { index, key -> key to '0'.code + index } +
      listOf(Key.Minus to 45, Key.Period to 46, Key.Comma to 44, Key.Slash to 47, Key.Equals to 61))
    .toMap()

// java.awt.event.KeyEvent / InputEvent values (the backend turns these into AWT events).
private const val KEY_ENTER = 10
private const val KEY_ESCAPE = 27
private const val KEY_BACKSPACE = 8
private const val KEY_DELETE = 127
private const val UNDEFINED_CHAR = '￿'
private const val SHIFT = 64 // SHIFT_DOWN_MASK
private const val CTRL = 128 // CTRL_DOWN_MASK
private const val META = 256 // META_DOWN_MASK
private const val ALT = 512 // ALT_DOWN_MASK
private const val BUTTON1 = 1 // MouseEvent.BUTTON1
private const val BUTTON1_DOWN = 1024 // BUTTON1_DOWN_MASK
private const val DOUBLE_CLICK_MILLIS = 500L

/** The platform's shortcut modifier, as for ⌘V / Ctrl+V and ⌘A / Ctrl+A. */
private val PASTE_MODIFIER = if (intellijandroid.client.ui.IS_MAC) META else CTRL
