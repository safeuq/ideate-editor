package intellijandroid.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class StickyLinesTest {
  private val lineHeight = 20f
  private val editorHeight = 800f // 40 lines

  // Lines (0-based): class 2..100, method 4..30, an if 10..20, a short if 40..42, method 50..99.
  private val classScope = StickyScope(2, 100, 41)
  private val method = StickyScope(4, 30, 80)
  private val ifBlock = StickyScope(10, 20, 200)
  private val shortIf = StickyScope(40, 42, 700)
  private val secondMethod = StickyScope(50, 99, 900)
  private val scopes = listOf(method, ifBlock, classScope, shortIf, secondMethod)

  private fun pinnedAt(firstVisibleLine: Int, offset: Float = 0f) =
    stickyPanel(scopes, firstVisibleLine * lineHeight + offset, lineHeight, editorHeight)

  @Test
  fun nothingIsPinnedWhileTheScopeStartsOnScreen() {
    assertEquals(StickyPanel.EMPTY, pinnedAt(0))
    assertEquals(StickyPanel.EMPTY, pinnedAt(2))
  }

  @Test
  fun pinsTheEnclosingScopesOuterFirst() {
    val panel = pinnedAt(12)
    assertEquals(listOf(classScope, method, ifBlock), panel.lines.map { it.scope })
    assertEquals(listOf(0f, 20f, 40f), panel.lines.map { it.y })
    assertEquals(60f, panel.height)
  }

  @Test
  fun aScopeEndingUnderThePanelIsPushedUp() {
    // Scrolled to line 17 + 10 px: the panel's third row ends at 350 + 60 = 410 px, 10 px into
    // line 20, where the if ends, so the if's pinned line moves up by those 10 px.
    val panel = pinnedAt(17, offset = 10f)
    assertEquals(listOf(classScope, method, ifBlock), panel.lines.map { it.scope })
    assertEquals(30f, panel.lines.last().y)
    assertEquals(50f, panel.height)
  }

  @Test
  fun shortScopesAreNotPinned() {
    assertEquals(listOf(classScope), pinnedAt(41).lines.map { it.scope })
  }

  @Test
  fun keepsTheLongestScopeOfTheSameStartLine() {
    val panel =
      stickyPanel(
        listOf(StickyScope(2, 10, 0), StickyScope(2, 60, 0)),
        20 * lineHeight,
        lineHeight,
        editorHeight,
      )
    assertEquals(listOf(StickyScope(2, 60, 0)), panel.lines.map { it.scope })
  }

  @Test
  fun stopsAtTheLimit() {
    val nested = (0 until 8).map { StickyScope(it, 100 - it, it) }
    assertEquals(5, stickyPanel(nested, 30 * lineHeight, lineHeight, editorHeight).lines.size)
  }

  @Test
  fun aShortEditorPinsNothing() {
    assertEquals(StickyPanel.EMPTY, stickyPanel(scopes, 12 * lineHeight, lineHeight, 60f))
  }
}
