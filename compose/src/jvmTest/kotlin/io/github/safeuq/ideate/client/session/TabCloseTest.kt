package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

class TabCloseTest {
  private val tabs = listOf("A.java", "B.java", "C.java")

  @Test
  fun selectsTheLeftNeighbour() {
    assertEquals("B.java", tabToSelectAfterClose(tabs, closedIndex = 2))
    assertEquals("A.java", tabToSelectAfterClose(tabs, closedIndex = 1))
  }

  @Test
  fun firstTabSelectsTheNextOne() {
    assertEquals("B.java", tabToSelectAfterClose(tabs, closedIndex = 0))
  }

  @Test
  fun lastRemainingTabSelectsNothing() {
    assertEquals(null, tabToSelectAfterClose(listOf("A.java"), closedIndex = 0))
  }
}
