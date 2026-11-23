package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values follow IntelliJ's `IndentGuides.buildDescriptors` on the same text. */
class IndentGuidesTest {
  private val noComments: (Int) -> Boolean = { false }

  @Test
  fun methodBodyGetsOneGuideAtTheMethodsIndent() {
    // The smoke project's Main.java: class at 0, method at 2, body at 4.
    val text =
      """
      |package demo;
      |
      |public class Main {
      |  public static void main(final String[] arguments) {
      |    final String unused = "never read";
      |    String greeting = "just a simple greeting";
      |    System.out.println(greeting);
      |    System.out.println(greeting.length());
      |  }
      |}
      |"""
        .trimMargin()
    // Level 0 (the class body) gets no guide; the method body is lines 4..7, drawn up to line 8.
    assertEquals(listOf(IndentGuide(2, 3, 8)), IndentGuides.compute(text, noComments))
  }

  @Test
  fun blankLinesInsideABlockKeepItsGuide() {
    val text = "a {\n  b {\n    c\n\n    d\n  }\n}\n"
    // The blank line 3 takes min(top 4, bottom 4) and does not end the block of line 1.
    assertEquals(listOf(IndentGuide(2, 1, 5)), IndentGuides.compute(text, noComments))
  }

  @Test
  fun blankRunBeforeAClosingBraceStaysInTheBlock() {
    // Line 3 is blank and followed by "  }": IntelliJ gives it the block's indent (topIndent).
    val text = "a {\n  b {\n    c\n\n  }\n}\n"
    assertEquals(listOf(IndentGuide(2, 1, 4)), IndentGuides.compute(text, noComments))
  }

  @Test
  fun commentLinesCountAsBlank() {
    val text = "a {\n  b {\n    c\n// note\n    d\n  }\n}\n"
    val commentStart = text.indexOf("//")
    val guides =
      IndentGuides.compute(text, isComment = { it in commentStart until commentStart + 7 })
    // Without comment handling the comment at column 0 would close the block at line 3.
    assertEquals(listOf(IndentGuide(2, 1, 5)), guides)
  }

  @Test
  fun tabsAdvanceToTheNextTabStop() {
    val text = "a {\n\tb {\n\t\tc\n\t}\n}\n"
    assertEquals(listOf(IndentGuide(4, 1, 3)), IndentGuides.compute(text, noComments, tabSize = 4))
  }
}
