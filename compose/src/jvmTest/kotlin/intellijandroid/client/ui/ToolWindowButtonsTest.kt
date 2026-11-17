package intellijandroid.client.ui

import intellijandroid.client.session.BackendToolWindow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of the backend's tool windows get a bar button, in what order, and which go under ⋯. */
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
          BackendToolWindow("Structure", "Structure", null, "LEFT"),
          BackendToolWindow("Database", "Database", null, "RIGHT"),
          BackendToolWindow("Bookmarks", "Bookmarks", null, "LEFT"),
        ),
      )
    assertEquals(
      listOf(
        listOf("Project", "Pull Requests"),
        listOf("Version Control", "Problems View"),
        listOf("Structure", "Database"),
      ),
      buttons.groups.map { group -> group.map { it.id } },
    )
    assertEquals(listOf("Bookmarks", "Coverage"), buttons.more.map { it.id })
  }
}
