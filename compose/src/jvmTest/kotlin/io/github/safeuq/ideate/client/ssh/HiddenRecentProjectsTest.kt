package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.StoredText
import kotlin.test.Test
import kotlin.test.assertEquals

class HiddenRecentProjectsTest {
  private class MemoryText(var text: String? = null) : StoredText {
    override fun read() = text

    override fun write(text: String) {
      this.text = text
    }
  }

  private fun project(path: String, lastOpened: Long) =
    RecentProject(path, "/Applications/IntelliJ IDEA.app/Contents", lastOpened, running = false)

  private val old = project("/Users/me/RESTest", 1_000)
  private val other = project("/Users/me/xtool", 2_000)

  @Test
  fun hideFiltersTheRow() {
    val hidden = HiddenRecentProjects(MemoryText())
    hidden.hide("local", old.projectPath, 5_000)
    assertEquals(listOf(other), hidden.visible("local", listOf(old, other)))
  }

  @Test
  fun newerUseShowsItAgainAndClearsTheEntry() {
    val storage = MemoryText()
    val hidden = HiddenRecentProjects(storage)
    hidden.hide("local", old.projectPath, 5_000)
    val usedAgain = project(old.projectPath, 6_000)
    assertEquals(listOf(usedAgain), hidden.visible("local", listOf(usedAgain)))
    assertEquals("[]", storage.text)
    // An older date after that no longer hides it either: the entry is gone.
    assertEquals(listOf(old), hidden.visible("local", listOf(old)))
  }

  @Test
  fun otherHostsUnaffected() {
    val hidden = HiddenRecentProjects(MemoryText())
    hidden.hide("local", old.projectPath, 5_000)
    assertEquals(listOf(old), hidden.visible("586e63d9", listOf(old)))
  }

  @Test
  fun emptyOrBlankStorageReadsAsEmpty() {
    assertEquals(listOf(old), HiddenRecentProjects(MemoryText(null)).visible("local", listOf(old)))
    assertEquals(listOf(old), HiddenRecentProjects(MemoryText("  ")).visible("local", listOf(old)))
  }
}
