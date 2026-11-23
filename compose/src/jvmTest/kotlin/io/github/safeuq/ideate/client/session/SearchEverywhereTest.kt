package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SearchEverywhereTest {
  /** An item as the backend sent it for "Main" (icons left out). */
  private fun item(uuid: String, name: String, match: Int, extra: String = "") =
    Json.parseToJsonElement(
        """
        {"type": "com.intellij.platform.searchEverywhere.SeItemDataImpl", "uuid": "$uuid",
         "providerId": {"value": "FileSearchEverywhereContributor"},
         "composedWeight": {"components": [
           {"key": {"id": "matchWeight", "order": 0, "defaultWeight": 0}, "weight": $match}$extra]},
         "presentation": {
           "type": "com.intellij.platform.searchEverywhere.presentations.SeTargetItemPresentationImpl",
           "backgroundColorId": null, "iconId": null, "presentableText": "$name",
           "presentableTextMatchedRanges": [{"startOffset": 0, "endOffset": 4}],
           "containerText": "src/demo", "containerTextMatchedRanges": [], "locationText": "smoke"},
         "uuidsToReplace": []}
        """,
      )
      .jsonObject

  @Test
  fun rowFromItem() {
    val row = searchRow(item("8d19", "Main.java", 11052))!!
    assertEquals("Main.java", row.name)
    assertEquals(listOf(0 until 4), row.nameMatches)
    assertEquals("src/demo", row.container)
    assertEquals("smoke", row.location)
  }

  @Test
  fun rowsInWeightOrder() {
    val notDeprecated =
      """, {"key": {"id": "notDeprecated", "order": 200, "defaultWeight": 1}, "weight": 0}"""
    var rows = emptyList<SearchRow>()
    for (next in
      listOf(
        item("a", "MainTest.java", 9000),
        item("b", "Main.java", 11052),
        // Same match weight as b, but deprecated: below it (a missing component counts as 1).
        item("c", "MainOld.java", 11052, notDeprecated),
      )) rows = insertByWeight(rows, searchRow(next)!!)
    assertEquals(listOf("b", "c", "a"), rows.map { it.uuid })
  }
}
