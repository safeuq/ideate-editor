package intellijandroid.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.codeWithMe.model.CwmRunConfigurationExecuteArgs
import com.jetbrains.codeWithMe.model.RdFileIconParams
import com.jetbrains.codeWithMe.model.RdFindFileParams
import com.jetbrains.codeWithMe.model.RdProjectViewAsyncPane
import com.jetbrains.codeWithMe.model.RdProjectViewItem
import com.jetbrains.codeWithMe.model.RdProjectViewNavigation
import com.jetbrains.codeWithMe.model.SyntaxHighlighterModel
import com.jetbrains.codeWithMe.model.VfsModel
import com.jetbrains.codeWithMe.model.codeWithMeRunConfigurationsModel
import com.jetbrains.codeWithMe.model.editorTabsModel
import com.jetbrains.codeWithMe.model.navigationBarModel
import com.jetbrains.codeWithMe.model.projectViewModel
import com.jetbrains.codeWithMe.model.vfsModel
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.DefaultRegistryModel
import com.jetbrains.ide.model.highlighterRegistration.highlighterRegistrationHostModel
import com.jetbrains.rd.framework.IMarshaller
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.MarshallersProvider
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.SerializationCtx
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.framework.UnsafeBuffer
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.BadgeIconModel
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.ide.model.BrowseMode
import com.jetbrains.rd.ide.model.ClipboardContent
import com.jetbrains.rd.ide.model.ClipboardModel
import com.jetbrains.rd.ide.model.ColorizedIconModel
import com.jetbrains.rd.ide.model.CompositionIconModel
import com.jetbrains.rd.ide.model.CropIconModel
import com.jetbrains.rd.ide.model.CustomImageIconModel
import com.jetbrains.rd.ide.model.DeferredIconModel
import com.jetbrains.rd.ide.model.EmptyIconModel
import com.jetbrains.rd.ide.model.FailedRequestComposite
import com.jetbrains.rd.ide.model.FileEditorManagerEventModel
import com.jetbrains.rd.ide.model.GutterMarkHighlighterModel
import com.jetbrains.rd.ide.model.HighlighterElement
import com.jetbrains.rd.ide.model.HyperlinkHighlightingArgs
import com.jetbrains.rd.ide.model.HyperlinkHighlightingResult
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.IconWithOverlayModel
import com.jetbrains.rd.ide.model.ImageIconModel
import com.jetbrains.rd.ide.model.IndicatorIconModel
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.MarkupDocumentExtension
import com.jetbrains.rd.ide.model.MarkupTextControlExtension
import com.jetbrains.rd.ide.model.OffsetIconModel
import com.jetbrains.rd.ide.model.OriginKind
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.ProjectViewTimestampModel
import com.jetbrains.rd.ide.model.RdCodeWithMeProject
import com.jetbrains.rd.ide.model.RdDocumentChangeBase
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileEditorId
import com.jetbrains.rd.ide.model.RdFileId
import com.jetbrains.rd.ide.model.RdFileIdNew
import com.jetbrains.rd.ide.model.RdMarkupKey
import com.jetbrains.rd.ide.model.RdMarkupModel
import com.jetbrains.rd.ide.model.RdMergeableUsage
import com.jetbrains.rd.ide.model.RdOpenSolution
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdRequestHolder
import com.jetbrains.rd.ide.model.RdRequests
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdRetryResponse
import com.jetbrains.rd.ide.model.RdRetryTimestamp
import com.jetbrains.rd.ide.model.RdShowUsagesSession
import com.jetbrains.rd.ide.model.RdSuccessResponse
import com.jetbrains.rd.ide.model.RdUsageBase
import com.jetbrains.rd.ide.model.RdUsageNavigationData
import com.jetbrains.rd.ide.model.RectangleModel
import com.jetbrains.rd.ide.model.RequestCompositeQuery
import com.jetbrains.rd.ide.model.RequestResponseModel
import com.jetbrains.rd.ide.model.RotatedIconModel
import com.jetbrains.rd.ide.model.RowIconModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.SuccessfulRequestComposite
import com.jetbrains.rd.ide.model.TextControlId
import com.jetbrains.rd.ide.model.TextControlLocation
import com.jetbrains.rd.ide.model.VisibleRange
import com.jetbrains.rd.ide.model.asyncActionsModel
import com.jetbrains.rd.ide.model.clipboardModel
import com.jetbrains.rd.ide.model.codeStructureTreeModel
import com.jetbrains.rd.ide.model.documentsOperationModel
import com.jetbrains.rd.ide.model.fileEditorsModel
import com.jetbrains.rd.ide.model.findUsagesHost
import com.jetbrains.rd.ide.model.fleetRpcModel
import com.jetbrains.rd.ide.model.hyperlinkHighlightingModel
import com.jetbrains.rd.ide.model.luxModel
import com.jetbrains.rd.ide.model.requestResponseModel
import com.jetbrains.rd.ide.model.solutionModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.isAlive
import com.jetbrains.rd.util.reactive.AddRemove
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.reactive.hasTrueValue
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.Connection
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.connection.SplitWire
import intellijandroid.client.kernel.SearchKernel
import intellijandroid.client.session.buildtree.BuildTrees
import intellijandroid.client.session.console.ConsoleDocuments
import intellijandroid.client.session.terminal.TerminalSessions
import intellijandroid.client.ui.IconJar
import intellijandroid.client.ui.MenuEntry
import intellijandroid.client.ui.MenuItem
import intellijandroid.client.ui.SubMenu
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Connects to a backend and keeps the Project tree and the open editors in a [StateFlow], creating
 * only the rd models those need (docs/split-protocol.md, "Our own client"). Plain JVM + rd: no UI
 * types here, so the same class can back an Android screen. All rd model access happens on the rd
 * scheduler thread; UI actions are queued onto it.
 */
class ClientSession(
  private val link: JoinLink,
  private val initialFile: String?,
  /** A stored identity to use [link]'s join token again; null makes a new one. */
  private val identity: ClientCertificate? = null,
  /** Where this project's open editors are kept between connections; null keeps none. */
  private val savedEditors: OpenEditors.Slot? = null,
  /**
   * The project to show, e.g. `/data/data/com.termux/files/home/IdeaProjects/commons`. One backend
   * serves all its projects over one connection, each a solution; the JetBrains Client opens a
   * window per solution, IDEate shows this one. Null: the first solution.
   */
  private val project: String? = null,
  /**
   * Called on the connect thread once the backend has accepted the connection, with the identity it
   * accepted.
   */
  private val onAccepted: (ClientCertificate) -> Unit = {},
) {
  private val lifetimeDefinition = LifetimeDefinition()
  private val lifetime: Lifetime = lifetimeDefinition
  private val mutableState = MutableStateFlow(SessionState())
  val state: StateFlow<SessionState> = mutableState

  @Volatile private var scheduler: IScheduler? = null

  // Touched only on the rd scheduler thread.
  private val schemes = mutableMapOf<String, ColorsSchemeModel>()
  private val documentPaths = mutableMapOf<Int, String>()
  private val documentTexts = mutableMapOf<RdDocumentId, String>()
  private val documentHighlights = mutableMapOf<RdDocumentId, MutableMap<String, RawHighlight>>()
  private val loadedChildren = mutableSetOf<Int>()

  /**
   * Rows under Expand Selected: a row arriving under one opens too. Cleared when the user opens or
   * closes a row, so later rows (a new file) don't open by themselves.
   */
  private val expandingAll = mutableSetOf<Int>()
  private val unhandledChangeKinds = mutableSetOf<String>()
  /** Documents whose markup changed since the last publish; see [publishMarkupLater]. */
  private val markupChanged = mutableSetOf<RdDocumentId>()
  private val markupTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "markup-publish").apply { isDaemon = true }
  }
  private var projectId: RdProjectId? = null
  private var solution: Solution? = null
  /** The shown solution's lifetime: ends when the backend removes it. */
  private var shownSolutionLifetime: Lifetime? = null
  private var vfs: VfsModel? = null
  private var pane: RdProjectViewAsyncPane? = null
  /** For the Lux components that panes and popups name by id. */
  private var luxProtocol: IProtocol? = null
  private val projectViews =
    ProjectViewPanes(
      { icon -> iconPaths(icon).firstOrNull() },
      { id -> luxProtocol?.luxModel?.components?.get(id) },
      ::log,
    ) { views ->
      mutableState.update { it.copy(projectViews = views) }
    }
  private val toolWindows =
    BackendToolWindows({ icon -> iconPaths(icon).firstOrNull() }) { windows ->
      mutableState.update { it.copy(toolWindows = windows) }
    }
  /**
   * Toolbars being loaded for backend tool windows: the menu serial and the window, e.g. Gradle.
   */
  private val toolbarSerials = mutableMapOf<Int, String>()
  private val backendViews =
    BackendToolWindowViews(
      { icon -> iconPaths(icon).firstOrNull() },
      loadToolbar = { windowId, groupId, place, timestamps ->
        val serial = menuSerials.incrementAndGet()
        toolbarSerials[serial] = windowId
        actionMenus?.show(serial, groupId, place, timestamps)
      },
      publish = { views -> mutableState.update { it.copy(backendViews = views) } },
      showRequested = { windowId ->
        mutableState.update {
          it.copy(showRequest = ShowRequest(windowId, (it.showRequest?.serial ?: 0) + 1))
        }
      },
      luxComponent = { id -> luxProtocol?.luxModel?.components?.get(id) },
      log = ::log,
    )
  private val statusWidgets =
    StatusBarWidgets({ icon -> iconPaths(icon).firstOrNull() }) { widgets ->
      mutableState.update { it.copy(statusWidgets = widgets) }
    }
  private val breadcrumbs =
    Breadcrumbs(
      { icon -> iconPaths(icon).firstOrNull() },
      publish = { crumbs -> mutableState.update { it.copy(breadcrumbs = crumbs) } },
      log = ::log,
    )
  /** Asks for the breadcrumbs again this long after the caret stops moving. */
  private var breadcrumbRefresh: java.util.concurrent.ScheduledFuture<*>? = null
  private var backgroundTasks: BackgroundTasks? = null
  private val compositeIds = mutableMapOf<String, RdFileEditorCompositeId>()
  /** Editors the backend opened itself, by `RdFileIdNew.backendId`, until their path is known. */
  private val unnamedComposites = mutableMapOf<Int, RdFileEditorCompositeId>()
  /**
   * The tabs' order: files in the order they were asked to open, the reopened ones first in their
   * saved order. Tabs appear as their documents arrive, which can be in any order.
   */
  private val tabOrder = mutableListOf<String>()
  /** Saved editors still being reopened; [saveEditors] waits for them so it doesn't drop any. */
  private val reopening = mutableSetOf<String>()
  /** Saved carets of reopened files, put back once each file's edit session is ready. */
  private val caretsToRestore = mutableMapOf<String, Int>()
  /** How often each file's caret was put back, for [EditorFile.caretToShow]. */
  private val caretsRestored = mutableMapOf<String, Int>()
  private var saveScheduled = false
  private val editSessions = mutableMapOf<RdDocumentId, EditSession>()
  /** Console documents (Run, Build Output): text from patches only, no edit session. */
  private val consoles = ConsoleDocuments { reason -> log("Console out of step: $reason") }
  /**
   * Backend patches for a document that arrived before its editor existed (normally the editor
   * comes first).
   */
  private val earlyPatches = mutableMapOf<RdDocumentId, MutableList<RdPatch>>()
  private val commands = CommandChain()
  private var requestIds = 0L
  /** The backend's retry timestamp; it comes with every `RdRetryResponse`. */
  private var retryTimestamp = RdRetryTimestamp(0, 0)
  private var fleet: FleetRpc? = null
  private var completion: Completion? = null

  /** Opens a terminal tab (a shell on the backend); it shows up in [SessionState.terminals]. */
  fun openTerminal() = onRd { checkNotNull(terminalSessions) { "No project yet" }.open() }

  fun closeTerminal(id: String) = onRd { checkNotNull(terminalSessions).close(id) }

  /** The terminal's grid is now [columns] × [rows] cells; keys and text can go from any thread. */
  fun resizeTerminal(id: String, columns: Int, rows: Int) =
    terminalSessions?.resize(id, columns, rows)

  fun terminalKey(id: String, keyCode: Int, modifiers: Int) =
    terminalSessions?.tab(id)?.sendKey(keyCode, modifiers)

  fun terminalText(id: String, text: String) = terminalSessions?.tab(id)?.sendText(text)

  fun pasteToTerminal(id: String, text: String) = terminalSessions?.tab(id)?.paste(text)

  fun clearTerminalBuffer(id: String) = terminalSessions?.tab(id)?.clearBuffer()

  /** Search Everywhere, once a project with a kernel id is open. */
  @Volatile
  var searchEverywhere: SearchEverywhere? = null
    private set

  /** The Build trees, once the kernel connection is made (needs the project's kernel id). */
  private var buildTrees: BuildTrees? = null

  /** Starts following the Build tree of the build [uid] (a `BuildTree` split control shows). */
  fun openBuildTree(uid: Int) = buildTrees?.open(uid)

  /** Stops following it. */
  fun closeBuildTree(uid: Int) {
    buildTrees?.close(uid)
    mutableState.update { it.copy(buildTrees = it.buildTrees - uid) }
  }

  fun selectBuildStep(uid: Int, nodeId: Int) = buildTrees?.select(uid, nodeId)

  fun toggleBuildStep(uid: Int, nodeId: Int) = buildTrees?.toggle(uid, nodeId)

  private var requestResponse: RequestResponseModel? = null
  private var actionMenus: ActionMenus? = null
  private var luxDialogs: LuxDialogs? = null
  private var backendPopups: BackendPopups? = null
  private var terminalSessions: TerminalSessions? = null
  private val menuSerials = AtomicInteger()
  private var selectedEditorId: RdFileEditorId? = null

  fun start() {
    thread(name = "client-session-connect", isDaemon = true) {
      runCatching { connect() }.onFailure { failure -> fail(failure, connectionLost = true) }
    }
  }

  fun stop() {
    markupTimer.shutdownNow()
    lifetimeDefinition.terminate()
  }

  // ---- Actions from the UI (any thread) ----

  /** Expands or collapses a tree node; the first expansion asks the backend for the children. */
  fun toggle(nodeId: Int) = onRd {
    expandingAll.clear()
    val tree = mutableState.value.tree
    if (nodeId in tree.expanded) {
      mutableState.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded - nodeId)) }
    } else {
      expand(nodeId)
    }
  }

  fun select(nodeId: Int) = onRd {
    mutableState.update { it.copy(tree = it.tree.copy(selected = nodeId)) }
    pane?.selection?.set(nodeId)
  }

  /**
   * The Project header's Select Opened File: the backend expands its tree down to the selected
   * editor's file and answers that row's id (`expandByPath` with the plain path, as JetBrains
   * Client's `ThinClientProjectViewPaneBinding.select` does for a file it has no id for). The rows
   * on the way arrive before the answer; IDEate opens them and selects the file's row.
   */
  fun selectOpenedFile() = onRd {
    val path = mutableState.value.selectedEditor ?: return@onRd
    val current = pane ?: return@onRd
    current.expandByPath.start(lifetime, path).result.advise(lifetime) { answer ->
      val id =
        runCatching { answer.unwrap() }.getOrNull()
          ?: return@advise log("The Project tree has no row for $path")
      val nodes = mutableState.value.tree.nodes
      val parents = generateSequence(nodes[id]?.parentId) { nodes[it]?.parentId }.toList()
      loadedChildren += parents
      mutableState.update {
        it.copy(tree = it.tree.copy(expanded = it.tree.expanded + parents, selected = id))
      }
      current.selection.set(id)
    }
  }

  /**
   * The Project header's Expand Selected: the selected row and every folder under it. Rows not
   * loaded yet open as they arrive (see [expandingAll]); the backend may answer an `expand` before
   * it sends the rows, so the answer can't be the cue.
   */
  fun expandSelected() = onRd { mutableState.value.tree.selected?.let(::expandAll) }

  private fun expandAll(nodeId: Int) {
    expandingAll += nodeId
    expand(nodeId)
    mutableState.value.tree.nodes.values
      .filter { it.parentId == nodeId && it.hasChildren }
      .forEach { child -> expandAll(child.id) }
  }

  /** The Project header's Collapse All: every row closes; the backend keeps what it loaded. */
  fun collapseProjectTree() = onRd {
    expandingAll.clear()
    mutableState.update { it.copy(tree = it.tree.copy(expanded = emptySet())) }
  }

  /** Shows another pane in the Project tool window, e.g. Packages or the Project Files scope. */
  fun chooseProjectView(option: ProjectViewOption) = onRd { projectViews.choose(option) }

  /** Expands or collapses a row of a pane the backend draws (Packages, Scopes). */
  fun paneToggle(nodeId: Int) = onRd { projectViews.toggle(nodeId) }

  fun paneSelect(nodeId: Int) = onRd { projectViews.select(nodeId) }

  /** Opens a Packages or Scopes row's file; the backend opens the editor. */
  fun paneOpen(nodeId: Int) = onRd { projectViews.open(nodeId) }

  /**
   * Runs a run configuration like the run widget's play button, e.g.
   * `runConfiguration("Application.Main")` (the key in
   * `codeWithMeRunConfigurationsModel.runConfigurations`); [executorId] is `Run` or `Debug`.
   */
  fun runConfiguration(id: String, executorId: String = "Run") = onRd {
    val model =
      requireNotNull(solution) { "No project is open; cannot run $id" }
        .codeWithMeRunConfigurationsModel
    // The backend lists its run configurations a few seconds after the project opens.
    startWhenListed(lifetime, model.runConfigurations, id) { configuration ->
      configuration.execute.start(lifetime, CwmRunConfigurationExecuteArgs(executorId))
    }
  }

  /**
   * The backend tool windows on screen, e.g. `{"Gradle"}`: the backend builds their content only
   * while they show.
   */
  fun setBackendToolWindowsShown(ids: Set<String>) = onRd { backendViews.setShown(ids) }

  /**
   * A tap on status bar widget [id], e.g. `Encoding`; [x], [y], [width], [height]: where it is in
   * the window, in px, for the backend's popup.
   */
  fun clickStatusWidget(id: String, x: Int, y: Int, width: Int, height: Int) = onRd {
    statusWidgets.click(id, RectangleModel(x, y, width, height))
  }

  /** A tap on a breadcrumb: its children in [SessionState.crumbMenu], as IntelliJ's list. */
  fun openCrumb(nodeId: Long) = onRd {
    breadcrumbs.children(nodeId) { items ->
      mutableState.update { it.copy(crumbMenu = CrumbMenu(nodeId, items)) }
    }
  }

  fun closeCrumbMenu() = mutableState.update { it.copy(crumbMenu = null) }

  /**
   * An item chosen in a breadcrumb's list: a file, class or member opens in the editor; a folder
   * lists its own children in the same place, as IntelliJ's list goes one level down.
   */
  fun chooseCrumbItem(item: Crumb) = onRd {
    val anchor = mutableState.value.crumbMenu?.anchorNodeId ?: item.nodeId
    if (item.inFile) {
      mutableState.update { it.copy(crumbMenu = null) }
      breadcrumbs.navigate(item.nodeId)
    } else
      breadcrumbs.children(item.nodeId) { items ->
        mutableState.update { it.copy(crumbMenu = CrumbMenu(anchor, items)) }
      }
  }

  fun cancelTask(id: Long) = onRd { backgroundTasks?.cancel(id) }

  fun pauseTask(id: Long) = onRd { backgroundTasks?.pause(id) }

  fun resumeTask(id: Long) = onRd { backgroundTasks?.resume(id) }

  /** Expands or collapses a row of a backend tool window's tree, e.g. Gradle's. */
  fun backendToggle(windowId: String, nodeId: Int) = onRd { backendViews.toggle(windowId, nodeId) }

  fun backendSelect(windowId: String, nodeId: Int) = onRd { backendViews.select(windowId, nodeId) }

  /** Double tap: the backend's default action for the row, e.g. Gradle runs a task. */
  fun backendOpen(windowId: String, nodeId: Int) = onRd { backendViews.open(windowId, nodeId) }

  /** Opens a file of the backend in an editor tab, or switches to its tab if it is already open. */
  fun open(path: String) = onRd {
    if (mutableState.value.editors.any { it.path == path }) {
      showEditor(path)
    } else {
      openFile(path)
    }
  }

  /**
   * A Project tree row's file. A file inside a jar or the JDK image (`…/21.0.6-tem!/java.base/…`)
   * isn't found by path, so the backend opens that row itself, as JetBrains Client's double-click
   * does; IDEate then follows its editor selection.
   */
  fun openNode(nodeId: Int, path: String) = onRd {
    if ("!/" !in path) return@onRd open(path)
    val current = pane ?: return@onRd log("No Project tree; cannot open $path")
    log("Backend opens $path")
    current.navigate.start(lifetime, RdProjectViewNavigation(listOf(nodeId), true, false))
  }

  fun selectEditor(path: String) = onRd { showEditor(path) }

  /**
   * Closes [path]'s tab the way JetBrains Client does (`FrontendFileEditorHost.removeComposite`):
   * it removes the file's entry from `fileEditorsModel.backendDeferredComposites`, and the backend
   * closes the file and drops its document. The tab goes at once; when it was the selected one, the
   * tab chosen by [tabToSelectAfterClose] becomes selected on both sides.
   */
  fun closeEditor(path: String) = onRd {
    val before = mutableState.value
    val index = before.editors.indexOfFirst { it.path == path }
    if (index < 0) return@onRd
    val next =
      if (before.selectedEditor == path)
        tabToSelectAfterClose(before.editors.map { it.path }, index)
      else before.selectedEditor
    mutableState.update { state ->
      state.copy(editors = state.editors.filterNot { it.path == path }, selectedEditor = next)
    }
    tabOrder -= path
    val compositeId = compositeIds.remove(path)
    if (compositeId != null) {
      // The next selection must not name the closed editor as the old one.
      if (selectedEditorId?.compositeId == compositeId) selectedEditorId = null
      solution?.fileEditorsModel?.backendDeferredComposites?.remove(compositeId)
    }
    if (next != null && next != before.selectedEditor) showEditor(next)
    saveEditors()
  }

  // Editing the selected file (docs/split-protocol.md, "Typing"). All local first, then confirmed
  // by the backend.

  fun type(character: Char) = onRd {
    val session = selectedEditSession() ?: return@onRd
    // While the completion popup is open, identifier letters narrow it down.
    if (completion?.typed(session, character) != true) {
      session.type(character)
      mutableState.value.selectedEditor?.let { path ->
        completion?.afterTyped(path, session, character)
      }
    }
  }

  fun key(key: EditorKey) = onRd {
    val session = selectedEditSession() ?: return@onRd
    when (key) {
      EditorKey.ENTER -> {
        completion?.cancel()
        session.enter()
      }
      EditorKey.BACKSPACE -> if (completion?.backspace(session) != true) session.backspace()
      EditorKey.DELETE -> {
        completion?.cancel()
        session.delete()
      }
    }
  }

  /** Moves the caret of the selected file; a selection when [anchor] differs from [offset]. */
  fun moveCaret(offset: Int, anchor: Int = offset) = onRd {
    completion?.cancel()
    selectedEditSession()?.moveCaret(offset, anchor)
  }

  /**
   * Paste, as JetBrains Client does it (FrontendCopyPasteManagerHolder.syncClipboard, then
   * EditorPaste): [text], the system clipboard, goes to `ClipboardModel.content` first, so the
   * backend's paste reads the same text. Line breaks become `\n`, as IntelliJ's paste makes them.
   */
  fun paste(text: String) = paste(text, "EditorPaste")

  /** Paste as Plain Text: the backend's `EditorPasteSimple`, without its paste processing. */
  fun pasteSimple(text: String) = paste(text, "EditorPasteSimple")

  private fun paste(text: String, actionId: String) = onRd {
    completion?.cancel()
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    clipboard?.let { model ->
      if (model.content.value?.text != normalized) {
        sentClipboard = normalized
        model.content.set(ClipboardContent(normalized, emptyList(), false))
      }
    }
    selectedEditSession()?.paste(normalized, actionId)
  }

  /** A copy in IDEate, sent to the backend's clipboard as the client's own (`internal`) copy. */
  fun copied(text: String) = onRd {
    val model = clipboard ?: return@onRd
    sentClipboard = text
    model.content.set(ClipboardContent(text, emptyList(), true))
  }

  private var clipboard: ClipboardModel? = null
  /** The last text this client set, so its own echo isn't taken for a backend copy. */
  private var sentClipboard: String? = null
  private var clipboardSerial = 0

  private fun bindClipboard(model: ClipboardModel) {
    clipboard = model
    model.content.advise(lifetime) { content ->
      val text = content?.text ?: return@advise
      if (text == sentClipboard) return@advise
      clipboardSerial++
      mutableState.update { it.copy(backendClipboard = ClipboardText(text, clipboardSerial)) }
    }
  }

  fun undo() = onRd {
    completion?.cancel()
    selectedEditSession()?.undo(isUndo = true)
  }

  fun redo() = onRd {
    completion?.cancel()
    selectedEditSession()?.undo(isUndo = false)
  }

  /**
   * Runs [actionId] for the project as a main menu entry would, e.g. `CompileDirty` (Build
   * Project).
   */
  fun runProjectAction(actionId: String) = onRd {
    val project = requireNotNull(solution) { "No project is open; cannot run $actionId" }.projectId
    actionMenus?.runAction(
      actionId,
      "MainMenu",
      ActionTimestampSetModel(listOf(ProjectTimestampModel(project))),
    ) {
      log("$actionId is disabled")
    }
  }

  /**
   * A shortcut in the editor, e.g. ⌘B: runs [actionId] on the backend at the selected editor's
   * caret. Where it navigates arrives as a caret move (same file) or as a backend editor selection
   * ([followBackendSelection]).
   */
  fun runEditorAction(actionId: String) = onRd {
    completion?.cancel()
    val session = selectedEditSession() ?: return@onRd log("No editor; $actionId not run")
    log("$actionId at offset ${session.caret}")
    actionMenus?.runAction(actionId, KEYBOARD_PLACE, session.actionTimestamps())
  }

  /**
   * The symbol at [offset] that a ⌘-click (Ctrl-click) would go to, for the underline while the
   * pointer is over it, as JetBrains Client's `FrontendCtrlClickHost` asks; [found] gets null for
   * none or while the backend is indexing.
   */
  fun linkAt(offset: Int, found: (IntRange?) -> Unit) = onRd {
    val session = selectedEditSession() ?: return@onRd found(null)
    val model = solution?.hyperlinkHighlightingModel ?: return@onRd found(null)
    model.getElementRange
      .start(lifetime, HyperlinkHighlightingArgs(session.editorId, offset, BrowseMode.Declaration))
      .result
      .advise(lifetime) { result ->
        val range =
          (runCatching { result.unwrap() }.getOrNull() as? HyperlinkHighlightingResult)
            ?.textRange
            ?.takeIf { it.endOffset > it.startOffset }
        found(range?.let { it.startOffset until it.endOffset })
      }
  }

  /** Ctrl+Space: basic completion at the caret of the selected file. */
  fun complete() = onRd {
    val path = mutableState.value.selectedEditor ?: return@onRd
    val session = selectedEditSession() ?: return@onRd
    completion?.complete(path, session) ?: log("Completion is not connected yet")
  }

  fun lookupKey(key: LookupKey) = onRd { completion?.key(key) }

  /** The image cache backend dialogs share (docs/lux-protocol.md); null before connecting. */
  val luxImages: intellijandroid.client.ui.lux.LuxImages?
    get() = luxDialogs?.images

  /** Input for backend dialog [dialogId]: mouse, keys, focus, closing. */
  /** The Project window's content area for a pane the backend paints; see [ProjectViewPanes]. */
  fun resizeProjectPanel(x: Int, y: Int, width: Int, height: Int) = onRd {
    projectViews.resizePanel(x, y, width, height)
  }

  /** The pane's panel lost what it had painted (a new size): the backend paints it all again. */
  fun repaintProjectPanel() = onRd { projectViews.repaintPanel() }

  fun projectPanelEvent(event: com.jetbrains.rd.ide.model.LxEvent) = onRd {
    projectViews.panelEvent(event)
  }

  /** Run, Build: tab [index] of backend window [windowId] was picked. */
  fun selectBackendTab(windowId: String, index: Int) = onRd {
    backendViews.selectTab(windowId, index)
  }

  fun closeBackendTab(windowId: String, index: Int) = onRd {
    backendViews.closeTab(windowId, index)
  }

  fun resizeBackendPanel(windowId: String, x: Int, y: Int, width: Int, height: Int) = onRd {
    backendViews.resizePanel(windowId, x, y, width, height)
  }

  fun repaintBackendPanel(windowId: String) = onRd { backendViews.repaintPanel(windowId) }

  fun backendPanelEvent(windowId: String, event: com.jetbrains.rd.ide.model.LxEvent) = onRd {
    backendViews.panelEvent(windowId, event)
  }

  fun luxEvent(dialogId: Int, event: com.jetbrains.rd.ide.model.LxEvent) = onRd {
    luxDialogs?.send(dialogId, event)
  }

  /** The user typed [text] into a backend popup's [field]. */
  fun popupType(field: PopupTextField, text: String) {
    field.text = text // at once, so the text field doesn't lag behind the keyboard
    onRd { backendPopups?.type(field, text) }
  }

  /** A tap on [row] of [list] in a backend popup. */
  fun popupSelect(list: PopupList, row: PopupRow) = onRd { backendPopups?.select(list, row) }

  /**
   * Up or Down ([keystroke] `pressed UP` / `pressed DOWN`) in backend popup [popup], for its list.
   * True when the popup listens for it (the backend then moves the list's selection).
   */
  fun popupKey(popup: BackendPopup, keystroke: String): Boolean {
    if (keystroke !in popup.shortcuts) return false
    onRd { backendPopups?.key(popup, keystroke) }
    return true
  }

  /** Enter in backend popup [popup]. */
  fun popupEnter(popup: BackendPopup) = onRd { backendPopups?.enter(popup) }

  /** Escape, Back or a tap outside backend popup [popup]. */
  fun popupCancel(popup: BackendPopup) = onRd { backendPopups?.cancel(popup) }

  /**
   * A right click on Project view node [nodeId]: the backend's `ProjectViewPopupMenu` for it, in
   * [SessionState.contextMenu]. The backend finds the node's file or folder from the node id.
   */
  fun projectMenu(nodeId: Int) {
    val serial = startMenu()
    onRd {
      val id = projectId ?: return@onRd log("No project yet; no menu")
      val context =
        ActionTimestampSetModel(
          listOf(
            ProjectTimestampModel(id),
            ProjectViewTimestampModel(PROJECT_PANE, listOf(nodeId)),
          ),
        )
      actionMenus?.show(serial, "ProjectViewPopupMenu", "ProjectViewPopup", context)
    }
  }

  /**
   * A right click on a Structure node, after the caret moved to it: the backend's
   * `StructureViewPopupMenu` in the editor's context. JetBrains Client sends no context for a
   * Structure node either (docs/action-protocol.md), so the backend's actions see the element at
   * the caret.
   */
  fun structureMenu() {
    val serial = startMenu()
    onRd {
      val session = selectedEditSession() ?: return@onRd log("No editor; no menu")
      actionMenus?.show(
        serial,
        "StructureViewPopupMenu",
        "StructureViewPopup",
        session.actionTimestamps(),
      )
    }
  }

  /**
   * A right click or long press in the editor, after the caret moved there: the backend's
   * `EditorPopupMenu` in the editor's context, as JetBrains Client asks for it.
   */
  fun editorMenu() {
    val serial = startMenu()
    onRd {
      val session = selectedEditSession() ?: return@onRd log("No editor; no menu")
      // CLIENT_EDITOR_MENU_GROUP: a debug aid, another group in the editor's place, e.g.
      // `CodeMenu` (Comment with Line Comment, Reformat Code) to try actions that edit the
      // document.
      val group = System.getenv("CLIENT_EDITOR_MENU_GROUP") ?: "EditorPopupMenu"
      actionMenus?.show(serial, group, "EditorPopup", session.actionTimestamps())
    }
  }

  /** Clears the last menu at once, so the new one shows "Loading…" until the backend answers. */
  private fun startMenu(): Int {
    val serial = menuSerials.incrementAndGet()
    mutableState.update { it.copy(contextMenu = BackendMenu(serial, null)) }
    return serial
  }

  /** A tap or click on a completion popup row. */
  fun chooseLookupItem(itemId: String) = onRd { completion?.choose(itemId) }

  /**
   * The breadcrumbs for the selected editor's caret, with the project and that editor's action
   * context, as JetBrains Client builds it from the focused editor.
   */
  private fun refreshBreadcrumbs() {
    val project = projectId ?: return
    val editor = selectedEditSession()?.actionTimestamps()?.models.orEmpty()
    breadcrumbs.refresh(ActionTimestampSetModel(listOf(ProjectTimestampModel(project)) + editor))
  }

  /** [refreshBreadcrumbs] once the caret has rested for [BREADCRUMB_DELAY_MS]. */
  private fun refreshBreadcrumbsSoon() {
    breadcrumbRefresh?.cancel(false)
    breadcrumbRefresh =
      markupTimer.schedule(
        { onRd { refreshBreadcrumbs() } },
        BREADCRUMB_DELAY_MS,
        TimeUnit.MILLISECONDS,
      )
  }

  private fun selectedEditSession(): EditSession? {
    val path = mutableState.value.selectedEditor ?: return null
    return editSessions.entries.firstOrNull { pathOf(it.key) == path }?.value
  }

  // ---- Connection and models ----

  private fun connect() {
    val certificate =
      identity ?: ClientCertificate.create(link, System.getProperty("user.name") ?: "android")
    val connection = Connection.open(link, certificate, ::log)
    onAccepted(certificate)
    val wire =
      SplitWire(connection) { failure ->
        fail(failure ?: IllegalStateException("Connection closed"), connectionLost = true)
      }
    lifetime.onTermination { wire.close() }
    val rdScheduler = SingleThreadScheduler(lifetime, "rd")
    val protocol =
      Protocol(
        "ThinClient",
        Serializers(ProtocolMarshallers),
        Identities(IdKind.Client),
        rdScheduler,
        wire,
        lifetime,
      )
    wire.start()
    scheduler = rdScheduler
    rdScheduler.queue {
      createModels(protocol)
      log("rd protocol up, models created")
    }
  }

  private fun onRd(action: () -> Unit) {
    val rdScheduler = scheduler ?: return log("Not connected yet")
    rdScheduler.queueUnlessStopped(::log) { runCatching(action).onFailure(::fail) }
  }

  private fun createModels(protocol: IProtocol) {
    luxProtocol = protocol
    luxDialogs =
      LuxDialogs(lifetime, protocol, ::log) { dialogs ->
          mutableState.update { it.copy(luxDialogs = dialogs) }
        }
        .also { it.bind() }
    backendPopups =
      BackendPopups(lifetime, protocol, ::log, { icon -> iconPaths(icon).firstOrNull() }) { popups
          ->
          mutableState.update { it.copy(backendPopups = popups) }
        }
        .also { it.bind() }
    val registry = DefaultRegistryModel()
    protocol.highlighterRegistrationHostModel.registry.set(registry)
    // The backend sends every installed scheme (Default, Darcula, Islands Dark, …), each with only
    // its own entries.
    registry.colorsScheme.advise(lifetime) { scheme ->
      schemes[scheme.name] = scheme
      val resolved = EditorScheme.resolve(EDITOR_SCHEME, schemes) ?: return@advise
      if (mutableState.value.scheme == null) log("Color scheme ${resolved.name} ready")
      mutableState.update { it.copy(scheme = resolved) }
      documentTexts.keys.forEach(::publishDocument)
    }

    val documentsModel = protocol.documentsOperationModel
    val requestResponseModel = documentsModel.requestResponseModel
    requestResponse = requestResponseModel
    requestResponseModel.backendPatches.advise(lifetime) { patchWithMetadata ->
      val patch = patchWithMetadata.patch
      consoles.apply(patch).forEach(::publishDocument)
      val handled = editSessions.values.filter { it.concerns(patch) }
      handled.forEach { it.backendPatch(patch) }
      for (change in patch.changes) {
        val documentId = (change as? RdDocumentChangeBase)?.documentId ?: continue
        if (documentId in consoles) continue
        if (editSessions[documentId] == null) {
          earlyPatches.getOrPut(documentId) { mutableListOf() } += patch
          if (change is RdResetDocumentChange) {
            documentTexts[documentId] = change.text
            publishDocument(documentId)
          }
        }
      }
    }
    actionMenus =
      ActionMenus(
        lifetime,
        protocol.asyncActionsModel,
        ::sendRequest,
        ::onRd,
        { icon -> iconPaths(icon).firstOrNull() },
        ::log,
        publish = publish@{ serial, entries ->
            toolbarSerials.remove(serial)?.let { windowId ->
              backendViews.toolbarLoaded(
                windowId,
                afterEach(entries) { refreshToolbarSoon(windowId) },
              )
              return@publish
            }
            mutableState.update {
              if (it.contextMenu?.serial == serial)
                it.copy(contextMenu = BackendMenu(serial, entries))
              else it
            }
          },
        applyPatch = { patch ->
          editSessions.values.filter { it.concerns(patch) }.forEach { it.backendPatch(patch) }
        },
      )
    requestResponseModel.responsesWithTime.advise(lifetime) { responses ->
      for (response in responses.responses) {
        // The backend ignores requests sent with an older retry timestamp than its own.
        if (response is RdRetryResponse) retryTimestamp = response.retryTimestamp
        if (actionMenus?.response(response) == true) continue
        val session =
          editSessions.values.firstOrNull { it.hasPending(response.requestId) } ?: continue
        when (response) {
          is RdSuccessResponse -> session.response(response.requestId, response.patch)
          is RdRetryResponse -> {
            log("Request ${response.requestId} to be sent again: ${response.message}")
            session.retry(response.requestId)
            // The backend now drops every request sent with the older timestamp, other editors'
            // too.
            editSessions.values.filter { it !== session }.forEach { it.resendPending() }
          }
          else -> {
            log("Request ${response.requestId} not applied: $response")
            session.failure(response.requestId)
          }
        }
      }
    }
    documentsModel.documents.view(lifetime) { documentLifetime, documentId, document ->
      // Like JetBrains Client: the client adds the document's markup models, one without a project
      // and one per
      // project. The backend fills the per-project one with code analysis results (errors,
      // warnings, unused).
      document.extensions.filterIsInstance<MarkupDocumentExtension>().firstOrNull()?.markups?.let {
        markups ->
        markups[RdMarkupKey(null)] = MarkupExtensions.documentMarkup()
        projectId?.let { id ->
          val projectMarkup = MarkupExtensions.documentMarkup()
          markups[RdMarkupKey(id)] = projectMarkup
          attachMarkup(documentLifetime, documentId, projectMarkup, "document")
        }
      }
      document.textControls.view(documentLifetime) { controlLifetime, controlId, control ->
        if (controlId.kind == TextControlLocation.Console) {
          registerConsole(documentId, controlId)
        } else createEditSession(controlLifetime, documentId, controlId)
        val markupExtension =
          control.extensions.filterIsInstance<MarkupTextControlExtension>().firstOrNull()
            ?: return@view log("Editor has no markup extension; no highlighting")
        // The client creates the editor's markup model; the backend then fills it with syntax
        // highlighters.
        val markup = MarkupExtensions.editorMarkup()
        markupExtension.markup.set(markup)
        attachMarkup(controlLifetime, documentId, markup, "editor")
        // The backend runs code analysis (unused, method declarations, fields, errors) only for
        // visible
        // editors, and for their visible range. ponytail: reports the whole document as visible.
        control.visibleChangedFromFrontend.fire(true)
        control.focusChangedFromFrontend.fire(true)
        control.visibleRange.set(
          VisibleRange(0, documentTexts[documentId]?.length ?: Int.MAX_VALUE, emptyList()),
        )
      }
      documentLifetime.onTermination {
        editSessions.remove(documentId)
        earlyPatches.remove(documentId)
        documentTexts.remove(documentId)
        consoles.kernelEditorId(documentId)?.let { id ->
          consoles.remove(documentId)
          mutableState.update { it.copy(consoles = it.consoles - id) }
        }
        documentHighlights.remove(documentId)
        val path = pathOf(documentId)
        mutableState.update { state ->
          state.copy(editors = state.editors.filterNot { it.path == path })
        }
      }
    }

    // Fleet RPC rides on this rd model; completion uses it (docs/split-protocol.md, "Code
    // completion").
    val rdScheduler = requireNotNull(scheduler) { "rd scheduler not set" }
    val rpc =
      FleetRpc(
        protocol.fleetRpcModel,
        lifetime,
        rdScheduler,
        { if (requestIds == 0L) -1 else requestIds },
        ::log,
      )
    fleet = rpc
    backgroundTasks =
      BackgroundTasks(rpc, { tasks -> mutableState.update { it.copy(tasks = tasks) } }, ::log)
        .also { it.start() }

    vfs = protocol.vfsModel
    bindClipboard(protocol.clipboardModel)
    fun bindSolution(
      solutionLifetime: Lifetime,
      newSolution: Solution,
      description: RdCodeWithMeProject?,
      hostPath: String?,
    ) {
      log("Project ${newSolution.projectId.value} ($hostPath) added")
      projectId = newSolution.projectId
      solution = newSolution
      shownSolutionLifetime = solutionLifetime
      // Its Project tree names it (the root row): IdeaHome, then smoke after This Window.
      mutableState.update { it.copy(projectName = null) }
      solutionLifetime.onTermination {
        // Not when another project has taken over already (the welcome project's hand-over).
        if (solution === newSolution) {
          projectId = null
          solution = null
          shownSolutionLifetime = null
        }
      }
      newSolution.solutionLifecycle.readyForExtsRegistration.set(true)
      // Fleet RPC names projects by the kernel id, e.g. "m3nniruk9k7j4qsibjui", not by the rd
      // project id.
      val kernelProjectId = description?.kernelProjectId
      if (kernelProjectId == null) {
        log("Project has no kernel id; no completion")
      } else {
        completion =
          Completion(
            rpc,
            kernelProjectId,
            editSessionFor = { editorId ->
              editSessions.entries
                .firstOrNull { it.value.kernelEditorId == editorId }
                ?.let { pathOf(it.key) to it.value }
            },
            publish = { lookup -> mutableState.update { it.copy(lookup = lookup) } },
            log = ::log,
          )
        solutionLifetime.onTermination { completion = null }
        // Search Everywhere: JetBrains' Fleet RPC client and Rhizome database next to FleetRpc.
        val kernel =
          SearchKernel.start(
            protocol.fleetRpcModel,
            solutionLifetime,
            rdScheduler,
            kernelProjectId,
            dataContextBlob(protocol, newSolution.projectId),
            ::log,
          )
        buildTrees =
          BuildTrees(
            kernel,
            publish = { uid, tree ->
              mutableState.update { it.copy(buildTrees = it.buildTrees + (uid to tree)) }
            },
          )
        solutionLifetime.onTermination { buildTrees = null }
        searchEverywhere =
          SearchEverywhere(kernel) { change ->
            mutableState.update { it.copy(search = change(it.search)) }
          }
        mutableState.update { it.copy(searchAvailable = true) }
        solutionLifetime.onTermination {
          searchEverywhere = null
          mutableState.update { it.copy(search = null, searchAvailable = false) }
        }
      }
      newSolution.editorTabsModel
      // The backend lists each open file's editor here; the client marks the one it shows as
      // visible.
      newSolution.fileEditorsModel.backendDeferredComposites.view(solutionLifetime) {
        compositeLifetime,
        compositeId,
        deferred ->
        // The backend asks to show a file it opened for navigation (Go to Declaration into
        // another file): `selectAsCurrent` true. Files IDEate opens itself come with false.
        if (deferred.openRequest.selectAsCurrent)
          followBackendSelection(EditorIdentity.textEditorId(compositeId))
        deferred.model.advise(compositeLifetime) { composite ->
          // The same for a file that is already open.
          composite.openWithFocus.advise(compositeLifetime) { request ->
            if (request.selectAsCurrent)
              followBackendSelection(EditorIdentity.textEditorId(compositeId))
          }
          // A file the backend opened itself (e.g. from the Packages pane): ask the VFS for its
          // path.
          (composite.fileId as? RdFileIdNew)?.let { fileId ->
            val known = documentPaths[fileId.backendId]
            if (known != null) {
              compositeIds.putIfAbsent(known, compositeId)
              fetchTabIcon(compositeLifetime, fileId, known)
            } else {
              unnamedComposites[fileId.backendId] = compositeId
              vfs
                ?.findFile
                ?.start(compositeLifetime, RdFindFileParams(fileId, null, false, false))
                ?.result
                ?.advise(compositeLifetime) { found ->
                  found.unwrap()?.properties?.path?.let { path ->
                    nameDocument(fileId.backendId, path)
                    fetchTabIcon(compositeLifetime, fileId, path)
                  }
                }
            }
          }
          val selected = selectedEditorId?.takeIf { it.compositeId == compositeId }
          composite.visible.set(selected != null)
          // The selection sent when the file opened can come before the backend made this editor,
          // and is then
          // refused ("Attempt to select already gone editor", ~0.2 s early on the Mac backend); the
          // backend then
          // has no selected editor, which the Structure tool window needs. Now the editor exists:
          // send it again.
          if (selected != null)
            newSolution.fileEditorsModel.selectionChanged.fire(
              FileEditorManagerEventModel(OriginKind.Frontend, null, selected),
            )
        }
      }
      newSolution.fileEditorsModel.selectionChanged.advise(solutionLifetime) { event ->
        if (event.origin == OriginKind.Backend) followBackendSelection(event.newEditorId)
      }
      createProjectView(solutionLifetime, newSolution)
      projectViews.bind(solutionLifetime, newSolution.projectViewModel)
      toolWindows.bind(solutionLifetime, newSolution)
      backendViews.bind(solutionLifetime, newSolution)
      statusWidgets.bind(solutionLifetime, newSolution)
      breadcrumbs.bind(solutionLifetime, newSolution.navigationBarModel, ::refreshBreadcrumbs)
      bindStructure(solutionLifetime, newSolution)
      bindUsages(solutionLifetime, newSolution)
      val terminals =
        TerminalSessions(
          rdScheduler,
          { edit -> mutableState.update { it.copy(terminals = edit(it.terminals)) } },
          ::log,
        )
      terminals.bind(solutionLifetime, newSolution)
      terminalSessions = terminals
      mutableState.update { it.copy(terminalsReady = true) }
      solutionLifetime.onTermination {
        if (terminalSessions === terminals) {
          terminalSessions = null
          mutableState.update { it.copy(terminalsReady = false) }
        }
      }
      newSolution.isLoaded.advise(solutionLifetime) { loaded ->
        if (!loaded) return@advise
        reopenEditors()
        if (initialFile != null) openFile(initialFile)
      }
    }
    protocol.solutionModel.solutions.view(lifetime) { solutionLifetime, solutionKey, newSolution ->
      // The backend asks the client to close a project (e.g. the welcome project after This
      // Window): the client removes it from the map, as JetBrains Client's
      // ThinClientRdProjectViewSession.closeSolution does; the entry's lifetime then ends.
      newSolution.closeRequested.adviseOn(solutionLifetime, rdScheduler) {
        log("Backend closed project ${newSolution.projectId.value}")
        protocol.solutionModel.solutions.remove(solutionKey)
      }
      val description =
        (newSolution.solutionOpenStrategy as? RdOpenSolution)?.description as? RdCodeWithMeProject
      val hostPath = description?.hostProjectBasePath
      if (isShownProject(project, hostPath, showing = solution != null)) {
        return@view bindSolution(solutionLifetime, newSolution, description, hostPath)
      }
      // A project opened from the welcome project in This Window: the backend adds it first, then
      // closes the welcome project (closeRequested). JetBrains Client moves its window over to it;
      // IDEate shows it as soon as the welcome project is gone.
      val welcomeLifetime = shownSolutionLifetime
      if (project == null && welcomeLifetime != null && isWelcomeProject(solution)) {
        log(
          "Project ${newSolution.projectId.value} ($hostPath) added; shown once the welcome project closes",
        )
        welcomeLifetime.onTermination {
          if (solutionLifetime.isAlive)
            bindSolution(solutionLifetime, newSolution, description, hostPath)
        }
        return@view
      }
      log("Project ${newSolution.projectId.value} ($hostPath) added; showing $project")
    }
  }

  /**
   * A toolbar's items asked for again a moment after one ran, so a toggle or an enabled state the
   * action changed shows (e.g. Gradle's offline mode).
   */
  private fun refreshToolbarSoon(windowId: String) {
    markupTimer.schedule(
      { onRd { backendViews.refreshToolbar(windowId) } },
      TOOLBAR_REFRESH_MILLIS,
      TimeUnit.MILLISECONDS,
    )
  }

  /** Whether the Structure tool window is showing; the backend builds the tree only while it is. */
  private var structureVisible = false

  /** When Structure last became visible (System.nanoTime), until its tree arrives. */
  private var structureShownAt: Long? = null

  fun setStructureVisible(visible: Boolean) = onRd {
    if (visible && !structureVisible) structureShownAt = System.nanoTime()
    structureVisible = visible
    val current = solution ?: return@onRd
    current.codeStructureTreeModel.toolWindowVisible.set(visible)
  }

  /** The Show Usages session the popup shows, by its key in `FindUsagesModel.sessions`. */
  private var usagesSession: Pair<Int, RdShowUsagesSession>? = null

  /**
   * IntelliJ's Show Usages (docs/feature-parity-plan.md, section 2): ⌘B on a declaration makes the
   * backend add an `RdShowUsagesSession` and stream its usages into it. As JetBrains Client does,
   * nothing shows when the backend handled it itself, a single usage is gone to at once, and the
   * popup lists the rest; closing it removes the session.
   */
  private fun bindUsages(solutionLifetime: Lifetime, solution: Solution) {
    solution.findUsagesHost.sessions.view(solutionLifetime) { sessionLifetime, id, session ->
      if (session !is RdShowUsagesSession) return@view
      val path = mutableState.value.selectedEditor ?: return@view
      val title = plainText(session.title)
      val rows = mutableListOf<UsageRow>()
      var complete = false
      fun refresh() {
        if (session.handledOnBackend.hasTrueValue) return
        if (complete && rows.size == 1 && session.navigateToSingleUsageImmediately) {
          goToUsage(id, session, rows.single().index)
          return
        }
        // Wait for a second row or the end, so a single usage doesn't flash a popup first.
        if (!complete && rows.size < 2) return
        usagesSession = id to session
        mutableState.update {
          it.copy(
            usages =
              UsagesPopup(
                path,
                title,
                session.scopeName.orEmpty(),
                rows.toList(),
                complete,
                it.usages?.selected ?: 0,
              ),
          )
        }
      }
      log("Usages of $title in ${session.scopeName}")
      session.usages.items.adviseAddRemove(sessionLifetime) { kind, _, batch ->
        if (kind == AddRemove.Add) {
          rows += batch.map(::usageRow)
          refresh()
        }
      }
      session.isSearchComplete.advise(sessionLifetime) {
        complete = it
        if (it) log("${rows.size} usages of $title")
        refresh()
      }
      sessionLifetime.onTermination {
        if (usagesSession?.first == id) {
          usagesSession = null
          mutableState.update { it.copy(usages = null) }
        }
      }
    }
  }

  /** Up, Down, Enter and Escape while the usages popup is open. */
  fun usagesKey(key: LookupKey) = onRd {
    val popup = mutableState.value.usages ?: return@onRd
    when (key) {
      LookupKey.UP,
      LookupKey.DOWN -> {
        if (popup.rows.isEmpty()) return@onRd
        val step = if (key == LookupKey.UP) -1 else 1
        val selected = (popup.selected + step).mod(popup.rows.size)
        mutableState.update { it.copy(usages = it.usages?.copy(selected = selected)) }
      }
      LookupKey.ACCEPT -> popup.rows.getOrNull(popup.selected)?.let { chooseUsageRow(it.index) }
      LookupKey.CANCEL -> closeUsages()
    }
  }

  /** A click on a usage row: go there, as the backend's navigation does. */
  fun chooseUsage(index: Int) = onRd { chooseUsageRow(index) }

  fun closeUsages() = onRd {
    val (id, _) = usagesSession ?: return@onRd
    solution?.findUsagesHost?.sessions?.remove(id)
  }

  private fun chooseUsageRow(index: Int) {
    val (id, session) = usagesSession ?: return
    goToUsage(id, session, index)
  }

  private fun goToUsage(id: Int, session: RdShowUsagesSession, index: Int) {
    log("Going to usage $index")
    mutableState.update { it.copy(usages = null) }
    // The session is removed once the backend answers: removing it first could drop the call.
    session.usages.navigateTo.start(lifetime, RdUsageNavigationData(index, true)).result.advise(
      lifetime,
    ) {
      solution?.findUsagesHost?.sessions?.remove(id)
    }
  }

  /**
   * The Structure tool window's model (docs/structure-protocol.md): the backend builds the tree for
   * the editor it sees as selected, sends it whole, and sends it again after every change to the
   * file.
   */
  private fun bindStructure(solutionLifetime: Lifetime, solution: Solution) {
    val model = solution.codeStructureTreeModel
    model.treeNodes.advise(solutionLifetime) { list ->
      val nodes =
        list.treeNodes
          .filterNot { it.isFromRelatedFile }
          .map { node ->
            StructureNode(
              node.id,
              node.shortName.ifBlank { node.name },
              node.icon?.let(::iconPaths).orEmpty(),
              node.offset.takeIf { node.navigable } ?: -1,
              node.children,
            )
          }
      val path = list.documentTimestamp?.documentId?.takeIf { nodes.isNotEmpty() }?.let(::pathOf)
      structureShownAt
        ?.takeIf { nodes.isNotEmpty() }
        ?.let { shownAt ->
          val waited = (System.nanoTime() - shownAt) / 1_000_000
          log("Structure tree: ${nodes.size} nodes for $path, $waited ms after showing")
          structureShownAt = null
        }
      mutableState.update { it.copy(structure = StructureTree(path, nodes)) }
    }
    model.toolWindowVisible.set(structureVisible)
  }

  private fun createProjectView(solutionLifetime: Lifetime, solution: Solution) {
    val projectView = solution.projectViewModel
    projectView.panes.view(solutionLifetime) { paneLifetime, _, candidate ->
      // "ProjectPane" is the Project view; others are Packages, Project Files, Scopes, ….
      if (candidate !is RdProjectViewAsyncPane || candidate.id != PROJECT_PANE) return@view
      pane = candidate
      // Unless the backend's default pane (e.g. the welcome project's) is already showing.
      if ((projectView.currentPane.value as String?) == null)
        projectView.currentPane.set(PROJECT_PANE)
      candidate.items.view(paneLifetime) { itemLifetime, id, item ->
        val node = item.toNode(id)
        mutableState.update {
          it.copy(
            projectName =
              it.projectName
                ?: node.takeIf { node.parentId == null }?.fragments?.firstOrNull()?.text,
            tree = it.tree.copy(nodes = it.tree.nodes + (id to node)),
          )
        }
        if (node.parentId in expandingAll && node.hasChildren) expandAll(id)
        // IntelliJ opens with the project root expanded.
        if (
          node.parentId == null &&
            node.hasChildren &&
            node.isDirectory &&
            mutableState.value.tree.expanded.isEmpty()
        )
          expand(id)
        itemLifetime.onTermination {
          mutableState.update { it.copy(tree = it.tree.copy(nodes = it.tree.nodes - id)) }
        }
      }
    }
  }

  private fun expand(nodeId: Int) {
    val currentPane = pane ?: return
    mutableState.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded + nodeId)) }
    // The backend adds the children to `items` (with parentId = nodeId) before it answers.
    if (loadedChildren.add(nodeId)) currentPane.expand.start(lifetime, nodeId)
  }

  /**
   * This project's tool window pane sizes as saved by name (`ui/PaneWeights.kt`), e.g. `{"left":
   * 0.28}`. Empty when none were saved, and when this session keeps nothing ([savedEditors] null:
   * snapshots and tests), which shows the defaults.
   */
  fun savedPanes(): Map<String, Float> = savedEditors?.readPanes().orEmpty()

  /** Keeps the pane sizes for this project's next connection; nothing without [savedEditors]. */
  fun savePanes(panes: Map<String, Float>) {
    savedEditors?.savePanes(panes)
  }

  /**
   * Reopens the editors open when this project was last left, in their tab order, and selects the
   * one that was selected, as JetBrains Client does on connecting (see [OpenEditors]). A file that
   * is gone is skipped and dropped from the saved list. [initialFile], when given, opens on top.
   */
  private fun reopenEditors() {
    val id = projectId?.value ?: return
    val saved = savedEditors?.read(id) ?: return
    val files = saved.files.filter { it != initialFile }
    if (files.isNotEmpty()) log("Reopening ${files.size} editors: ${files.joinToString()}")
    reopening += files
    tabOrder += files
    caretsToRestore += saved.carets.filterKeys { it in files }
    for (path in files) openFile(path, select = path == saved.selected && initialFile == null)
  }

  /** Keeps the open editors and the selected one for the next connection. */
  private fun saveEditors() {
    val slot = savedEditors ?: return
    val id = projectId?.value ?: return
    if (reopening.isNotEmpty()) return
    val files = tabOrder.filter { it in compositeIds }
    val carets =
      files
        .mapNotNull { path -> (caretsToRestore[path] ?: caretOf(path))?.let { path to it } }
        .toMap()
    slot.save(id, SavedEditors(files, mutableState.value.selectedEditor, carets))
  }

  /** [path]'s caret offset in its edit session, once the backend has sent it. */
  private fun caretOf(path: String): Int? =
    editSessions.entries
      .firstOrNull { (documentId, _) -> pathOf(documentId) == path }
      ?.value
      ?.takeIf { it.isReady }
      ?.caret

  /**
   * Saves the editors a second after the last edit or caret move, so a caret that moved since the
   * last tab change is kept without a write per key stroke.
   */
  private fun saveEditorsSoon() {
    if (saveScheduled || savedEditors == null) return
    val rdScheduler = scheduler ?: return
    saveScheduled = true
    markupTimer.schedule(
      {
        rdScheduler.queueUnlessStopped(::log) {
          saveScheduled = false
          saveEditors()
        }
      },
      SAVE_DELAY_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  /**
   * Puts [path]'s caret back where it was last time, once [session] has the document and the
   * editor's caret version: a move sent with the document alone was refused ("not matched expected
   * versions").
   */
  private fun restoreCaret(path: String, session: EditSession) {
    if (!session.isReady || session.caretModelVersion < 0) return
    val offset = caretsToRestore.remove(path) ?: return
    caretsRestored[path] = (caretsRestored[path] ?: 0) + 1
    session.moveCaret(offset)
  }

  /** [path]'s open request ended, opened or not. */
  private fun finishedOpening(path: String) {
    reopening -= path
    saveEditors()
  }

  /** Opens [path] in a tab; [select] makes it the selected tab. */
  private fun openFile(path: String, select: Boolean = true) {
    val currentSolution = solution ?: return log("No project yet; cannot open $path")
    val fileSystem = vfs ?: return
    if (path !in tabOrder) tabOrder += path
    fileSystem.findFile.start(lifetime, RdFindFileParams(null, path, false, false)).result.advise(
      lifetime,
    ) { found ->
      val snapshot = found.unwrap()
      if (snapshot == null) {
        log("Backend has no file $path")
        tabOrder -= path
        return@advise finishedOpening(path)
      }
      (snapshot.id as? RdFileIdNew)?.let { documentPaths[it.backendId] = path }
      if (select) mutableState.update { it.copy(selectedEditor = path) }
      currentSolution.fileEditorsModel.requestComposite
        .start(lifetime, RequestCompositeQuery(snapshot.id, "file://$path"))
        .result
        .advise(lifetime) { response ->
          when (val composite = response.unwrap()) {
            is SuccessfulRequestComposite -> {
              compositeIds[path] = composite.compositeId
              if (select) showEditor(path)
            }
            is FailedRequestComposite -> log("Backend could not open $path: $composite")
            else -> log("Unexpected answer opening $path: $composite")
          }
          finishedOpening(path)
        }
    }
  }

  /**
   * Makes [path]'s editor the selected one, on both sides. The backend runs code analysis only for
   * the selected, visible editor (recorded session: `fileEditorsModel.selectionChanged` and
   * `backendDeferredComposites[…].model.visible`).
   */
  private fun showEditor(path: String) {
    mutableState.update { it.copy(selectedEditor = path) }
    refreshBreadcrumbsSoon()
    val fileEditors = solution?.fileEditorsModel ?: return
    val compositeId = compositeIds[path] ?: return
    val editorId = EditorIdentity.textEditorId(compositeId)
    if (editorId == selectedEditorId) return
    fileEditors.selectionChanged.fire(
      FileEditorManagerEventModel(OriginKind.Frontend, selectedEditorId, editorId),
    )
    selectedEditorId = editorId
    for ((id, deferred) in fileEditors.backendDeferredComposites) deferred.model.valueOrNull
      ?.visible
      ?.set(id == compositeId)
    saveEditors()
  }

  /**
   * The backend asked to show an editor (Go to Declaration into another file: an `openRequest` or
   * `openWithFocus` with `selectAsCurrent`, or a backend `selectionChanged`): show that tab and
   * tell the backend it is the selected, visible one, as [showEditor] does. A file the backend just
   * opened may not have its tab or path yet; [pendingBackendSelection] keeps it until
   * [nameDocument] names it.
   */
  private fun followBackendSelection(editorId: RdFileEditorId?) {
    val compositeId = editorId?.compositeId ?: return
    val path = compositeIds.entries.firstOrNull { it.value == compositeId }?.key
    if (path == null || mutableState.value.editors.none { it.path == path }) {
      pendingBackendSelection = editorId
      return
    }
    pendingBackendSelection = null
    log("Backend showed $path")
    showEditor(path)
  }

  /**
   * Asks the backend for [path]'s tab icon, as JetBrains Client does (`VfsModel.getFileIcon`):
   * IntelliJ's tabs use `IconUtil.computeFileIcon(file, ICON_FLAG_READ_STATUS, project)`
   * (EditorsSplitters), e.g. the class icon for `Main.java`, which the file type alone can't tell.
   */
  private fun fetchTabIcon(compositeLifetime: Lifetime, fileId: RdFileId, path: String) {
    val project = projectId ?: return
    val fileSystem = vfs ?: return
    fileSystem.getFileIcon
      .start(compositeLifetime, RdFileIconParams(project, fileId, ICON_FLAG_READ_STATUS))
      .result
      .advise(compositeLifetime) { result ->
        val icon = runCatching { result.unwrap() }.getOrNull() ?: return@advise
        val iconPath = iconPaths(icon).firstOrNull() ?: return@advise
        mutableState.update { it.copy(tabIcons = it.tabIcons + (path to iconPath)) }
      }
  }

  /** A backend selection whose file has no tab or path yet. */
  private var pendingBackendSelection: RdFileEditorId? = null

  private fun createEditSession(
    controlLifetime: Lifetime,
    documentId: RdDocumentId,
    controlId: TextControlId,
  ) {
    val identity =
      EditorIdentity.of(documentId, controlId, compositeIds[pathOf(documentId)])
        ?: return log("Cannot edit $controlId: unknown editor id type")
    val session =
      EditSession(
        identity,
        commands,
        ::sendRequest,
        changed = { edit ->
          edit?.let { shiftHighlights(documentId, it) }
          publishDocument(documentId)
          editSessions[documentId]?.let { restoreCaret(pathOf(documentId), it) }
          saveEditorsSoon()
        },
        outOfStep = { reason ->
          log("Out of step with the backend ($reason)")
          recoverEditors()
        },
      )
    editSessions[documentId] = session
    earlyPatches.remove(documentId)?.forEach(session::backendPatch)
    controlLifetime.onTermination {
      if (editSessions[documentId] === session) editSessions.remove(documentId)
    }
  }

  /**
   * A console text control (`kind == Console`) has no file, so [EditorIdentity.of] has nothing to
   * work with; its text only comes from the backend's patches ([ConsoleDocuments]).
   */
  private fun registerConsole(documentId: RdDocumentId, controlId: TextControlId) {
    val kernelEditorId =
      requireNotNull((controlId as? KernelAwareTextControlId)?.kernelEditorId) {
        "Console text control without a kernel editor id: $controlId"
      }
    consoles.register(documentId, kernelEditorId)
    earlyPatches.remove(documentId)?.forEach { consoles.apply(it) }
    documentTexts.remove(documentId)
    publishDocument(documentId)
  }

  private var recovering = false

  /**
   * Like JetBrains Client's `TrustBackendRecoveringStrategy`: drop every queued request on the
   * backend, then take its text, caret and versions of every open editor. `dropAllRequests` moves
   * the backend's retry timestamp to (0, generation + 1).
   */
  private fun recoverEditors() {
    val model = requestResponse ?: return
    if (recovering) return
    recovering = true
    log("Taking the backend's editor state")
    editSessions.values.forEach { it.dropPending() }
    model.dropAllRequests.start(lifetime, Unit).result.advise(lifetime) {
      retryTimestamp = RdRetryTimestamp(0, retryTimestamp.generation + 1)
      val sessions = editSessions.values.toList()
      model.getEditorStates.start(lifetime, sessions.map { it.editorId }).result.advise(lifetime) {
        result ->
        val state = runCatching {
          result.unwrap()
        }
          .onFailure { failure -> log("No editor state from the backend: $failure") }
          .getOrNull()
        sessions.forEach { it.recovered(state) }
        recovering = false
      }
    }
  }

  private fun sendRequest(request: RdRequest): Long {
    val requestId = ++requestIds
    val model = requestResponse ?: error("requestResponseModel not ready")
    // RdRequests.timestamp: wall clock in epoch nanoseconds, like JetBrains Client.
    val now = java.time.Instant.now()
    model.requestsWithTime.fire(
      RdRequests(
        listOf(RdRequestHolder(requestId, request, retryTimestamp, null)),
        now.epochSecond * 1_000_000_000 + now.nano,
      ),
    )
    return requestId
  }

  /** Keeps highlighter ranges in place over local edits until the backend sends fresh ones. */
  private fun shiftHighlights(documentId: RdDocumentId, edit: EditSession.TextEdit) {
    val highlights = documentHighlights[documentId] ?: return
    for ((key, highlight) in highlights.entries.toList()) {
      val (start, end) = edit.shift(highlight.start, highlight.end)
      highlights[key] = highlight.copy(start = start, end = end)
    }
  }

  private fun attachMarkup(
    markupLifetime: Lifetime,
    documentId: RdDocumentId,
    markup: RdMarkupModel,
    source: String,
  ) {
    val highlights = documentHighlights.getOrPut(documentId) { mutableMapOf() }
    markup.highlightersAdded.advise(markupLifetime) { list ->
      list.elements.forEach { highlights["$source:${it.highlighter.id}"] = it.toRaw() }
      publishMarkupLater(documentId)
    }
    markup.highlightersUpdated.advise(markupLifetime) { list ->
      list.elements.forEach { highlights["$source:${it.highlighter.id}"] = it.toRaw() }
      publishMarkupLater(documentId)
    }
    markup.highlightersRemoved.advise(markupLifetime) { removed ->
      removed.elements.forEach { highlights.remove("$source:$it") }
      publishMarkupLater(documentId)
    }
    markup.highlightersCleared.advise(markupLifetime) {
      highlights.keys.removeAll { it.startsWith("$source:") }
      publishMarkupLater(documentId)
    }
    markupLifetime.onTermination { highlights.keys.removeAll { it.startsWith("$source:") } }
  }

  /**
   * The backend sends a large file's highlighters in many small batches, for a 4,500-line file one
   * at a time over more than a minute. Publishing each one rebuilt the editor's state and redrew
   * every visible line about 15 times a second, which made scrolling stutter. Like Swing's
   * RepaintManager for IntelliJ's markup, changes are collected and published once per
   * [MARKUP_DELAY_MS]. Edits and caret moves still publish at once.
   */
  private fun publishMarkupLater(documentId: RdDocumentId) {
    if (!markupChanged.add(documentId) || markupChanged.size > 1) return
    val rdScheduler = scheduler ?: return publishDocument(documentId)
    markupTimer.schedule(
      {
        rdScheduler.queueUnlessStopped(::log) {
          val documents = markupChanged.toList()
          markupChanged.clear()
          documents.forEach(::publishDocument)
        }
      },
      MARKUP_DELAY_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun publishDocument(documentId: RdDocumentId) {
    if (documentId in consoles) return publishConsole(documentId)
    val session = editSessions[documentId]?.takeIf { it.isReady }
    // The caret may have moved into another class or method.
    if (session != null && pathOf(documentId) == mutableState.value.selectedEditor) {
      refreshBreadcrumbsSoon()
    }
    val text = session?.text ?: documentTexts[documentId] ?: return
    documentTexts[documentId] = text
    val scheme = mutableState.value.scheme
    val raw =
      documentHighlights[documentId]?.values.orEmpty().filter {
        it.start in 0..it.end && it.end <= text.length
      }
    val highlights =
      if (scheme == null) emptyList()
      else
        raw
          .map { Highlight(it.id, it.start, it.end, it.layer, scheme.style(it.keyChains)) }
          .sortedWith(compareBy({ it.layer }, { it.start }))
    // Comment tokens from the backend's syntax highlighting: comment lines neither open nor close
    // blocks, like blank lines (IndentGuideCalculator).
    val comments =
      raw
        .filter { highlight ->
          highlight.keyChains.any { chain -> chain.any { it in COMMENT_KEYS } }
        }
        .sortedBy { it.start }
    val guides =
      IndentGuides.compute(
        text,
        isComment = { offset ->
          val index =
            comments.binarySearchBy(offset) { it.start }.let { if (it >= 0) it else -it - 2 }
          index >= 0 && offset < comments[index].end
        },
      )
    val gutterIcons =
      raw
        .filter { it.gutterIconPaths.isNotEmpty() }
        .sortedBy { it.start }
        .map { GutterIcon(it.start, it.gutterIconPaths) }
    val stickyRanges =
      raw
        .filter { highlight -> highlight.keyChains.any { it.firstOrNull() == STICKY_LINE_MARKER } }
        .map { StickyRange(it.start, it.end) }
        .distinct()
        .sortedBy { it.start }
    val file =
      EditorFile(
        pathOf(documentId),
        text,
        highlights,
        guides,
        gutterIcons,
        stickyRanges,
        caret = session?.caret ?: 0,
        selectionStart = session?.selectionStart ?: 0,
        selectionEnd = session?.selectionEnd ?: 0,
        editable = session != null,
        caretToShow = (caretsRestored[pathOf(documentId)] ?: 0) + (session?.backendCaretMoves ?: 0),
        canUndo = session?.canUndo == true,
        canRedo = session?.canRedo == true,
      )
    // Debug aid: CLIENT_HIGHLIGHT_DUMP=<file> writes every highlight with its keys and covered
    // text.
    System.getenv("CLIENT_HIGHLIGHT_DUMP")?.let { dump ->
      java.io
        .File(dump)
        .writeText(
          documentHighlights[documentId]
            .orEmpty()
            .entries
            .sortedBy { it.value.start }
            .joinToString("\n") { (key, raw) ->
              "$key ${raw.start}-${raw.end} layer=${raw.layer} model=${raw.modelName} keys=${raw.keyChains.map { it.first() }} '${text.substring(raw.start, raw.end).take(30)}'"
            },
        )
    }
    mutableState.update { state ->
      val editors =
        if (state.editors.any { it.path == file.path })
          state.editors.map { if (it.path == file.path) file else it }
        else (state.editors + file).sortedBy { tabIndex(it.path) }
      state.copy(editors = editors, selectedEditor = state.selectedEditor ?: file.path)
    }
    // A backend selection that came before this file's tab.
    pendingBackendSelection?.let(::followBackendSelection)
  }

  private fun publishConsole(documentId: RdDocumentId) {
    val text = consoles.text(documentId) ?: return
    val id = requireNotNull(consoles.kernelEditorId(documentId))
    val scheme = mutableState.value.scheme
    val highlights =
      if (scheme == null) emptyList()
      else
        documentHighlights[documentId]
          ?.values
          .orEmpty()
          .filter { it.start in 0..it.end && it.end <= text.length }
          .map { Highlight(it.id, it.start, it.end, it.layer, scheme.style(it.keyChains)) }
          .sortedWith(compareBy({ it.layer }, { it.start }))
    mutableState.update { it.copy(consoles = it.consoles + (id to ConsoleText(text, highlights))) }
  }

  /** Where [path]'s tab goes; a file opened some other way goes last. */
  private fun tabIndex(path: String): Int =
    tabOrder.indexOf(path).let { if (it < 0) Int.MAX_VALUE else it }

  /**
   * Gives the editor of a file the backend opened itself its [path], e.g. `document-938` becomes
   * `/…/smoke/src/demo/Other.java`.
   */
  private fun nameDocument(backendId: Int, path: String) {
    documentPaths[backendId] = path
    unnamedComposites.remove(backendId)?.let { compositeIds[path] = it }
    val unnamed = "document-$backendId"
    mutableState.update { state ->
      state.copy(
        editors = state.editors.map { if (it.path == unnamed) it.copy(path = path) else it },
        selectedEditor = if (state.selectedEditor == unnamed) path else state.selectedEditor,
      )
    }
    pendingBackendSelection?.let(::followBackendSelection)
  }

  private fun pathOf(documentId: RdDocumentId): String {
    val fileId = (documentId as? RdDocumentIdWithFileId)?.fileId as? RdFileIdNew
    return fileId?.let { documentPaths[it.backendId] }
      ?: "document-${fileId?.backendId ?: documentId.hashCode()}"
  }

  private fun log(message: String) {
    val line = "${LocalTime.now().format(TIME)} $message"
    println(line)
    mutableState.update { it.copy(log = (it.log + line).takeLast(200)) }
  }

  private fun fail(failure: Throwable, connectionLost: Boolean = false) {
    log("FAILED: $failure")
    failure.printStackTrace()
    mutableState.update {
      it.copy(
        failure = failure.toString(),
        connectionLost = if (connectionLost) failure.toString() else it.connectionLost,
      )
    }
  }

  /** A highlighter before style resolution, so a scheme change can restyle it. */
  private data class RawHighlight(
    val id: Long,
    val start: Int,
    val end: Int,
    val layer: Int,
    val keyChains: List<List<String>>,
    val gutterIconPaths: List<String>,
    /** The rd model's class, e.g. `SyntaxHighlighterModel`; only for CLIENT_HIGHLIGHT_DUMP. */
    val modelName: String,
  )

  private companion object {
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** The user's IntelliJ uses Islands Dark (its theme and its editor scheme). */
    const val EDITOR_SCHEME = "Islands Dark"
    const val PROJECT_PANE = "ProjectPane"

    /** `ActionPlaces.KEYBOARD_SHORTCUT`: where an action run by its shortcut comes from. */
    const val KEYBOARD_PLACE = "keyboard shortcut"

    /** About two frames at 60 Hz. */
    const val MARKUP_DELAY_MS = 32L
    /** How long after an edit or caret move the open editors and carets are saved. */
    const val SAVE_DELAY_MS = 1_000L
    /** Breadcrumbs follow the caret once it rests this long, so typing doesn't ask each key. */
    const val BREADCRUMB_DELAY_MS = 300L
    /** How long after a toolbar action its toolbar is asked for again. */
    const val TOOLBAR_REFRESH_MILLIS = 500L

    /** `Iconable.ICON_FLAG_READ_STATUS`: the file icon with a lock when the file is read-only. */
    const val ICON_FLAG_READ_STATUS = 2

    /** The default comment keys every language's comment colors fall back to. */
    val COMMENT_KEYS = setOf("DEFAULT_LINE_COMMENT", "DEFAULT_BLOCK_COMMENT", "DEFAULT_DOC_COMMENT")

    /** Text attributes key of the backend's sticky line scopes (`StickyLinesModelImpl`). */
    const val STICKY_LINE_MARKER = "STICKY_LINE_MARKER"

    fun HighlighterElement.toRaw(): RawHighlight {
      val model = highlighter
      val keys =
        (model as? SyntaxHighlighterModel)?.textAttributesKeys
          ?: listOfNotNull(model.textAttributesKey)
      val gutterIcon =
        (model as? GutterMarkHighlighterModel)?.gutterMarkRenderer?.icon?.let(::iconPaths).orEmpty()
      return RawHighlight(
        model.id,
        model.start,
        model.end,
        model.layer,
        keys.map(EditorScheme::keyChain),
        gutterIcon,
        model.javaClass.simpleName,
      )
    }

    fun RdProjectViewItem.toNode(id: Int) =
      TreeNode(
        id = id,
        parentId = parentId,
        fragments =
          descriptor.presentation.map { fragment ->
            TextFragment(
              fragment.text,
              fragment.attributes.style,
              fragment.attributes.fgColor?.let { it.toLong() and 0xFFFFFFFFL },
            )
          },
        iconPaths = descriptor.icon?.let(::iconPaths).orEmpty(),
        hasChildren = descriptor.hasChildren,
        isDirectory = descriptor.isDirectory,
        path = descriptor.path,
        sortKey = descriptor.sortKey,
        fileStatus = descriptor.fileStatus,
        background = descriptor.scope?.color?.let { it.toLong() and 0xFFFFFFFFL },
      )

    private val unhandledIconTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun iconPaths(icon: IconModel): List<String> =
      when (icon) {
        is ImageIconModel -> {
          // A plugin's icon comes with its SVG (the client's icons jar has only the platform's).
          icon.imageSources?.let { sent ->
            IconJar.remember(
              icon.id.path,
              sent.sources.mapNotNull { source -> source.body?.let { source.path to it } }.toMap(),
            )
          }
          listOf(icon.id.path)
        }
        is RowIconModel -> icon.subNodes.flatMap(::iconPaths)
        // Layered icon (e.g. the module file): the first layer is the base icon.
        is CompositionIconModel -> icon.subNodes.flatMap(::iconPaths)
        // Shown until the real icon is computed; the backend sends the final one as an item update.
        is DeferredIconModel -> icon.baseIcon?.let(::iconPaths).orEmpty()
        // A blank slot, e.g. the visibility place in a Structure row's icon.
        is EmptyIconModel -> emptyList()
        // An icon with something on or around it (a badge dot, an overlay, a tint, an offset):
        // the icon itself, without the extra. E.g. a tool window button with a badge.
        is BadgeIconModel -> iconPaths(icon.icon)
        is IconWithOverlayModel -> iconPaths(icon.mainIcon)
        is IndicatorIconModel -> icon.base?.let(::iconPaths).orEmpty()
        is ColorizedIconModel -> iconPaths(icon.base)
        is OffsetIconModel -> iconPaths(icon.icon)
        is CropIconModel -> iconPaths(icon.base)
        is RotatedIconModel -> iconPaths(icon.staticNode)
        is CustomImageIconModel -> iconPaths(icon.model)
        else -> {
          if (unhandledIconTypes.add(icon::class.simpleName.orEmpty()))
            println("Icon type not handled yet: $icon")
          emptyList()
        }
      }
  }
}

/**
 * The client's marshallers: the IDE root models, then the split-mode models (both resolve ids from
 * resource lists).
 */
/**
 * A data context naming only the project, as a Fleet RPC `DataContextId` carries it: JetBrains
 * Client's `FrontendDataContextRpcSerializer` writes an [ActionTimestampSetModel] with rd's
 * marshaller and sends the bytes as base64, e.g. `AQAAAA…`.
 */
private fun dataContextBlob(protocol: IProtocol, projectId: RdProjectId): String {
  val buffer = UnsafeBuffer(ByteArray(64))
  // A context without the connection's intern table: through it the project id would go out as
  // a number only this connection knows (`02000000`), not as text.
  ActionTimestampSetModel.write(
    SerializationCtx(protocol.serializers, protocol.identity),
    buffer,
    ActionTimestampSetModel(listOf(ProjectTimestampModel(projectId))),
  )
  return Base64.getEncoder().encodeToString(buffer.getArray().copyOf(buffer.position))
}

private object ProtocolMarshallers : MarshallersProvider {
  override fun getMarshaller(id: RdId): IMarshaller<*>? =
    BasicIdeRootMarshallersProvider.getMarshaller(id)
      ?: DefaultSplitMarshallersProvider.getMarshaller(id)
}

/**
 * The tab IntelliJ selects after the selected one at [closedIndex] in [paths] closes, with its
 * default settings (`EditorWindow.computeIndexToSelect`: neither "most recently used" nor "right
 * neighbour" is on): the left neighbour, or for the first tab the next one. Null when it was the
 * only tab.
 */
internal fun tabToSelectAfterClose(paths: List<String>, closedIndex: Int): String? =
  paths.getOrNull(closedIndex - 1) ?: paths.getOrNull(closedIndex + 1)

/**
 * A usage as a popup row: its line trimmed, the usage's place in it. [RdMergeableUsage] carries the
 * line and column (0-based); a plain [RdUsageBase] doesn't, and its row then has no match.
 */
internal fun usageRow(usage: RdUsageBase): UsageRow {
  val fileName =
    usage.groups.lastOrNull { it.groupingRuleId.endsWith(".FileGroupingRule") }?.text.orEmpty()
  val text = usage.text.trim()
  val position =
    (usage as? RdMergeableUsage)?.position ?: return UsageRow(usage.index, fileName, 0, text, 0, 0)
  val column = position.column - (usage.text.length - usage.text.trimStart().length)
  val end = column + usage.endOffset - usage.startOffset
  return if (column >= 0 && end <= text.length) {
    UsageRow(usage.index, fileName, position.line + 1, text, column, end)
  } else {
    UsageRow(usage.index, fileName, position.line + 1, text, 0, 0)
  }
}

/** The backend's HTML title ("<b>Method</b>&nbsp;<b>twice(int)</b>…") as plain text. */
internal fun plainText(html: String): String =
  html
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

/**
 * Whether a solution the backend added is the one IDEate shows: the [wanted] project's (paths
 * compared without a trailing `/`), or with none wanted the first one, while none is [showing].
 */
internal fun isShownProject(wanted: String?, hostPath: String?, showing: Boolean): Boolean =
  !showing && (wanted == null || hostPath?.trimEnd('/') == wanted.trimEnd('/'))

/** [entries] with [after] run after each item's own click, also inside submenus. */
internal fun afterEach(entries: List<MenuEntry>, after: () -> Unit): List<MenuEntry> =
  entries.map { entry ->
    when (entry) {
      is MenuItem ->
        entry.copy(
          onClick = {
            entry.onClick()
            after()
          },
        )
      is SubMenu -> entry.copy(entries = afterEach(entry.entries, after))
      else -> entry
    }
  }

/** IntelliJ's welcome project (IdeaHome), which the backend opens when no project was given. */
internal fun isWelcomeProject(solution: Solution?): Boolean =
  solution?.welcomeScreenProject?.valueOrNull != null

/**
 * Queues [action] on the rd thread unless the session has stopped. After [ClientSession.stop] the
 * screen stays up while IDEate reconnects, and the rd thread is gone (`SingleThreadScheduler` ends
 * with the session's lifetime). A tap then has nothing to reach, so it is dropped and logged rather
 * than thrown into the UI as "Task … rejected from … [Terminated …]".
 */
internal fun IScheduler.queueUnlessStopped(log: (String) -> Unit, action: () -> Unit) {
  try {
    queue(action)
  } catch (stopped: RejectedExecutionException) {
    log("Session stopped; dropped an action")
  }
}
