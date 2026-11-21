package intellijandroid.client.session.terminal

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
}
