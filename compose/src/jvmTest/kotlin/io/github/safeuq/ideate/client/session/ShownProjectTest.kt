package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One backend sends every open project as a solution; Ideate shows the one the user opened. */
class ShownProjectTest {
  private val commons = "/data/data/com.termux/files/home/IdeaProjects/commons"
  private val xtool = "/data/data/com.termux/files/home/IdeaProjects/xtool"

  @Test
  fun theWantedProjectIsShownAndTheOtherIsNot() {
    assertFalse(isShownProject(commons, xtool, showing = false))
    assertTrue(isShownProject(commons, "$commons/", showing = false))
  }

  @Test
  fun withNoProjectWantedTheFirstIsShown() {
    assertTrue(isShownProject(null, xtool, showing = false))
    assertFalse(isShownProject(null, commons, showing = true))
  }

  @Test
  fun aProjectWithoutAPathIsNotTakenForTheWantedOne() {
    assertFalse(isShownProject(commons, null, showing = false))
  }
}
