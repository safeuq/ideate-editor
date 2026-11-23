package intellijandroid.client.session.terminal

import com.jediterm.core.input.InputEvent
import com.jediterm.core.input.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The keyboard bar's sticky Ctrl (docs/terminal-and-consoles-plan.md 6.1). */
class TerminalKeyInputTest {
  private val sent = mutableListOf<String>()
  private val input =
    TerminalKeyInput(
      sendText = { sent += it },
      sendKey = { keyCode, modifiers -> sent += "key $keyCode/$modifiers" },
    )

  @Test
  fun ctrlThenCSendsTheInterruptByteAndGoesOff() {
    input.toggleCtrl()
    assertTrue(input.ctrl)
    input.type('c')
    assertEquals(listOf("\u0003"), sent)
    assertFalse(input.ctrl)
    input.type('c')
    assertEquals("c", sent.last())
  }

  @Test
  fun ctrlMapsLettersAndBracketsAndLeavesOtherCharactersAlone() {
    assertEquals(0x01.toChar(), TerminalKeyInput.controlCharacter('a'))
    assertEquals(0x01.toChar(), TerminalKeyInput.controlCharacter('A'))
    assertEquals(0x1A.toChar(), TerminalKeyInput.controlCharacter('z'))
    assertEquals(0x1B.toChar(), TerminalKeyInput.controlCharacter('['))
    assertEquals(null, TerminalKeyInput.controlCharacter('1'))
  }

  @Test
  fun aKeyPressAlsoEndsStickyCtrl() {
    input.toggleCtrl()
    input.key(KeyEvent.VK_UP)
    assertFalse(input.ctrl)
  }

  @Test
  fun altThenBSendsEscThenBAndGoesOff() {
    input.toggleAlt()
    input.type('b')
    assertEquals(listOf("\u001bb"), sent)
    assertFalse(input.alt)
  }

  @Test
  fun stickyAltAndCtrlJoinAKeyAsAwtBitsAndGoOff() {
    input.toggleAlt()
    input.toggleCtrl()
    input.key(KeyEvent.VK_UP)
    assertEquals(
      listOf("key ${KeyEvent.VK_UP}/${InputEvent.ALT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK}"),
      sent,
    )
    assertFalse(input.alt || input.ctrl)
  }

  @Test
  fun metaRunsTheFourCommandsAndSendsNothing() {
    val commands = mutableListOf<Char>()
    input.onCommand = {
      commands += it
      it in "cvak"
    }
    for (letter in "cvak") {
      input.toggleMeta()
      input.type(letter)
      assertFalse(input.meta)
    }
    assertEquals(listOf('c', 'v', 'a', 'k'), commands)
    assertEquals(emptyList(), sent)
  }

  @Test
  fun metaWithAnyOtherCharacterOrKeySendsNothingAndGoesOff() {
    input.toggleMeta()
    input.type('x')
    input.toggleMeta()
    input.key(KeyEvent.VK_UP)
    input.toggleMeta()
    input.text("word")
    assertEquals(emptyList(), sent)
    assertFalse(input.meta)
  }
}
