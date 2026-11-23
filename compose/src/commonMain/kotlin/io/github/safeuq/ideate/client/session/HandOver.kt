package io.github.safeuq.ideate.client.session

/**
 * What [handOver] shows, which fields have taken their value from the new session so far, and
 * whether all have.
 */
data class HandOverResult(val shown: SessionState, val switched: Set<String>, val done: Boolean)

/**
 * One panel of the screen: when [filled] says the new session (first argument) has sent it, [carry]
 * stops using the old one (second argument).
 */
private class PanelField(
  val name: String,
  val filled: (SessionState, SessionState) -> Boolean,
  val carry: (SessionState, SessionState) -> SessionState,
)

private val panelFields =
  listOf(
    PanelField("projectName", { new, _ -> new.projectName != null }) { old, new ->
      new.copy(projectName = old.projectName)
    },
    PanelField("tree", { new, old -> new.tree.shows(old.tree) }) { old, new ->
      new.copy(tree = old.tree)
    },
    PanelField("tabIcons", { new, _ -> new.tabIcons.isNotEmpty() }) { old, new ->
      new.copy(tabIcons = old.tabIcons)
    },
    PanelField("selectedEditor", { new, _ -> new.selectedEditor != null }) { old, new ->
      new.copy(selectedEditor = old.selectedEditor)
    },
    PanelField("consoles", { new, _ -> new.consoles.isNotEmpty() }) { old, new ->
      new.copy(consoles = old.consoles)
    },
    PanelField("buildTrees", { new, _ -> new.buildTrees.isNotEmpty() }) { old, new ->
      new.copy(buildTrees = old.buildTrees)
    },
    PanelField("scheme", { new, _ -> new.scheme != null }) { old, new ->
      new.copy(scheme = old.scheme)
    },
    PanelField("structure", { new, _ -> new.structure.nodes.isNotEmpty() }) { old, new ->
      new.copy(structure = old.structure)
    },
    PanelField("toolWindows", { new, _ -> new.toolWindows.isNotEmpty() }) { old, new ->
      new.copy(toolWindows = old.toolWindows)
    },
    PanelField("projectViews", { new, _ -> new.projectViews.options.isNotEmpty() }) { old, new ->
      new.copy(projectViews = old.projectViews)
    },
    PanelField("backendViews", { new, _ -> new.backendViews.isNotEmpty() }) { old, new ->
      new.copy(backendViews = old.backendViews)
    },
    PanelField("statusWidgets", { new, _ -> new.statusWidgets.isNotEmpty() }) { old, new ->
      new.copy(statusWidgets = old.statusWidgets)
    },
    PanelField("breadcrumbs", { new, _ -> new.breadcrumbs.isNotEmpty() }) { old, new ->
      new.copy(breadcrumbs = old.breadcrumbs)
    },
    PanelField("tasks", { new, _ -> new.tasks.isNotEmpty() }) { old, new ->
      new.copy(tasks = old.tasks)
    },
    PanelField("terminals", { new, _ -> new.terminals.isNotEmpty() }) { old, new ->
      new.copy(terminals = old.terminals)
    },
    PanelField("terminalsReady", { new, _ -> new.terminalsReady }) { old, new ->
      new.copy(terminalsReady = old.terminalsReady)
    },
    PanelField("searchAvailable", { new, _ -> new.searchAvailable }) { old, new ->
      new.copy(searchAvailable = old.searchAvailable)
    },
  )

internal val handOverFieldNames: Set<String>
  get() = panelFields.map { it.name }.toSet()

/**
 * What the screen shows while a new session catches up after a reconnect: each panel of [previous]
 * (the old session's last state) until [current] has its first value for it, then [current] for
 * good ([switched] remembers which, from the last result). Each open editor stays as it was, but
 * not editable, until [current]'s editor for that path is editable. Popups, menus, failures and the
 * like always come from [current].
 */
fun handOver(previous: SessionState, current: SessionState, switched: Set<String>): HandOverResult {
  val nowSwitched = switched.toMutableSet()
  var shown = current
  for (field in panelFields) {
    if (field.name in nowSwitched || field.filled(current, previous)) nowSwitched += field.name
    else shown = field.carry(previous, shown)
  }
  val currentEditors = current.editors.associateBy { it.path }
  val carried =
    previous.editors.map { old ->
      currentEditors[old.path]?.takeIf { it.editable } ?: old.copy(editable = false)
    }
  val carriedPaths = previous.editors.map { it.path }.toSet()
  val editorsDone = previous.editors.all { currentEditors[it.path]?.editable == true }
  shown = shown.copy(editors = carried + current.editors.filter { it.path !in carriedPaths })
  val done = editorsDone && nowSwitched.size == panelFields.size
  return HandOverResult(shown, nowSwitched, done)
}

/**
 * Whether this tree shows every row [old] showed (by file path), so it can replace it without rows
 * disappearing: the reconnected session opens the same folders again as their rows arrive.
 */
private fun ProjectTree.shows(old: ProjectTree): Boolean {
  if (nodes.isEmpty()) return false
  val visible = visibleRows().mapNotNull { (node, _) -> node.path }.toSet()
  return old.visibleRows().all { (node, _) -> node.path == null || node.path in visible }
}
