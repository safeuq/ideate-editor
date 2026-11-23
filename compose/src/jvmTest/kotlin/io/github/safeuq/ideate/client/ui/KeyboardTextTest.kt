package io.github.safeuq.ideate.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardTextTest {
  /** Records the edits as a string: a character typed, `⌫` Backspace, `⏎` Enter, `[…]` a paste. */
  private class Recorder : EditorTextTarget {
    val edits = StringBuilder()
    var before = ""

    override fun type(character: Char) {
      edits.append(character)
    }

    override fun enter() {
      edits.append('⏎')
    }

    override fun backspace() {
      edits.append('⌫')
    }

    override fun delete() = error("not sent by KeyboardText")

    override fun paste(text: String) {
      edits.append('[').append(text).append(']')
    }

    override fun pasteClipboard() = error("not sent by KeyboardText")

    override fun copy() = error("not sent by KeyboardText")

    override fun cut() = error("not sent by KeyboardText")

    override fun selectAll() = error("not sent by KeyboardText")

    override fun textBeforeCaret(count: Int) = before.takeLast(count)

    override fun textAfterCaret(count: Int) = ""

    override fun selectedText() = ""
  }

  private val recorder = Recorder()
  private val keyboard = KeyboardText { recorder }

  @Test
  fun `one committed character is typed and a newline is Enter`() {
    keyboard.commit("a")
    keyboard.commit("\n")
    assertEquals("a⏎", recorder.edits.toString())
  }

  @Test
  fun `longer committed text is pasted`() {
    keyboard.commit("System.out.println()")
    assertEquals("[System.out.println()]", recorder.edits.toString())
  }

  @Test
  fun `composing text is typed as it grows and corrected with Backspace`() {
    keyboard.setComposing("h")
    keyboard.setComposing("he")
    keyboard.setComposing("hel")
    keyboard.setComposing("help")
    // The keyboard replaces its guess: only what differs is deleted; more than one new character
    // (a picked suggestion) is pasted.
    keyboard.commit("hello")
    keyboard.commit(" ")
    assertEquals("help⌫[lo] ", recorder.edits.toString())
  }

  @Test
  fun `finishing a composed word keeps it and starts the next one fresh`() {
    keyboard.setComposing("a")
    keyboard.setComposing("ab")
    keyboard.finishComposing()
    keyboard.setComposing("c")
    keyboard.setComposing("")
    assertEquals("abc⌫", recorder.edits.toString())
  }

  @Test
  fun `a word the keyboard resumes is not typed again`() {
    // Back in the editor after `sy` was typed, Gboard sends `syz` for `z`.
    recorder.before = "  sy"
    keyboard.setComposing("syz")
    assertEquals("z", recorder.edits.toString())
  }

  @Test
  fun `a clip Gboard adds to the composing word is pasted`() {
    recorder.before = "aa"
    keyboard.setComposing("aaMain.java")
    keyboard.finishComposing()
    assertEquals("[Main.java]", recorder.edits.toString())
  }

  @Test
  fun `new composing text that doesn't continue the word before the caret is typed`() {
    recorder.before = "sy "
    keyboard.setComposing("s")
    assertEquals("s", recorder.edits.toString())
  }
}
