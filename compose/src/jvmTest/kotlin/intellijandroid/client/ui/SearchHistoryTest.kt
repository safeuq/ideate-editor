package intellijandroid.client.ui

import intellijandroid.client.ui.component.SearchHistory
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryTest {
  @Test
  fun `newest first, no blanks, a repeat moves to the top, at most five`() {
    val history = SearchHistory()
    listOf("a", "b", " ", "A", "c", "d", "e", "f").forEach(history::add)
    // "A" replaced "a" and moved up; "b", the oldest, fell off when "f" came.
    assertEquals(listOf("f", "e", "d", "c", "A"), history.items.toList())
  }

  @Test
  fun `the newest search stays as it is, even in another case`() {
    val history = SearchHistory()
    history.add("smoke")
    history.add("SMOKE")
    assertEquals(listOf("smoke"), history.items.toList())
  }
}
