package intellijandroid.client.session

import intellijandroid.client.connect.StoredText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A project's open editors: the files in tab order, the selected one, and each file's caret offset,
 * e.g. `carets = {"/Users/me/smoke/src/demo/Big.java": 3120}`.
 */
data class SavedEditors(
  val files: List<String>,
  val selected: String?,
  val carets: Map<String, Int> = emptyMap(),
)

/**
 * The editors open in each project, kept on this device so the next connection reopens them, as
 * JetBrains Client keeps its own `FileEditorManager` state (the backend leaves reopening to the
 * frontend while the registry key `editor.rd.reopen.editors.on.frontend` is true, the 263 default).
 * Stored as one JSON object in [storage], keyed by project, e.g. `{"ssh:me@mac:/Users/me/smoke":
 * {"files": ["/Users/me/smoke/src/demo/Main.java"], "selected":
 * "/Users/me/smoke/src/demo/Main.java"}}`.
 */
class OpenEditors(private val storage: StoredText) {
  fun read(project: String): SavedEditors? {
    val entry = all()[project]?.jsonObject ?: return null
    return SavedEditors(
      entry.getValue("files").jsonArray.map { it.jsonPrimitive.content },
      entry["selected"]?.jsonPrimitive?.contentOrNull,
      // Saved before carets were kept: none.
      entry["carets"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }.orEmpty(),
    )
  }

  fun save(project: String, editors: SavedEditors) {
    val entry = buildJsonObject {
      put("files", JsonArray(editors.files.map(::JsonPrimitive)))
      editors.selected?.let { put("selected", it) }
      put("carets", JsonObject(editors.carets.mapValues { JsonPrimitive(it.value) }))
    }
    storage.write(JsonObject(all() + (project to entry)).toString())
  }

  /** [project]'s editors in this store, for one [ClientSession]. */
  fun slot(project: String) =
    object : Slot {
      override fun read() = read(project)

      override fun save(editors: SavedEditors) = save(project, editors)
    }

  interface Slot {
    fun read(): SavedEditors?

    fun save(editors: SavedEditors)
  }

  private fun all(): JsonObject = Json.parseToJsonElement(storage.read() ?: "{}").jsonObject
}
