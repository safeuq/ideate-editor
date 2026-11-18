package intellijandroid.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleShiftTest {
  private fun run(vararg events: Triple<Boolean, Boolean, Long>): List<Boolean> {
    val detector = DoubleShift()
    return events.map { (shift, down, time) -> detector.onKey(shift, down, time) }
  }

  private fun shift(down: Boolean, time: Long) = Triple(true, down, time)

  @Test
  fun twoQuickPressesOpen() {
    assertEquals(
      listOf(false, false, false, true),
      run(shift(true, 0), shift(false, 80), shift(true, 200), shift(false, 260)),
    )
  }

  @Test
  fun aSlowSecondPressDoesNot() {
    assertEquals(
      listOf(false, false, false, false),
      run(shift(true, 0), shift(false, 80), shift(true, 450), shift(false, 500)),
    )
  }

  @Test
  fun anotherKeyBetweenDoesNot() {
    assertEquals(
      listOf(false, false, false, false, false),
      run(
        shift(true, 0),
        shift(false, 80),
        Triple(false, true, 120),
        shift(true, 200),
        shift(false, 260),
      ),
    )
  }
}
