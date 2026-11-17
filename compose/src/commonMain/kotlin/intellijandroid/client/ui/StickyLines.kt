package intellijandroid.client.ui

/**
 * A scope the backend marks for sticky lines, in lines: [primaryLine] holds its start (e.g. `public
 * class Main {`), [scopeLine] its end; a tap on the pinned line moves the caret to
 * [navigateOffset].
 */
internal data class StickyScope(val primaryLine: Int, val scopeLine: Int, val navigateOffset: Int)

/** A scope's start line pinned at the top of the editor, [y] px below its top edge. */
internal data class PinnedLine(val scope: StickyScope, val y: Float)

/** The pinned lines and the height they take, without the border line under them. */
internal data class StickyPanel(val lines: List<PinnedLine>, val height: Float) {
  companion object {
    val EMPTY = StickyPanel(emptyList(), 0f)
  }
}

/**
 * Which scope lines IntelliJ pins at the top of the editor, and where (`VisualStickyLines` in
 * IntelliJ 263.7307.200, without folding or soft wraps, so every line is [lineHeight] high and line
 * `n` starts at `n × lineHeight`):
 * - scopes shorter than [minScopeLines] lines are left out, and of scopes starting on the same line
 *   only the longest is kept;
 * - in order of their start line (outer scope first when two start together), a scope is pinned
 *   while its start line is above the next free row of the panel and its end line reaches it;
 * - when a scope's end line comes up under the panel, its pinned line is pushed up by the overlap
 *   and nothing below it is pinned;
 * - at most [limit] lines (IntelliJ's default 5), and the panel never takes more than half the
 *   editor minus two lines.
 *
 * [scrollY]: how far the editor is scrolled down, in px.
 */
internal fun stickyPanel(
  scopes: List<StickyScope>,
  scrollY: Float,
  lineHeight: Float,
  editorHeight: Float,
  limit: Int = 5,
  minScopeLines: Int = 5,
): StickyPanel {
  if (lineHeight <= 0f || 2 * lineHeight > editorHeight / 2) return StickyPanel.EMPTY
  val candidates =
    scopes
      .filter { it.scopeLine - it.primaryLine + 1 >= minScopeLines }
      .groupBy { it.primaryLine }
      .map { (_, sameStart) -> sameStart.maxBy { it.scopeLine } }
      .sortedWith(compareBy({ it.primaryLine }, { -it.scopeLine }))
  val pinned = mutableListOf<PinnedLine>()
  var height = 0f
  for (scope in candidates) {
    val startBottom = (scope.primaryLine + 1) * lineHeight
    val endTop = scope.scopeLine * lineHeight
    val endBottom = endTop + lineHeight
    val stickyY = scrollY + height + lineHeight
    if (startBottom < stickyY && stickyY <= endBottom) {
      val overlap = if (stickyY <= endTop) 0f else stickyY - endTop
      if (lineHeight > overlap) pinned += PinnedLine(scope, height - overlap)
      height += lineHeight - overlap
      if (overlap > 0f || pinned.size >= limit || height + 2 * lineHeight > editorHeight / 2) break
    }
  }
  return StickyPanel(pinned, height)
}
