package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.connect.StoredText
import kotlinx.serialization.json.Json
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
 * A project's open editors as an older version kept them in JSON: the files in tab order, the
 * selected one, and each file's caret offset, e.g. `carets = {"/Users/me/smoke/src/demo/Big.java":
 * 3120}`. Only read now, to reopen them once (the tabs are in [JetBrainsWorkspace] since).
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
 * `editor.rd.reopen.editors.on.frontend` is true, the 263 default). The tabs are kept in
 * [workspace], the panes as one JSON object in [storage], keyed by connection, e.g.
 * `{"link:127.0.0.1:5993": {"panes": {"left": 0.28, "leftSplit": 0.6}}}`; entries an older version
 * wrote also hold `files`, `selected` and `carets` (see [SavedEditors]).
 */
class OpenEditors(private val storage: StoredText, private val workspace: JetBrainsWorkspace) {
  fun read(project: String): SavedEditors? {
    val entry = all()[project]?.jsonObject ?: return null
    return SavedEditors(
      entry.getValue("files").jsonArray.map { it.jsonPrimitive.content },
      entry["selected"]?.jsonPrimitive?.contentOrNull,
      // Saved before carets were kept: none.
      entry["carets"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }.orEmpty(),
    )
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

  /** [project]'s entry with [fields] put in, the rest kept. */
  private fun merge(project: String, fields: JsonObject) {
    val all = all()
    val kept = all[project]?.jsonObject.orEmpty()
    storage.write(JsonObject(all + (project to JsonObject(kept + fields))).toString())
  }

  /**
   * One [ClientSession]'s editors and panes. The tabs are kept by the backend's project id, as
   * JetBrains Client keeps its state (`ThinClientProjectUtil.createProjectDir(RdProjectId)`):
   * `23888a6f-a97e-4246-b9a9-20a835ed6667:3MDuM4gn3BvOPth1xHooNd9f6Q3`. One backend can open
   * different projects on the same port, and one project can be reached by different addresses. The
   * panes stay under the connection's key [connection] (`link:127.0.0.1:5993`), because the screen
   * reads them before the backend names the project.
   */
  fun slot(connection: String) =
    object : Slot {
      override fun readTabs(projectId: String) = workspace.read(projectId)

      override fun saveTabs(projectId: String, tabs: List<WorkspaceTab>) =
        workspace.save(projectId, tabs)

      override fun readSavedEditors(projectId: String): SavedEditors? =
        this@OpenEditors.read(PROJECT_PREFIX + projectId)

      override fun readPanes() = readPanes(connection)

      override fun savePanes(panes: Map<String, Float>) = savePanes(connection, panes)
    }

  /** Tabs by the backend's project id (`Solution.projectId`), panes by the connection. */
  interface Slot {
    /** The project's tabs in order; null when none were ever saved. */
    fun readTabs(projectId: String): List<WorkspaceTab>?

    fun saveTabs(projectId: String, tabs: List<WorkspaceTab>)

    /** What an older version saved, for a project with no tabs saved yet. */
    fun readSavedEditors(projectId: String): SavedEditors?

    fun readPanes(): Map<String, Float>

    fun savePanes(panes: Map<String, Float>)
  }

  private companion object {
    const val PROJECT_PREFIX = "project:"
  }

  private fun all(): JsonObject = Json.parseToJsonElement(storage.read() ?: "{}").jsonObject
}
