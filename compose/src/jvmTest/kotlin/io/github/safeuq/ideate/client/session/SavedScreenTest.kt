package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.connect.ConnectionKind
import io.github.safeuq.ideate.client.session.terminal.TerminalCursor
import io.github.safeuq.ideate.client.session.terminal.TerminalCursorShape
import io.github.safeuq.ideate.client.session.terminal.TerminalPaint
import io.github.safeuq.ideate.client.session.terminal.TerminalRun
import io.github.safeuq.ideate.client.session.terminal.TerminalScreen
import io.github.safeuq.ideate.client.session.terminal.TerminalScreenLine
import io.github.safeuq.ideate.client.session.terminal.TerminalTabState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedScreenTest {
  private fun line(text: String) =
    TerminalScreenLine(listOf(TerminalRun(0, text.length, text, TerminalPaint.Indexed(2))), false)

  private val state =
    SessionState(
      projectName = "recon",
      tree =
        ProjectTree(
          mapOf(
            1 to
              TreeNode(
                1,
                null,
                listOf(TextFragment("recon", 1, null)),
                listOf("a.svg"),
                true,
                true,
                "/p",
                0,
              ),
          ),
          expanded = setOf(1),
          selected = 1,
        ),
      editors =
        listOf(
          EditorFile(
            "/p/Hello.java",
            "class Hello {}",
            listOf(Highlight(7, 0, 5, 1, TextStyle(foreground = 0xFFCF8E6D, fontType = 1))),
            listOf(IndentGuide(4, 0, 2)),
            caret = 3,
          ),
        ),
      tabIcons = mapOf("/p/Hello.java" to "nodes/class.svg"),
      selectedEditor = "/p/Hello.java",
      scheme =
        EditorScheme(
          "Islands Dark",
          listOf(mapOf("TEXT" to TextStyle(foreground = 0xFFBCBEC4, background = 0xFF191A1C))),
          listOf(mapOf("INDENT_GUIDE" to 0xFF393B40)),
          mapOf("GUIDE_ALIAS" to "INDENT_GUIDE"),
        ),
      terminals =
        listOf(
          TerminalTabState(
            "SessionId#1",
            "Local",
            TerminalScreen(
              listOf(line("old history"), line("ls"), line("Hello.java")),
              80,
              2,
              TerminalCursor(0, 1, true, TerminalCursorShape.BAR, false),
              false,
            ),
          ),
        ),
    )

  @Test
  fun roundTripKeepsWhatTheScreenDraws() {
    val saved =
      SavedSession(
        ConnectionKind.LINK,
        "tcp://127.0.0.1:5994#jb=x",
        null,
        null,
        "identity",
        "link:127.0.0.1:5994",
        SavedScreen.of(state, mapOf("LEFT_TOP" to "Project", "BOTTOM_LEFT" to "Terminal")),
      )
    val back = checkNotNull(SavedSession.decode(saved.encode()))
    assertEquals("link:127.0.0.1:5994", back.editorsKey)
    assertEquals("Terminal", back.screen.openWindows["BOTTOM_LEFT"])
    val restored = back.screen.toState()
    assertEquals(state.projectName, restored.projectName)
    assertEquals(state.tree, restored.tree)
    assertEquals(state.editors, restored.editors)
    assertEquals(state.tabIcons, restored.tabIcons)
    assertEquals(state.selectedEditor, restored.selectedEditor)
    val scheme = checkNotNull(restored.scheme)
    assertEquals(0xFF191A1C, scheme.background)
    assertEquals(0xFF393B40, scheme.color("GUIDE_ALIAS"))
    // Only the lines on screen (2 rows), not the history above them.
    assertEquals(
      listOf("ls", "Hello.java"),
      restored.terminals.single().screen.lines.map { it.text },
    )
    assertEquals(state.terminals.single().screen.cursor, restored.terminals.single().screen.cursor)
  }

  @Test
  fun unreadableTextIsNothingSaved() {
    assertNull(SavedSession.decode(""))
    assertNull(SavedSession.decode("{not json"))
  }
}
