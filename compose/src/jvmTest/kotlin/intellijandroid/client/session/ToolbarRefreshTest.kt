package intellijandroid.client.session

import intellijandroid.client.ui.MenuItem
import intellijandroid.client.ui.MenuSeparator
import intellijandroid.client.ui.SubMenu
import kotlin.test.Test
import kotlin.test.assertEquals

/** A toolbar item's click runs the action, then asks for the toolbar again; also inside a group. */
class ToolbarRefreshTest {
  @Test
  fun everyItemRunsItsActionAndThenTheRefresh() {
    val calls = mutableListOf<String>()
    val entries =
      listOf(
        MenuItem("Sync All Gradle Projects", onClick = { calls += "sync" }),
        MenuSeparator,
        SubMenu(
          "Build Tool Settings",
          listOf(MenuItem("Auto-Sync Settings…", onClick = { calls += "settings" })),
        ),
      )

    val wrapped = afterEach(entries) { calls += "refresh" }
    (wrapped[0] as MenuItem).onClick()
    ((wrapped[2] as SubMenu).entries[0] as MenuItem).onClick()

    assertEquals(listOf("sync", "refresh", "settings", "refresh"), calls)
    assertEquals(MenuSeparator, wrapped[1])
  }
}
