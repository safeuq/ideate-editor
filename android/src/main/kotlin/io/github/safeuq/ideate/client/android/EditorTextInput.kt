package io.github.safeuq.ideate.client.android

import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.unit.IntSize
import io.github.safeuq.ideate.client.ui.EditorTextTarget
import io.github.safeuq.ideate.client.ui.KeyboardText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opens the soft keyboard while the editor has focus and connects it to [target] (docs/edit-
 * protocol.md, "Pasting from the Android soft keyboard"). The editor asks for multi-line text
 * without suggestions, so keyboards show their clipboard bar and paste button; what they commit
 * goes through [KeyboardText] (one `type()` per character, longer text pasted). Keys the keyboard
 * sends as key events (Enter, Delete, arrows) still reach the editor's key handler.
 */
fun editorTextInput(target: EditorTextTarget): Modifier =
  Modifier.then(EditorTextInputElement(target))

private class EditorTextInputElement(private val target: EditorTextTarget) :
  ModifierNodeElement<EditorTextInputNode>() {
  override fun create() = EditorTextInputNode(target)

  override fun update(node: EditorTextInputNode) {
    node.target = target
  }

  // A new target every recomposition is fine: update() hands it to the same node.
  override fun hashCode() = 0

  override fun equals(other: Any?) = other is EditorTextInputElement
}

private class EditorTextInputNode(var target: EditorTextTarget) :
  Modifier.Node(),
  FocusEventModifierNode,
  PlatformTextInputModifierNode,
  PointerInputModifierNode,
  CompositionLocalConsumerModifierNode {
  private var session: Job? = null
  private var pressedAt: Offset? = null

  override fun onFocusEvent(focusState: FocusState) {
    if (focusState.isFocused == (session != null)) return
    session?.cancel()
    session = null
    if (focusState.isFocused) startSession()
  }

  /**
   * A tap on the focused editor brings the keyboard back after Back hid it: focus doesn't change
   * then, so [onFocusEvent] doesn't run (user report). A new input session shows the keyboard.
   */
  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    if (pass != PointerEventPass.Main || session == null) return
    val change = pointerEvent.changes.firstOrNull() ?: return
    when (pointerEvent.type) {
      PointerEventType.Press -> pressedAt = change.position
      PointerEventType.Release -> {
        val start = pressedAt ?: return
        pressedAt = null
        val tapped =
          (change.position - start).getDistance() < currentValueOf(LocalViewConfiguration).touchSlop
        val keyboardShown =
          currentValueOf(LocalView).rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (tapped && !keyboardShown) {
          session?.cancel()
          startSession()
        }
      }
    }
  }

  override fun onCancelPointerInput() {
    pressedAt = null
  }

  private fun startSession() {
    session = coroutineScope.launch {
      establishTextInputSession {
        val inputView = view
        startInputMethod(
          object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
              outAttributes.inputType =
                InputType.TYPE_CLASS_TEXT or
                  InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                  InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
              outAttributes.imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
              return EditorInputConnection(inputView) { target }
            }
          },
        )
      }
    }
  }
}

/**
 * The keyboard's calls, mapped onto the editor. `fullEditor = false`: the base class keeps no copy
 * of the text; the text around the caret comes from the editor's document.
 */
private class EditorInputConnection(view: View, private val target: () -> EditorTextTarget) :
  BaseInputConnection(view, false) {
  private val keyboardText = KeyboardText(target)

  override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
    keyboardText.commit(text.toString())
    return true
  }

  override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
    keyboardText.setComposing(text.toString())
    return true
  }

  override fun finishComposingText(): Boolean {
    keyboardText.finishComposing()
    return true
  }

  override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
    repeat(beforeLength) { target().backspace() }
    repeat(afterLength) { target().delete() }
    return true
  }

  /**
   * The keyboard's Paste, Copy, Cut, Select all, Undo and Redo buttons (Gboard's Text Editing
   * panel).
   */
  override fun performContextMenuAction(id: Int): Boolean =
    when (id) {
      android.R.id.paste -> {
        target().pasteClipboard()
        true
      }
      android.R.id.copy -> {
        target().copy()
        true
      }
      android.R.id.cut -> {
        target().cut()
        true
      }
      android.R.id.selectAll -> {
        target().selectAll()
        true
      }
      android.R.id.undo -> {
        target().undo()
        true
      }
      android.R.id.redo -> {
        target().redo()
        true
      }
      else -> false
    }

  override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence =
    target().textBeforeCaret(length)

  override fun getTextAfterCursor(length: Int, flags: Int): CharSequence =
    target().textAfterCaret(length)

  override fun getSelectedText(flags: Int): CharSequence? = target().selectedText().ifEmpty { null }
}
