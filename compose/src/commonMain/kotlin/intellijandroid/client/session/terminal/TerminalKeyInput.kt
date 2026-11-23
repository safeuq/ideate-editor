package intellijandroid.client.session.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.input.InputEvent

/**
 * Turns what the user types into what the shell gets. One per terminal view; the keyboard bar's
 * keys and the soft keyboard's text go through it, so the sticky Ctrl (soft keyboards have no Ctrl
 * key) applies to both. [sendKey] takes a JediTerm key code (`KeyEvent.VK_UP`) with its modifier
 * bits and lets JediTerm pick the bytes, which follows the program's cursor-key mode: Up is `\e[A`,
 * or `\eOA` after the program sent `\e[?1h`. [sendText] sends the characters as they are.
 */
class TerminalKeyInput(
  private val sendText: (String) -> Unit,
  private val sendKey: (keyCode: Int, modifiers: Int) -> Unit,
) {
  /**
   * The Ctrl key of the keyboard bar is on: the next key is sent as Ctrl+that key, then it goes
   * off. [alt] (Option) and [meta] (⌘) work the same way.
   */
  var ctrl by mutableStateOf(false)
    private set

  var alt by mutableStateOf(false)
    private set

  var meta by mutableStateOf(false)
    private set

  /**
   * Runs ⌘+letter (copy, paste, select all, clear buffer: the terminal view sets it; true when the
   * letter is one of them). Like IntelliJ's terminal, ⌘ never reaches the shell: JediTerm's
   * `metaSendsEscape` is off and `TerminalView.onKey` drops every other ⌘ key.
   */
  var onCommand: (Char) -> Boolean = { false }

  fun toggleCtrl() {
    ctrl = !ctrl
  }

  fun toggleAlt() {
    alt = !alt
  }

  fun toggleMeta() {
    meta = !meta
  }

  /**
   * A typed character: with Ctrl on, `c` is sent as 0x03 (Ctrl+C). With Alt on, Esc goes first
   * (`\eb`, readline's "back a word"), which is how JediTerm and xterm send Alt+key. With ⌘ on, the
   * character runs [onCommand] and nothing is sent.
   */
  fun type(character: Char) {
    if (meta) {
      clearModifiers()
      onCommand(character)
      return
    }
    val withCtrl = if (ctrl) controlCharacter(character) else null
    val prefix = if (alt) "\u001b" else ""
    clearModifiers()
    sendText(prefix + (withCtrl ?: character))
  }

  /**
   * A key that has no character: arrows, Enter, Backspace, Tab, Escape, F-keys. Ctrl and Alt join
   * [modifiers] as AWT bits, which JediTerm's `TerminalKeyEncoder.getCode` turns into `\e[1;3A`
   * (Alt+Up) and the like. With ⌘ on nothing is sent.
   */
  fun key(keyCode: Int, modifiers: Int = 0) {
    val stickyModifiers =
      (if (ctrl) InputEvent.CTRL_DOWN_MASK else 0) or (if (alt) InputEvent.ALT_DOWN_MASK else 0)
    val commandOn = meta
    clearModifiers()
    if (commandOn) return
    sendKey(keyCode, modifiers or stickyModifiers)
  }

  /** Text from the soft keyboard, e.g. a pasted clip or a whole word. */
  fun text(text: String) {
    val commandOn = meta
    clearModifiers()
    if (commandOn) return
    sendText(text)
  }

  private fun clearModifiers() {
    ctrl = false
    alt = false
    meta = false
  }

  companion object {
    /**
     * What a terminal sends for Ctrl+[character]: the letter's number minus 64 or 96, so `a` and
     * `A` are 0x01, `c` is 0x03, `z` is 0x1A, `[` is 0x1B (Esc); null for characters Ctrl does
     * nothing to, like `1`.
     */
    fun controlCharacter(character: Char): Char? =
      when (character) {
        in '@'..'_' -> (character.code - 64).toChar()
        in 'a'..'z' -> (character.code - 96).toChar()
        else -> null
      }
  }
}
