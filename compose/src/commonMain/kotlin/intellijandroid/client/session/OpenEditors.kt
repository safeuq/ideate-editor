package intellijandroid.client.session

import intellijandroid.client.connect.StoredText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
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
 * The editors open in each project, and the sizes of its tool window panes, kept on this device so
 * the next connection reopens them, as JetBrains Client keeps its own `FileEditorManager` state
 * (the backend leaves reopening to the frontend while the registry key
 * `editor.rd.reopen.editors.on.frontend` is true, the 263 default). Stored as one JSON object in
 * [storage], keyed by project, e.g. `{"ssh:me@mac:/Users/me/smoke": {"files":
 * ["/Users/me/smoke/src/demo/Main.java"], "selected": "/Users/me/smoke/src/demo/Main.java",
 * "panes": {"left": 0.28, "leftSplit": 0.6}}}`. Saving the editors keeps the panes and the other
 * way round.
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
    // "selected" is left out when nothing is selected, so it goes before the merge.
    merge(project, entry, drop = "selected")
  }

  /** [project]'s pane sizes by name, e.g. `{"left": 0.28}`; empty when none were saved. */
  fun readPanes(project: String): Map<String, Float> =
    all()[project]
      ?.jsonObject
      ?.get("panes")
      ?.jsonObject
      ?.mapValues {
        it.value.jsonPrimitive.float
      }
      .orEmpty()

  fun savePanes(project: String, panes: Map<String, Float>) =
    merge(
      project,
      buildJsonObject { put("panes", JsonObject(panes.mapValues { JsonPrimitive(it.value) })) },
    )

  /** [project]'s entry with [fields] put in, the rest kept ([drop] removed first). */
  private fun merge(project: String, fields: JsonObject, drop: String? = null) {
    val all = all()
    val kept = all[project]?.jsonObject.orEmpty() - setOfNotNull(drop)
    storage.write(JsonObject(all + (project to JsonObject(kept + fields))).toString())
  }

  /**
   * One [ClientSession]'s editors and panes. The editors are kept by the backend's project id, as
   * JetBrains Client keeps its state (`ThinClientProjectUtil.createProjectDir(RdProjectId)`):
   * `project:23888a6f-a97e-4246-b9a9-20a835ed6667:3MDuM4gn3BvOPth1xHooNd9f6Q3`. One backend can
   * open different projects on the same port, and one project can be reached by different
   * addresses. The panes stay under the connection's key [connection] (`link:127.0.0.1:5993`),
   * because the screen reads them before the backend names the project.
   */
  fun slot(connection: String) =
    object : Slot {
      override fun read(projectId: String): SavedEditors? =
        this@OpenEditors.read(PROJECT_PREFIX + projectId)

      override fun save(projectId: String, editors: SavedEditors) =
        this@OpenEditors.save(PROJECT_PREFIX + projectId, editors)

      override fun readPanes() = readPanes(connection)

      override fun savePanes(panes: Map<String, Float>) = savePanes(connection, panes)
    }

  /** Editors by the backend's project id (`Solution.projectId`), panes by the connection. */
  interface Slot {
    fun read(projectId: String): SavedEditors?

    fun save(projectId: String, editors: SavedEditors)

    fun readPanes(): Map<String, Float>

    fun savePanes(panes: Map<String, Float>)
  }

  private companion object {
    const val PROJECT_PREFIX = "project:"
  }

  private fun all(): JsonObject = Json.parseToJsonElement(storage.read() ?: "{}").jsonObject
}
