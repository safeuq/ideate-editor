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
      listOf(ErrorStripeSpot(100f, 102f, 0xFFFF0000, false)),
      spots(10, highlight(5, 0xFFFF0000)),
    )
  }

  @Test
  fun aLongFileIsScaledToTheTrack() {
    // 40 lines of 20 px = 800 px in a 400 px track: line 20 is at 200; lines 20 to 30 end at 300.
    assertEquals(
      listOf(ErrorStripeSpot(200f, 300f, 0xFFFF0000, false)),
      spots(40, highlight(20, 0xFFFF0000, endLine = 30)),
    )
  }

  @Test
  fun aHighlighterWithoutStripeColorGetsNoMark() {
    assertEquals(emptyList(), spots(10, highlight(5, null)))
  }
}
