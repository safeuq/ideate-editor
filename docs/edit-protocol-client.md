# JetBrains Client: how edits go to the backend and how the local document stays in step

Read from IntelliJ IDEA 2026.3 (263.7307.200) jars, decompiled, on 2026-11-18. The backend's side is in [`edit-protocol-backend.md`](edit-protocol-backend.md); what IDEate does with both is in [`edit-protocol.md`](edit-protocol.md) section 12.

Source: IntelliJ IDEA (decompiled) `intellij.rd.client.jar` (`com.jetbrains.rdclient.*`), `intellij.rd.platform.jar`
(`com.jetbrains.rd.ide.*`), `intellij.platform.frontend.split.jar` (`com.jetbrains.thinclient.*`),
`intellij.platform.frontend.split.base.jar`, `intellij.rd.client.base.jar`, `intellij.rd.ide.model.generated.jar`.
Tags: **[Seen code]** = read in decompiled code or bytecode. **[Inferred]** = reasoned from code, not read directly.

Decompile gaps: `FrontendAsyncRequestExecutorNew`, `FrontendAsyncRequestExecutor` (facade), `FrontendAsyncRequestExecutorHelper`,
`FrontendAsyncRequestWithImmediateResult.redoInternal`, `FrontendTypeKeyRequest.doRedo`, `FrontendCallEditorActionRequest.doRedo`,
`SpeculativeUndoServiceImpl.undoAndCollectPatch` and `LocalTypeKeyRequestFactory.typeFrontend` crash Fernflower. I read these with
`javap -c`.

Short names below: `New` = `FrontendAsyncRequestExecutorNew`, `Old` = `FrontendAsyncRequestExecutorOld`,
`Queue` = `SpeculativeQueueProcessor`.

---------------------------------------------------------------------------------------------------------------------

## 0. Which executor runs, and how Old differs

* `FrontendAsyncRequestExecutor.impl_delegate$lambda$0` picks the implementation:
  `PatchEngineRegistryKt.isPatchEngineNewAsyncRequestExecutorEnabled()` ? `New` : `Old`. [Seen code]
* That function is `Registry.is("rdclient.patch.engine.new.async.request.executor.enabled", false)`; the code fallback is `false`, but the
  registry key is declared in `intellij.rd.client.jar` and `intellij.idea.ultimate.customization.jar` plugin XML with
  `defaultValue="true" restartRequired="true"` ("Does not enable it in Rider"). So **`New` is the default** in IntelliJ IDEA. [Seen code]
* `isPatchEngineEnabled()` is hard-coded `true`; `isTypingSessionEnabled()` is hard-coded `false` (`PatchEngineRegistryKt`). [Seen code]
* Key differences, `Old` vs `New` (the rest of this file describes `New` unless it says otherwise): [Seen code]

| Topic | `New` (default) | `Old` |
|---|---|---|
| Requests in flight | no limit; each is sent as soon as submitted | one batch at a time (`executionInProgress`) |
| Batching | none: one `RdRequests` holds exactly one `RdRequestHolder` (`createRequestWrapper$lambda$0$send`) | up to `MAX_BATCH_SIZE = 10` (`FrontendAsyncRequestExecutorKt`), only if `isBatchingAllowed()` and the same `ClientId` (`Old.executeNextRequest`) |
| Merging queued requests | none (`tryMergeWith` not called) | `Old.submitRequest` merges the last pending request with the new one via `FrontendAsyncRequest.tryMergeWith` (only caret-only `FrontendApplyPatchRequest`s merge) |
| Retries | at most 1 resend, then the request is dropped (see 4.4) | `retryCounter > 1` gives up (same limit `MAX_RETRY_TRIALS = 1`) |
| Response ordering/rebase | `Queue.process` fast path + `rebase` | `rollbackConflictingRequests` / `rollForwardRevertedRequests` |
| `RdFailureResponse` | success with empty patch, speculation is rolled back, no IDE error | `handleFailure`: logs `Logger.error("Can't execute ...")`, undoes the request |

---------------------------------------------------------------------------------------------------------------------

## 1. Request lifecycle

### 1.1 Common pipeline [Seen code]

1. The user action runs a **local, recording** pass first: the normal IntelliJ handler edits the local document/caret model while a
   patch accumulates (`PatchEngine.openAccumulatingSession(sendOnClose=false)` ... `session.buildPatchAndClear()`).
   * Document edits arrive through `PatchEngineDocumentSynchronizer.documentChanged` (bumps the document version, pushes an
     `RdDocumentChange` with `sendDocumentChange`).
   * Caret/selection changes arrive through `PatchEngineEditorSynchronizer.sendCaretState` (bumps the editor version, pushes a caret item).
   * Command start/finish arrive through `FrontendUndoSpy.pushStart / pushFinish` (cmd items + the expected cmd version).
2. The patch goes into a request object (a subclass of `FrontendAsyncRequest`).
3. `FrontendAsyncRequestExecutor.submitRequest` (EDT only) hands it to `New.submitRequest`, which: assigns `id = requestIdCounter.incrementAndGet()`
   (counter starts at -1, so the first id is **0**), sets status `PENDING`, wraps it in `SpeculativeRequestWrapper`, and
   `Queue.submitRequest(wrapper, sendImmediately = true)` which calls `wrapper.send(globalRetryTimestamp)`. [Seen code]
4. `createRequestWrapper$lambda$0$send` builds the wire message: [Seen code]
   `RdRequests(requests = [RdRequestHolder(id, request.createModel(), globalRetryTimestamp.toModel(), data=null)], timestamp = TelemetryDataUtilKt.getCurrentTime())`
   and fires it on `RequestResponseModel.requestsWithTime`; sets status `EXECUTING`; sets `wrapper.backendChangeMarker = Queue.appliedResponsesCount()`.
   `createModel()` runs **at send time**, so a resend after a rebase carries the request's *current* (re-recorded) patch.
5. Exceptions to "sent immediately": [Seen code]
   * Before `RequestResponseModel.backendBound` fires, the send is parked in `beforeInitializedQueue` and run by `initialize()` (`bindWithModel$lambda$2`).
   * While a backend patch batch is being prepared (`backendPatchPreparationInProgress`), the request is put in the queue with
     `sendImmediately=false` and sent after the batch is processed (`sendRequestsSubmittedDuringPatchPreparation`, only if still `PENDING`).
   * While recovering (`isRecoveryInProgress`), `submitRequest` **returns without queueing or sending** (see section 6).
   * Submitting while a response is being processed throws `IllegalArgumentException("... was triggered during another response")` (`nestedExecutionGuard`).

### 1.2 Typed character [Seen code]

* Entry: `FrontendAsyncRawTypedHandler.execute` -> `executeBackendTypedAction`. It declines (returns false, so the character goes the plain local way)
  when: patch engine off (never), local-editor support, `ActionCallPolicy...("type%").isBackendInvolved()` is false, no frontend session, no `TextControlId`,
  an accumulating patch is already active, a live template is active (`isTemplateActive`), or any `FrontendAsyncRawTypedHandlerPolicy.isAvailable` says no.
  No policy implementation is declared in the bundled plugin XML [Inferred: none by default].
* `LocalTypeKeyRequestFactory.createRequest` -> `typeFrontend`: opens an accumulating session, runs `nextHandler.execute(editor, char, dataContext)`
  (the normal typed handler, so typing assists run locally), builds the patch. It returns `null` (nothing is sent) only if the patch equals the empty patch
  and the file has no write access; otherwise `FrontendTypeKeyRequest(patch, char, editorId, session, resetUndoHistoryToken)`.
* Wire request: `RdTypeKeyRequest(char, editorId, patch)` (`FrontendTypeKeyRequest.createModel`). `isBatchingAllowed() = true` (only matters for `Old`).
* The caller then does `FrontendAsyncRequestExecutor.executeRequest(request)` = `submitRequest` + `await()` until the request completes.
* Patch content for one character (recorded, see 2.x): `[RdCmdItem(Start), RdCaretStateSnapshot(wrap=true, before), RdDocumentChange, RdCaretStateSnapshot(wrap=false, after), RdCmdItem(Finish)]`,
  `expectedItemVersions = [RdCmdItemVersion(prevCmd), RdPatchEditorVersion(before), RdPatchDocumentVersion(before)]`,
  `updatedItemVersions = [RdPatchEditorVersion(after), RdPatchDocumentVersion(after)]`.

### 1.3 Editor actions: Backspace, Enter, Delete, paste, arrows [Seen code]

* Entry: `FrontendAsyncEditorActionHandler.doExecute`. With the patch engine on, `ActionCallPolicy.getActionCallStrategy` returns `FrontendFirst` only for
  `$Copy`, `$Cut`, `$Paste`; everything else is `BackendOnly` (`ActionCallPolicy`). The per-editor-action strategy comes from `backendDeclaredStrategy`
  and the `...StrategyCustomizer` classes (not enumerated, see section 9).
* Backend-involved actions: `LocalCallEditorActionRequestFactory.createRequest` runs the base handler locally under an accumulating session (same recording idea
  as typing) and builds `FrontendCallEditorActionRequest(patch, editorId, frontendActionId, ..., timestampSetModel)`.
  Wire: `RdCallEditorActionRequest(backendActionId, editorId, patch, ActionTimestampModelProvider.filterOutTransientTimestamps(timestampSetModel))`.
* `FrontendFirst` / `FrontendOnly` actions: the action runs locally under recording and the patch is submitted as `FrontendApplyPatchRequest`
  (`RdApplyPatchRequest(patch)`), no backend action call.
* Paste id list and clipboard hand-over are already in `edit-protocol.md`; the request type is `RdCallEditorActionRequest("EditorPaste")`. [Inferred from doc; not re-read]
* Caret move by arrow key is also an editor action (it goes through `beforeAllCaretsAction`/`afterAllCaretsAction`, see 1.4); exact wire form of
  arrow-key requests [Inferred]: if it is `BackendOnly` it is `RdCallEditorActionRequest` with a caret-only patch, if frontend-only it is `RdApplyPatchRequest`.

### 1.4 Caret move and selection change [Seen code]

* Any caret/selection change that is not inside an action and not caused by a received backend change calls `PatchEngineEditorSynchronizer.sendCaretStateChange`
  -> `sendCaretState(CHANGE, false)`. A new `RdCaretState` is only sent if `newState != oldState` (data-class equality: carets, offsets, selections, document version).
* `sendCaretState(newState, mode)` picks the item:
  * `CHANGE`: if `oldState.documentVersion != newState.documentVersion` -> `RdCaretStateSnapshot(documentChangeWrapping = true, newState)`, else `RdCaretStateChange(oldState, newState)`.
  * `SNAPSHOT` (`sendCaretStateSnapshot`): `RdCaretStateSnapshot(false, newState)`.
  * `DOCUMENT_WRAP_SNAPSHOT`: `RdCaretStateSnapshot(true, newState)`.
* Then it bumps the editor version (`EditorVersionHolder.incrementVersion(newState)`) and `sendChange` opens a `sendOnClose=true` session and pushes (in this order):
  `pushExpected(RdPatchEditorVersion(oldVersion))`, `pushUpdated(RdPatchEditorVersion(newVersion))` only if `updatingChange` (not a transparent editor change and old != new),
  `pushExpected(RdPatchDocumentVersion(currentDocumentVersion, uniqueId, documentId))`, `pushChange(item)`.
  So **a caret-only patch also expects the current document version**. On session close `RdPatchEngine.send` -> `FrontendPatchEngineHost.sendPatch` ->
  `submitRequest(FrontendApplyPatchRequest(patch))`.
* Inside an "all carets action" (`beforeAllCaretsAction` .. `afterAllCaretsAction`): the before-state is parked in `postponedState`; the first document change sends the parked state as
  `RdCaretStateSnapshot(wrap=true)` (`beforeDocumentChangeOrBulkEvent` -> `sendCaretState(DOCUMENT_WRAP_SNAPSHOT, force=true)`); when the action ends and no snapshot was parked-consumed,
  `afterAllCaretsAction` closes the lifetime and `beforeAllCaretsAction$lambda$1` sends `RdCaretStateSnapshot(wrap=false)` with `force=true`. This is where the "after" snapshot of
  the typing sample comes from. Exact handling of a caret-only action (no document change) inside this scope is [Inferred].
* Every document change also does `beforeDocumentChangeOrBulkEvent`: opens a `sendOnClose=true` session around the "before" snapshot. If an outer session (typed/action recording) is
  already open, everything lands in the outer patch (`openAccumulatingSession` re-uses the current session, `sendOnClose` of the outer decides).
* `RdCaretStateSnapshot` / `RdCaretStateChange` are **not** `RdTransparentItem`, so they take part in `speculativelyEquals` (4.1). `RdCmdItem` and `RdStackSizeItem` are transparent (`RdTransparentItem`). [Seen code, model classes]

### 1.5 Undo and redo [Seen code]

* Speculative undo is on when `UndoRemoteBehaviorService.isSpeculativeUndoEnabled()`: `FrontendUndoRemoteBehavior.enableIfAllowed` -> `SpeculativeUndoServiceImpl.canEnableSpeculativeUndo()` =
  remote dev connection **and** backend platform not `Rider`/`CLion`/`AppCode`. So for an IntelliJ IDEA backend it is on.
* `UndoRedoActionCustomization.getActionCallStrategy`: if speculative undo is enabled and the undo manager is a `FrontendUndoManager` for a file editor with a known id and the
  editor is not marked "corrupted" -> `FrontendOnly`; otherwise `null` (falls back to the backend action path).
* `SpeculativeUndoServiceImpl.speculativeUndoOrRedo` -> `undoAndCollectPatch`: under an accumulating session it does `FrontendUndoManager.performLocalUndoRedo(editor, isUndo)` **locally**,
  then pushes `RdStackSizeItem(fileId, undoSize, redoSize)` first for the global stack (`fileId = null`), then for each `DocumentReference` of the editor, and fills
  `undoStackSizes` / `redoStackSizes` lists in the same order `[global, file1, ...]`. The constructor of `UndoRequestInfo` requires
  `undoStackSizes.size == redoStackSizes.size == fileIds.size + 1`.
* The request: `FrontendUndoRequest(...)`; wire `RdUndoRedoRequest(fileEditorId, fileIds, undoStackSizes, redoStackSizes, isUndo, undoProjectId)`.
  The local patch is **not on the wire** (the model has no patch field); it is only the local speculation used for rollback/replay.
  The sizes are the stack sizes **after** the local undo/redo. Replay (`FrontendUndoRequest.doRedo`) performs the undo again locally against the rebased state; if the new patch contains an
  `updatedItemVersions` entry equal to the initial doc version it returns the empty patch.
* `FrontendStackSizeHandler.apply`: if a received `RdStackSizeItem` says `isUndoCorrupted` and the request is a `FrontendUndoRequest`, `markUndoCorrupted()` clears the local stacks and future undo goes to the backend.
* Fallback (speculative undo off): `PatchEngineUndoRedo.Listener.undoRedoStarted` records the local undo in a session and submits `FrontendUndoCallActionRequest` = wire `UndoRdApplyPatchRequest(patch)` (here the patch **is** sent). [Seen code; used when `isSpeculativeUndoEnabled()` is false]
* Cmd items for an undo request are marked while a rollback/perform happens (`FrontendCmdHandler.handleUndoRebase` toggles `ForeignCommandProcessor.setUndoDisabled`). [Seen code]

### 1.6 What is applied locally (speculation) and when [Seen code]

* Everything is applied locally **before** the request is submitted (steps 1-2 above); the request stores the recorded patch, and `ResetUndoHistoryToken` (IDE local undo history) is
  reset on rollback (`resetHistory`) and refreshed on replay (`refresh`) so the IDE's own undo stack does not grow from rebase work.
* Typed key and call-editor-action requests replay by **re-running the action**, not by shifting recorded offsets:
  * `FrontendTypeKeyRequest.doRedo`: if a completion `LookupImpl` is active, `lookup.truncatePrefix(false, 1, false)`; then opens an accumulating session and calls `EditorImpl.type(char.toString())`; the
    resulting new patch replaces `request.patch` (`FrontendAsyncRequestWithImmediateResult.updatePatchAfterRedo`). If the editor is not an `EditorImpl` it returns the empty patch.
  * `FrontendCallEditorActionRequest.doRedo`: creates a fresh timestamp set for the action, validates it against the stored one (logs an error on mismatch, keeps the fragile ones),
    then runs the action locally again under a session.
  * `FrontendApplyPatchRequest` has no `doRedo` override: base `FrontendAsyncRequestWithImmediateResult.redoInternal` re-applies `patch.changes` through the handler registry
    with operation `Redo` inside a fresh accumulating session (so the handlers re-record it with new offsets/versions) and `buildPatchAndClear()` is the new patch.

---------------------------------------------------------------------------------------------------------------------

## 2. Versions the client tracks

### 2.1 Document version `RdDocumentVersion(value: Int, hash: Long)` [Seen code]

* Holder: `DocumentVersionHolderImpl` (one per document), read as `PatchEngineDocumentSynchronizer.getVersion()`.
* Initial value `DocumentVersionsKt.InitialDocVersion = RdDocumentVersion(-1, 0)`.
* Local edit: `DocumentVersionsKt.nextVersion(version, offset, moveOffset, oldText, newText, oldDocumentLength)`:
  `value = old.value + 1`; `hash = mix(mix(mix(mix(mix(old.hash, offset), moveOffset), oldDocumentLength), oldText), newText)` where
  `mix(long h, int v) = h*31 + v` and `mix(long h, String s)` = `IncrementalHashKt.getPlatformIndependentHash(s, h)` = start `h`, for each UTF-16 char `h = h*31 + char`
  (empty string gives `h` back; null gives 0). All 64-bit wrap-around. Throws if `value` overflows below 0.
* Whole-text reset: `nextVersion(version, text)`: `value = old.value + 1`, `hash = mix(127, text)`.
* Fields of a produced `RdDocumentChange` (`VersionsExKt.createRdDocumentChange`): `offset`, `moveOffset = event.getMoveOffset()`, `newText`, `oldText`,
  `oldDocumentLength = doc.textLength + event.oldLength - event.newLength` (= length before the edit), `modificationKind`, `text` (null except in tests), `isWholeTextReplaced`,
  `documentId`, `newVersion`, `oldVersion`, `uniqueId`.
  `modificationKind`: only if `oldText` is empty **and** the event is a `DocumentEventImpl`: `initialStartOffset < offset` -> `RightSide`; else `initialStartOffset + initialOldLength > offset` -> `LeftSide`; else `NotSpecified`. All other events: `NotSpecified`.
* Example (verified with Python against the numbers in `edit-protocol.md`), doc `260` chars, version `(0, -5446235163934729545)`:
  * Enter at 210 (`oldText=""`, `newText="\n    "`, `oldDocumentLength=260`) -> `(1, -6257571537734692227)`.
  * Type `i` at 215 (`oldDocumentLength=265`) -> `(2, -2740688552363290787)`.
* Backend change applied locally (`Perform`): the client does **not** trust its own recompute. `PatchEngineDocumentSynchronizer.receiveDocumentChange(change)` applies the text, the document
  listener bumps the version holder, then `ensureDocumentVersion(change)` calls `resetVersion(change.newVersion)` if they differ. So the version after a backend change is **the backend's `newVersion`**.
* `uniqueId`: `DocumentUniqueIdSessionRegistry.getUniqueId(document)`; checked only if registry `rd.client.patch.engine.enable.unique.document.id` is on (`FrontendPatchDocumentItemHandler.isCurrentUniqueIdExpected`).
* `documentLog` (`DocumentLog`) keeps history of changes for range shifting; `lastKnownVersion` = last version confirmed by the backend; `resetVersion` also `rollbackHistoryToVersion(v)`.

### 2.2 Editor version `RdEditorVersion(caretModelVersion: Int, documentHash: Long, hash: Long)` [Seen code]

* Holder: `EditorVersionHolder`; initial `EditorVersionsKt.InitialEditorVersion = RdEditorVersion(-1, InitialDocVersion.hash = 0, -1)`.
* `EditorVersionsKt.increment(version, state)`: `caretModelVersion + 1`, `documentHash = state.documentVersion.hash`, `hash = computeHash(state)`.
* `computeHash(state)`: only the **first** caret in `state.carets` (not the primary caret):
  `init = 127*31 + caretId.number` (= `3938` for number 1);
  if `selection.start.offset != selection.end.offset`: `hash = init*31 + ((init*31 + start.offset) * (init*31 + end.offset))`, else `hash = init*31 + caret.offset.offset`. Virtual offsets are not hashed.
* Examples (verified): caret 210 -> `122288`, 215 -> `122293`, 216 -> `122294`; selection 140..148, caret id 1 -> `14938339346`.
* When it bumps: every `sendCaretState` (local), every `receiveCaretState` (a received `RdCaretStateChange.newState` or `RdCaretStateSnapshot.state`), every `softUpdate` (virtual replay).
  Document changes alone do **not** bump it, but each document change causes a "before" snapshot (+1) and the action end an "after" snapshot (+1): Enter `1 -> 3`, `i` `3 -> 5`, a pure click `+1`.
* After a received caret item the client checks `computeHash(appliedState) == computeHash(receivedState)` and logs an error if not (`receiveCaretState`); it also warns/errors if the received state's
  document version differs from the actual one (`logWarnOrErrorBasedOnRecovery`: warn while `InsideRecoveryStatusService.isRecoveryInProgress()`, error otherwise).
* Version reset points: rollback of a request and `Merge` call `EditorSynchronizer.resetVersion(expectedVersion)` (`FrontendEditorPatchItemHandler.update`).

### 2.3 Command version `RdCmdItemVersion(RdCmdVersion(hash), RdCmdId)` [Seen code]

* `FrontendUndoSpy.pushStart`: pushes `RdCmdItem(RdPatchCmdStart)` into `changes`, and `pushExpected(modelSupport.getPatchCmdVersion(CmdIdService.previousCommandId()))` **only if a previous command id exists**.
  `RdCmdModelSupport.getPatchCmdVersion(id) = RdCmdVersion(id.asLong())` (per `edit-protocol.md`; class not re-read here).
* No updated cmd version is ever pushed. Because `pushExpected` is `putIfAbsent` per item id, the cmd item is the first expected entry of a command patch.
* Client side validation of cmd versions: none (`FrontendRdPatchItemHandler.validate` default returns `true`, `FrontendCmdHandler` does not override it). The backend decides.
* A command that finishes with no patch open and is not "backend only" makes a one-shot patch of `[Start, Finish]` (`FrontendUndoSpy.commandFinished0`, `selfPatch`), sent as
  `FrontendApplyPatchRequest` (the "transparent empty marker"). `markCurrentCommandAsBackendOnly` suppresses it.
* `RdCmdItem` and `RdStackSizeItem` are transparent, so they never count in `speculativelyEquals`.

### 2.4 Item ids and conflicts [Seen code]

* `RdPatchItemId` kinds seen: `RdDocumentId` (document), `TextControlId` (editor), `RdCmdId`. `RdNonConflictingPatchItemId` ids are skipped by `isIntersecting` (which ids are non-conflicting: not enumerated).
* `RdPatchUtilsKt.isIntersecting(a, b)` = `a.expected ids ∩ b.updated ids ≠ ∅` **or** `a.updated ids ∩ b.expected ids ≠ ∅`. Versions are not compared, only ids.

### 2.5 Undo stack sizes [Seen code]

* `FrontendUndoManager.getStackSize(docRef, isUndo)` after the local undo, per `DocumentReference` of the file editor; list index 0 is the global stack. See 1.5.

### 2.6 Retry timestamp [Seen code]

* `PatchEngineRetryTimestampWithGeneration(timestamp, generation)`; compare by generation first, then timestamp. Client start value `(0, 0)` (`Queue.globalRetryTimestamp`).
* Changes only by: backend answers (`RdRetryResponse.retryTimestamp`, `RdSuccessResponse` newRetryTimestamp is only set from a retry-give-up, see 4.4) and `Queue.cancelAndRemoveAllPending()`
  = `globalRetryTimestamp.incrementGeneration()` (generation + 1, timestamp reset to 0), called in recovery. The client never calls `incrementTimestamp()` in this code path; the timestamp number only comes from the backend.
* Every `send` stamps the request holder with the current global value.

---------------------------------------------------------------------------------------------------------------------

## 3. The queue

* One `SpeculativeQueueProcessor<FrontendRebaseSession, SpeculativeRequestWrapper>` per session: `pendingRequestsQueue` (`ConcurrentLinkedDeque`, FIFO by submission),
  `pendingResponses` (`ArrayDeque`), `globalRetryTimestamp`, `backendChangesCounter`. [Seen code]
* In flight: **unlimited** in `New`. A request leaves the queue only when its `RdSuccessResponse` (or a failure/retry-give-up mapped to success) is processed. The first pending request must match the
  response's `requestId` (`Queue.tryProcessWithoutPhysicalRebase` asserts it; `applyAllPendingResponses` throws `IllegalStateException("Unexpected response ...")` which triggers recovery). Responses are therefore expected **in request order**. [Seen code]
* `MAX_BATCH_SIZE = 10` and batching exist only in `Old` (`FrontendAsyncRequestExecutorKt.MAX_BATCH_SIZE`). `New` always sends one request per `RdRequests`. [Seen code]
* Incoming backend things (`backendPatches` patches and `responsesWithTime` responses) are queued in `BackendChangesProcessingScheduler` and processed in arrival order, one batch at a time. A batch is built by
  `ProcessingGroup.Node.enqueueToCurrentOrNext`: an item joins the current node if the node is not frozen, has the same `ClientId`, and its item-id set (`expectedItemVersions ids ∪ updatedItemVersions ids`) is empty-or-equal to the node's;
  otherwise a new node starts. Responses without a patch have an empty id set and join any batch. [Seen code]
* Processing a batch (`New.processBackendPatchesAndRecover`, serialized by `executionMutex` via `synchronizeWithPatchEngine`): [Seen code]
  1. `backendPatchPreparationInProgress = true`; `FrontendAsyncRequestExecutorHelper.preparePatchesBeforeApplying(patches)` runs `FrontendRdPatchPreprocessor` extensions (suspending; failures only warn).
  2. In a write-intent read action and `Cancellation.executeInNonCancelableSection`: move all batch items into `Queue.onBackendResponse`, run `Queue.process()`, then `sendRequestsSubmittedDuringPatchPreparation()`.
  3. A `ProcessCanceledException`/`CancellationException` only logs a warning; any other `Throwable` -> `Logger.error` with attachments `pending-requests.txt`, `pending-responses.txt`, `fireProblemOccurred`, then `recoverState` on the EDT.
* Ordering rule on resend: when a rebase ends with a changed `globalRetryTimestamp`, every pending request is re-recorded (`replay`) and **re-sent in queue order** with the new timestamp (`rollForwardAllPendingRequests(..., timestampChanged)`); `JustRetry`
  re-sends all pending requests without replay (`resendPendingRequests`). [Seen code]
* The backend drops requests whose retry timestamp is older than its own (known from the existing doc, not visible in this code); the client's only counter move is generation+1 on recovery (2.6). [Inferred; backend sources not read here]
* Response filter at arrival (`bindWithModel$lambda$1`): a response is **dropped** if `isRecoveryInProgress` or `requestId < leastSaneRequest`. `leastSaneRequest` is set to `requestIdCounter.get()` at the start of recovery, so after a recovery responses
  to older ids are ignored (note the `<`: the response of the very last pre-recovery id is not dropped by this test; [Inferred] harmless because `dropAllRequests` clears backend state first).
  Backend patches are dropped while `isRecoveryInProgress` (`bindWithModel$lambda$0`). [Seen code]

---------------------------------------------------------------------------------------------------------------------

## 4. Response handling

### 4.1 `RdSuccessResponse` comparison [Seen code]

`New.createResponseWrapper` maps: `RdSuccessResponse` -> `SuccessResponseWrapper(requestId, patch, ...)`; `RdFailureResponse` (and its subclass `RdNoEditorFoundResponse`) -> `SuccessResponseWrapper(requestId, patch = null, ...)`;
`RdRetryResponse` -> `handleResponse(retry)` (4.4). `RdResponses.responses` must contain exactly one element (`single()`).

`SuccessResponseWrapper.compare(request)`:
* Request is a `FrontendAsyncRequestWithImmediateResult` (typed key, call action, apply patch, undo): `FrontendAsyncRequestExecutorHelper.speculativelyEquals(request.patch, response.patch, request, allowEquivalent = true)`:
  if the test hook `forceRebase(request, response.patch)` says true -> `Different`; else `RdPatchUtilsKt.speculativelyEquals(a, b, true)`.
* Any other request type (e.g. `FrontendSimpleRequest`): `Equals` if the response patch is null/has no changes, else `Different`.

`RdPatchUtilsKt.speculativelyEquals(a, b, allowEquivalentChanges)`: [Seen code]
* `a === b` -> `Equals`; either null -> `Different`.
* Filter out `RdTransparentItem`s (`RdCmdItem`, `RdStackSizeItem`) from both `changes` lists. If the two lists are **equal as lists** (`RdDocumentChange`/caret items compared as data classes, so offsets, texts, `oldDocumentLength`,
  both versions, document ids and caret states must all match) -> `Equals`.
* Else, if `allowEquivalentChanges && areChangesEquivalent(a, b)` -> `Equivalent`, else `Different`. `features`, `expectedItemVersions`, `updatedItemVersions` are not compared.
* `areChangesEquivalent`: (1) caret items per editor: compare the **first** and **last** caret states (via `RdCaretStateChange.old/new` or snapshot `state`); carets must match in count, offset and selection ends, selection **direction may be swapped**;
  (2) the remaining (non-caret) items are walked pairwise: equal, or two `RdDocumentChange`s that `areDocumentChangesEqual` (same doc/offset/moveOffset/texts, or one is a sub-change of the other with the same surrounding text), or
  one change on one side equals two adjacent changes on the other (`checkReplaceOrComplexChangeSplitIn2`: the second change must start at `offset + newText.length`, or at the same offset with `oldText == previous newText`). Version fields are not compared in this mode.

Outcomes in `Queue.tryProcessWithoutPhysicalRebase` (looks at the pending response batch from the head): [Seen code]
* `Equals`: `completeRequest`: poll the request off the queue, `SuccessResponseWrapper.complete` = `applyBackendFeaturesOnlyAndUpdate(patch)` (apply `patch.features`, e.g. `RdScrollingRequest`, then `registry.update(updatedItemVersions)`; documents' `lastKnownVersion` moves),
  status `COMPLETED`, `fireRequestCompleted`. **No text, caret or version change.** `backendChangesCounter++`.
* `Equivalent` ("virtual rebase", `tryProcessWithoutPhysicalRebase$lambda$3`): `rollbackAllPendingRequests(virtually=true)` (reverse order), `SuccessResponseWrapper.apply(virtually=true)` = `applyBackendChangesOnly(patch, virtually=true)`,
  `completeRequest`, `rollForwardAllPendingRequests(virtually=true)`. "Virtually" = `SoftRollback` / `SoftPerform` / `SoftRedo` operations: **the document text and the real caret are not touched**; only the version holders and the document log are re-derived
  from the backend's change decomposition (`PatchEngineDocumentSynchronizer.updateDocumentLog` re-increments the version holder with the backend's change parameters; `PatchEngineEditorSynchronizer.softUpdate` re-stamps carets with the current doc version and bumps the editor version).
  After it, the local document version equals what the backend computed from the same text. No re-send.
* `Different`: returns `Rebase` to `Queue.process`, which calls `rebase` (4.2).
* In all three, the response's `newRetryTimestamp` (only set by a give-up, 4.4) must be greater than the global one and replaces it.
* `RdRetryResponse` fast path = `JustRetry`: `iterator.remove()`, adopt `newGlobalRetryTimestamp` (must be greater), then `resendPendingRequests()` (every pending request, in order, with the new timestamp).

### 4.2 The rebase algorithm [Seen code]

`Queue.process` -> `rebase(oldTimestamp)` -> first response's `withChangeContext(batchSize) { context -> ... }` (a fresh `FrontendRebaseSession`, which is also registered as a `DocumentSynchronizerListenerOld` to record every document change made during the rebase):

1. `rollbackAllPendingRequests(virtually=false, context)`: iterate pending requests **newest first** and call `SpeculativeRequestWrapper.rollback` -> `FrontendAsyncRequestWithImmediateResult.undo(context, false)`:
   1. build `FrontendRdPatchHandlerContext(Rollback)`; `rebaseSession.validateAndUpdateVersions(patch.updatedItemVersions)`: the **current local versions must equal the request's `updatedItemVersions`**, else `IllegalStateException("Not matched versions")` (recovery);
   2. `undoInternal`: `registry.apply(patch.changes.reversed())` with operation `Rollback` (documents apply `UtilKt.reverse(change)` and `rebaseSession.record(item)`; carets apply the reversed caret item and `record(step)`;
      cmd items do nothing except undo-request bookkeeping); `registry.update(patch.expectedItemVersions)` (Rollback: doc `resetVersion(expected)` + `documentLog.rollbackHistoryToVersion`; editor `resetVersion(expected)`);
   3. `ResetUndoHistoryToken.resetHistory()`;
   4. `validateAndUpdateVersions(patch.expectedItemVersions)`: local versions must now equal the request's `expectedItemVersions`.
   Statistics: `collectRebase(...)`.
2. `applyAllPendingResponses(context)`: for each pending response in arrival order (removing it):
   * `BackendChangeWrapper` (independent backend patch): `FrontendAsyncRequestExecutorHelper.applyBackendPatch(patch, context, null, session)` = `applyBackendChangesOnly(patch, virtually=false)` + `applyBackendFeaturesOnlyAndUpdate`.
   * `RetryRequiredWrapper`: adopt the new global retry timestamp (must be greater).
   * `SuccessResponseWrapper`: adopt `newRetryTimestamp` if any; check `pendingRequestsQueue.peek().id == requestId` else `IllegalStateException`; `apply(virtually=false, request, context)` (nothing if `patch == null`) then `completeRequest` (poll the request, `complete`: features + `update(updatedItemVersions)`, status `COMPLETED`, `fireRequestCompleted`); `collectRedone(1)`.
   * `backendChangesCounter++` for each applied change/response.
   `applyBackendChangesOnly(patch, virtually, ...)` (`FrontendAsyncRequestExecutorHelper`): operation `Perform` (or `SoftPerform` if virtual); `validateAndUpdateVersions(patch.expectedItemVersions)` (**throws "Not matched versions" if any local item version differs from the backend's expected one**);
   `registry.apply(patch.changes)`; `registry.update(patch.updatedItemVersions)`; `validateAndUpdateVersions(patch.updatedItemVersions)` (**throws if the local versions after applying differ from the backend's updated ones**, comparing the editor version incl. hash and the document version incl. hash).
   `validateAndUpdateVersions` also stores the versions in `rebaseSession.updatedVersionsMap` and validates the **whole accumulated map** each time.
   Applying items: document change -> `PatchEngineDocumentSynchronizer.receiveDocumentChange` (needs `document.textLength == change.oldDocumentLength`, else `IllegalStateException("expected document length")` - but see 6.1: `registry.apply` swallows handler exceptions into `Logger.error`);
   caret item -> `receiveSelection` (applies the state, bumps editor version); `RdResetDocumentChange` -> `setText` if text differs and `resetVersion(newVersion)`.
3. `rollForwardAllPendingRequests(virtually=false, context, timestampChanged)`: for each remaining pending request in order: `SpeculativeRequestWrapper.replay` -> `FrontendAsyncRequestWithImmediateResult.redo(context, false)`:
   1. `patchBefore = patch`; take its `expectedItemVersions`, **replace each by `rebaseSession.updatedVersionsMap[id]` if present** (the version the earlier steps of this rebase left), and `validateAndUpdateVersions` them (must equal local state);
   2. `ResetUndoHistoryToken.refresh()`;
   3. `doRedo` (`redoInternal` for `FrontendApplyPatchRequest`; re-typing for `FrontendTypeKeyRequest`; re-running the action for `FrontendCallEditorActionRequest`; undo again for `FrontendUndoRequest`), producing `patchAfterRedo` (new `RdPatch` with new offsets, versions, hashes);
      for `redoInternal` each `RdDocumentChange` is replayed by `FrontendRebaseSession.replay(documentText, change, currentVersion)`: `offset`, `moveOffset` and `offset + oldText.length` are shifted through the changes recorded in `rebaseSession.history` after the item's anchor index
      (`DocumentLogRangeShifterKt.shiftOnePoint`; if that returns `-1`, a second pass with `true,true` is used), `oldDocumentLength = current document text length`, `oldText = documentText.substring(shiftOffset, shiftedOldEnd)` (or `""` if the shifted range is empty),
      `newVersion = nextVersion(currentVersion, shiftOffset, shiftMoveOffset, shiftOldText, newText, shiftOldDocumentLength)`; whole-text replacements keep their offsets;
      carets are shifted the same way and re-stamped with the new document version (`FrontendRebaseSession.replay(RdSelectionChangeBase, version)`); `RdCaretStateSnapshot(wrap=true)` items are **not** replayed
      (`FrontendSelectionChangeHandler.redo`: they are re-recorded by the document change itself). If the shifted offset or length is `-1` or the document length does not match the change's `oldDocumentLength`, the change is skipped with a warning (assertion in tests);
   4. `validateAndUpdateVersions(patchAfterRedo.updatedItemVersions)`; `this.patch = patchAfterRedo` (`updatePatchAfterRedo`);
   5. if the global retry timestamp changed during this rebase, `request.send(globalRetryTimestamp)` (with the new patch, since `createModel()` reads `getPatch()`).
4. Afterwards all processed responses get `executed()` (`SuccessResponseWrapper.executed` -> `fireResponsesExecuted`), and `FrontendRebaseSession.publishTouchedDocuments()` publishes document logs.

Illustration [Inferred: invented numbers, mechanics from the code above] (Backspace speculation answered with an extra change, `Different`): local doc `(17,H17)`; request patch `[change(offset 215, old "i", new "")]` -> local `(18,H18)`.
Backend answered with `[change(215,"i"->""), change(214,"(" -> "")]` and updated `(19,H19)`. Rebase: undo request (doc back to `(17,H17)`, text restored), validate expected `(17,H17)` OK,
apply backend patch (doc `(19,H19)` taken from `change.newVersion`, text now matches the backend), validate updated `(19,H19)` OK.

### 4.3 `RdFailureResponse` and `RdNoEditorFoundResponse` [Seen code]

* `New.createResponseWrapper` handles `RdFailureResponse` (a non-final class; `RdNoEditorFoundResponse` extends it) as a success with **null patch**. For requests with a patch `compare` gives `Different` (patch non-null vs null), so `Queue` does a physical rebase:
  the failed request is rolled back with all pending ones, "applied" with nothing, removed and completed (status `COMPLETED`, `fireRequestCompleted` with the failure response), the others are replayed. The user's speculative edit **disappears** and the editor state is kept; no recovery dialog, no IDE error in `New`. 
  For a non-patch request type (`FrontendSimpleRequest`) `compare` gives `Equals`.
* `Old.handleFailure` logs `Logger.error("Can't execute ...")` and undoes the request if it is `EXECUTING`.

### 4.4 `RdRetryResponse` [Seen code]

`New.handleResponse(RdRetryResponse)`:
* `first = queue.pendingRequests.first()`, `applied = queue.appliedResponsesCount()`.
* **Stale retry**: if `first.id != response.requestId || applied != first.backendChangeMarker` (the head request is not the one answered, or some response/backend change was applied since it was sent) -> `RetryRequiredWrapper(requestId, response.retryTimestamp)` without touching the counter.
* Else if `request.retryCounter == 1` (limit `MAX_RETRY_TRIALS = 1`, inlined) -> **give up**: `SuccessResponseWrapper(requestId, patch = null, response, session, newRetryTimestamp = response.retryTimestamp)`. As in 4.3 this is `Different`, so the request is rolled back and completed, and the new retry timestamp is adopted.
* Else `retryCounter++` (0 to 1) and `RetryRequiredWrapper(requestId, response.retryTimestamp)`.
* `RetryRequiredWrapper` is a `BackendResponse.RetryRequired`: in `Queue` the new global timestamp must be greater than the current one; fast path `JustRetry` re-sends every pending request in queue order (no rebase, no replay). In a `rebase`, the timestamp is adopted and `rollForwardAll...` re-sends if it changed.
* So one request is sent at most twice. `Old` uses the same limit and additionally consults `FrontendAsyncRequestExecutor.RetryCustomizer.retry(request, response)` which can force more retries (default service not read).
* Retry does not roll back anything on its own and is not an error.

---------------------------------------------------------------------------------------------------------------------

## 5. `backendPatches` (changes the backend makes by itself)

* Wire: `RequestResponseModel.backendPatches: IAsyncSignal<RdPatchWithMetadata(patch, changeReason, changeReasonExtra, batchSize)>`. `New.bindWithModel$lambda$0` wraps each in `BackendChangeWrapper` and schedules it in the same `BackendChangesProcessingScheduler` as responses, keyed by the ids in `expectedItemVersions ∪ updatedItemVersions`. Ignored (dropped with a trace log) while recovering. [Seen code]
* In `Queue.tryProcessWithoutPhysicalRebase` for a `BackendOriginatedChange`: if **any** pending request `conflictsWith` it (`BackendChangeWrapper.conflictsWith` = `request.conflictWith(patch)`; for `FrontendAsyncRequestWithImmediateResult` that is `RdPatchUtilsKt.isIntersecting(request.patch, backendPatch)`; for
  `FrontendSimpleRequest` always false) -> `Rebase`. Otherwise: remove from the batch and apply immediately: `apply` = `withChangeContext(1) { FrontendAsyncRequestExecutorHelper.applyBackendPatch(patch, context, null, session) }` (`Perform`; validates expected/updated versions strictly like 4.2) and `backendChangesCounter++`. [Seen code]
* "Conflict" is by item ids only. Any pending speculative edit on the same document or the same editor conflicts with any backend patch that updates that document or editor (their `expected`/`updated` id sets intersect), even when the offsets are far apart.
  An empty pending queue never conflicts. [Seen code]
* On conflict the rebase is the one in 4.2: roll back all pending requests newest first, apply **all** queued responses/changes in arrival order (the backend change among them), replay the remaining requests. A request that completes during step 2 is finished there, not replayed.
* Applying a backend change through `Perform`: `RdDocumentChange` -> text edit (with `oldDocumentLength` check) and the document version becomes the change's `newVersion`; caret items -> caret/selection moved and editor version +1 per item;
  `RdResetDocumentChange` -> `setText` if different (`UndoUtil.disableUndoIn`) and `resetVersion`; `RdCmdItem`/`RdStackSizeItem` -> bookkeeping for undo. [Seen code]
* Batch counter: `RdPatchWithMetadata.batchSize` is only used for statistics (`ConflictTracker`). [Seen code]

---------------------------------------------------------------------------------------------------------------------

## 6. Recovery

### 6.1 What triggers it [Seen code]

`New.recoverState(reason)` is only called from `processBackendPatchesAndRecover` when processing a batch throws any `Throwable` other than `ProcessCanceledException`/`CancellationException`. In practice:
* `FrontendRebaseSession.validateAndUpdateVersions` throws `IllegalStateException("Not matched versions")` when a validation fails (called directly, **not** wrapped): wrong editor version / doc version (value or hash) against `expectedItemVersions` or `updatedItemVersions` of a backend patch/response or a rollback/replay.
  The handler `validate` itself logs `Logger.error("Not matched versions for ... actual: ..., expected: ...")` first.
* `Queue.applyAllPendingResponses`: `IllegalStateException("Unexpected response ...")` if the response id is not the head request's id.
* Exceptions thrown from request `undo`/`redo` code and anything else inside `Queue.process` (for example `IllegalStateException("command already in progress")` from the cmd handler, or "editor synchronizer is null" in `FrontendSelectionChangeHandler.rollback`).
* Not a trigger: exceptions inside a single item handler (`FrontendRdPatchHandlerRegistry.apply/update/validate` wrap each handler call in `LoggerKt.getOrLogException`: error logged, processing continues). The document `expected document length` exception and the "Unexpected version" log are of this kind, so they alone do not start recovery.
* Not a trigger: `RdRetryResponse` (4.4) and `RdFailureResponse` (4.3).
* `New.recoverState` never runs on its own timer; `Old.runGuardedWithRecovery` is the equivalent for `Old`.

### 6.2 Sequence [Seen code]

`New.recoverState`:
1. `isRecoveryInProgress = true`; `leastSaneRequest = requestIdCounter.get()`; `yield()`; log `>> Start recovering states after exception...`.
2. `FrontendAsyncRequestExecutorKt.recoverWithModalDialog(impl, session, reason, pendingRequests(), clearQueue)`:
   open a **modal progress** (`withModalProgress`, non-cancellable, text "actions.recovering.editors", owner = focus owner or a guess), inside it:
   1. `yield()`;
   2. for every pending request: build `RdFailureResponse("Version mismatch", request.id)`, if not completed `fireRequestCompleted(request, response)`, set status `CANCELLED_COMPLETED`, `fireResponsesExecuted`. (Local edits are **not** rolled back here.)
   3. `SyncEventsLogger.logRecovery(count, reason.class)`;
   4. `clearQueue` = `Queue.cancelAndRemoveAllPending()` (EDT + write-intent asserted): clear `pendingResponses` and `pendingRequestsQueue`, `globalRetryTimestamp = incrementGeneration()` (timestamp 0, generation +1);
   5. `impl.allowPumpProtocolUnderCurrentModality()` and `IPermittedModalities.getInstance().allowPumpProtocolUnderCurrentModality()` (so protocol messages are processed while the modal dialog runs, see 7);
   6. `InsideRecoveryStatusService.withFlagSuspend { RecoveringStrategyProvider.getInstance().createRecoveringStrategy(session).recover() }` (flag makes version-mismatch logs warnings instead of errors).
3. `TrustBackendRecoveringStrategy.recover` (frontend module, runs on EDT):
   1. `documentHost.beforeRecoveryStarts()` and `editorHost.beforeRecoveryStarts()` clear the "recently closed" sets;
   2. `textControlsIds = editorHost.getEditorsIds()`; if empty: warn `<< No opened editors found, skipping recovering` and stop;
   3. `RequestResponseModel.dropAllRequests.startSuspending(Unit)` (backend forgets queued requests);
   4. `patch = RequestResponseModel.getEditorStates.startSuspending(textControlsIds)` -> an `RdPatch` with the full state of every open editor (null -> `Logger.error("Resulting patch is null")`, stop);
   5. `rebaseSession = FrontendRebaseSession.createEmpty(RECOVERY)`; `registry.update(patch.expectedItemVersions, ctx(Rollback))` (sets each local document/editor version to the patch's *expected* version via `resetVersion`);
   6. `FrontendAsyncRequestExecutorHelper.applyBackendPatch(patch, rebaseSession, null, session)` (= `applyBackendChangesOnly` with the expected/updated validation, then features and `update(updatedItemVersions)`): in practice `RdResetDocumentChange` (text replaced if different) plus
      caret snapshots. [Seen code for the call; contents of the `getEditorStates` patch are [Inferred]]
   7. Errors: `CancellationException` -> log "Recovering was cancelled"; `TimeoutException` -> "Recovering was timed out"; other -> `Logger.error("Error occurred during recovering", t)`. All end with the sequence finished (no retry, no second recovery).
4. `isRecoveryInProgress = false`; log `<< End recovering states`. `resetEditorStates: RdCall<RdPatch,Unit>` exists in the model but this strategy does not call it.

### 6.3 Typing while recovering [Seen code]

* `New.submitRequest` while `isRecoveryInProgress`: logs a trace line and **returns without id, queueing or sending**. The request's status stays `PENDING` and its completion never fires, so `executeRequest` (typed handler / action coroutine) would wait. [Inferred: the waiting coroutine hangs until the request object is dropped, not verified.]
* Responses and backend patches that arrive while recovering are dropped (3). After recovery, new requests continue with the old id counter (no reset), a bumped retry generation, and starting versions from the `getEditorStates` patch.
* The recovery dialog is modal on the EDT; the local edit that the user already made before the failed request is replaced by the backend's text when the reset patch is applied. [Inferred]

---------------------------------------------------------------------------------------------------------------------

## 7. Typing while a backend modal dialog is open, and while completion is open

### 7.1 Modalities [Seen code unless stated]

* `IPermittedModalities` / `PermittedModalitiesImpl` (`intellij.rd.client.base.jar`): `allowPumpProtocolUnderCurrentModality()` does `if (LaterInvocator.isInModalContext()) allowedModalities.add(ModalityState.current())` (no duplicate); the entry is popped on `beforeModalityStateChanged(entering=false)` when it is the last one;
  `allowPumpProtocolForComponent(component, disposable)` allows it when the component is shown (`UiNotifyConnector.Once` -> `showNotify`); `viewPermittedModality(lifetime, handler)` lets the protocol scheduler observe the current permitted `ModalityState`.
  [Inferred] RD protocol messages (responses, backend patches, `getEditorStates` result) are run on the EDT with the permitted modality; messages for a modality that was not allowed wait until that dialog closes.
* Callers of `allowPumpProtocolUnderCurrentModality`: `recoverWithModalDialog` (both the executor's and the global one), `FrontendEditorCompositeModalityListener.onNewModalityInsideBlockingWaitOnEdt` (modal wait while an editor composite opens), and
  `FrontendFileEditorManagerKt.openFrontendRequestedCompositeDeferred`. `New.allowPumpProtocolUnderCurrentModality` just forwards to `IPermittedModalities`.
* Nothing in `rdclient.requests` blocks typing because a modal dialog exists. A modal dialog normally owns keyboard focus, so no typed-key request is produced for the editor behind it [Inferred]; requests already in flight keep being answered only when
  their modality is permitted. For a client that keeps an editor typeable under a dialog: the protocol pump must still run the response/backend-patch handlers, or responses pile up and the rebase batches get larger (they are processed in order anyway).

### 7.2 Completion [Seen code]

* The typed-key path has no completion check: `FrontendAsyncRawTypedHandler` only skips for live templates, an active patch, or a `FrontendAsyncRawTypedHandlerPolicy` veto (none registered by default). The IDE's lookup popup is a local component; typing continues to produce `RdTypeKeyRequest`s.
* On replay of a typed request while a lookup is active: `LookupImpl.truncatePrefix(false, 1, false)` runs before re-typing (keeps the lookup prefix in step with the re-applied character). Exact meaning of the three arguments is [Inferred].
* Completion data itself is not part of this edit protocol (see `completion-protocol.md`).

---------------------------------------------------------------------------------------------------------------------

## 8. Corrections to `docs/edit-protocol.md` sections 5-9

Each item quotes the doc and gives the code fact. All points marked [Seen code] unless noted.

1. **Section 7**: "Sent with `retryTimestamp=(0,0)`, **no patch and no speculative change**: the client does not apply undo locally in this setup."
   Wrong for IntelliJ IDEA backends: the client applies undo **locally** (`SpeculativeUndoServiceImpl.undoAndCollectPatch` -> `FrontendUndoManager.performLocalUndoRedo`) and keeps the recorded patch as speculation (rolled back/replayed like any request). It is only **not on the wire**
   (`RdUndoRedoRequest` has no patch field). `(0,0)` is just the initial global retry timestamp; it changes after retries/recovery. The speculation is off (no local undo, `UndoRdApplyPatchRequest` with a patch is used instead when applicable) if speculative undo is disabled or the editor's undo state is marked corrupted.
2. **Section 7**: "The sizes are the client's last known undo/redo stack depths (they come from the `RdStackSizeItem`s the backend sent earlier)".
   They come from the frontend's own `FrontendUndoManager` **after** performing the local undo/redo, i.e. the expected sizes after the action (`undoAndCollectPatch`: `pushStackSizeItem` runs after `performLocalUndoRedo`). Order `[global, file1, ...]` is confirmed (`UndoRequestInfo` requires `size == fileIds.size + 1`).
   "the backend presumably rejects/retries when they differ" stays [Inferred]; client side, a received `RdStackSizeItem` flagged corrupted clears local stacks and disables speculative undo for that editor (`FrontendStackSizeHandler`).
3. **Section 8**: "`RdFailureResponse(message)` / `RdNoEditorFoundResponse` end the request (client throws/recovers: `recoverState`)."
   `New` does not throw or recover: both are mapped to a success response with a null patch, which makes the speculative edit roll back and the request complete (4.3). Recovery is triggered by version-validation exceptions and similar (6.1), not by these responses.
4. **Section 8**: "`RdRetryResponse`: set global `retryTimestamp`, resend all pending requests with it (`JustRetry`)."
   Incomplete: the retry also has a limit. The first valid retry increments `retryCounter` (0 to 1) and resends; a second valid retry for the same request gives up: the request is dropped and its speculation rolled back (4.4). A stale retry (head id differs or something was applied since the send) resends without counting.
5. **Section 8**: "for each `RdDocumentChange` check `oldVersion == current version` and `oldDocumentLength == document.length` (otherwise it throws `expected document length`)".
   Only the length check throws (and `registry.apply` swallows it into `Logger.error`). The `oldVersion` mismatch is only logged (`Unexpected version`), then `ensureDocumentVersion` forces the version to `change.newVersion`.
   The real strict gate is `FrontendRebaseSession.validateAndUpdateVersions`: **before** applying a backend patch the local item versions must equal its `expectedItemVersions`, and **after** applying they must equal its `updatedItemVersions` (editor `RdEditorVersion` incl. hash, document `RdDocumentVersion` incl. hash). A mismatch throws and starts recovery. The doc does not mention this.
6. **Section 5.4**: "Document: expected = `RdPatchDocumentVersion(old, uniqueId, docId)`, updated = new. Only pushed when old != new (no doc entry in updated for caret-only patches)."
   True for `PatchEngineDocumentSynchronizer.sendDocumentChange`, but incomplete: every **caret** change (`PatchEngineEditorSynchronizer.sendChange`) also does `pushExpected(RdPatchDocumentVersion(currentDocumentVersion, ...))`. So caret-only patches expect the doc version (as the doc's own section 4 samples show) but never update it.
7. **Section 5.2**: "Initial editor version: `RdEditorVersion(-1, -1 doc hash of initial -1/0, -1)`".
   `InitialEditorVersion = RdEditorVersion(caretModelVersion = -1, documentHash = InitialDocVersion.hash = 0, hash = -1)`, i.e. `(-1, 0, -1)`.
8. **Section 8**, "Equals/Equivalent/Different ... compare non-transparent changes ... (cmd items ignored)": correct, but add that `RdStackSizeItem` is also transparent, that caret snapshots/changes are **not** transparent (so the backend's caret items must equal the local ones, or be "equivalent"),
   and that `features` and the version lists are not compared. Equals needs `oldDocumentLength` and both versions to be identical. Equivalent compares only first/last carets (selection direction may differ) and tolerates one change on one side being two adjacent changes on the other.
9. **Section 8**: "Equivalent ...: virtual rollback, apply backend response virtually, replay."
   Add: `Soft*` operations never touch the document text or the real caret. They only re-derive versions and the document log from the backend's change decomposition. The local version ends up equal to the backend's `updatedItemVersions`.
10. **Section 8**: "Different ...: roll back all pending requests in reverse, apply backend patch, complete the request, replay the other pending requests." Correct, with the missing rule that applying **all** queued responses/backend changes happens in arrival order in one pass, and replay recomputes
    each request's patch (re-typing the char, re-running the action, shifting offsets through the session's recorded history for plain patches), and re-sends it only if the global retry timestamp changed (4.2).
11. **Section 8**: "An independent backend patch ... that conflicts ... also triggers a rebase; if it does not conflict it is applied directly." Correct; add: conflict is by item-id overlap only (`isIntersecting`: expected ids vs updated ids both ways), batches are grouped by identical id sets, and recovery drops them (5).
12. **Section 9 step 2**: "`RdRequests([RdRequestHolder(id=++counter, ..., RdRetryTimestamp(0,0), null)], timestamp=nowNanos)`".
    First id is `0` (counter starts at -1). `retryTimestamp` is the **current global** timestamp (initially `(0,0)`, then whatever retry responses set; generation +1 after each recovery). `timestamp` is `TelemetryDataUtilKt.getCurrentTime()`. One request per `RdRequests` (no batching in `New`). Ids are never reused or reset, including after recovery.
13. **Section 9 step 3**: "Send the transparent `RdApplyPatchRequest` marker ... (optional for the text ...)". The real client sends it for every IDE command that ends without an open patch unless the command is marked backend-only (`FrontendUndoSpy.commandFinished0`); confirmed, and the expected cmd version is the previous command id (`CmdIdService.previousCommandId()`), which is pushed only when it exists.
14. **Section 9 intro**: "no local speculation required". If a client does not apply the edit locally and sends an empty/absent patch, the real code has no such path: every patch request carries a patch (`createModel` falls back to `FrontendAsyncRequest.emptyPatch` only if the stored patch is null). Whether the backend accepts it is [Inferred, backend not read].
15. **Section 6**: "`RdApplyPatchRequest` ... sent by the document/editor synchronizers when an accumulating session closes (`sendOnClose=true`)." Confirmed (`RdPatchEngine.send` -> `FrontendPatchEngineHost.sendPatch` -> `FrontendApplyPatchRequest`). Add: typed key / action / undo requests record into a session with `sendOnClose=false` and send their patch themselves, inside `RdTypeKeyRequest` / `RdCallEditorActionRequest`.
16. **Section 5.1**: confirmed formulas (nextVersion, reset hash `mix(127, text)`, string mix without +1). Added exact `modificationKind` rule and that the version after applying a backend change is the backend's `newVersion` (not recomputed).
17. **Section 5.3** "no updated entry is sent for it": confirmed. Also: the client never validates cmd versions (`FrontendRdPatchItemHandler.validate` default is true).

No other statement in sections 5-9 was contradicted by the code I read.

---------------------------------------------------------------------------------------------------------------------

## 9. Not verified / gaps

* Backend side (when it answers `RdRetryResponse`, what it does with expected-version mismatches): backend retry code is not in `proto-src/src/backend` (no `RdRetryResponse` references). Everything about the backend's drop rule is from the existing doc.
* `ActionCallStrategyKind` per action (`FrontendAsyncEditorActionHandler.getCallStrategy`, `*StrategyCustomizer` classes), `FrontendActionTimestampValidator` rules, `ActionTimestampModelProvider.filterOutTransientTimestamps`: not read in detail.
* Which `RdPatchItemId` subclasses are `RdNonConflictingPatchItemId` (affects `isIntersecting`): enumeration timed out, not done.
* Exact `getEditorStates` patch content, and the protocol scheduler's use of `viewPermittedModality`: not in the provided sources.
* Arrow-key / caret-only action wire form and the `postponedState` bookkeeping in `PatchEngineEditorSynchronizer.sendCaretState` (decompiler output dropped some branches): partly [Inferred].
* `DocumentLogRangeShifterKt.shiftOnePoint` rules (how offsets move for inserts at the offset, deletes covering it): not read; needed to reproduce replay exactly. The IDEate client can avoid it by always re-running its own edit against the rebased text.
* `Old` executor: only the points in section 0 were read.
