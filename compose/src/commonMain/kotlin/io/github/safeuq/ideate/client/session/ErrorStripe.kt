package io.github.safeuq.ideate.client.session

/**
 * One painted mark on the scrollbar, in px from the track's top; [offset] is where its highlighter
 * starts in the text.
 */
data class ErrorStripeSpot(
  val top: Float,
  val bottom: Float,
  val color: Long,
  val thin: Boolean,
  val offset: Int,
)

/**
 * The marks on the editor's scrollbar, IntelliJ's error stripe (`EditorMarkupModelImpl`). Sizes in
 * dp are IntelliJ's unscaled pixels.
 */
object ErrorStripe {
  /**
   * `DaemonCodeAnalyzerSettings.errorStripeMarkMinHeight`: a mark's least height, a thin one's
   * width.
   */
  const val MIN_MARK_HEIGHT = 2
  /** `thinGap`: between the thin marks' column and the wide marks' one. */
  const val THIN_GAP = 2
  /** `Editor.scrollBarWidth`: a wide mark's width. */
  const val SCROLLBAR_WIDTH = 14
  /** The strip the marks are painted in, from the editor's right edge (`getThickness`). */
  const val THICKNESS = SCROLLBAR_WIDTH + THIN_GAP + MIN_MARK_HEIGHT

  /**
   * Where each highlighter with a stripe color is painted (`offsetsToYPositions`,
   * `drawErrorStripeMarkers`): at its first line's top, down to its last line's top, at least
   * [minMarkHeight] tall. A file shorter than the track keeps its own line positions; a longer one
   * is scaled to the track. [highlights] are sorted by layer, lowest first.
   */
  // ponytail: one spot per highlighter, painted in layer order, so a higher layer covers a lower
  // one. IntelliJ merges overlapping spots first; the pixels come out the same for opaque colors.
  fun spots(
    highlights: List<Highlight>,
    lineOf: (Int) -> Int,
    lineCount: Int,
    lineHeight: Float,
    trackHeight: Float,
    minMarkHeight: Float,
  ): List<ErrorStripeSpot> {
    val sourceHeight = lineCount * lineHeight
    val scale =
      if (sourceHeight < trackHeight || sourceHeight == 0f) 1f else trackHeight / sourceHeight
    return highlights.mapNotNull { highlight ->
      val color = highlight.errorStripeColor ?: return@mapNotNull null
      val startLine = lineOf(highlight.start)
      val endLine = lineOf(highlight.end)
      var top = startLine * lineHeight * scale
      var bottom = if (endLine == startLine) top else endLine * lineHeight * scale
      if (bottom - top < minMarkHeight) bottom = top + minMarkHeight
      // drawSpot: a short thin mark is made a little taller.
      if (highlight.thinErrorStripeMark && bottom - top < 3 * minMarkHeight) {
        top -= minMarkHeight / 2
        bottom += bottom - top - minMarkHeight / 2
      }
      ErrorStripeSpot(top, bottom, color, highlight.thinErrorStripeMark, highlight.start)
    }
  }

  /**
   * The mark a click at [y] goes to (`getNearestRangeHighlighter`): of the marks that reach within
   * two [minMarkHeight] of [y], the one whose top is closest to it; null when there is none. Of
   * marks equally close, e.g. an error and a warning on one line, the one painted last wins: it is
   * the one seen. (IntelliJ takes whichever its hash set yields first.)
   */
  fun nearest(spots: List<ErrorStripeSpot>, y: Float, minMarkHeight: Float): ErrorStripeSpot? =
    spots
      .asReversed()
      .filter { y >= it.top - minMarkHeight * 2 && y <= it.bottom + minMarkHeight * 2 }
      .minByOrNull { kotlin.math.abs(it.top - y) }
}
