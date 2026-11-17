package intellijandroid.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.codeWithMe.model.RdFindFileParams
import com.jetbrains.codeWithMe.model.RdProjectViewAsyncPane
import com.jetbrains.codeWithMe.model.RdProjectViewItem
import com.jetbrains.codeWithMe.model.SyntaxHighlighterModel
import com.jetbrains.codeWithMe.model.VfsModel
import com.jetbrains.codeWithMe.model.editorTabsModel
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
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.ide.model.ClipboardContent
import com.jetbrains.rd.ide.model.ClipboardModel
import com.jetbrains.rd.ide.model.CompositionIconModel
import com.jetbrains.rd.ide.model.DeferredIconModel
import com.jetbrains.rd.ide.model.EmptyIconModel
import com.jetbrains.rd.ide.model.FailedRequestComposite
import com.jetbrains.rd.ide.model.FileEditorManagerEventModel
import com.jetbrains.rd.ide.model.GutterMarkHighlighterModel
import com.jetbrains.rd.ide.model.HighlighterElement
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.ImageIconModel
import com.jetbrains.rd.ide.model.MarkupDocumentExtension
import com.jetbrains.rd.ide.model.MarkupTextControlExtension
import com.jetbrains.rd.ide.model.OriginKind
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.ProjectViewTimestampModel
import com.jetbrains.rd.ide.model.RdCodeWithMeProject
import com.jetbrains.rd.ide.model.RdDocumentChangeBase
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileEditorId
import com.jetbrains.rd.ide.model.RdFileIdNew
import com.jetbrains.rd.ide.model.RdMarkupKey
import com.jetbrains.rd.ide.model.RdMarkupModel
import com.jetbrains.rd.ide.model.RdOpenSolution
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdRequestHolder
import com.jetbrains.rd.ide.model.RdRequests
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdRetryTimestamp
import com.jetbrains.rd.ide.model.RdSuccessResponse
import com.jetbrains.rd.ide.model.RequestCompositeQuery
import com.jetbrains.rd.ide.model.RequestResponseModel
import com.jetbrains.rd.ide.model.RowIconModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.SuccessfulRequestComposite
import com.jetbrains.rd.ide.model.TextControlId
import com.jetbrains.rd.ide.model.VisibleRange
import com.jetbrains.rd.ide.model.asyncActionsModel
import com.jetbrains.rd.ide.model.clipboardModel
import com.jetbrains.rd.ide.model.codeStructureTreeModel
import com.jetbrains.rd.ide.model.documentsOperationModel
import com.jetbrains.rd.ide.model.fileEditorsModel
import com.jetbrains.rd.ide.model.fleetRpcModel
import com.jetbrains.rd.ide.model.requestResponseModel
import com.jetbrains.rd.ide.model.solutionModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.Connection
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.connection.SplitWire
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
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
  private val unhandledChangeKinds = mutableSetOf<String>()
  /** Documents whose markup changed since the last publish; see [publishMarkupLater]. */
  private val markupChanged = mutableSetOf<RdDocumentId>()
  private val markupTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "markup-publish").apply { isDaemon = true }
  }
  private var projectId: RdProjectId? = null
  private var solution: Solution? = null
  private var vfs: VfsModel? = null
  private var pane: RdProjectViewAsyncPane? = null
  private val compositeIds = mutableMapOf<String, RdFileEditorCompositeId>()
  /**
   * The tabs' order: files in the order they were asked to open, the reopened ones first in their
   * saved order. Tabs appear as their documents arrive, which can be in any order.
   */
  private val tabOrder = mutableListOf<String>()
  /** Saved editors still being reopened; [saveEditors] waits for them so it doesn't drop any. */
  private val reopening = mutableSetOf<String>()
  /** Saved carets of reopened files, put back once each file's edit session is ready. */
  private val caretsToRestore = mutableMapOf<String, Int>()
  /** How often each file's caret was put back, for [EditorFile.caretRestored]. */
  private val caretsRestored = mutableMapOf<String, Int>()
  private var saveScheduled = false
  private val editSessions = mutableMapOf<RdDocumentId, EditSession>()
  /**
   * Backend patches for a document that arrived before its editor existed (normally the editor
   * comes first).
   */
  private val earlyPatches = mutableMapOf<RdDocumentId, MutableList<RdPatch>>()
  private val commands = CommandChain()
  private var requestIds = 0L
  private var fleet: FleetRpc? = null
  private var completion: Completion? = null
  private var requestResponse: RequestResponseModel? = null
  private var actionMenus: ActionMenus? = null
  private var luxDialogs: LuxDialogs? = null
  private var backendPopups: BackendPopups? = null
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

  /** Opens a file of the backend in an editor tab, or switches to its tab if it is already open. */
  fun open(path: String) = onRd {
    if (mutableState.value.editors.any { it.path == path }) {
      showEditor(path)
    } else {
      openFile(path)
    }
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
    rdScheduler.queue { runCatching(action).onFailure(::fail) }
  }

  private fun createModels(protocol: IProtocol) {
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
      val handled = editSessions.values.filter { it.concerns(patch) }
      handled.forEach { it.backendPatch(patch) }
      for (change in patch.changes) {
        val documentId = (change as? RdDocumentChangeBase)?.documentId ?: continue
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
        publish = { serial, entries ->
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
        if (actionMenus?.response(response) == true) continue
        val session =
          editSessions.values.firstOrNull { it.hasPending(response.requestId) } ?: continue
        when (response) {
          is RdSuccessResponse -> session.response(response.requestId, response.patch)
          else -> {
            log("Request ${response.requestId} not applied: $response")
            session.retryOrFailure(response.requestId)
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
        createEditSession(controlLifetime, documentId, controlId)
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

    vfs = protocol.vfsModel
    bindClipboard(protocol.clipboardModel)
    protocol.solutionModel.solutions.view(lifetime) { solutionLifetime, _, newSolution ->
      log("Project ${newSolution.projectId.value} added")
      projectId = newSolution.projectId
      solution = newSolution
      newSolution.solutionLifecycle.readyForExtsRegistration.set(true)
      // Fleet RPC names projects by the kernel id, e.g. "m3nniruk9k7j4qsibjui", not by the rd
      // project id.
      val kernelProjectId =
        ((newSolution.solutionOpenStrategy as? RdOpenSolution)?.description as? RdCodeWithMeProject)
          ?.kernelProjectId
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
      }
      newSolution.editorTabsModel
      // The backend lists each open file's editor here; the client marks the one it shows as
      // visible.
      newSolution.fileEditorsModel.backendDeferredComposites.view(solutionLifetime) {
        compositeLifetime,
        compositeId,
        deferred ->
        deferred.model.advise(compositeLifetime) { composite ->
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
      createProjectView(solutionLifetime, newSolution)
      bindStructure(solutionLifetime, newSolution)
      newSolution.isLoaded.advise(solutionLifetime) { loaded ->
        if (!loaded) return@advise
        reopenEditors()
        if (initialFile != null) openFile(initialFile)
      }
    }
  }

  /** Whether the Structure tool window is showing; the backend builds the tree only while it is. */
  private var structureVisible = false

  fun setStructureVisible(visible: Boolean) = onRd {
    structureVisible = visible
    val current = solution ?: return@onRd
    current.codeStructureTreeModel.toolWindowVisible.set(visible)
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
   * Reopens the editors open when this project was last left, in their tab order, and selects the
   * one that was selected, as JetBrains Client does on connecting (see [OpenEditors]). A file that
   * is gone is skipped and dropped from the saved list. [initialFile], when given, opens on top.
   */
  private fun reopenEditors() {
    val saved = savedEditors?.read() ?: return
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
    if (reopening.isNotEmpty()) return
    val files = tabOrder.filter { it in compositeIds }
    val carets =
      files
        .mapNotNull { path -> (caretsToRestore[path] ?: caretOf(path))?.let { path to it } }
        .toMap()
    slot.save(SavedEditors(files, mutableState.value.selectedEditor, carets))
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
        rdScheduler.queue {
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

  private fun createEditSession(
    controlLifetime: Lifetime,
    documentId: RdDocumentId,
    controlId: TextControlId,
  ) {
    val identity =
      EditorIdentity.of(documentId, controlId, compositeIds[pathOf(documentId)])
        ?: return log("Cannot edit $controlId: unknown editor id type")
    val session =
      EditSession(identity, commands, ::sendRequest) { edit ->
        edit?.let { shiftHighlights(documentId, it) }
        publishDocument(documentId)
        restoreCaret(pathOf(documentId), editSessions[documentId] ?: return@EditSession)
        saveEditorsSoon()
      }
    editSessions[documentId] = session
    earlyPatches.remove(documentId)?.forEach(session::backendPatch)
    controlLifetime.onTermination {
      if (editSessions[documentId] === session) editSessions.remove(documentId)
    }
  }

  private fun sendRequest(request: RdRequest): Long {
    val requestId = ++requestIds
    val model = requestResponse ?: error("requestResponseModel not ready")
    // RdRequests.timestamp: wall clock in epoch nanoseconds, like JetBrains Client.
    val now = java.time.Instant.now()
    model.requestsWithTime.fire(
      RdRequests(
        listOf(RdRequestHolder(requestId, request, RdRetryTimestamp(0, 0), null)),
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
        rdScheduler.queue {
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
    val session = editSessions[documentId]?.takeIf { it.isReady }
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
        caretRestored = caretsRestored[pathOf(documentId)] ?: 0,
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
  }

  /** Where [path]'s tab goes; a file opened some other way goes last. */
  private fun tabIndex(path: String): Int =
    tabOrder.indexOf(path).let { if (it < 0) Int.MAX_VALUE else it }

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

    /** About two frames at 60 Hz. */
    const val MARKUP_DELAY_MS = 32L
    /** How long after an edit or caret move the open editors and carets are saved. */
    const val SAVE_DELAY_MS = 1_000L

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
      )

    private val unhandledIconTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun iconPaths(icon: IconModel): List<String> =
      when (icon) {
        is ImageIconModel -> listOf(icon.id.path)
        is RowIconModel -> icon.subNodes.flatMap(::iconPaths)
        // Layered icon (e.g. the module file): the first layer is the base icon.
        is CompositionIconModel -> icon.subNodes.flatMap(::iconPaths)
        // Shown until the real icon is computed; the backend sends the final one as an item update.
        is DeferredIconModel -> icon.baseIcon?.let(::iconPaths).orEmpty()
        // A blank slot, e.g. the visibility place in a Structure row's icon.
        is EmptyIconModel -> emptyList()
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
