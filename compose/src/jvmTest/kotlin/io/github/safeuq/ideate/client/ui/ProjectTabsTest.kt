package io.github.safeuq.ideate.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTabsTest {
  @Test
  fun lastTabTakesTheRemainder() {
    assertEquals(listOf(333, 333, 334), projectTabWidths(1000, 3))
    assertEquals(listOf(450, 450), projectTabWidths(900, 2))
    assertEquals(emptyList(), projectTabWidths(10, 0))
  }
}
