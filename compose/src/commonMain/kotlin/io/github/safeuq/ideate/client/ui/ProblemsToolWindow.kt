package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.HighlightSeverity
import io.github.safeuq.ideate.client.session.HighlightingProblem
import io.github.safeuq.ideate.client.session.ProblemsView
import io.github.safeuq.ideate.client.session.ProjectProblem
import io.github.safeuq.ideate.client.session.ProjectTree
import io.github.safeuq.ideate.client.session.SessionState
import io.github.safeuq.ideate.client.session.TextFragment
import io.github.safeuq.ideate.client.session.TreeNode
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/** The Problems window's tabs, with ProblemsViewBundle's titles. */
enum class ProblemsTab(val title: String) {
  FILE("File"),
  PROJECT_ERRORS("Project Errors"),
}

/**
 * The Problems tool window's File tab, IntelliJ's `HighlightingPanel`: the selected file as the
 * root row and one row per problem of its highlighters, drawn with the Project tree's rows.
 * JetBrains Client builds this on its own side too (`problems.view.split.enabled`). A tap selects a
 * problem; a double tap moves the caret to it and calls [onNavigated]. Left out: the Project Errors
 * tab, the toolbar, quick fixes and "Analyzing…".
 */
@Composable
fun ProblemsToolWindow(
  state: SessionState,
  session: ClientSession,
  tab: ProblemsTab,
  onNavigated: () -> Unit,
  modifier: Modifier,
  scroll: SidewaysScroll = remember { SidewaysScroll() },
) {
  if (tab == ProblemsTab.PROJECT_ERRORS) {
    ProjectErrorsPanel(state, session, onNavigated, modifier, scroll)
    return
  }
  val file = state.editors.firstOrNull { it.path == state.selectedEditor }
  val problems =
    remember(file?.text, file?.highlights) {
      if (file == null) emptyList()
      else ProblemsView.highlightingProblems(file.text, file.highlights)
    }
  if (file == null || problems.isEmpty()) {
    Box(modifier.padding(12.dp)) {
      BasicText(
        // ProblemsViewBundle: problems.view.highlighting.no.selected.file and
        // problems.view.highlighting.problems.not.found.
        if (file == null) "Open file in editor to see problems"
        else "No problems in ${file.path.substringAfterLast('/')}",
        style = LocalClientFonts.current.label(Islands.textSecondary),
      )
    }
    return
  }
  var selected by remember(file.path) { mutableStateOf<Long?>(null) }
  var collapsed by remember(file.path) { mutableStateOf(false) }
  val gray = Islands.textSecondary.toArgb().toLong() and 0xFFFFFFFFL
  val iconPath = state.tabIcons[file.path]
  val tree =
    remember(file.path, problems, selected, collapsed, state.severities, gray, iconPath) {
      problemsTree(file.path, iconPath, problems, state.severities, gray, selected, collapsed)
    }
  fun select(node: TreeNode) {
    if (node.id != FILE_NODE) selected = problems[node.sortKey].highlightId
  }
  ProjectTreeView(
    tree = tree,
    onToggle = { collapsed = !collapsed },
    onSelect = ::select,
    // Double click or Enter goes to the problem; a single click only selects, as in IntelliJ
    // (`ProblemsViewState.autoscrollToSource` is false by default).
    onOpen = { node ->
      if (node.id != FILE_NODE) {
        select(node)
        session.moveCaret(problems[node.sortKey].offset)
        onNavigated()
      }
    },
    modifier = modifier,
    scroll = scroll,
  )
}

/**
 * The Project Errors tab (`FrontendProblemsViewProjectErrorsRoot`): the backend's project problems,
 * grouped under a row per file; problems without a file come first. A double tap on a problem opens
 * its file at the problem's line and column; on a file row it opens the file.
 */
@Composable
private fun ProjectErrorsPanel(
  state: SessionState,
  session: ClientSession,
  onNavigated: () -> Unit,
  modifier: Modifier,
  scroll: SidewaysScroll,
) {
  val problems = state.projectErrors
  if (problems.isEmpty()) {
    Box(modifier.padding(12.dp)) {
      // ProblemsViewBundle: problems.view.project.empty.
      BasicText(
        "No errors found by the IDE",
        style = LocalClientFonts.current.label(Islands.textSecondary),
      )
    }
    return
  }
  var selected by remember { mutableStateOf<String?>(null) }
  var collapsed by remember { mutableStateOf(emptySet<String>()) }
  val gray = Islands.textSecondary.toArgb().toLong() and 0xFFFFFFFFL
  val rows =
    remember(problems, state.tabIcons, gray) { projectErrorRows(problems, state.tabIcons, gray) }
  val tree =
    remember(rows, selected, collapsed) {
      ProjectTree(
        rows.associate { it.node.id to it.node },
        rows.filter { it.problem == null && it.filePath !in collapsed }.map { it.node.id }.toSet(),
        rows.firstOrNull { it.problem != null && it.problem.id == selected }?.node?.id,
      )
    }
  val byId = remember(rows) { rows.associateBy { it.node.id } }
  ProjectTreeView(
    tree = tree,
    onToggle = { node ->
      byId[node.id]?.filePath?.let { path ->
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
      }
    },
    onSelect = { node -> byId[node.id]?.problem?.let { selected = it.id } },
    onOpen = { node ->
      val row = byId[node.id] ?: return@ProjectTreeView
      row.problem?.let { selected = it.id }
      row.filePath?.let { path ->
        val problem = row.problem
        if (problem == null) session.open(path)
        else session.open(path, problem.line, problem.column)
        onNavigated()
      }
    },
    modifier = modifier,
    scroll = scroll,
  )
}

/** A tree row with what it stands for: a file ([problem] null) or one problem. */
internal class ProjectErrorRow(
  val node: TreeNode,
  val filePath: String?,
  val problem: ProjectProblem?,
)

/** Problems without a file first, then each file with its problems by line and column. */
internal fun projectErrorRows(
  problems: List<ProjectProblem>,
  fileIcons: Map<String, String>,
  gray: Long,
): List<ProjectErrorRow> {
  val rows = mutableListOf<ProjectErrorRow>()
  fun problemNode(problem: ProjectProblem, parent: Int?) =
    TreeNode(
      id = rows.size,
      parentId = parent,
      fragments =
        listOfNotNull(
          TextFragment(problem.text, 0, null),
          problem.filePath?.let { TextFragment(" :${problem.line + 1}", 0, gray) },
        ),
      iconPaths = listOfNotNull(problem.iconPath),
      hasChildren = false,
      isDirectory = false,
      path = null,
      sortKey = rows.size,
    )
  problems
    .filter { it.filePath == null }
    .forEach { rows += ProjectErrorRow(problemNode(it, null), null, it) }
  for ((path, inFile) in problems.filter { it.filePath != null }.groupBy { it.filePath!! }) {
    val count = if (inFile.size == 1) "1 problem" else "${inFile.size} problems"
    val fileId = rows.size
    rows +=
      ProjectErrorRow(
        TreeNode(
          id = fileId,
          parentId = null,
          fragments =
            listOf(
              TextFragment(path.substringAfterLast('/'), 0, null),
              TextFragment("  " + path.substringBeforeLast('/', ""), 0, gray),
              TextFragment("  $count", 0, gray),
            ),
          iconPaths = listOfNotNull(fileIcons[path]),
          hasChildren = true,
          isDirectory = true,
          path = null,
          sortKey = fileId,
        ),
        path,
        null,
      )
    inFile.sortedWith(compareBy({ it.line }, { it.column })).forEach {
      rows += ProjectErrorRow(problemNode(it, fileId), path, it)
    }
  }
  return rows
}

private const val FILE_NODE = 0

/**
 * `FileNode` then its `ProblemNode`s. Example rows: `Main.java /work/demo/src 2 problems`, then
 * `Cannot resolve symbol 'Strin' :5`. A problem's node id is its index plus one; `sortKey` is the
 * index.
 */
internal fun problemsTree(
  path: String,
  iconPath: String?,
  problems: List<HighlightingProblem>,
  severities: List<HighlightSeverity>,
  gray: Long,
  selected: Long?,
  collapsed: Boolean,
): ProjectTree {
  val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
  val fileNode =
    TreeNode(
      id = FILE_NODE,
      parentId = null,
      fragments =
        listOf(
          TextFragment(path.substringAfterLast('/'), 0, null),
          // ponytail: the folder as the backend names it; IntelliJ writes the home folder as ~,
          // which needs the backend's home path.
          TextFragment("  " + path.substringBeforeLast('/', ""), 0, gray),
          TextFragment("  $count", 0, gray),
        ),
      iconPaths = listOfNotNull(iconPath),
      hasChildren = true,
      isDirectory = true,
      path = null,
      sortKey = 0,
    )
  val problemNodes = problems.mapIndexed { index, problem ->
    TreeNode(
      id = index + 1,
      parentId = FILE_NODE,
      fragments =
        listOf(
          TextFragment(problem.text, 0, null),
          TextFragment(" :${problem.line + 1}", 0, gray),
        ),
      iconPaths = severityIconPaths(problem, severities),
      hasChildren = false,
      isDirectory = false,
      path = null,
      sortKey = index,
    )
  }
  val nodes = (listOf(fileNode) + problemNodes).associateBy { it.id }
  val selectedNode = problems.indexOfFirst { it.highlightId == selected }.takeIf { it >= 0 }
  return ProjectTree(
    nodes,
    if (collapsed) emptySet() else setOf(FILE_NODE),
    selectedNode?.let { it + 1 },
  )
}

/** The backend's icon for the severity; before its list arrives, IntelliJ's two usual ones. */
private fun severityIconPaths(
  problem: HighlightingProblem,
  severities: List<HighlightSeverity>,
): List<String> =
  severities.firstOrNull { it.id == problem.severityId }?.iconPaths?.takeIf { it.isNotEmpty() }
    ?: listOf(
      if (problem.severity >= 400) "expui/status/error.svg" else "expui/status/warning.svg",
    )
