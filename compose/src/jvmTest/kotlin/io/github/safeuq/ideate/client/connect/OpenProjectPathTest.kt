package io.github.safeuq.ideate.client.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Termux's `~/storage/shared` is a symlink; `intellij-backend start` lists the resolved path. */
class OpenProjectPathTest {
  private val viaSymlink =
    "/data/data/com.termux/files/home/storage/shared/Files/Projects/Xflow/commons"
  private val resolved = "/storage/emulated/0/Files/Projects/Xflow/commons"
  private val xtool = "/storage/emulated/0/Files/Projects/Xflow/xtool"

  @Test
  fun beforeStartASymlinkPathIsNotOpenSoStartRuns() {
    assertNull(openProjectPath(listOf(xtool, resolved), xtool, viaSymlink, started = false))
  }

  @Test
  fun afterStartTheProjectWithTheSameFolderNameIsTaken() {
    assertEquals(
      resolved,
      openProjectPath(listOf(xtool, resolved), xtool, viaSymlink, started = true),
    )
  }

  @Test
  fun afterStartWithNoUniqueNameTheLastOpenedIsTaken() {
    assertEquals(
      resolved,
      openProjectPath(listOf(xtool, resolved), resolved, "/elsewhere/renamed", started = true),
    )
  }

  @Test
  fun anExactPathMatchesWithOrWithoutTheTrailingSlash() {
    assertEquals(
      resolved,
      openProjectPath(listOf(xtool, resolved), xtool, "$resolved/", started = false),
    )
  }
}
