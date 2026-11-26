package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.session.MainMenuClientItems.Kind
import io.github.safeuq.ideate.client.session.MainMenuClientItems.Row
import io.github.safeuq.ideate.client.ui.MenuEntry
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.SubMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MainMenuClientItemsTest {
  private fun labels(entries: List<MenuEntry>) = entries.map {
    when (it) {
      is MenuItem -> it.label
      is SubMenu -> it.label
      else -> "-"
    }
  }

  private val menus =
    listOf(
      SubMenu(
        "Edit",
        listOf(
          MenuItem("Delete"),
          SubMenu(
            "Find",
            listOf(MenuItem("Scroll to Results During Typing"), MenuItem("Search Structurally…")),
          ),
        ),
      ),
      SubMenu("VCS", emptyList()),
      SubMenu("Help", emptyList()),
    )

  @Test
  fun `a row goes after its anchor, greyed`() {
    val merged =
      MainMenuClientItems.merge(
        menus,
        listOf(Row("Edit", "Find", "Find Next", Kind.ITEM, "Scroll to Results During Typing")),
      )
    val find = merged[0].entries.filterIsInstance<SubMenu>().single()
    assertEquals(
      listOf("Scroll to Results During Typing", "Find Next", "Search Structurally…"),
      labels(find.entries),
    )
    assertFalse((find.entries[1] as MenuItem).enabled)
  }

  @Test
  fun `a missing anchor goes after the previous row, else at the end`() {
    val merged =
      MainMenuClientItems.merge(
        menus,
        listOf(
          Row("Edit", "", "Missing Anchor", Kind.ITEM, "Not There"),
          Row("Edit", "", "After Previous", Kind.ITEM, "Also Not There"),
          Row("Edit", "", "Empty After", Kind.ITEM, ""),
        ),
      )
    assertEquals(
      listOf("Empty After", "Delete", "Find", "Missing Anchor", "After Previous"),
      labels(merged[0].entries),
    )
  }

  @Test
  fun `a label Ideate shows already is skipped`() {
    val merged = MainMenuClientItems.merge(menus, listOf(Row("Edit", "", "Delete", Kind.ITEM, "")))
    assertEquals(listOf("Delete", "Find"), labels(merged[0].entries))
    assertEquals(true, (merged[0].entries[0] as MenuItem).enabled)
  }

  @Test
  fun `the whole Window menu goes after VCS`() {
    val merged = MainMenuClientItems.merge(menus)
    assertEquals(listOf("Edit", "VCS", "Window", "Help"), merged.map { it.label })
    assertFalse(merged[2].enabled)
    assertEquals(emptyList(), merged[2].entries)
  }
}
