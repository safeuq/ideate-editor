package io.github.safeuq.ideate.client.ui.lux

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the BuildView panel's toolbar buttons are, and how a point in a drawn cell maps back. */
class BuildToolbarCellsTest {
  private val buildStrips = BuildStrips(top = 0, left = 33, consoleLeft = 41)
  private val buildCells =
    stripCells(BUILD_VIEW_LABELS, 0, 0) + stripCells(BUILD_CONSOLE_LABELS, 41, 0)

  @Test
  fun runCellsAreUnderItsToolbarRow() {
    // Run at 411 dp: console strip at 379, under the 32 dp toolbar row
    val cells = stripCells(BUILD_CONSOLE_LABELS, 379, 32)
    assertEquals(
      listOf(IntOffset(384, 36), IntOffset(384, 62), IntOffset(384, 88)),
      cells.map { IntOffset(it.x, it.y) },
    )
  }

  @Test
  fun cellsAreWhereTheBackendDrewThem() {
    // label to (x, y) of the cell's top left in the 73 dp panel, measured on the Mac backend
    val expected =
      mapOf(
        "Rerun" to IntOffset(5, 4),
        "Stop" to IntOffset(5, 30),
        "Pin" to IntOffset(5, 56),
        "Filter" to IntOffset(5, 82),
        "Soft-Wrap" to IntOffset(46, 4),
        "Scroll to the End" to IntOffset(46, 30),
        "Clear All" to IntOffset(46, 56),
      )
    assertEquals(41, buildStrips.consoleLeft)
    val actual = buildCells.associate { it.label to IntOffset(it.x, it.y) }
    assertEquals(expected, actual)
  }

  @Test
  fun cellsStayInsideThePanelAndDontOverlap() {
    val cells = buildCells
    for (cell in cells) {
      assertTrue(cell.x >= 0 && cell.x + BUILD_CELL_SIZE <= BUILD_STRIPS_WIDTH, cell.label)
    }
    for (first in cells) for (second in cells) if (first !== second) {
      val apart =
        first.x + BUILD_CELL_SIZE <= second.x ||
          second.x + BUILD_CELL_SIZE <= first.x ||
          first.y + BUILD_CELL_SIZE <= second.y ||
          second.y + BUILD_CELL_SIZE <= first.y
      assertTrue(apart, "${first.label} overlaps ${second.label}")
    }
  }

  @Test
  fun aPointInACellIsSentAtThePlaceInTheStrip() {
    // (cell label, point inside the cell) to the panel point the backend is told
    val expected =
      mapOf(
        ("Rerun" to IntOffset(0, 0)) to IntOffset(5, 4),
        ("Pin" to IntOffset(11, 11)) to IntOffset(16, 67),
        ("Filter" to IntOffset(21, 21)) to IntOffset(26, 103),
        ("Clear All" to IntOffset(11, 11)) to IntOffset(57, 67),
      )
    val cells = buildCells.associateBy { it.label }
    val actual = expected.mapValues { (input, _) ->
      cells.getValue(input.first).panelPoint(input.second.x, input.second.y)
    }
    assertEquals(expected, actual)
  }

  @Test
  fun panelOriginPutsTheCellWhereItIsDrawn() {
    // Filter drawn at (94, 583) on the screen: the panel's top left must be at (89, 501)
    val filter = buildCells.first { it.label == "Filter" }
    assertEquals(IntOffset(89, 501), filter.panelOrigin(94, 583))
  }
}
