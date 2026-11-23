package io.github.safeuq.ideate.client.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class PannedAxesTest {
  private val sidewaysOnly = Offset(1f, 0f)
  private val verticalOnly = Offset(0f, 1f)
  private val both = Offset(1f, 1f)

  @Test
  fun keepsToOneAxisNearIt() {
    assertEquals(sidewaysOnly, pannedAxes(Offset(30f, 4f)))
    assertEquals(sidewaysOnly, pannedAxes(Offset(-30f, 15f)))
    assertEquals(verticalOnly, pannedAxes(Offset(4f, -30f)))
    assertEquals(verticalOnly, pannedAxes(Offset(0f, 9f)))
  }

  @Test
  fun movesBothWaysOnADiagonal() {
    assertEquals(both, pannedAxes(Offset(20f, 15f)))
    assertEquals(both, pannedAxes(Offset(-12f, -20f)))
  }
}
