package io.github.safeuq.ideate.client.connect

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundClockTest {
  @Test
  fun countsOnlyTheTimeInTheForeground() {
    var time = 0L
    val clock = ForegroundClock { time }
    clock.inForeground(true)
    time = 50_000
    clock.inForeground(false) // Home; the phone freezes the app for 10 minutes
    time = 650_000
    assertEquals(50_000, clock.elapsed())
    clock.inForeground(true)
    time = 720_000
    assertEquals(120_000, clock.elapsed())
  }
}
