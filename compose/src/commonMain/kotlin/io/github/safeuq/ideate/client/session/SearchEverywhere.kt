package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.kernel.SearchKernel
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search Everywhere's tabs, with the backend provider each one asks (`SeProviderId`). All, Actions
 * and Text are not supported yet: All mixes providers in groups, Actions and Text have rows of
 * their own kinds.
 */
enum class SearchTab(val title: String, val providerId: String?) {
  ALL("All", null),
  CLASSES("Classes", "ClassSearchEverywhereContributor"),
  FILES("Files", "FileSearchEverywhereContributor"),
  SYMBOLS("Symbols", "SymbolSearchEverywhereContributor"),
  ACTIONS("Actions", null),
  TEXT("Text", null),
}

/** The open Search Everywhere popup. */
data class SearchPopup(
  val tab: SearchTab,
  val query: String = "",
  val rows: List<SearchRow> = emptyList(),
  val selected: Int = 0,
  /** The backend is still sending rows for [query]. */
  val searching: Boolean = false,
)

/**
 * A row as IntelliJ's `SeTargetItemPresentationRenderer` draws it: icon, [name] (the matched ranges
 * highlighted on the selected row), [container] in grey, [location] on the right with its icon.
 * Example: `Main.java`, `src/demo`, `smoke`.
 */
data class SearchRow(
  val uuid: String,
  val iconPath: String?,
  val name: String,
  val nameMatches: List<IntRange>,
  val container: String?,
  val location: String?,
  val locationIconPath: String?,
  /** `SeComposedWeight`'s components as (order, id) to weight; rows are sorted by it. */
  val weight: Map<Pair<Int, String>, Int>,
  /** `SeComposedWeight` defaults of the keys in [weight], for keys another row lacks. */
  val weightDefaults: Map<Pair<Int, String>, Int>,
)

/**
 * The popup's state and its calls to [kernel]: one search per query, the rows in IntelliJ's order,
 * the chosen row opened by the backend.
 */
class SearchEverywhere(
  private val kernel: SearchKernel,
  private val update: ((SearchPopup?) -> SearchPopup?) -> Unit,
) {
  private var job: Job? = null

  /** `SeItemData` JSON by uuid, sent back when a row is chosen. */
  private val items = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

  fun open(tab: SearchTab) {
    update { SearchPopup(tab) }
    search(tab, "")
  }

  fun query(text: String) {
    var tab: SearchTab? = null
    update { popup -> popup?.copy(query = text).also { tab = popup?.tab } }
    tab?.let { search(it, text) }
  }

  fun tab(tab: SearchTab) {
    var query: String? = null
    update { popup -> popup?.copy(tab = tab).also { query = popup?.query } }
    query?.let { search(tab, it) }
  }

  fun key(key: LookupKey, current: SearchPopup) {
    when (key) {
      LookupKey.UP,
      LookupKey.DOWN -> {
        if (current.rows.isEmpty()) return
        val step = if (key == LookupKey.UP) -1 else 1
        update { it?.copy(selected = (it.selected + step).mod(it.rows.size)) }
      }
      LookupKey.ACCEPT ->
        current.rows.getOrNull(current.selected)?.let { choose(it, current.query) }
      LookupKey.CANCEL -> close()
    }
  }

  /** Enter or a tap: the backend opens the item and says whether the popup closes. */
  fun choose(row: SearchRow, query: String) {
    val item = items[row.uuid] ?: return
    kernel.choose(item, query) { closes -> if (closes) close() }
  }

  fun close() {
    job?.cancel()
    items.clear()
    kernel.endSession()
    update { null }
  }

  private fun search(tab: SearchTab, query: String) {
    job?.cancel()
    items.clear()
    val providerId = tab.providerId
    // ponytail: an empty query shows nothing; IntelliJ's Files tab shows recent files there.
    if (providerId == null || query.isBlank()) {
      update { it?.copy(rows = emptyList(), selected = 0, searching = false) }
      return
    }
    update { it?.copy(rows = emptyList(), selected = 0, searching = true) }
    job =
      kernel.search(
        providerId,
        query,
        onItem = item@{ item ->
            val row = searchRow(item) ?: return@item
            items[row.uuid] = item
            val replaced =
              item["uuidsToReplace"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
            update { popup ->
              popup?.copy(rows = insertByWeight(popup.rows.filter { it.uuid !in replaced }, row))
            }
          },
        onEnd = { update { it?.copy(searching = false) } },
      )
  }
}

/**
 * The row of an `SeItemDataImpl`, e.g. `{"uuid": "8d19…", "presentation": {"type":
 * "…SeTargetItemPresentationImpl", "presentableText": "Main.java", "containerText": "src/demo",
 * …}}`. Null for presentations without a `presentableText` (actions, text matches).
 */
internal fun searchRow(item: JsonObject): SearchRow? {
  val presentation = item["presentation"] as? JsonObject ?: return null
  val name = presentation.text("presentableText") ?: return null
  val weight = mutableMapOf<Pair<Int, String>, Int>()
  val defaults = mutableMapOf<Pair<Int, String>, Int>()
  (item["composedWeight"] as? JsonObject)?.get("components")?.jsonArray?.forEach { component ->
    val key = component.jsonObject.getValue("key").jsonObject
    val id = key.getValue("order").jsonPrimitive.int to key.text("id").orEmpty()
    weight[id] = component.jsonObject.getValue("weight").jsonPrimitive.int
    defaults[id] = key["defaultWeight"]?.jsonPrimitive?.int ?: 0
  }
  return SearchRow(
    uuid = item.text("uuid") ?: return null,
    iconPath = iconPath(presentation["iconId"]),
    name = name,
    nameMatches =
      (presentation["presentableTextMatchedRanges"] as? JsonArray).orEmpty().map { range ->
        val start = range.jsonObject.getValue("startOffset").jsonPrimitive.int
        start until range.jsonObject.getValue("endOffset").jsonPrimitive.int
      },
    container = presentation.text("containerText"),
    location = presentation.text("locationText"),
    locationIconPath = iconPath(presentation["locationIconId"]),
    weight = weight,
    weightDefaults = defaults,
  )
}

/**
 * `SeComposedWeight.compareTo`: key by key in key order (`order`, then `id`), a missing component
 * counting as its key's default weight. Example: {match=11052, notDeprecated=1} is above
 * {match=9000}.
 */
internal fun compareWeights(left: SearchRow, right: SearchRow): Int {
  val defaults = left.weightDefaults + right.weightDefaults
  for (key in
    (left.weight.keys + right.weight.keys).sortedWith(compareBy({ it.first }, { it.second }))) {
    val difference =
      (left.weight[key] ?: defaults[key] ?: 0).compareTo(right.weight[key] ?: defaults[key] ?: 0)
    if (difference != 0) return difference
  }
  return 0
}

/** [rows] with [row] after every row of at least its weight, as IntelliJ's results list orders. */
internal fun insertByWeight(rows: List<SearchRow>, row: SearchRow): List<SearchRow> {
  val position = rows.indexOfFirst { compareWeights(it, row) < 0 }.takeIf { it >= 0 } ?: rows.size
  return rows.toMutableList().apply { add(position, row) }
}

/** The string at [key], or null when it is missing or not a string (`null` in the JSON). */
private fun JsonObject.text(key: String) =
  (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
