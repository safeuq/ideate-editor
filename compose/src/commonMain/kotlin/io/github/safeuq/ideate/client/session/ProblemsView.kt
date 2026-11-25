package io.github.safeuq.ideate.client.session

/**
 * One row of the Problems tool window's File tab: IntelliJ's `HighlightingProblem`, built from a
 * highlighter that carries a [HighlightInfo]. [line] and [column] count from 0.
 */
data class HighlightingProblem(
  val highlightId: Long,
  val offset: Int,
  val line: Int,
  val column: Int,
  val text: String,
  val description: String,
  val severity: Int,
  val severityId: String,
  val hasQuickFixes: Boolean,
)

object ProblemsView {
  /**
   * The lowest severity the File tab lists: `HighlightSeverity.TEXT_ATTRIBUTES.myVal + 1`, as
   * `ProblemsViewHighlightingFileRoot` passes to `ProblemsViewHighlightingWatcher` (INFORMATION is
   * 10 and TEXT ATTRIBUTES 11, so neither is listed).
   */
  const val FILE_LEVEL = 12

  /**
   * The problems of [text]'s highlighters, in the File tab's order. A highlighter counts when
   * `ProblemsViewHighlightingWatcher.isValid` holds: it has a description and its severity is at
   * least [level].
   */
  fun highlightingProblems(
    text: String,
    highlights: List<Highlight>,
    level: Int = FILE_LEVEL,
  ): List<HighlightingProblem> =
    highlights
      .mapNotNull { highlight ->
        val info = highlight.info ?: return@mapNotNull null
        val description = info.description ?: return@mapNotNull null
        if (info.severity < level || highlight.start !in 0..text.length) return@mapNotNull null
        val lineStart = text.lastIndexOf('\n', highlight.start - 1) + 1
        HighlightingProblem(
          highlightId = highlight.id,
          offset = highlight.start,
          line = text.count(0, lineStart),
          column = highlight.start - lineStart,
          text = problemText(description),
          description = description,
          severity = info.severity,
          severityId = info.severityId,
          hasQuickFixes = info.hasQuickFixes,
        )
      }
      .sortedWith(ProblemsViewNodeComparator)

  /**
   * `HighlightingProblem.getText`: the description, cut at its first line break with "…" unless it
   * is HTML.
   */
  fun problemText(description: String): String {
    val lineBreak = description.indexOfFirst { it == '\n' || it == '\r' }
    return if (lineBreak < 0 || description.startsWith("<html>", ignoreCase = true)) description
    else description.substring(0, lineBreak) + "…"
  }

  /**
   * `ProblemsViewNodeComparator` with "sort by severity" on (the default): severity high to low,
   * then line, column, text.
   */
  // ponytail: text compared without case, not IntelliJ's natural compare (digits by value); only
  // decides between two problems of one severity at the same position.
  val ProblemsViewNodeComparator: Comparator<HighlightingProblem> =
    compareByDescending<HighlightingProblem> { it.severity }
      .thenBy { it.line }
      .thenBy { it.column }
      .thenBy(String.CASE_INSENSITIVE_ORDER) { it.text }

  private fun String.count(from: Int, until: Int): Int {
    var lines = 0
    for (index in from until until) if (this[index] == '\n') lines++
    return lines
  }
}
