package io.github.safeuq.ideate.client.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SubmenuPosition]: where a cascading submenu goes, as IntelliJ's `WizardPopup` places it. */
class SubmenuPositionTest {
  private val position = SubmenuPosition(stepPadding = 2, verticalInset = 6)
  private val screen = IntSize(1000, 800)
  private val submenu = IntSize(300, 400)

  private fun place(row: IntRect) =
    position.calculatePosition(row, screen, LayoutDirection.Ltr, submenu)

  /** A row of a menu at x 100..340: the submenu at 338, its top 6 above the row's. */
  @Test
  fun rightOfTheParent() = assertEquals(IntOffset(338, 94), place(IntRect(100, 100, 340, 124)))

  /** A menu at x 600..840: 838 + 300 is past 1000, so left of it at 600 - 300 + 2. */
  @Test
  fun leftWithNoRoomOnTheRight() =
    assertEquals(IntOffset(302, 94), place(IntRect(600, 100, 840, 124)))

  /** A row near the bottom: moved up so all 400 fit in 800. */
  @Test
  fun movedUpToFitTheHeight() =
    assertEquals(IntOffset(338, 400), place(IntRect(100, 700, 340, 724)))
}
