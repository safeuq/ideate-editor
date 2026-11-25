package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rules follow `ProblemsViewHighlightingWatcher`, `HighlightingProblem`,
 * `ProblemsViewNodeComparator`.
 */
class ProblemsViewTest {
  private val text = "class A {\n  Strin a;\n  int unused;\n}\n"

  private fun highlight(id: Long, start: Int, severity: Int, description: String?) =
    Highlight(
      id,
      start,
      start + 1,
      layer = 4000,
      style = TextStyle(),
      info = HighlightInfo("S$severity", severity, description, null, null, null, false, false),
    )

  @Test
  fun listsErrorsBeforeWarningsWithLineAndColumn() {
    val problems =
      ProblemsView.highlightingProblems(
        text,
        listOf(
          highlight(1, text.indexOf("unused"), 300, "Variable 'unused' is never used"),
          highlight(2, text.indexOf("Strin"), 400, "Cannot resolve symbol 'Strin'"),
        ),
      )
    assertEquals(listOf(2L, 1L), problems.map { it.highlightId })
    assertEquals(1 to 2, problems[0].line to problems[0].column)
    assertEquals(2 to 6, problems[1].line to problems[1].column)
  }

  @Test
  fun leavesOutHighlightsWithoutDescriptionOrBelowTheLevel() {
    val problems =
      ProblemsView.highlightingProblems(
        text,
        listOf(
          highlight(1, 0, 400, null),
          highlight(2, 0, 11, "text attributes only"),
          highlight(3, 0, 12, "listed"),
          Highlight(4, 0, 1, 1000, TextStyle()),
        ),
      )
    assertEquals(listOf(3L), problems.map { it.highlightId })
  }

  @Test
  fun cutsTheTextAtTheFirstLineBreakUnlessHtml() {
    assertEquals("first…", ProblemsView.problemText("first\nsecond"))
    assertEquals(
      "<html>first\nsecond</html>",
      ProblemsView.problemText("<html>first\nsecond</html>"),
    )
    assertEquals("one line", ProblemsView.problemText("one line"))
  }

  @Test
  fun statusItemsCountPerSeverityMostSevereFirst() {
    val severities = listOf(HighlightSeverity("S400", 400, listOf("error.svg"), "Error", null))
    val items =
      ProblemsView.statusItems(
        listOf(
          highlight(1, 0, 300, "unused"),
          highlight(2, 1, 400, "unresolved"),
          highlight(3, 2, 400, "unresolved too"),
          // TEXT ATTRIBUTES (11) and below are not counted.
          highlight(4, 3, 11, "attributes"),
        ),
        severities,
      )
    assertEquals(
      listOf(
        StatusItem("S400", listOf("error.svg"), 2, "Error"),
        StatusItem("S300", emptyList(), 1, null),
      ),
      items,
    )
  }
}
