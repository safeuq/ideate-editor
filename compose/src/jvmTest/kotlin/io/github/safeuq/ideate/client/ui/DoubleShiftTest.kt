package io.github.safeuq.ideate.client.ui

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

  /** Typing `$$`: each `$` is Shift down, 4 down and up, Shift up, a few ms apart. */
  @Test
  fun shiftsOfTypedCharactersDoNot() {
    val four = { down: Boolean, time: Long -> Triple(false, down, time) }
    assertEquals(
      List(8) { false },
      run(
        shift(true, 0),
        four(true, 2),
        four(false, 4),
        shift(false, 5),
        shift(true, 40),
        four(true, 42),
        four(false, 44),
        shift(false, 45),
      ),
    )
  }

  /** IntelliJ's rule: a Shift less than 100 ms after another key doesn't count, later ones do. */
  @Test
  fun aDoubleShiftSoonAfterTypingDoesNot() {
    assertEquals(
      listOf(false, false, false, false, false),
      run(
        Triple(false, true, 0),
        shift(true, 50),
        shift(false, 120),
        shift(true, 200),
        shift(false, 260),
      ),
    )
    assertEquals(
      listOf(false, false, false, false, true),
      run(
        Triple(false, true, 0),
        shift(true, 150),
        shift(false, 220),
        shift(true, 300),
        shift(false, 360),
      ),
    )
  }

  /**
   * Android: each Shift seen before the soft keyboard, then again after it, and once more as the
   * keyboard app's copy. Only the first delivery counts.
   */
  @Test
  fun onlyEventsBeforeTheSoftKeyboardCountOnceSeen() {
    val detector = DoubleShift()
    val results =
      listOf(true to 0L, false to 15L, true to 21L, false to 25L).flatMap { (down, time) ->
        listOf(
          detector.onKey(true, down, time, beforeSoftKeyboard = true),
          detector.onKey(true, down, time),
          detector.onKey(true, down, time + 1),
        )
      }
    assertEquals(List(9) { false } + listOf(true, false, false), results)
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
