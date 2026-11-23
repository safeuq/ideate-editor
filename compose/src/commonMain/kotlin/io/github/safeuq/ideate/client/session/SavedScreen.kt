package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.connect.ConnectionKind
import io.github.safeuq.ideate.client.session.terminal.TerminalTabState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the screen needs to draw itself again after Android ended the app's process: the parts of a
 * [SessionState] the user sees at once. Written when the app goes to the background; the next start
 * shows it (not interactive) while the connection comes back (`ConnectController.restoreSaved`).
 * [openWindows] are the open tool windows by place, e.g. `{"LEFT_TOP": "Project", "BOTTOM_LEFT":
 * "Terminal"}`.
 */
@Serializable
class SavedScreen(
  val projectName: String?,
  val tree: ProjectTree,
  val editors: List<EditorFile>,
  val tabIcons: Map<String, String>,
  val selectedEditor: String?,
  val scheme: EditorScheme?,
  val toolWindows: List<BackendToolWindow>,
  val terminals: List<TerminalTabState>,
  val openWindows: Map<String, String>,
) {
  fun toState() =
    SessionState(
      projectName = projectName,
      tree = tree,
      editors = editors,
      tabIcons = tabIcons,
      selectedEditor = selectedEditor,
      scheme = scheme,
      toolWindows = toolWindows,
      terminals = terminals,
    )

  companion object {
    /** The terminals keep only the lines on screen, not the 5000 lines of history. */
    fun of(state: SessionState, openWindows: Map<String, String>) =
      SavedScreen(
        state.projectName,
        state.tree,
        state.editors,
        state.tabIcons,
        state.selectedEditor,
        state.scheme,
        state.toolWindows,
        state.terminals.map { tab ->
          tab.copy(screen = tab.screen.copy(lines = tab.screen.lines.takeLast(tab.screen.rows)))
        },
        openWindows,
      )
  }
}

/**
 * A [SavedScreen] with what reconnects it: the connection as [text] (the join link last used, e.g.
 * `tcp://192.168.0.105:5990#jb=...`), the [identity] the backend accepted, and the `editorsKey`
 * that names the project (e.g. `link:192.168.0.105:5990`).
 */
@Serializable
class SavedSession(
  val kind: ConnectionKind,
  val text: String,
  val project: String?,
  val openFile: String?,
  val identity: String,
  val editorsKey: String,
  val screen: SavedScreen,
) {
  fun encode(): String = json.encodeToString(serializer(), this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null for nothing saved, or text this version can't read (saved by an older version). */
    fun decode(text: String?): SavedSession? =
      text
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
  }
}
