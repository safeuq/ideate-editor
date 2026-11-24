package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.CaretTimestampModel
import com.jetbrains.rd.ide.model.ControlTimestampModel
import com.jetbrains.rd.ide.model.DocOffsetAndVirtualModel
import com.jetbrains.rd.ide.model.DocumentTimestampModel
import com.jetbrains.rd.ide.model.EditorComponentId
import com.jetbrains.rd.ide.model.FileEditorTimestampModel
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.OffsetTimestampModel
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.RangeMarkerTimestampModel
import com.jetbrains.rd.ide.model.RdApplyPatchRequest
import com.jetbrains.rd.ide.model.RdCallEditorActionRequest
import com.jetbrains.rd.ide.model.RdCaret
import com.jetbrains.rd.ide.model.RdCaretId
import com.jetbrains.rd.ide.model.RdCaretState
import com.jetbrains.rd.ide.model.RdCaretStateChange
import com.jetbrains.rd.ide.model.RdCaretStateSnapshot
import com.jetbrains.rd.ide.model.RdCmdGroupId
import com.jetbrains.rd.ide.model.RdCmdId
import com.jetbrains.rd.ide.model.RdCmdItem
import com.jetbrains.rd.ide.model.RdCmdItemVersion
import com.jetbrains.rd.ide.model.RdCmdVersion
import com.jetbrains.rd.ide.model.RdCommandName
import com.jetbrains.rd.ide.model.RdDelGroupId
import com.jetbrains.rd.ide.model.RdDocGroupId
import com.jetbrains.rd.ide.model.RdDocumentChange
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdEditorVersion
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileEditorId
import com.jetbrains.rd.ide.model.RdFileEditorIdStrict
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdPatchCmdFinish
import com.jetbrains.rd.ide.model.RdPatchCmdStart
import com.jetbrains.rd.ide.model.RdPatchDocumentVersion
import com.jetbrains.rd.ide.model.RdPatchEditorVersion
import com.jetbrains.rd.ide.model.RdPatchItem
import com.jetbrains.rd.ide.model.RdPatchItemVersion
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdSelection
import com.jetbrains.rd.ide.model.RdSelectionChangeBase
import com.jetbrains.rd.ide.model.RdStackSizeItem
import com.jetbrains.rd.ide.model.RdStringGroupId
import com.jetbrains.rd.ide.model.RdTextModificationKind
import com.jetbrains.rd.ide.model.RdTextRange
import com.jetbrains.rd.ide.model.RdTypeKeyRequest
import com.jetbrains.rd.ide.model.RdUndoConfirmationPolicy
import com.jetbrains.rd.ide.model.RdUndoMeta
import com.jetbrains.rd.ide.model.RdUndoRedoRequest
import com.jetbrains.rd.ide.model.TextControlId
import com.jetbrains.rd.ide.model.VirtualFileArrayTimestampModel
import com.jetbrains.rd.ide.model.VirtualFileTimestampModel

private val DEBUG = System.getenv("CLIENT_EDIT_DEBUG") != null
private const val CHOOSE_LOOKUP_ITEM = "#Choose Lookup Item"
private val EMPTY_PATCH = RdPatch(emptyList(), emptyList(), emptyList(), emptyList())

/**
 * Ids of the client's commands, shared by all editors: negative and counting down, like JetBrains
 * Client's.
 */
class CommandChain {
  private var nextId = -2L

  /** The id of the last command the client finished; each patch expects it (`RdCmdItemVersion`). */
  var lastFinished = 0L
    private set

  fun next(): Long = nextId--

  fun finished(commandId: Long) {
    lastFinished = commandId
  }
}

/** What the backend needs to identify an open file's editor in commands and actions. */
class EditorIdentity(
  val documentId: RdDocumentId,
  val editorId: TextControlId,
  val fileEditorId: RdFileEditorId,
  val projectId: RdProjectId,
) {
  val fileId
    get() = fileEditorId.compositeId.fileId

  companion object {
    const val TEXT_EDITOR_PROVIDER = "text-editor"

    /**
     * The text editor in a composite. Its index is the composite's, not 1: the second file opened
     * gets composite index 2 and editor `text-editor` index 2 (seen in
     * `FileEditorCompositeModel.editors`, 2026-11-16). With index 1 the backend refused the
     * selection ("Attempt to select already gone editor").
     */
    fun textEditorId(composite: RdFileEditorCompositeId) =
      RdFileEditorId(composite, TEXT_EDITOR_PROVIDER, composite.index)

    /**
     * For an editor the backend opened by itself, the file editor id comes from the text control
     * id: `KernelAwareTextControlId(documentId = file, tabIndex, clientId, projectId)` →
     * `RdFileEditorCompositeId(file, clientId, tabIndex, projectId)`.
     */
    fun of(
      documentId: RdDocumentId,
      editorId: TextControlId,
      knownComposite: RdFileEditorCompositeId?,
    ): EditorIdentity? {
      val control = editorId as? KernelAwareTextControlId ?: return null
      val fileId = (control.documentId as? RdDocumentIdWithFileId)?.fileId ?: return null
      val projectId = control.projectId ?: return null
      val composite =
        knownComposite
          ?: RdFileEditorCompositeId(fileId, control.clientId, control.tabIndex, projectId)
      return EditorIdentity(documentId, editorId, textEditorId(composite), projectId)
    }
  }
}

/**
 * Local-first editing of one open editor over IntelliJ's patch engine, the way JetBrains Client's
 * `FrontendAsyncRequestExecutorNew` does it (docs/edit-protocol-client.md). Each user action is
 * applied here first and sent with its local result as the speculative patch. When an answer
 * differs, or the backend changes the document itself, the pending actions are undone, the
 * backend's change is applied (its versions must match ours before and after), and the pending
 * actions are run again on the new text. Typing never waits for the backend.
 */
class EditSession(
  private val identity: EditorIdentity,
  private val commands: CommandChain,
  /** Sends a request and returns its request id. */
  private val send: (RdRequest) -> Long,
  /**
   * Called after every change of text or caret, with the change (offset, removed length, inserted
   * length) if any.
   */
  private val changed: (TextEdit?) -> Unit,
  /**
   * Called when the versions here no longer match the backend's (a check before or after applying
   * one of its patches failed, or an answer came out of order). The caller then takes the backend's
   * state of every editor ([dropPending], [recovered]).
   */
  private val outOfStep: (String) -> Unit = {},
) {
  data class TextEdit(val offset: Int, val removedLength: Int, val insertedLength: Int) {
    /**
     * Where the range [start]..[end] is after this edit, the way IntelliJ moves a non-greedy range
     * marker (what the backend's highlighters are): text inserted exactly at the end stays outside
     * the range, text inserted exactly at the start pushes the whole range right. Example: `;` at
     * 209..210, Enter typed at 210 → `;` stays 209..210.
     */
    fun shift(start: Int, end: Int): Pair<Int, Int> {
      val delta = insertedLength - removedLength
      val editEnd = offset + removedLength
      val newStart =
        when {
          start >= editEnd -> start + delta
          start > offset -> offset
          else -> start
        }
      val newEnd =
        when {
          end == start -> newStart
          end > editEnd || (end == editEnd && removedLength > 0) -> end + delta
          end > offset -> offset
          else -> end
        }
      return newStart to maxOf(newStart, newEnd)
    }
  }

  /** One user action (a key, a caret move, …): what runs again when pending edits are replayed. */
  private class Action(val run: () -> Unit)

  /**
   * A request the backend hasn't answered yet. [undoState] is the editor state before its local
   * change; [action] made it (null for undo and redo, which the backend's answer carries).
   */
  private class Pending(
    var requestId: Long,
    var request: RdRequest,
    var speculative: RdPatch,
    var undoState: Snapshot,
    var edit: TextEdit?,
    val action: Action?,
    /** How many backend patches and answers were applied when it was sent. */
    var sentAfter: Int,
  ) {
    /** Whether the backend already asked for it again (`RdRetryResponse`). */
    var retried = false
  }

  private data class Snapshot(
    val text: String,
    val version: RdDocumentVersion,
    val caret: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val editorVersion: RdEditorVersion,
  )

  var text = ""
    private set

  var caret = 0
    private set

  var selectionStart = 0
    private set

  var selectionEnd = 0
    private set

  private var documentVersion = RdDocumentVersion(-1, 0)
  private var uniqueId = ""
  private var editorVersion = RdEditorVersion(-1, 0, -1)
  private var undoSizes = listOf(0, 0)
  private var redoSizes = listOf(0, 0)

  /**
   * What is known about the backend's undo stacks. The backend tells their sizes only after an undo
   * or redo (`RdStackSizeItem`), so until the first one they are unknown, and a text edit made
   * since the last one isn't counted in them.
   */
  private var undoSizesKnown = false

  /**
   * How often the backend moved the caret by itself, not in answer to a request: navigation, e.g. a
   * symbol chosen in Search Everywhere puts the caret on `method399` at offset 64440.
   */
  var backendCaretMoves = 0
    private set

  /**
   * Stays true when the edit is later dropped (a failure, or a second retry in [rebase]): Undo can
   * then show enabled with nothing to undo, which does no harm.
   */
  private var editedSinceUndoSizes = false

  /** Undo may do something: unknown counts as yes (the backend may hold older history). */
  val canUndo
    get() =
      canEdit() && undoAvailable(undoSizesKnown, editedSinceUndoSizes, undoSizes.any { it > 0 })

  /** Redo is known to have something: only right after an undo, before the next edit. */
  val canRedo
    get() =
      canEdit() && redoAvailable(undoSizesKnown, editedSinceUndoSizes, redoSizes.any { it > 0 })

  private val pending = ArrayDeque<Pending>()
  /** The action running now, for the requests it sends. */
  private var currentAction: Action? = null
  /**
   * While an action is replayed: its earlier requests, which the replay updates instead of sending.
   */
  private var replayed: ArrayDeque<Pending>? = null
  /**
   * While replaying: whether replayed requests go to the backend again (the retry timestamp moved).
   */
  private var resendReplayed = false
  /** Backend patches and answers applied so far (JetBrains Client's `backendChangesCounter`). */
  private var applied = 0
  /** Between [dropPending] and [recovered]: typing is ignored, backend patches too. */
  private var recovering = false

  val isReady
    get() = documentVersion.value >= 0

  /**
   * What completion requests name: `RdEditorVersion.caretModelVersion` and
   * `RdDocumentVersion.value`.
   */
  val caretModelVersion
    get() = editorVersion.caretModelVersion

  val documentVersionValue
    get() = documentVersion.value

  /** The editor's id in Fleet RPC (`EditorId`): the `kernelEditorId` of its rd text control id. */
  val kernelEditorId
    get() = (identity.editorId as? KernelAwareTextControlId)?.kernelEditorId

  private val whenAnswered = mutableListOf<() -> Unit>()

  /**
   * Runs [action] once the backend has answered every pending edit, so it has the same text and
   * caret as here.
   */
  fun afterPendingEdits(action: () -> Unit) {
    if (pending.isEmpty()) action() else whenAnswered += action
  }

  fun hasPending(requestId: Long) = pending.any { it.requestId == requestId }

  /** Whether a backend patch touches this document or editor. */
  fun concerns(patch: RdPatch): Boolean =
    patch.changes.any { item ->
      (item is RdDocumentChange && item.documentId == identity.documentId) ||
        (item is RdResetDocumentChange && item.documentId == identity.documentId) ||
        (item is RdSelectionChangeBase && item.editorId == identity.editorId)
    }

  // ---- User actions ----

  fun type(character: Char) = perform {
    val (offset, removed) = replacedRange()
    edit(
      offset,
      removed,
      character.toString(),
      offset + 1,
      startName = "#",
      finishName = "#Typing",
      startGroup = RdDocGroupId(identity.fileId),
      finishGroup = RdDocGroupId(identity.fileId),
    ) { patch ->
      RdTypeKeyRequest(character, identity.editorId, patch)
    }
    sendTransparentMarker()
  }

  fun enter() = perform {
    val (offset, removed) = replacedRange()
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    val indent = text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    editorAction(
      "EditorEnter",
      offset,
      removed,
      "\n" + indent,
      offset + 1 + indent.length,
      "#Enter",
      "#Typing",
      null,
      null,
    )
  }

  /**
   * Pastes [text] the way JetBrains Client does (docs/edit-protocol.md, "Paste"): the backend's
   * [actionId] action (`EditorPaste`, or `EditorPasteSimple` for Paste as Plain Text), with the
   * plain insertion as the local guess. The backend pastes from the `ClipboardModel` the session
   * filled just before; when it also reformats, adds imports or moves the caret, its answer differs
   * and replaces the guess.
   */
  fun paste(text: String, actionId: String = "EditorPaste") = perform {
    if (text.isEmpty()) return@perform
    val (offset, removed) = replacedRange()
    editorAction(
      actionId,
      offset,
      removed,
      text,
      offset + text.length,
      "Paste",
      "Paste",
      null,
      null,
    )
  }

  fun backspace() = perform {
    if (selectionStart != selectionEnd) {
      editorAction(
        "EditorBackSpace",
        selectionStart,
        text.substring(selectionStart, selectionEnd),
        "",
        selectionStart,
        "#Backspace",
        "#Backspace",
        null,
        RdDelGroupId(),
      )
    } else if (caret > 0) {
      editorAction(
        "EditorBackSpace",
        caret - 1,
        text.substring(caret - 1, caret),
        "",
        caret - 1,
        "#Backspace",
        "#Backspace",
        null,
        RdDelGroupId(),
      )
    }
  }

  fun delete() = perform {
    if (selectionStart != selectionEnd) {
      editorAction(
        "EditorDelete",
        selectionStart,
        text.substring(selectionStart, selectionEnd),
        "",
        selectionStart,
        "#Delete",
        "#Delete",
        null,
        RdDelGroupId(),
      )
    } else if (caret < text.length) {
      editorAction(
        "EditorDelete",
        caret,
        text.substring(caret, caret + 1),
        "",
        caret,
        "#Delete",
        "#Delete",
        null,
        RdDelGroupId(),
      )
    }
  }

  /** Moves the caret, with a selection when [anchor] differs from [offset]. */
  fun moveCaret(offset: Int, anchor: Int = offset) = perform {
    val target = offset.coerceIn(0, text.length)
    val from = minOf(anchor.coerceIn(0, text.length), target)
    val to = maxOf(anchor.coerceIn(0, text.length), target)
    if (target == caret && from == selectionStart && to == selectionEnd) return@perform
    val before = snapshot()
    val oldState = caretState()
    val editorBefore = editorVersion
    caret = target
    selectionStart = from
    selectionEnd = to
    val newState = caretState()
    editorVersion =
      RdEditorVersion(editorBefore.caretModelVersion + 1, documentVersion.hash, caretHash())
    val commandId = commands.next()
    val patch =
      RdPatch(
        changes =
          listOf(
            commandStart(commandId, "#", null),
            RdCaretStateChange(oldState, newState, identity.editorId),
            commandFinish(commandId, "#", null),
          ),
        features = emptyList(),
        expectedItemVersions =
          listOf(
            commandVersion(),
            RdPatchEditorVersion(editorBefore, identity.editorId),
            documentItemVersion(documentVersion),
          ),
        updatedItemVersions = listOf(RdPatchEditorVersion(editorVersion, identity.editorId)),
      )
    commands.finished(commandId)
    submit(RdApplyPatchRequest(patch), patch, before, null)
    changed(null)
  }

  /**
   * Inserts a completion item like JetBrains Client's `EditorChooseLookupItem`: the typed [prefix]
   * before the caret becomes [lookupString]. Recorded: prefix "gre", item "greeting" → change(218,
   * +"eting"). The backend runs the item's insert handler (parentheses, imports) itself; its extra
   * edits arrive as a different answer, followed here.
   */
  fun chooseLookupItem(prefix: String, lookupString: String) = perform {
    val start = caret - prefix.length
    val group = RdStringGroupId("Completion${lookupString.hashCode()}")
    if (lookupString.startsWith(prefix)) {
      editorAction(
        "EditorChooseLookupItem",
        caret,
        "",
        lookupString.drop(prefix.length),
        start + lookupString.length,
        CHOOSE_LOOKUP_ITEM,
        CHOOSE_LOOKUP_ITEM,
        null,
        group,
      )
    } else {
      editorAction(
        "EditorChooseLookupItem",
        start,
        prefix,
        lookupString,
        start + lookupString.length,
        CHOOSE_LOOKUP_ITEM,
        CHOOSE_LOOKUP_ITEM,
        null,
        group,
      )
    }
  }

  /**
   * Undo or redo. Nothing changes here; the backend's answer carries the change (JetBrains Client
   * undoes locally first, Ideate has no undo history of its own). The stack sizes are the ones
   * expected after it, as the backend compares them after undoing (docs/edit-protocol-backend.md
   * 1.6b): undo moves one step from the undo stacks to the redo stacks, redo the other way.
   */
  fun undo(isUndo: Boolean = true) {
    if (!canEdit()) return
    val step = if (isUndo) -1 else 1
    val expectedUndo = undoSizes.map { maxOf(0, it + step) }
    val expectedRedo = redoSizes.map { maxOf(0, it - step) }
    val request =
      RdUndoRedoRequest(
        RdFileEditorIdStrict(identity.fileEditorId, false),
        listOf(identity.fileId),
        expectedUndo,
        expectedRedo,
        isUndo,
        identity.projectId,
      )
    pending.addLast(
      Pending(send(request), request, EMPTY_PATCH, snapshot(), null, null, applied),
    )
  }

  // ---- From the backend ----

  /**
   * A patch the backend sent on its own (`backendPatches`): opening the file, changes made on the
   * backend, … With edits pending it is applied under them (rebase); dropped while recovering.
   */
  fun backendPatch(patch: RdPatch) {
    debug { "backend patch ${describe(patch)} with ${pending.size} pending" }
    if (recovering) return
    if (patch.changes.any { it is RdCaretStateChange && it.editorId == identity.editorId })
      backendCaretMoves++
    if (pending.isEmpty()) {
      if (applyChecked(patch)) applied++ else outOfStep("backend patch ${describe(patch)}")
    } else rebase(patch, completesHead = false, resend = false)
  }

  /** The answer to request [requestId]; [patch] is null for a refused request. */
  fun response(requestId: Long, patch: RdPatch?) {
    val head = pending.firstOrNull()
    if (head?.requestId != requestId) {
      if (pending.any { it.requestId == requestId })
        outOfStep("answer #$requestId before #${head?.requestId}")
      else debug { "answer #$requestId for no pending request" }
      return
    }
    debug {
      "answer #$requestId ${patch?.let(::describe)} same=${patch != null && sameEdit(head.speculative, patch)}"
    }
    if (patch != null && sameEdit(head.speculative, patch)) {
      // Confirmed: the local text is already right. Its versions are those after this edit, so
      // they are ours only when nothing later is pending; otherwise ours already went further.
      pending.removeFirst()
      if (pending.isEmpty()) takeVersions(patch)
      else for (item in patch.changes) if (item is RdStackSizeItem) applyItem(item)
      applied++
    } else rebase(patch, completesHead = true, resend = false)
    runWhenAnswered()
  }

  private fun runWhenAnswered() {
    if (pending.isEmpty() && whenAnswered.isNotEmpty())
      whenAnswered.toList().also { whenAnswered.clear() }.forEach { it() }
  }

  /**
   * The backend refused request [requestId] (`RdFailureResponse`, `RdNoEditorFoundResponse`): like
   * JetBrains Client, an answer without a patch. Its local change is undone, the later ones
   * replayed.
   */
  fun failure(requestId: Long) = response(requestId, null)

  /**
   * The backend asked for request [requestId] again (`RdRetryResponse`; the caller already took its
   * new retry timestamp). It drops, without an answer, every request sent with the older timestamp,
   * so every pending request goes again. A second retry of the same request gives up on it: its
   * local change is undone and the rest replayed and sent again (`FrontendAsyncRequestExecutorNew.
   * handleResponse`, docs/edit-protocol-client.md 4.4).
   */
  fun retry(requestId: Long) {
    val head = pending.firstOrNull() ?: return
    // A stale retry (not the oldest request, or something was applied since it went) only resends.
    if (head.requestId != requestId || head.sentAfter != applied) return resendPending()
    if (head.retried) {
      rebase(null, completesHead = true, resend = true)
      return runWhenAnswered()
    }
    head.retried = true
    resendPending()
  }

  /** Sends every pending request again: the retry timestamp moved (here or for another editor). */
  fun resendPending() {
    for (request in pending) {
      request.requestId = send(request.request)
      request.sentAfter = applied
    }
  }

  /** The editor's id in the rd protocol, as `getEditorStates` takes it. */
  val editorId
    get() = identity.editorId

  /** Forgets the pending edits and ignores typing until [recovered]. */
  fun dropPending() {
    rollBackPending()
    pending.clear()
    recovering = true
  }

  /**
   * The backend's state of every open editor (`getEditorStates`: a reset of the text and the
   * caret), or null if it didn't come. Taken without checks, starting from its expected versions.
   */
  fun recovered(state: RdPatch?) {
    if (state != null) {
      takeVersions(state.expectedItemVersions)
      for (item in state.changes) applyItem(item)
      takeVersions(state)
      changed(null)
    }
    recovering = false
    applied++
  }

  // ---- Internals ----

  private fun canEdit() = isReady && !recovering

  /** Runs a user action, remembered for replay with the requests it sends. */
  private fun perform(body: () -> Unit) {
    if (!canEdit()) return
    currentAction = Action(body)
    try {
      body()
    } finally {
      currentAction = null
    }
  }

  /**
   * JetBrains Client's rebase (`SpeculativeQueueProcessor.rebase`): undo every pending local
   * change, apply the backend's [patch] (null: a refused request), drop the answered request if
   * [completesHead], then run the remaining actions again on the new text. [resend]: the retry
   * timestamp moved, so the replayed requests go to the backend again.
   */
  private fun rebase(patch: RdPatch?, completesHead: Boolean, resend: Boolean) {
    val earlier = pending.toList()
    rollBackPending()
    pending.clear()
    if (patch != null && !applyChecked(patch)) {
      outOfStep("${if (completesHead) "answer" else "backend patch"} ${describe(patch)}")
      return
    }
    applied++
    if (completesHead) replay(earlier.drop(1), resend, done = earlier.first().action)
    else replay(earlier, resend, done = null)
  }

  /**
   * Runs the actions of [earlier] again. Requests of the [done] action (the one just answered,
   * whose result came from the backend) are kept as they are, like the marker sent after a key.
   */
  private fun replay(earlier: List<Pending>, resend: Boolean, done: Action?) {
    var index = 0
    while (index < earlier.size) {
      val first = earlier[index]
      val action = first.action
      if (action == null || action === done) {
        // Undo/redo, or the rest of the answered action: nothing local to redo.
        first.undoState = snapshot()
        if (resend) {
          first.requestId = send(first.request)
          first.sentAfter = applied
        }
        pending.addLast(first)
        index++
        continue
      }
      val group = earlier.drop(index).takeWhile { it.action === action }
      index += group.size
      replayed = ArrayDeque(group)
      resendReplayed = resend
      currentAction = action
      try {
        action.run()
      } finally {
        currentAction = null
      }
      // Requests the replay didn't make again (Backspace at the start of the text, …): still sent,
      // so the backend answers them; nothing local is expected of them now.
      for (left in replayed.orEmpty()) {
        left.speculative = EMPTY_PATCH
        left.edit = null
        left.undoState = snapshot()
        if (resend) {
          left.requestId = send(left.request)
          left.sentAfter = applied
        }
        pending.addLast(left)
      }
      replayed = null
    }
  }

  private fun replacedRange(): Pair<Int, String> =
    if (selectionStart != selectionEnd)
      selectionStart to text.substring(selectionStart, selectionEnd)
    else caret to ""

  private fun editorAction(
    actionId: String,
    offset: Int,
    oldText: String,
    newText: String,
    caretAfter: Int,
    startName: String,
    finishName: String,
    startGroup: RdCmdGroupId?,
    finishGroup: RdCmdGroupId?,
  ) {
    val timestamps = actionTimestamps()
    edit(offset, oldText, newText, caretAfter, startName, finishName, startGroup, finishGroup) {
      patch ->
      RdCallEditorActionRequest(actionId, identity.editorId, patch, timestamps)
    }
    sendTransparentMarker()
  }

  private fun edit(
    offset: Int,
    oldText: String,
    newText: String,
    caretAfter: Int,
    startName: String,
    finishName: String,
    startGroup: RdCmdGroupId?,
    finishGroup: RdCmdGroupId?,
    request: (RdPatch) -> RdRequest,
  ) {
    val before = snapshot()
    val stateBefore = caretState()
    val editorBefore = editorVersion
    val oldVersion = documentVersion
    // Replacing a selection is two document changes on the backend: delete the selection, then
    // insert. Recorded:
    // typing 'x' over ");" at 208 answers change(208, -');', +'') v0->1, then change(208, +'x')
    // v1->2.
    val parts =
      if (oldText.isNotEmpty() && newText.isNotEmpty()) listOf(oldText to "", "" to newText)
      else listOf(oldText to newText)
    val documentChanges = parts.map { (removed, inserted) ->
      val from = documentVersion
      val to =
        RdDocumentVersion(
          from.value + 1,
          VersionHashes.change(from.hash, offset, offset, text.length, removed, inserted),
        )
      val change =
        RdDocumentChange(
          offset,
          offset,
          inserted,
          removed,
          RdTextModificationKind.NotSpecified,
          text.length,
          null,
          false,
          identity.documentId,
          to,
          from,
          uniqueId,
        )
      text = text.replaceRange(offset, offset + removed.length, inserted)
      documentVersion = to
      change
    }
    val newVersion = documentVersion
    caret = caretAfter
    selectionStart = caretAfter
    selectionEnd = caretAfter
    editorVersion =
      RdEditorVersion(editorBefore.caretModelVersion + 2, newVersion.hash, caretHash())
    val commandId = commands.next()
    val patch =
      RdPatch(
        changes =
          listOf(
            commandStart(commandId, startName, startGroup),
            RdCaretStateSnapshot(true, stateBefore, identity.editorId),
            *documentChanges.toTypedArray(),
            RdCaretStateSnapshot(false, caretState(), identity.editorId),
            commandFinish(commandId, finishName, finishGroup),
          ),
        features = emptyList(),
        expectedItemVersions =
          listOf(
            commandVersion(),
            RdPatchEditorVersion(editorBefore, identity.editorId),
            documentItemVersion(oldVersion),
          ),
        updatedItemVersions =
          listOf(
            RdPatchEditorVersion(editorVersion, identity.editorId),
            documentItemVersion(newVersion),
          ),
      )
    commands.finished(commandId)
    val textEdit = TextEdit(offset, oldText.length, newText.length)
    submit(request(patch), patch, before, textEdit)
    changed(textEdit)
  }

  /**
   * After every edit command JetBrains Client sends an empty transparent command; it advances the
   * command chain.
   */
  private fun sendTransparentMarker() {
    val commandId = commands.next()
    val meta = undoMeta()
    val patch =
      RdPatch(
        changes =
          listOf(
            RdCmdItem(
              RdPatchCmdStart(
                commandId,
                null,
                null,
                RdCommandName("#"),
                RdUndoConfirmationPolicy.DEFAULT,
                true,
                meta,
              ),
            ),
            RdCmdItem(
              RdPatchCmdFinish(
                commandId,
                null,
                null,
                RdCommandName("#"),
                RdUndoConfirmationPolicy.DEFAULT,
                true,
                meta,
              ),
            ),
          ),
        features = emptyList(),
        expectedItemVersions = listOf(commandVersion()),
        updatedItemVersions = emptyList(),
      )
    commands.finished(commandId)
    submit(RdApplyPatchRequest(patch), patch, snapshot(), null)
  }

  private fun submit(request: RdRequest, patch: RdPatch, before: Snapshot, edit: TextEdit?) {
    // A text change is a new undo step on the backend, and it empties the redo stack.
    if (edit != null) editedSinceUndoSizes = true
    val again = replayed?.removeFirstOrNull()
    if (again != null) {
      again.request = request
      again.speculative = patch
      again.undoState = before
      again.edit = edit
      if (resendReplayed) {
        again.requestId = send(request)
        again.sentAfter = applied
      }
      pending.addLast(again)
      debug { "replayed #${again.requestId} ${describe(patch)}" }
      return
    }
    val requestId = send(request)
    debug {
      "send #$requestId ${request::class.simpleName} ${describe(patch)} -> text ${text.length} chars, caret $caret ($selectionStart..$selectionEnd)"
    }
    pending.addLast(Pending(requestId, request, patch, before, edit, currentAction, applied))
  }

  /**
   * A patch for the log: the editor's caret version and the document's value it expects and leaves,
   * ours now, then its items. Example: `expects [3, 1] leaves [5, 2] ours 7/3 [caret(159), …]`.
   */
  private fun describe(patch: RdPatch) =
    "expects ${versionNumbers(patch.expectedItemVersions)} leaves ${versionNumbers(patch.updatedItemVersions)} " +
      "ours ${editorVersion.caretModelVersion}/${documentVersion.value} ${describeItems(patch)}"

  private fun versionNumbers(versions: List<RdPatchItemVersion>) = versions.mapNotNull {
    (it as? RdPatchEditorVersion)?.version?.caretModelVersion
      ?: (it as? RdPatchDocumentVersion)?.version?.value
  }

  private fun describeItems(patch: RdPatch) =
    patch.changes.mapNotNull { item ->
      when (item) {
        is RdDocumentChange ->
          "change(${item.offset}, -'${item.oldText.replace("\n", "\\n")}', +'${item.newText.replace("\n", "\\n")}', len ${item.oldDocumentLength}, v${item.oldVersion.value}->${item.newVersion.value}, whole=${item.isWholeTextReplaced})"
        is RdResetDocumentChange -> "reset(${item.text.length} chars, v${item.newVersion.value})"
        is RdCaretStateSnapshot ->
          "caret(${item.state.carets.firstOrNull()?.let { "${it.offset.offset} ${it.selection.start.offset}..${it.selection.end.offset}" }})"
        is RdCaretStateChange ->
          "caretChange(${item.newState.carets.firstOrNull()?.let { "${it.offset.offset} ${it.selection.start.offset}..${it.selection.end.offset}" }})"
        else -> null
      }
    }

  private inline fun debug(message: () -> String) {
    if (DEBUG) println("[edit] ${message()}")
  }

  /** Undoes all pending local edits (newest first); [pending] itself is left as it is. */
  private fun rollBackPending() {
    val oldest = pending.firstOrNull() ?: return
    // Tell the highlight bookkeeping how the text moves back, newest edit first.
    for (request in pending.reversed()) request.edit?.let {
      changed(TextEdit(it.offset, it.insertedLength, it.removedLength))
    }
    restore(oldest.undoState)
  }

  /**
   * Applies a backend patch like JetBrains Client (`applyBackendChangesOnly`): our versions must
   * equal its expected ones before and its updated ones after, computed here item by item. False
   * when they don't: Ideate and the backend are out of step.
   */
  private fun applyChecked(patch: RdPatch): Boolean {
    if (!versionsMatch(patch.expectedItemVersions, "expected")) return false
    for (item in patch.changes) applyItem(item)
    // Compared before the listeners hear of it: one of them puts a saved caret back
    // (ClientSession.restoreCaret → moveCaret), which moves our editor version one step on.
    val matches = versionsMatch(patch.updatedItemVersions, "updated")
    changed(null)
    return matches
  }

  /**
   * Whether our document and editor versions are [versions] (only the entries for this editor and
   * its document). Not checked before the first version came (opening the file).
   */
  private fun versionsMatch(versions: List<RdPatchItemVersion>, what: String): Boolean {
    for (version in versions) {
      val (ours, theirs) =
        when {
          version is RdPatchDocumentVersion && version.id == identity.documentId ->
            documentVersion.takeIf { it.value >= 0 } to version.version
          version is RdPatchEditorVersion && version.id == identity.editorId ->
            editorVersion.takeIf { it.caretModelVersion >= 0 } to version.version
          else -> null to null
        }
      if (ours != null && ours != theirs) {
        println("[edit] $what version: ours $ours, backend's $theirs")
        return false
      }
    }
    return true
  }

  private fun applyItem(item: RdPatchItem) {
    when (item) {
      is RdResetDocumentChange ->
        if (item.documentId == identity.documentId) {
          val oldLength = text.length
          text = item.text
          documentVersion = item.newVersion
          uniqueId = item.uniqueId
          changed(TextEdit(0, oldLength, text.length))
        }
      is RdDocumentChange ->
        if (item.documentId == identity.documentId) {
          val end = (item.offset + item.oldText.length).coerceAtMost(text.length)
          text = text.replaceRange(item.offset, end, item.newText)
          documentVersion = item.newVersion
          changed(TextEdit(item.offset, item.oldText.length, item.newText.length))
        }
      is RdSelectionChangeBase ->
        if (item.editorId == identity.editorId) {
          val state =
            when (item) {
              is RdCaretStateSnapshot -> item.state
              is RdCaretStateChange -> item.newState
              else -> null
            }
          state?.carets?.firstOrNull()?.let { primary ->
            caret = primary.offset.offset
            selectionStart = primary.selection.start.offset
            selectionEnd = primary.selection.end.offset
          }
          // One step per caret item, as both sides count (docs/edit-protocol-backend.md 4.2).
          if (state != null)
            editorVersion =
              RdEditorVersion(
                editorVersion.caretModelVersion + 1,
                state.documentVersion.hash,
                caretHash(),
              )
        }
      is RdStackSizeItem -> {
        undoSizesKnown = true
        editedSinceUndoSizes = false
        if (item.fileId == null) {
          undoSizes = listOf(item.undoSize, undoSizes[1])
          redoSizes = listOf(item.redoSize, redoSizes[1])
        } else {
          undoSizes = listOf(undoSizes[0], item.undoSize)
          redoSizes = listOf(redoSizes[0], item.redoSize)
        }
      }
      else -> Unit
    }
  }

  /** The backend's versions win over our own computation. */
  private fun takeVersions(patch: RdPatch) {
    takeVersions(patch.updatedItemVersions)
    for (item in patch.changes) if (item is RdStackSizeItem) applyItem(item)
  }

  private fun takeVersions(versions: List<RdPatchItemVersion>) {
    for (version in versions) {
      when {
        version is RdPatchEditorVersion && version.id == identity.editorId ->
          editorVersion = version.version
        version is RdPatchDocumentVersion && version.id == identity.documentId ->
          documentVersion = version.version
      }
    }
  }

  /** Same document changes and same final caret: the backend did exactly what we did locally. */
  private fun sameEdit(speculative: RdPatch, answer: RdPatch): Boolean {
    fun edits(patch: RdPatch) =
      patch.changes.filterIsInstance<RdDocumentChange>().map {
        Triple(it.offset, it.oldText, it.newText)
      }
    fun lastCaret(patch: RdPatch) =
      patch.changes
        .filterIsInstance<RdSelectionChangeBase>()
        .lastOrNull()
        ?.let { item ->
          (item as? RdCaretStateSnapshot)?.state ?: (item as? RdCaretStateChange)?.newState
        }
        ?.carets
        ?.firstOrNull()
        ?.let { Triple(it.offset.offset, it.selection.start.offset, it.selection.end.offset) }
    return edits(speculative) == edits(answer) &&
      (lastCaret(speculative) == null || lastCaret(speculative) == lastCaret(answer))
  }

  private fun snapshot() =
    Snapshot(text, documentVersion, caret, selectionStart, selectionEnd, editorVersion)

  private fun restore(snapshot: Snapshot) {
    text = snapshot.text
    documentVersion = snapshot.version
    caret = snapshot.caret
    selectionStart = snapshot.selectionStart
    selectionEnd = snapshot.selectionEnd
    editorVersion = snapshot.editorVersion
  }

  private fun caretState(): RdCaretState {
    val id = RdCaretId(true, 1)
    fun position(offset: Int) = DocOffsetAndVirtualModel(offset, 0, false)
    return RdCaretState(
      id,
      listOf(
        RdCaret(id, position(caret), RdSelection(position(selectionStart), position(selectionEnd))),
      ),
      documentVersion,
    )
  }

  private fun caretHash() = VersionHashes.caret(1, caret, selectionStart, selectionEnd)

  private fun commandVersion() = RdCmdItemVersion(RdCmdVersion(commands.lastFinished), RdCmdId())

  private fun documentItemVersion(version: RdDocumentVersion) =
    RdPatchDocumentVersion(version, uniqueId, identity.documentId)

  private fun undoMeta(): List<RdUndoMeta> {
    val strict = RdFileEditorIdStrict(identity.fileEditorId, false)
    return listOf(
      RdUndoMeta(null, strict, emptyList(), false),
      RdUndoMeta(identity.projectId, strict, emptyList(), false),
    )
  }

  private fun commandStart(commandId: Long, name: String, group: RdCmdGroupId?) =
    RdCmdItem(
      RdPatchCmdStart(
        commandId,
        identity.projectId,
        group,
        RdCommandName(name),
        RdUndoConfirmationPolicy.DEFAULT,
        false,
        undoMeta(),
      ),
    )

  private fun commandFinish(commandId: Long, name: String, group: RdCmdGroupId?) =
    RdCmdItem(
      RdPatchCmdFinish(
        commandId,
        identity.projectId,
        group,
        RdCommandName(name),
        RdUndoConfirmationPolicy.DEFAULT,
        false,
        undoMeta(),
      ),
    )

  /** The state the action is invoked in (caret before the action), as JetBrains Client sends it. */
  fun actionTimestamps(): ActionTimestampSetModel {
    val document = DocumentTimestampModel(identity.documentId, documentVersion)
    return ActionTimestampSetModel(
      listOf(
        com.jetbrains.rd.ide.model.EditorTimestampModel(
          identity.editorId,
          listOf(
            CaretTimestampModel(
              RangeMarkerTimestampModel(RdTextRange(selectionStart, selectionEnd), true, document),
              OffsetTimestampModel(caret, false, document),
            ),
          ),
        ),
        FileEditorTimestampModel(identity.fileEditorId),
        ProjectTimestampModel(identity.projectId),
        VirtualFileTimestampModel(identity.fileId),
        ControlTimestampModel(EditorComponentId(identity.editorId)),
        VirtualFileArrayTimestampModel(listOf(identity.fileId)),
      ),
    )
  }
}

/**
 * Whether Undo may do something. Examples: nothing known yet → true; sizes known and all 0 → false;
 * sizes 0 but typed since → true.
 */
internal fun undoAvailable(sizesKnown: Boolean, editedSince: Boolean, anyUndo: Boolean) =
  !sizesKnown || editedSince || anyUndo

/** Whether Redo has something: the sizes are known, say so, and nothing was typed since. */
internal fun redoAvailable(sizesKnown: Boolean, editedSince: Boolean, anyRedo: Boolean) =
  sizesKnown && !editedSince && anyRedo
