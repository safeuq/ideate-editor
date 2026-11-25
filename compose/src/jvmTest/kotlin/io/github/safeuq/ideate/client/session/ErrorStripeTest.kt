package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

/** Positions follow `EditorMarkupModelImpl.offsetsToYPositions` and `drawErrorStripeMarkers`. */
class ErrorStripeTest {
  private fun highlight(line: Int, color: Long?, endLine: Int = line) =
    Highlight(
      line.toLong(),
      line * 10,
      endLine * 10 + 1,
      4000,
      TextStyle(),
      errorStripeColor = color,
    )

  private fun spots(lineCount: Int, vararg highlights: Highlight) =
    ErrorStripe.spots(highlights.toList(), { it / 10 }, lineCount, 20f, 400f, 2f)

  @Test
  fun aShortFileKeepsItsLinePositions() {
    // 10 lines of 20 px in a 400 px track: line 5 is at 100.
    assertEquals(
      listOf(ErrorStripeSpot(100f, 102f, 0xFFFF0000, false, 50)),
      spots(10, highlight(5, 0xFFFF0000)),
    )
  }

  @Test
  fun aLongFileIsScaledToTheTrack() {
    // 40 lines of 20 px = 800 px in a 400 px track: line 20 is at 200; lines 20 to 30 end at 300.
    assertEquals(
      listOf(ErrorStripeSpot(200f, 300f, 0xFFFF0000, false, 200)),
      spots(40, highlight(20, 0xFFFF0000, endLine = 30)),
    )
  }

  @Test
  fun aHighlighterWithoutStripeColorGetsNoMark() {
    assertEquals(emptyList(), spots(10, highlight(5, null)))
  }

  @Test
  fun aClickGoesToTheNearestMarkWithinTwoMarkHeights() {
    // Marks at 100 to 102 (line 5) and 120 to 122 (line 6).
    val marks = spots(10, highlight(5, 0xFFFF0000), highlight(6, 0xFFFFFF00))
    assertEquals(50, ErrorStripe.nearest(marks, 104f, 2f)?.offset)
    assertEquals(60, ErrorStripe.nearest(marks, 117f, 2f)?.offset)
    // 110 is more than 4 px from both.
    assertEquals(null, ErrorStripe.nearest(marks, 110f, 2f))
    // Two marks on one line: the one painted last (on top) wins.
    val onOneLine = spots(10, highlight(5, 0xFFFFFF00), highlight(5, 0xFFFF0000))
    assertEquals(0xFFFF0000, ErrorStripe.nearest(onOneLine, 101f, 2f)?.color)
  }
}
