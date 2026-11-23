package io.github.safeuq.ideate.client.ui

import io.github.safeuq.ideate.client.session.BackendToolWindow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of the backend's tool windows get a bar button, in which place, and which go under ⋯. */
class ToolWindowButtonsTest {
  @Test
  fun followsTheNewUiDefaultLayout() {
    val buttons =
      toolWindowButtons(
        listOf(
          BackendToolWindow("Coverage", "Coverage", null, "RIGHT"),
          BackendToolWindow("Problems View", "Problems", null, "BOTTOM"),
          BackendToolWindow("Commit", "Commit", null, "LEFT", available = false),
          BackendToolWindow("Version Control", "Version Control", null, "BOTTOM"),
          BackendToolWindow("Pull Requests", "Pull Requests", null, "LEFT", onStripe = true),
          BackendToolWindow("Structure", "Structure", null, "LEFT", split = true),
          BackendToolWindow("Database", "Database", null, "RIGHT"),
          BackendToolWindow("Bookmarks", "Bookmarks", null, "LEFT"),
        ),
      )
    assertEquals(
      mapOf(
        Place.LEFT_TOP to listOf("Project", "Pull Requests"),
        Place.LEFT_BOTTOM to listOf("Structure"),
        // Terminal is the client's own window: always there, after the backend's bottom windows.
        Place.BOTTOM_LEFT to listOf("Version Control", "Problems View", "Terminal"),
        Place.RIGHT_TOP to listOf("Database"),
      ),
      buttons.byPlace.mapValues { (_, group) -> group.map { it.id } },
    )
    assertEquals(listOf("Bookmarks", "Coverage"), buttons.more.map { it.id })
  }

  @Test
  fun eachLayoutKeepsItsOwnRule() {
    val database = ToolWindow("Database", "Database", null, Place.RIGHT_TOP)
    val terminal = ToolWindow("Terminal", "Terminal", null, Place.BOTTOM_LEFT)
    fun ids(state: ToolWindowState) = Place.entries.mapNotNull { state.open[it]?.id }

    // Desktop: one side pane, split; the bottom area on its own.
    val wide = ToolWindowState()
    wide.open(ToolWindow.STRUCTURE, ScreenLayout.WIDE)
    wide.open(terminal, ScreenLayout.WIDE)
    assertEquals(listOf("Project", "Structure", "Terminal"), ids(wide))
    wide.open(database, ScreenLayout.WIDE)
    assertEquals(listOf("Terminal", "Database"), ids(wide))

    // Phone sideways: one tool window.
    val compact = ToolWindowState()
    compact.open(terminal, ScreenLayout.COMPACT)
    assertEquals(listOf("Terminal"), ids(compact))

    // Phone upright: everything stays; a rotation to sideways keeps the first.
    val portrait = ToolWindowState()
    portrait.open(ToolWindow.STRUCTURE, ScreenLayout.PORTRAIT)
    portrait.open(database, ScreenLayout.PORTRAIT)
    assertEquals(listOf("Project", "Structure", "Database"), ids(portrait))
    portrait.fit(ScreenLayout.COMPACT)
    assertEquals(listOf("Project"), ids(portrait))

    // A tapped open window closes; an emptied side opens its last windows again.
    portrait.toggle(ToolWindow.PROJECT, ScreenLayout.PORTRAIT)
    assertEquals(emptyList(), ids(portrait))
    assertEquals(true, portrait.reopen(Side.LEFT))
    assertEquals(listOf("Project", "Structure"), ids(portrait))
  }
}
