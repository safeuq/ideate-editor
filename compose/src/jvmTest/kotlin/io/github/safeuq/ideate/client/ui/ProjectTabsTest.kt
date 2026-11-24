package io.github.safeuq.ideate.client.ui

import io.github.safeuq.ideate.client.session.OpenProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectTabsTest {
  @Test
  fun lastTabTakesTheRemainder() {
    assertEquals(listOf(333, 333, 334), projectTabWidths(1000, 3))
    assertEquals(listOf(450, 450), projectTabWidths(900, 2))
    assertEquals(emptyList(), projectTabWidths(10, 0))
  }

  @Test
  fun neighbourStopsAtBothEnds() {
    val projects = listOf(OpenProject("alpha", "/work/alpha"), OpenProject("beta", "/work/beta"))
    assertEquals("/work/beta", neighbourProject(projects, "/work/alpha", 1))
    assertEquals("/work/alpha", neighbourProject(projects, "/work/beta", -1))
    assertNull(neighbourProject(projects, "/work/beta", 1))
    assertNull(neighbourProject(projects, "/work/alpha", -1))
    assertNull(neighbourProject(projects, "/work/gone", 1))
  }
}
