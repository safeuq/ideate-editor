package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.session.terminal.TerminalTabState
import io.github.safeuq.ideate.client.ui.MenuEntry
import kotlinx.serialization.Serializable

/** One piece of a tree node's label, e.g. the project name (bold) and its path (grey). */
@Serializable
data class TextFragment(
  val text: String,
  /**
   * IntelliJ `SimpleTextAttributes` style bits: 1 bold, 2 italic, 4 strikeout, 8 waved, 16
   * underline.
   */
  val style: Int,
  /** ARGB, or null for the tree's default foreground. */
  val foreground: Long?,
)

/** One row of the Project view, as `RdProjectViewItem` describes it. */
@Serializable
data class TreeNode(
  val id: Int,
  val parentId: Int?,
  val fragments: List<TextFragment>,
  /** Icon paths inside the icons jar, e.g. `expui/nodes/module.svg`; first is the main icon. */
  val iconPaths: List<String>,
  val hasChildren: Boolean,
  val isDirectory: Boolean,
  /** File path on the backend; null for virtual nodes such as "External Libraries". */
  val path: String?,
  val sortKey: Int,
  /**
   * `RdProjectViewItemDescriptor.fileStatus`, the git status that colours the name, e.g. `IGNORED`;
   * null or `NOT_CHANGED` for the default colour.
   */
  val fileStatus: String? = null,
  /**
   * ARGB row background from the node's scope (Settings | Appearance | File Colors), as the
   * backend's theme computed it, or null; [backgroundName] wins when the current theme has it.
   */
  val background: Long? = null,
  /** The scope's color name, e.g. `Orange` for `FileColor.Orange`, or null. */
  val backgroundName: String? = null,
  /** Gray text at the row's right edge, e.g. a Build step's `1 sec, 251 ms`; null: none. */
  val trailing: String? = null,
)

@Serializable
data class ProjectTree(
  val nodes: Map<Int, TreeNode> = emptyMap(),
  val expanded: Set<Int> = emptySet(),
  val selected: Int? = null,
) {
  /** Children in display order. */
  fun children(parentId: Int?): List<TreeNode> =
    nodes.values.filter { it.parentId == parentId }.sortedWith(compareBy({ it.sortKey }, { it.id }))

  /**
   * The visible rows, depth-first, with their depth (1 for top-level nodes, like IntelliJ's root
   * handles).
   */
  fun visibleRows(): List<Pair<TreeNode, Int>> {
    val rows = mutableListOf<Pair<TreeNode, Int>>()
    fun add(parentId: Int?, depth: Int) {
      for (node in children(parentId)) {
        rows += node to depth
        if (node.id in expanded) add(node.id, depth + 1)
      }
    }
    add(null, 1)
    return rows
  }
}

/**
 * The Show Usages popup for the editor of [path]: [title] is the target ("Method twice(int) of
 * demo.Caller"), [scope] the search scope ("Project and Libraries"); rows come in while the backend
 * searches, until [complete].
 */
data class UsagesPopup(
  val path: String,
  val title: String,
  val scope: String,
  val rows: List<UsageRow>,
  val complete: Boolean,
  val selected: Int = 0,
)

/**
 * One usage: [text] is its line, trimmed; [matchStart] until [matchEnd] the usage in it; [line] is
 * 1-based, 0 when the backend didn't send it.
 */
data class UsageRow(
  val index: Int,
  val fileName: String,
  val line: Int,
  val text: String,
  val matchStart: Int,
  val matchEnd: Int,
)

/** A highlighted range. [style] is already resolved against the current scheme. */
@Serializable
data class Highlight(
  val id: Long,
  val start: Int,
  val end: Int,
  val layer: Int,
  val style: TextStyle,
  /** The problem this range marks (error, warning, …); null for syntax colors and the like. */
  val info: HighlightInfo? = null,
  /**
   * `RangeHighlighter.getErrorStripeMarkColor`, here always the text attributes' stripe color (the
   * backend sends no color of the highlighter's own); null: no mark on the scrollbar.
   */
  val errorStripeColor: Long? = null,
  /** `isThinErrorStripeMark`: a narrow mark at the bar's left edge instead of a wide one. */
  val thinErrorStripeMark: Boolean = false,
)

/**
 * IntelliJ's `HighlightInfo` as the backend sends it with a highlighter (`HighlightInfoModel`).
 * [severity] is `HighlightSeverity.myVal`: ERROR 400, WARNING 300, WEAK WARNING 200.
 */
@Serializable
data class HighlightInfo(
  val severityId: String,
  val severity: Int,
  val description: String?,
  val toolTip: String?,
  val toolId: String?,
  val inspectionId: String?,
  val hasQuickFixes: Boolean,
  val suppressOnErrorStripe: Boolean,
)

/** IntelliJ's `HighlightSeverity` with its icon, as the backend lists them once per connection. */
@Serializable
data class HighlightSeverity(
  val id: String,
  val value: Int,
  val iconPaths: List<String>,
  val displayName: String,
  val countMessageTemplate: String?,
)

/**
 * IntelliJ's `IndentGuideDescriptor`: a block opened on [startLine] whose lines are indented past
 * [indentLevel] (a column). The guide is drawn at that column on the lines after [startLine] and
 * before [endLine] (exclusive; equal to the line count when the block runs to the end).
 */
@Serializable data class IndentGuide(val indentLevel: Int, val startLine: Int, val endLine: Int)

/**
 * A gutter icon (run marker, override marker, …) on the line of [offset]; [iconPaths] are paths in
 * the icons jar.
 */
@Serializable data class GutterIcon(val offset: Int, val iconPaths: List<String>)

/** [windowId] is a backend tool window id, e.g. `Run`; [show] false asks to hide it. */
data class ShowRequest(val windowId: String, val serial: Int, val show: Boolean = true)

/** A console's text and its colors, e.g. the Run window's `Main` output. */
data class ConsoleText(val text: String, val highlights: List<Highlight>)

/** What an editor shows, as IntelliJ's `EditorKind`: a file's text, or a console's output. */
enum class EditorKind {
  MAIN_EDITOR,
  CONSOLE,
}

@Serializable
data class EditorFile(
  val path: String,
  val text: String,
  /** Sorted by layer, lowest first, so later ones paint over earlier ones. */
  val highlights: List<Highlight>,
  val indentGuides: List<IndentGuide>,
  val gutterIcons: List<GutterIcon> = emptyList(),
  /** Scopes for sticky lines, sorted by start. */
  val stickyRanges: List<StickyRange> = emptyList(),
  val caret: Int = 0,
  val selectionStart: Int = 0,
  val selectionEnd: Int = 0,
  /**
   * False until the backend has sent the document's version and the editor's caret, or while
   * catching up with it.
   */
  val editable: Boolean = false,
  /**
   * Bumped when the caret was put back where it was last time, or the backend moved it by itself
   * (navigation); the view then centres the caret's line, like IntelliJ's
   * `OpenFileDescriptor.navigateIn` (`scrollToCaret(ScrollType.CENTER)`).
   */
  val caretToShow: Int = 0,
  /**
   * For the [caretToShow] of a caret put back: dp from the view's top to the caret line's top, as
   * saved (`relative-caret-position`). Null for navigation, which centres the line.
   */
  val caretFromTop: Int? = null,
  /** Undo may do something (true while the backend's undo stack is unknown). */
  val canUndo: Boolean = false,
  /** Redo has something: right after an undo, until the next edit. */
  val canRedo: Boolean = false,
) {
  val name: String
    get() = path.substringAfterLast('/')
}

/**
 * A scope the backend marks with a `STICKY_LINE_MARKER` highlighter (`StickyLinesModelImpl`), in
 * document offsets: [start] is the scope's name (e.g. `Main` in `public class Main {`), where a tap
 * on its pinned line puts the caret; [end] is its end.
 */
@Serializable data class StickyRange(val start: Int, val end: Int)

/** Editor keys that become backend editor actions. */
enum class EditorKey {
  ENTER,
  BACKSPACE,
  DELETE,
}

/** Keys the completion popup takes while it is open. */
enum class LookupKey {
  UP,
  DOWN,
  ACCEPT,
  CANCEL,
}

/**
 * A project open on the backend, e.g. `OpenProject("beta", "/work/beta")`. [hostPath] is its folder
 * there (`RdCodeWithMeProject.hostProjectBasePath`); [name] is the folder's name.
 */
data class OpenProject(val name: String, val hostPath: String)

data class SessionState(
  val log: List<String> = emptyList(),
  val failure: String? = null,
  /**
   * Set when the connection failed or dropped; [failure] also holds errors the session carries on
   * after.
   */
  val connectionLost: String? = null,
  val projectName: String? = null,
  /** The backend's project widget button; null until it is loaded, then [projectName] gives way. */
  val projectWidget: ProjectWidgetLook? = null,
  /** Every project open on the backend, in the order the backend listed them. */
  val openProjects: List<OpenProject> = emptyList(),
  /** The [OpenProject.hostPath] of the project this session shows; null until one is bound. */
  val shownProject: String? = null,
  /** True while the shown project is the welcome project (IntelliJ IDEA Home). */
  val welcomeProject: Boolean = false,
  val tree: ProjectTree = ProjectTree(),
  val editors: List<EditorFile> = emptyList(),
  /** Each open file's tab icon from the backend, e.g. `/…/Main.java` to `nodes/class.svg`. */
  val tabIcons: Map<String, String> = emptyMap(),
  val selectedEditor: String? = null,
  /**
   * The backend's consoles by `KernelAwareTextControlId.kernelEditorId`, e.g.
   * `g9ojv29tmj8vgr2tv7gf`.
   */
  val consoles: Map<String, ConsoleText> = emptyMap(),
  /** The Build trees shown, by the backend's model id (`BeSplitComponentControl.modelUid`). */
  val buildTrees: Map<Int, ProjectTree> = emptyMap(),
  /**
   * Text the backend put on the clipboard (`ClipboardModel.content` set by the backend, e.g. its
   * Copy Reference); the UI copies it to the system clipboard. [ClipboardText.serial] tells two
   * copies of the same text apart.
   */
  val backendClipboard: ClipboardText? = null,
  val scheme: EditorScheme? = null,
  /** Every severity the backend knows, highest [HighlightSeverity.value] first. */
  val severities: List<HighlightSeverity> = emptyList(),
  /** The completion popup, if one is open. */
  val lookup: Lookup? = null,
  /** IntelliJ's Show Usages popup, if one is open (⌘B or ⌘-click on a declaration). */
  val usages: UsagesPopup? = null,
  /** The open Search Everywhere popup. */
  val search: SearchPopup? = null,
  /** Search Everywhere can be opened: the project has a kernel id (SearchEverywhere.kt). */
  val searchAvailable: Boolean = false,
  /** The Structure tool window's tree, for the file the backend sees as selected. */
  val structure: StructureTree = StructureTree(),
  /** The run widget's buttons after the selector, as the backend says they are now. */
  val runButtons: List<RunToolbarButton> = emptyList(),
  /** The run widget's selector: the selected configuration, or "Current File". */
  val runSelector: RunSelector = RunSelector(),
  /** The open context menu built by the backend (ActionMenus). */
  val contextMenu: BackendMenu? = null,
  /** The backend's open dialogs over Lux (LuxDialogs), oldest first. */
  val luxDialogs: List<LuxDialog> = emptyList(),
  /** The backend's open BeControl popups (BackendPopups), e.g. New ▸ File's name popup. */
  val backendPopups: List<BackendPopup> = emptyList(),
  /** The tool windows the backend has, in the order it registered them. */
  val toolWindows: List<BackendToolWindow> = emptyList(),
  /** The Project tool window's views (Project, Packages, scopes) and the current one. */
  val projectViews: ProjectViews = ProjectViews(),
  /**
   * The backend asked for a tool window to show (`BeToolWindow.state` went Active, e.g. Run when a
   * run starts); `serial` is new each time so the same window can be asked again.
   */
  val showRequest: ShowRequest? = null,
  /** What the backend's tool windows on screen show, by id, e.g. `Gradle`. */
  val backendViews: Map<String, BackendToolWindowView> = emptyMap(),
  /**
   * The backend's status bar widgets in IntelliJ's order, the visible ones (docs/status-bar.md).
   */
  val statusWidgets: List<StatusWidget> = emptyList(),
  /** The status bar's breadcrumbs to the caret, e.g. `smoke › src › demo › Main › main`. */
  val breadcrumbs: List<Crumb> = emptyList(),
  /** A breadcrumb's open list: the crumb and its children; null when none is open. */
  val crumbMenu: CrumbMenu? = null,
  /** The backend's running tasks, newest last. */
  val tasks: List<BackgroundTask> = emptyList(),
  /** The Terminal tool window's tabs, in the order they opened. */
  val terminals: List<TerminalTabState> = emptyList(),
  /** A terminal tab can open: the project is bound (its terminal model exists). */
  val terminalsReady: Boolean = false,
  /**
   * The backend's project has loaded and the editors reopened on connecting have been opened or
   * found gone (not that their text has arrived). A reconnect's hand-over ends on it.
   */
  val loaded: Boolean = false,
)

/** Context menu number [serial]; [entries] null until the backend has answered ("Loading…"). */
data class BackendMenu(val serial: Int, val entries: List<MenuEntry>?)

/**
 * The structure of [path] as the backend sends it (`CodeStructureTreeModel.treeNodes`,
 * docs/structure-protocol.md): [nodes] in pre-order, `nodes[0]` the root; each node's
 * [StructureNode.children] are indexes into [nodes], not ids.
 */
data class StructureTree(val path: String? = null, val nodes: List<StructureNode> = emptyList())

data class StructureNode(
  /** The backend's id; new on every rebuild. */
  val id: Int,
  /** E.g. `hello(): void`, `name: String`. */
  val name: String,
  val iconPaths: List<String>,
  /** Start of the element in the document; -1 when it isn't a PSI element (the file root). */
  val offset: Int,
  val children: List<Int>,
)

/**
 * IntelliJ's completion popup ("lookup") for the editor of [path]. [prefix] is the part of the
 * identifier typed before the caret, which the items replace; the popup sits under its first
 * character.
 */
data class Lookup(
  val path: String,
  val items: List<LookupItem>,
  val selected: Int,
  val prefix: String,
  /**
   * False for an autopopup until the user moves the selection: the selected row is then grey, not
   * blue.
   */
  val focused: Boolean,
  /** The tip in the popup's footer ("Press ^. to choose the selected (or first) suggestion…"). */
  val advertisement: String?,
)

/**
 * One row: icon, item text (the typed prefix highlighted), tail in grey ("(String s)"), type text
 * on the right.
 */
data class LookupItem(
  val id: String,
  val lookupString: String,
  val itemText: String,
  val tail: String,
  val typeText: String,
  val iconPath: String?,
  val bold: Boolean,
  val strikeout: Boolean,
  /**
   * A group shown after the ungrouped items under its own header, e.g. "Commands"; null for
   * ordinary items.
   */
  val group: String? = null,
  val groupOrder: Int = 0,
)

data class ClipboardText(val text: String, val serial: Int)

/**
 * The list a breadcrumb opens, under crumb [anchorNodeId]: [items] are the children of the crumb or
 * of the folder chosen in it last, e.g. the files of `src`.
 */
data class CrumbMenu(val anchorNodeId: Long, val items: List<Crumb>)
