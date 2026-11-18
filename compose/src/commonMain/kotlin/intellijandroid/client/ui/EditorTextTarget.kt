package intellijandroid.client.ui

/**
 * What the editor offers a platform's text input (Android's soft keyboard): the same edits as its
 * keys and menu, and the text around the caret for the keyboard to read. [EditorView] implements
 * it; the platform's input connection calls it.
 */
interface EditorTextTarget {
  /** One typed character, as a key press types it (`EditorActions.type`). */
  fun type(character: Char)

  fun enter()

  fun backspace()

  fun delete()

  /** [text] inserted through the backend's paste, e.g. a clip from the keyboard's clipboard bar. */
  fun paste(text: String)

  /** The system clipboard through the backend's paste: the keyboard's Paste button. */
  fun pasteClipboard()

  fun copy()

  fun cut()

  /** The whole document selected, caret at its end. */
  fun selectAll()

  /** The keyboard's Undo and Redo: the backend's, as ⌘Z / Ctrl+Z. */
  fun undo() = Unit

  fun redo() = Unit

  /** Up to [count] characters before the caret, for the keyboard's capitalisation and Backspace. */
  fun textBeforeCaret(count: Int): String

  fun textAfterCaret(count: Int): String

  /** The selected text, empty when nothing is selected. */
  fun selectedText(): String
}

/**
 * Turns what a soft keyboard sends (`InputConnection.commitText`, `setComposingText`,
 * `finishComposingText`) into the editor's edits, keeping one `type()` per character so the typing
 * protocol stays the same (docs/edit-protocol.md, "Pasting from the Android soft keyboard").
 * - A single committed character is typed; `\n` is Enter.
 * - Longer committed text is pasted: a clip from the keyboard's clipboard bar, or a whole word some
 *   keyboards commit at once.
 * - Composing text (a word the keyboard may still change) is typed as it grows; when the keyboard
 *   changes it, the characters after the common start are deleted with Backspace. One new character
 *   is typed; more than one at once are pasted. Examples: composing `hel` then `help` types `p`;
 *   `help` then `hello` (a picked suggestion) deletes `p` and pastes `lo`. Gboard inserts a clip
 *   from its clipboard bar as `setComposingText(word + clip)`, then `finishComposingText()`, so the
 *   clip is pasted too.
 * - A keyboard resumes the word before the caret without saying so. Gboard, back in the editor
 *   after `abc` was typed, sends `setComposingText("abcd")` for `d`, treating `abc` as composing
 *   already. So when nothing is composing, the word before the caret counts as composing if the new
 *   text starts with it. Seen on Gboard, Android 14 emulator, 2026-11-17.
 */
class KeyboardText(private val target: () -> EditorTextTarget) {
  private var composing = ""

  fun commit(text: String) {
    if (composing.isNotEmpty()) {
      replaceComposing(text)
      composing = ""
      return
    }
    when {
      text.isEmpty() -> Unit
      text.length == 1 -> typeOne(text[0])
      else -> target().paste(text)
    }
  }

  fun setComposing(text: String) {
    if (composing.isEmpty()) composing = resumedWord(text)
    replaceComposing(text)
    composing = text
  }

  fun finishComposing() {
    composing = ""
  }

  private fun replaceComposing(replacement: String) {
    val kept = composing.commonPrefixWith(replacement).length
    repeat(composing.length - kept) { target().backspace() }
    val added = replacement.substring(kept)
    if (added.length > 1) target().paste(added) else added.forEach(::typeOne)
  }

  /** The letters and digits just before the caret, if [text] continues them; else empty. */
  private fun resumedWord(text: String): String {
    val word =
      target().textBeforeCaret(RESUME_LOOK_BACK).takeLastWhile { it.isLetterOrDigit() || it == '_' }
    return if (word.isNotEmpty() && text.startsWith(word)) word else ""
  }

  private companion object {
    /** Longer words aren't resumed; the keyboard's own look back is shorter. */
    const val RESUME_LOOK_BACK = 64
  }

  private fun typeOne(character: Char) {
    if (character == '\n') target().enter() else target().type(character)
  }
}
