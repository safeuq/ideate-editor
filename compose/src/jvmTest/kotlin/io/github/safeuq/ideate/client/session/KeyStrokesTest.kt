package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.KeyStrokeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Key codes and modifier masks as the backend sends them for IntelliJ's keymaps. */
class KeyStrokesTest {
  private fun stroke(keyCode: Int, modifiers: Int) =
    KeyStrokeModel('\uFFFF', keyCode, modifiers, false)

  @Test
  fun copyAbsolutePathIsShiftCommandC() {
    // VK_C with SHIFT_DOWN_MASK | META_DOWN_MASK.
    assertEquals("⇧⌘C", KeyStrokes.text(stroke(0x43, 0x40 or 0x100), mac = true))
    assertEquals("Shift+Meta+C", KeyStrokes.text(stroke(0x43, 0x40 or 0x100), mac = false))
  }

  @Test
  fun macOrderIsControlOptionShiftCommand() {
    // Optimize Imports on macOS: ⌃⌥O, with the old CTRL_MASK | ALT_MASK bits.
    assertEquals("⌃⌥O", KeyStrokes.text(stroke(0x4F, 0x2 or 0x8), mac = true))
  }

  @Test
  fun functionAndNamedKeys() {
    assertEquals("Ctrl+Shift+F9", KeyStrokes.text(stroke(0x78, 0x80 or 0x40), mac = false))
    assertEquals("⌦", KeyStrokes.text(stroke(0x7F, 0), mac = true))
  }

  @Test
  fun unknownKeyGivesNoText() {
    assertNull(KeyStrokes.text(stroke(0xFFFF, 0), mac = true))
  }
}
