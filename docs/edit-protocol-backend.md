# How the IntelliJ backend handles editor edit requests (split mode), for Ideate

Read from IntelliJ IDEA 2026.3 (263.7307.200) jars, decompiled, on 2026-11-18. The client's side is in [`edit-protocol-client.md`](edit-protocol-client.md); what Ideate does with both is in [`edit-protocol.md`](edit-protocol.md) section 12.

Source: IntelliJ IDEA build in `/Users/safeuq/Applications/IntelliJ IDEA.app` (same build as the client notes, 263.7307.200).
Read: `intellij.platform.backend.split.jar` (`com.jetbrains.rdserver.*`), `intellij.rd.platform.jar` (`com.jetbrains.rd.ide.*`),
`intellij.rd.ide.model.generated.jar` (model classes), `intellij.platform.completion.backend.split.jar`, `intellij.platform.undo.jar`,
`intellij.platform.ide.impl.jar` (`EditorImpl`). Classes that failed to decompile (`BackendAsyncRequestExecutor`,
`BackendTypeKeyRequestProcessor`, `BackendCallEditorActionRequestProcessor`, `BackendRdPatchHandlerRegistry`, the `getEditorStates` body)
were read with `javap -c -p -constants`.

Tags: **[Seen code]** = read in the decompiled code or bytecode. **[Inferred]** = follows from the code plus ordinary IntelliJ behaviour,
not visible in these classes. **[Trace]** = only known from the client's recorded trace in `docs/edit-protocol.md`.

Short names: `Executor` = `com.jetbrains.rdserver.requests.BackendAsyncRequestExecutor`; `Registry` =
`com.jetbrains.rdserver.rdPatchHandlers.BackendRdPatchHandlerRegistry`; `DocSync` = `com.jetbrains.rd.ide.document.PatchEngineDocumentSynchronizer`;
`EditorSync` = `com.jetbrains.rdserver.editors.BackendEditorSynchronizer`.

---------------------------------------------------------------------------------------------------

## 0. The picture in 12 lines

1. Client fires `RequestResponseModel.requestsWithTime(RdRequests)`. Backend answers on `responsesWithTime(RdResponses)` and, on its own, on `backendPatches(RdPatchWithMetadata)`. [Seen code: `Executor.bindWithModel`]
2. Every `RdRequests` runs on the EDT under one **JVM-wide** mutex, so requests of all clients run strictly one after another. [Seen code: `Executor.bindWithModel$1`, `executeRequests` asserts EDT]
3. Before a request runs, the backend waits until it has no open patch session (no backend command in progress). [Seen code: `Executor.executeRequest` -> `PatchEngine.awaitNoPatches`]
4. The request runs inside a private patch session. What IntelliJ records during it (document edits, caret moves, command start/finish) becomes the response patch. [Seen code]
5. Only `RdApplyPatchRequest` checks the client's `expectedItemVersions`. `RdTypeKeyRequest` and `RdCallEditorActionRequest` check no version; they act on the backend's current state. [Seen code]
6. `RdCallEditorActionRequest` and `RdCallActionRequest` carry a `timestampSetModel`. The backend moves the client's caret offsets from the client's document version to the current one and compares with its real caret. A mismatch gives a retry. [Seen code: `OffsetTimestamp.upgrade`, `EditorTimestamp.upgrade`]
7. Retry = `RdRetryResponse` with a bumped global retry timestamp. A request with an older retry timestamp is dropped without any answer. [Seen code]
8. The document version hash is a rolling hash of the edit history. When the backend applies a client document change it **adopts the client's `newVersion` as given**. [Seen code: `DocSync.ensureDocumentVersion`]
9. The editor version is recomputed by the backend for every caret item it applies or sends (`caretModelVersion + 1`, hash of the first caret). [Seen code]
10. `BackendCmdItemHandler.validate` always returns true: the command-id chain (`RdCmdItemVersion`) is **not** enforced. [Seen code, bytecode checked]
11. The `::` prefix goes on every command name the backend writes into a patch. [Seen code: `RdCmdModelSupport.toModel(String)` with prefix `"::"`]
12. A modal dialog on the backend does not make it refuse requests. No backend code lets `RequestResponseModel` run during a modal dialog, so requests stay queued until it closes. [Inferred, 6.4]

---------------------------------------------------------------------------------------------------

## 1. Request types and how each runs

### 1.1 Which types exist

Subclasses of `RdRequest` in the model: `RdApplyPatchRequest`, `RdCallActionRequestBase` (-> `RdCallActionRequest`),
`RdCallEditorActionRequest`, `RdTypeKeyRequest`, `RdAbstractUndoRequest` (-> `RdUndoRedoRequest`), `RdSaveAllRequest`,
`RdRefreshRequest`, `RdSaveSettingsRequest`, `RdNavigationBarRequest`. [Seen code: model `javap`]

Processors are registered as `rdserver.requestExecutor forClass=... implementationClass=...` in `intellij.platform.backend.split.xml`
(lines 503-510 and 674). Look-up: `BackendAsyncRequestDelegates.getDelegate(request)` = `ClassExtension.findSingle(request.getClass())`. [Seen code]

| Request | Processor | Description string |
|---|---|---|
| `RdApplyPatchRequest(patch)` | `document.BackendApplyPatchRequestProcessor` | "apply patch" |
| `RdTypeKeyRequest(char, editorId, speculativePatch)` | `actions.BackendTypeKeyRequestProcessor` | "type char" |
| `RdCallEditorActionRequest(actionId, editorId, speculativePatch, timestampSetModel)` | `actions.BackendCallEditorActionRequestProcessor` | "call editor action" |
| `RdCallActionRequest(actionId, actionPlace, presentation, inputEventModel, isInsideCommand, command, timestampSetModel)` | `actions.BackendCallActionRequestProcessor` | "call action" |
| `RdUndoRedoRequest(fileEditorId, fileIds, undoStackSizes, redoStackSizes, isUndo, undoProjectId)` | `undo.BackendUndoRedoRequestProcessor` -> `BackendUndoManager` | "call undo/redo" |
| `RdSaveAllRequest` | `document.BackendSaveAllRequestProcessor` | "save files" |
| `RdSaveSettingsRequest(forceSavingAllSettings, forceExecuteImmediately)` | `document.BackendSaveSettingsRequestProcessor` | "save settings" |
| `RdRefreshRequest` | `document.BackendRefreshRequestProcessor` | "refresh file system" |
| `RdNavigationBarRequest` | `navigation.BackendNavBarRequestProcessor` | not read (not text editing) |

There is **no completion request type** on this model. Completion runs on its own RPC protocol (`intellij.platform.completion.*`). It meets this model in two places [Seen code]:
- Choosing a lookup item with Enter or Tab arrives as a normal `RdCallEditorActionRequest` (action `ChooseItemAction`). `BackendCompletionChooseItemActionHandler` is registered on `rdserver.backendEditorActionExecutorDelegateHandler` with `order="before defaultHandler"`. For a `ChooseItemAction` it applies the selection from the backend's lookup mirror and waits up to **2 seconds** for the commit (`AWAIT_TIMEOUT = toDuration(2, SECONDS)`).
- Backend completion code waits for the request pipeline with `awaitPatchEngine` (30 s default), see 6.1.

Not accepted: `RdRequestFocusRequest` is a patch item (`RdEditorChange`); `RdGutterExecutionRequest` and `RdGutterHoverRequest` are not `RdRequest`.
Any other `RdRequest` (also `RdRequest_Unknown`) has no processor: `Executor.executeRequest` throws
`IllegalStateException("Unknown request: <SimpleClassName>::<requestId>")` -> `RdFailureResponse` (1.8). [Seen code: string `Unknown request: \u0001::\u0001`]

### 1.2 Common pipeline for one request (`Executor.executeRequest`)

In this order, on the EDT [Seen code, bytecode]:
1. `PatchEngine.awaitNoPatches()` (suspends while any accumulating session is open, 6.2).
2. `ActivityTracker.inc()`, `IdleTracker.restartIdleTimer()` (counts as user activity).
3. Find the processor; none -> `IllegalStateException("Unknown request: ...")`.
4. `softAssertThereIsNoActivePatch(...)`, then `openAccumulatingSession(sendOnClose = false, "backend: <description>", reason UNKNOWN)`.
5. `processor.execute(context, changeScope, request)` wrapped in `runCatching` + `getOrLogException` (errors are logged and the patch built so far is still used), inside the outer catches of 1.8.
6. `session.buildPatchAndClear()` -> `RdSuccessResponse(patch, requestId, telemetry)`; `_lastProcessedRequest = requestId`.
7. Safety net: sessions still open afterwards -> `Logger.error("There are unclosed accumulating sessions after recordChanges. Close them forcibly")`, closed.

`sendOnClose = false`: the patch is returned in the response and **not** also fired on `backendPatches`.

### 1.3 `RdTypeKeyRequest` -- `BackendTypeKeyRequestProcessor.execute`

[Seen code, bytecode]
1. `BackendEditorHost.tryGetOwnRemoteEditor(editorId)`; null -> `patchEngineNoEditor` (message `Editor with id='<TextControlId>' is not found`, see 1.8).
2. `getCmdStartFinish(session, speculativePatch)`: only if `speculativePatch != null` and it holds at least one `RdCmdItem`. Start event = the **first** `RdCmdItem` in `changes`; finish event = the **last** (`RdCmdModelSupport.getCmdEventFromPatch`). Nothing else in the patch is read. A null or empty patch is fine (no foreign command).
3. With both events: `ForeignCommandProcessor.startCommand(start)`: registers the **client's command id** (negative number) with `CmdIdService` and starts an IntelliJ command with the client's name, group id and undo metadata.
4. Loop over extension point `rdserver.backendTypedActionExecutorDelegateHandler` (`Handler.performTyping`); the first handler that returns true wins. Only the default exists in the IDE: `DefaultTypingRequestHandler` = `EditorImpl.type(String.valueOf(char))`, always returns true, ignores the `ActionCallback`. A handler exception is logged and the next handler tried.
5. `finally`: `ForeignCommandProcessor.finishCommand(finish)`.

`EditorImpl.type(String)` (bytecode, `intellij.platform.ide.impl.jar`): for each char `processKeyTyped(c)`; that returns false (nothing typed, silently) while `ProgressManager.hasModalProgressIndicator()`; otherwise `ActionManagerEx.fireBeforeEditorTyping`, then `processKeyTypedNormally` -> `TypedAction` chain (typed handlers: auto-close of brackets and quotes, smart typing). [Seen code for this chain; the TypedAction internals are Inferred.] The typing action wraps itself in a command ("Typing") and a write action. [Inferred]

No `timestampSetModel` on this request: the char is typed **at the backend's current caret**, whatever offset the client had. `char` is one UTF-16 unit. [Seen code]

### 1.4 `RdCallEditorActionRequest` -- `BackendCallEditorActionRequestProcessor.execute`

[Seen code, bytecode] In order:
1. `ActionManager.getAction(actionId)`; null -> `PatchEngineFailedException("Action with id='<actionId>' is not found")` (plain failure).
2. `tryGetOwnRemoteEditor(editorId)`; null -> `patchEngineNoEditor`.
3. `withContext(Dispatchers.EDT)`: `ActionUtilsKt.createDataContext(session, actionId, timestampSetModel)` (fallback `EditorUtil.getEditorDataContext(editor)`). Timestamp checks run **lazily when the action first reads the data** (2.4). Only extensions of `rdserver.actionTimestampValidator` run eagerly (`BackendExecutionLineTimestampValidator`: debugger only).
4. `AnActionEvent.createFromAnAction(action, null, "keyboard shortcut", dataContext)`: place string `"keyboard shortcut"`, no input event.
5. Same foreign-command handling as 1.3.
6. Loop over `rdserver.backendEditorActionExecutorDelegateHandler`; default `DefaultActionRequestHandler` = `ActionUtil.performAction(action, event)`. **No `update()` and no enabled check** here (unlike `RdCallActionRequest`). Completion's handler (1.1) goes first.
7. `finally`: `finishCommand`.

So `EditorEnter`, `EditorBackSpace`, `EditorDelete`, `EditorPaste`, `EditorPasteSimple`, ... are the IntelliJ editor actions with those ids. They make their own command and write action inside (`EditorWriteActionHandler`). [Inferred] The processor itself takes no write action.

### 1.5 `RdCallActionRequest` -- `BackendCallActionRequestProcessor`

[Seen code, decompiled] Action found via `ActionProvider.getAction(id, event, session)`; not found -> `IllegalStateException("`<id>` action not found")` (logged, `RdFailureResponse`). Then:
- `ActionFilterProvider` may forbid it: `warn("... is forbidden and won't be executed")`, still a success response.
- Presentation not enabled: `warn("... is disabled and won't be executed [...]")`, nothing runs, success response.
- Group without perform: `IllegalStateException("Non-performing action group ... cannot be invoked")`.
- Dumb mode and not DumbAware: feature item `RdDumbModeNotification(projectId, actionId, message)` in the patch, nothing runs.
- Run via `executeInCommand(project, request.isInsideCommand, cmdEvent, ...)`: `request.command` present -> `ForeignCommandProcessor.start/finishCommand`; else `isInsideCommand` -> `CommandProcessor.executeCommand`; else no command. Invocation = `Handler` extension (default: `action.actionPerformed(event)`, or `ToggleAction.setSelected` when the presentation carries a toggle state).
- `ReadOnlyModificationException`: notification group "RDCT PermissionManager", request still answered.

### 1.6 `RdApplyPatchRequest` -- `BackendApplyPatchRequestProcessor.applyClientPatch`

[Seen code, decompiled] In order:
1. `patch.features` not empty -> `Logger.error("Patch contains features: ...")` (log only; features are not applied).
2. `Registry.validate(patch.expectedItemVersions)`; false -> `PatchEngineRetryException("not matched expected versions")` -> `RdRetryResponse` (2.1, 1.8). **Nothing is applied.**
3. Access tokens from `rdserver.backendPatchAccessTokenProvider`.
4. `Registry.apply(patch.changes)`: per item, handler by the item's class:
   - `RdDocumentChangeBase` -> `BackendPatchDocumentItemHandler.apply` -> `DocSync.receiveDocumentChange(change, false)`
   - `RdSelectionChangeBase` (caret items) -> `BackendEditorPatchItemHandler.apply` -> `EditorSync.receiveSelection`
   - `RdCmdItem` -> `BackendCmdItemHandler.apply` -> `RdCmdItemHandler.perform` (starts or finishes a foreign command)
5. Release tokens. `Registry.validate(patch.updatedItemVersions)`; false -> only `Logger.error("Post-apply patch validation failed: ...")`.
6. **Echo**: `changeScope.pushChange` for every change, `pushExpected` for every expected version, `pushUpdated` for every updated version. The response patch **contains the client's own items again**, plus what IntelliJ recorded while applying (7.5).

Details of step 4 (bytecode of `Registry.apply`):
- The result of `Registry.apply` is **ignored** by the processor.
- A handler returning `false` ends the loop; the rest of the items is silently not applied. Document handler: false if no synchronizer. Editor handler: false (plus `error("cannot find synchronizers for ...")`) if no synchronizer.
- A handler exception is logged (`getOrLogException`) and counted as `true`, so the loop continues. Example: a document change whose `oldDocumentLength` differs from the backend text length throws `IllegalStateException("expected document length: X, but actual length: Y")` in `receiveDocumentChange`; the change is not applied, the error only logged, later caret items are applied, and the response still echoes the whole patch as if it worked. **The client gets no signal.**
- An item class without handler (e.g. `RdStackSizeItem`): NPE, logged, counted `true`.
- Document changes are applied inside `Application.runWriteAction`, one per change; without a running command they go through `CommandProcessor.runUndoTransparentAction` (source of the extra `Start("::")`/`Finish("::")`, 7.5). Caret items: `CommandProcessor.executeCommand(project, ..., "", noneGroup)` when the session is a controller, else direct. [Seen code: `DocSync.receiveDocumentChange$lambda$1`, `EditorSync.receiveCaretState`]

### 1.6b `RdUndoRedoRequest` -- `BackendUndoManager.handleUndoRedoRequest`

[Seen code, decompiled]
1. `fileEditor = fromModel(request.fileEditorId)`; `docRefs` = the editor's document references **filtered to fileIds listed in `request.fileIds`**.
2. `isUndoAvailable(fileEditor)` / `isRedoAvailable(fileEditor)` by `isUndo`.
3. Not available: nothing is undone. For each docRef push `RdUndoUtilsKt.undoCorruptionItem(fileId)`; error `"undo/redo not available"`.
4. Available: `WriteIntentReadAction.run { undoOrRedo(fileEditor, isUndo) }` on the EDT (IntelliJ's own undo command; `::` prefix from `BackendUndoSpy`, 7.4). Afterwards push `RdStackSizeItem(fileId, undoSize, redoSize)` for the global stack (fileId null) and for each docRef, sizes read **after** the undo.
5. Size check after the undo (`sameStackSizes`): index 0 of `undoStackSizes`/`redoStackSizes` = global stack; index `i+1` = `fileIds[i]`. Backend sizes differ from the request's -> the `RdStackSizeItem` is replaced by `undoCorruptionItem(fileId)`, error `"stack size for <fileId> not match"` collected.
6. Any collected error: `Logger.error("undo/redo stacks are out of sync\n<message>")` with attachment `backend-undo-corruption-dump.txt`.

The backend **never answers retry or failure for undo.** A size mismatch does not stop the undo (already done); the client sees a corruption item instead of a stack size item.
`undoProjectId == null` -> application-level undo manager (`UndoManagerImpl.getGlobalInstance()`), else the project's. [Seen code]

### 1.7 The three small ones

[Seen code]
- `RdSaveAllRequest`: nothing unless `GeneralSettings.isSaveOnFrameDeactivation` **and** `canSyncOrSave()` (= not `LaterInvocator.isInModalContext()` and no modal progress). Then `FileDocumentManagerImpl.saveAllDocuments(false)` and `ManagingFS.flushPendingUpdates()`. No patch of its own.
- `RdSaveSettingsRequest`: same two conditions, then `SaveAndSyncHandler.scheduleSave(SaveTask(null, forceSavingAllSettings), forceExecuteImmediately)`.
- `RdRefreshRequest`: if `GeneralSettings.isSyncOnFrameActivation` -> `SaveAndSyncHandler.scheduleRefresh()`. A refresh may later reload documents (section 5).

### 1.8 Errors and what each becomes (`Executor.executeRequest`, exception table at the end)

[Seen code, bytecode]

| Thrown | Response | `_lastProcessedRequest` set? | Example message |
|---|---|---|---|
| `PatchEngineRetryException`, single request | `RdRetryResponse(message, metadata=null, incrementGlobalRetryTimestamp().toModel(), requestId)` | **no** | `not matched expected versions` |
| `PatchEngineRetryException`, request inside a batch (6.1) | **empty `RdSuccessResponse(patch=null, requestId)`** | yes | message lost |
| `PatchEngineRecentlyRemovedEditorException` | `RdNoEditorFoundResponse(message, requestId)` | yes | `Editor with id='...' is not found` |
| `PatchEngineFailedException` | `RdFailureResponse(message, requestId)` | yes | `Action with id='EditorFoo' is not found`, `Caret count was changed` |
| `ProcessCanceledException`, `CancellationException` | `RdRetryResponse(getThrowableText(e), null, incrementGlobalRetryTimestamp(), requestId)` | **no** | stack trace text |
| any other `Throwable` (also "Unknown request") | `Logger.error(e)`; `RdFailureResponse(getThrowableText(e), requestId)` | yes | stack trace text |

`incrementGlobalRetryTimestamp()`: `globalRetryTimestamp = globalRetryTimestamp.incrementTimestamp()` = `(timestamp + 1, same generation)`.
`patchEngineNoEditor` throws the "recently removed" kind when `BackendEditorHost.isRecentlyUnbound(editorId)`; that is the only way to get `RdNoEditorFoundResponse`.

---------------------------------------------------------------------------------------------------

## 2. Version checks

### 2.1 Where `expectedItemVersions` are checked

Only for `RdApplyPatchRequest` (1.6 step 2), through `Registry.validate(versions)`. [Seen code]

`Registry.validate` (bytecode): empty list -> true. Else for each version, find the handler by the **version's class** and call `validate`. First `false` stops with false. A handler exception is logged and counted **true** (a missing handler is such an exception).

Handlers (extension `rd.backendRdPatchItemHandler`, xml lines 527-529):

| Version class | Handler | `validate` is true when | Otherwise |
|---|---|---|---|
| `RdPatchDocumentVersion(version, uniqueId, id=RdDocumentId)` | `BackendPatchDocumentItemHandler` | synchronizer for `id` exists and `DocSync.getVersion() == version.version`: **`value` and `hash`** equal. `uniqueId` is **not** compared. | no synchronizer: false, no log. Differ: `warn("Not matched versions for <id> documentId, actual: ..., expected: ...")`, false |
| `RdPatchEditorVersion(version, id=TextControlId)` | `BackendEditorPatchItemHandler` | synchronizer for `id` exists and `EditorSync.getVersion() == version.version`: **`caretModelVersion`, `documentHash`, `hash` all equal** | none: `warn("There is no EditorSynchronizer for <id> documentId")`; differ: `warn("Not matched versions for <id> editorId, actual: ..., expected: ...")`; both false |
| `RdCmdItemVersion(RdCmdVersion(hash), id=RdCmdId)` | `BackendCmdItemHandler` | **always**. It computes `fromModel(version)` and `CmdIdService.currentCommandId()`, calls `areEqual`, and **drops the result** (`istore; iconst_1; ireturn`) | never |

Example: client sends expected `[RdCmdItemVersion(-16), RdPatchEditorVersion((35, 3879811, 122288), editorId), RdPatchDocumentVersion((17, 8653263034029471048), uniqueId, docId)]`. Backend editor `(35, 3879811, 122288)` and document `(17, 8653263034029471048)`: valid; the cmd version is not even compared. Backend document at `(18, ...)`: `RdRetryResponse("not matched expected versions")`, nothing applied.

A failed check is always a **retry**, never a failure. The message is the constant `"not matched expected versions"` (`BackendApplyPatchRequestProcessorKt.retryMessage`).

### 2.2 TypeKey and CallEditorAction do not validate versions

Their `speculativePatch` expected/updated lists are never read; only the first and last `RdCmdItem` (1.3). A stale TypeKey is not detected: it types at the backend's current caret and the client learns from the response patch. The protection against stale requests is the retry timestamp (2.5).

### 2.3 Undo

No version check; stack sizes are compared after the undo (1.6b).

### 2.4 Action timestamps (`timestampSetModel` of `RdCallEditorActionRequest` and `RdCallActionRequest`)

[Seen code: `ActionUtilsKt.createDataContext`, `BackendActionDataContext.createTimestampProvider`, `actions.timestamps.*`]

`createDataContext` builds `ActionTimestamp`s from the model through the `BackendActionTimestampProvider` extensions. The data context is a snapshot provider: when the action reads a data key (for example `CommonDataKeys.EDITOR`), each request timestamp that is an `UpgradableTimestamp` gets `upgrade(actualSet)` called once (result cached):

| `upgrade` result | Backend reaction |
|---|---|
| `Failure(message)` | `patchEngineFail(message)` -> `RdFailureResponse(message)`; the context is marked failed (later reads fail with `"Requesting snapshot for already failed DataContext"`) |
| `RetryRequest(message)` | `patchEngineRetry(message)` -> `RdRetryResponse(message, ..., bumped global stamp)` |
| `Success` | the upgraded value is used |

| Class (key) | Check | Result strings |
|---|---|---|
| `EditorTimestamp` (`CommonDataKeys.EDITOR`) | editor exists; `caretTimestamps.size == editor.caretModel.caretCount`; each caret upgraded, then the **upgraded** offset must equal the backend caret offset and the upgraded selection must equal the backend `selectionStart/End` | `Failure("Editor not found")`, `Failure("Caret count was changed")`, `RetryRequest("Caret position was changed")`, `RetryRequest("Selection was changed")` |
| `CaretTimestamp` (`CARET_MODEL`) | offset upgrade, then range upgrade; first non-success returned | below |
| `OffsetTimestamp` (`DOCUMENT_OFFSET`) | `shifter.shiftOnePoint(document.version, offset, isStickingToRight)` on the synchronizer of `document.documentId`; `-1` = cannot move | `Failure("No Document Synchronizer")`, **`RetryRequest("The offset was changed beyond recovering")`** |
| `RangeMarkerTimestamp` (`DOCUMENT_RANGE`) | needs a `ProjectTimestamp` with data; `shifter.shiftRange(...)` | `Failure("No Project")`, `Failure("No Document Synchronizer")`, **`RetryRequest("Range was changed beyond recovering")`** |
| `LogicalLineTimestamp`, `DebuggerExecutionLineTimestamp`, `ProjectViewTimestamp` | similar, not text editing | |

So the strings from the question come from here, **not** from the patch-apply path:
- `"The offset was changed beyond recovering"`: `shiftOnePoint` returned `-1`: the point lies inside text replaced or deleted since the client's document version, a `RdResetDocumentChange` lies in between, or the base version is unknown or too old (3.2).
- `"Range was changed beyond recovering"`: same for the selection range.
- `"Caret position was changed"` / `"Selection was changed"`: the shifted client caret is not where the backend caret is now.

Other timestamp kinds (Document, Project, VirtualFile, FileEditor, Control id, ...) are plain data and not compared. [Seen code]

Quirk: `ActionUtilsKt.validateTimestampSet` (eager validator path) calls `patchEngineFail` also for `RetryRequest` (both branches, bytecode checked). Only the debugger-line validator uses it.

### 2.5 The retry timestamp gate (decides retry or silent drop)

`RdRequestHolder.retryTimestamp = RdRetryTimestamp(timestamp, generation)`. In `Executor.executeRequests`, single-request path, under `retryTimestampMutex`: [Seen code]
```
if (fromModel(holder.retryTimestamp) < globalRetryTimestamp) -> drop (no response, _lastProcessedRequest unchanged)
```
`compareTo`: generation first, then timestamp. Initial `globalRetryTimestamp = (timestamp 0, generation 0)`.

| Global now | Request carries | Result |
|---|---|---|
| (0,0) | (0,0) | runs |
| (1,0) after one retry | (0,0) | dropped, **no answer at all** |
| (1,0) | (1,0) | runs |
| (0,1) after `dropAllRequests` | (5,0) | dropped (generation 0 < 1) |
| (0,1) | (0,1) | runs |

`dropAllRequests` (`RdCall<Unit,Unit>`, `Executor.bindWithModel$2`): under `retryTimestampMutex` (so it waits for a running single request) `globalRetryTimestamp = globalRetryTimestamp.incrementGeneration()` = `(0, generation + 1)`. Queued requests are not touched; they are dropped when they reach the gate.

---------------------------------------------------------------------------------------------------

## 3. Offset shifting (`IDocumentRangeShifter`)

[Seen code: `DocSync.createRangeShifter()` = `DocumentLogRangeShifter(documentLog)`; `DocumentLogRangeShifterKt`, `DocumentLog`, `DocumentLogSnapshot`]

Used by `OffsetTimestamp.upgrade`, `RangeMarkerTimestamp.upgrade` (2.4) and backend-internal code. `RdApplyPatchRequest` does **not** shift: it validates exactly (2.1). Nothing rebases a client edit onto a newer document; stale applies get a retry.

### 3.1 The log

Each `DocSync` owns `DocumentLog(considerGlobalSuppressors = true)`: map `version.value -> RdDocumentChangeBase` (the change whose `newVersion` has that value). Entries are added by `documentChanged` (`addOrUpdate`), `sendInitialTextChange` (the `RdResetDocumentChange`), and when a client change is applied (`ensureDocumentVersion`: first `resetVersion(change.newVersion)` which **deletes all entries with value > newVersion.value** (`rollbackHistoryToVersion`), then `addOrUpdate`). The shifter reads an immutable snapshot republished by `publish()` after each document change on the EDT.

### 3.2 When it gives up

`checkVersions(snapshot, base)`: `snapshot.contains(base)` must hold: an entry under key `base.value` **whose `newVersion` equals `base` (value and hash)**. Otherwise:
- `base.value > snapshot.head.value`: `warn("Base version <v> is newer than the latest recorded document version <head>: the document has not received the changes up to this version yet ...")`
- else: `warn("Base version <v> is older than the oldest retained change and was already cleaned up: it is too old to shift from.")`

Both return `-1` / `null`, giving the "beyond recovering" retry. A right value with a wrong hash fails the same way.

### 3.3 How long versions are kept

[Seen code: `DocumentLog.updateLastKnownVersion`, `shrinkHistory`]
- `shrinkHistory` deletes entries with `value < floor`, `floor = min(lastKnownVersion.value, smallest pinned value)`. The `floor` entry itself stays.
- `lastKnownVersion` only moves forward. It is set by `DocSync.updateLastKnownVersion`, called from `BackendPatchDocumentItemHandler.update`, which `Registry.update(expectedItemVersions)` calls for **the expected versions of every patch the backend sends**: after each successful single request (`Executor.executeRequests`, response patch non-null) and in `BackendPatchEngineHost.sendPatch` before each `backendPatches` fire.
- So `lastKnownVersion` = the **old** document version of the most recent patch that touched the document. Example: the backend sends a response whose doc entry goes `(17,h17) -> (18,h18)`. From then on the log keeps 17, 18, ...; entries <= 16 are gone. A client timestamp based on 16 fails ("too old"); on 17 or 18 it works.
- A timer in the `DocSync` constructor repeats `updateLastKnownVersion(lastKnown)` and `publish()` every **10 seconds** (re-runs shrink; does not move lastKnown).
- Pins: `captureAndPinShiftBase(lifetime)` (via `withRangeShifter`, backend-internal code such as markup) keeps `value >= pinned` while its lifetime lives. A remote client cannot pin.
- `isShrinkingAllowed` asks `DocumentLogShrinkingSuppressor`s; the only implementations in the jars are in the client (`FrontendMarkupSuppressor`), none on the backend, so shrinking is always allowed. [Seen code: jar scan]

Practical rule: base a timestamp on a document version at least as new as the doc entry of `expectedItemVersions` of the last response or backend patch for that document. A client that applies backend patches in order and uses its latest applied version satisfies this.

### 3.4 `shiftOnePoint(changes, offset, isStickingToRight, surviveRangeRemoval=false)`

`changes` = log entries with `value > base.value`, in order (`changesAfter`: index of the entry with the greatest value <= base.value, plus 1, to the end). Per change: `start = offset`, `end = start + oldText.length`, `delta = newText.length - oldText.length`:
- `RdResetDocumentChange` in the list: `-1`.
- point `< start`: unchanged.
- point `== end` (and `>= start`): if `delta > 0` and `modificationKind == RightSide`: unchanged. Else if sticking right: `+= delta`. Else if `delta <= 0` or `balance != 0`: `+= delta` and (`delta < 0` -> `balance++`; `delta > 0` -> `balance--`). Else unchanged.
- `start <= point < end`: `point == start` stays; otherwise `-1`.
- point `> end`: `+= delta`.

Example, change replaces `"ab"` at 5 with `"xyz"` (`start 5, end 7, delta +1`), not sticking:

| point | result |
|---|---|
| 4 | 4 |
| 5 | 5 (at start, stays) |
| 6 | **-1** (inside replaced text) |
| 7 | 7 (at end, growing replace: stays) |
| 8 | 9 |

Pure insert `"x"` at 7 (`start=end=7`): caret at 7 not sticking stays 7; sticking right becomes 8. Delete `[5,7)` (`delta -2`): point 7 -> 5 (`balance` 1); point 6 -> `-1`.
Caret timestamps use `isStickingToRight = false` (`CaretTimestamp.create` -> `OffsetTimestamp.create$default(..., false)`).
`shiftRange` is only summarised: a change entirely before the range leaves it; entirely before-or-at start shifts it; a change inside the range resizes its end; partial overlap returns null; "move" changes (`oldText` empty and `moveOffset != offset`) move it. Read `DocumentLogRangeShifterKt.shiftRange` for exact greedy rules.

---------------------------------------------------------------------------------------------------

## 4. How the backend computes the versions it sends

### 4.1 Document version `RdDocumentVersion(value:int, hash:long)`

[Seen code: `DocumentVersionsKt`, `IncrementalHashKt.getPlatformIndependentHash`, `DocumentVersionHolderImpl`; recomputed in Python and equal to the doc's numbers]

64-bit signed, wrap-around.
```
mix(h:long, v:int)    = h*31 + v
mix(h:long, s:String) = for each UTF-16 char c: h = h*31 + c       (no +1)
initial (no text)     = (-1, 0)
text reset            : value+1, hash = mix(127, text)
incremental change    : value+1,
   hash = mix(mix(mix(mix(mix(old.hash, offset), moveOffset), oldDocumentLength), oldText), newText)
```
Examples: `"abc"` reset: `(0, 3879811)`. Then insert `"x"` at 3 (`oldDocumentLength 3`): `(1, 3583087027000)`. The doc's `mix(-5446235163934729545, 210, 210, 260, "", "\n    ") = -6257571537734692227` is reproduced. [Verified]

Where the numbers come from:
- Backend edit (`DocSync.documentChanged` -> `VersionsExKt.createRdDocumentChange`): `offset`, `moveOffset`, `oldText = oldFragment`, `newText = newFragment` from the `DocumentEvent`; `oldDocumentLength = document.textLength + event.oldLength - event.newLength` (length **before** the change); then `versionHolder.incrementVersion(...)`. `modificationKind` is `LeftSide`/`RightSide` only for an insertion (`oldFragment` empty) where `DocumentEventImpl.initialStartOffset` differs from the offset, else `NotSpecified`; `isWholeTextReplaced = event.isWholeTextReplaced`.
- Client edit applied by the backend: **no recomputation**. `ensureDocumentVersion(change)`: if `change.newVersion != current`, `resetVersion(change.newVersion)`. The backend's version is whatever the client wrote. A wrong hash is not detected (the post-apply `validate(updatedItemVersions)` compares the adopted value with the same client value). `applyChange` only logs `error("Unexpected version: <current>, expected: <change.oldVersion>...")` when `change.oldVersion != current`. It **throws** only when `change.oldDocumentLength != document.textLength` (1.6).
- Reset on bind and on `getEditorStates`: `sendInitialTextChange()` -> `RdResetDocumentChange(text, docId, newVersion, oldVersion, uniqueId)`, `newVersion = (old.value+1, mix(127, text))`.
- `RdPatchDocumentVersion.uniqueId` = `DocumentUniqueIdSessionRegistry.getUniqueId(document)` (registered at bind from `RdDocumentModel.uniqueId`); null -> error log and `""`. Not compared on validate.

### 4.2 Editor version `RdEditorVersion(caretModelVersion:int, documentHash:long, hash:long)`

[Seen code: `EditorVersionsKt`, `EditorVersionHolder`, `EditorSync`]
```
initial                  = (-1, 0, -1)            // documentHash = hash of initial doc version = 0, not -1
increment(old, state)    = (old.caretModelVersion + 1, state.documentVersion.hash, computeHash(state))
computeHash(state)       = 0 if no carets, else computeHash(state.carets[0])      // FIRST caret only
computeHash(caret):
   init = 127*31 + caret.id.number                                                // 3938 for caret number 1
   if selection.start.offset != selection.end.offset:                              // virtualDelta ignored
        init*31 + ((init*31 + start.offset) * (init*31 + end.offset))
   else init*31 + caret.offset.offset
```
Examples: caret 1 at 210: `3938*31 + 210 = 122288`. Selection 140..148: `14938339346`. Selection 40..48: `14913904946`. [Verified in Python]

When the backend increments:
- `sendCaretState` (it sends a caret item): increments with the **new** state. The item's old/new editor versions are before/after.
- `receiveCaretState` (it applies a client caret item): `applyStateTo(newState, editor)`, then `versionHolder.incrementVersion(newState)`: **one increment per received caret item** whatever the client claimed. Then it reads the caret back and logs `error("current state hash: X, doesn't match to received state hash: Y ...")` if the hashes differ; and warns/errors `"Received newState based on document version: ..., but actual document version is ..."` if `newState.documentVersion != DocSync.getVersion()` (warn only during recovery).
- `sendInitialCaretState()`: `RdCaretStateSnapshot(false, oldState, editorId)`, reason `INIT`, one increment.

So the client's editor version matches only if it adds exactly `+1` per caret item (change or snapshot, sent or received), takes `documentHash` from the `documentVersion` **inside the state**, and hashes the first caret the same way. One skipped or doubled step makes every later `RdApplyPatchRequest` fail with "not matched expected versions".

### 4.3 What goes into `expectedItemVersions` / `updatedItemVersions`

[Seen code: `PatchBuilder`, `DocSync.sendDocumentChange`, `BackendEditorSynchronizerKt.sendChange`]
- `pushExpected` = `putIfAbsent(version.id)`: earliest version per item. `pushUpdated` = `put(version.id)`: latest. Both insertion-ordered.
- Document change, if `old != new`: `pushExpected(RdPatchDocumentVersion(old, uniqueId, docId))`, `pushUpdated(new)`, `pushChange(change)`.
- Caret item: `pushExpected(RdPatchEditorVersion(old))`; `pushUpdated(RdPatchEditorVersion(new))` only if `updatingChange` (not `RdTransparentEditorChange` and `old != new`); **also** `pushExpected(RdPatchDocumentVersion(current doc version, uniqueId, docId))`; then `pushChange(change)` (`pushFeature` for `RdFeatureItem`, e.g. scroll).
  So caret-only patches have a doc entry in **expected** but none in updated.
- The backend never writes `RdCmdItemVersion`: only `BackendCmdItemHandler` references it in the backend, rd.platform, undo and ide.impl jars. A response to TypeKey/EditorAction has expected `[editor, doc]` and updated `[editor, doc]` (editor first because the "before" caret snapshot is recorded first), never a cmd version.
- For `RdApplyPatchRequest` the response copies the client's lists (echo).

---------------------------------------------------------------------------------------------------

## 5. What the backend sends on its own (`backendPatches`)

Mechanism [Seen code]: `RdPatchEngine` collects items in a session. A session opened with `sendOnClose = true` fires when its last token closes: `ChangeAccumulatingSession.send()` -> `BackendPatchEngineHost.sendPatch`: **first** `Registry.update(patch.expectedItemVersions)` (moves `DocSync.lastKnownVersion`, 3.3), **then** `backendPatches.fire(RdPatchWithMetadata(patch, changeReason, changeReasonExtra, batchSize))`.
- `changeReason`: `INIT, COMMAND, UNDO_SPY_COMMAND, DOCUMENT_CHANGE, SELECTION_CHANGE, CARET_CHANGE, OTHER, UNKNOWN`.
- `changeReasonExtra`: class name of the command's runnable if it passes `ClassNameRuleValidator` (platform classes keep their name, third-party ones become a generic description), else null.
- `batchSize`: number of tokens that joined the session.

| Source | When | Reason | Contents |
|---|---|---|---|
| `PatchEngineCommandListener` | any IntelliJ command that is **not** a foreign command (host user's command, scheduled action, format on save, code cleanup, auto-import ...); session open from `commandStarted` to `commandFinished` | `COMMAND` | everything recorded meanwhile: doc changes, caret items, cmd items |
| `BackendUndoSpy` | `commandStarted0`/`commandFinished0` for **every** remote session; opens its own session if none is open | `UNDO_SPY_COMMAND` | `RdCmdItem` Start/Finish (+ rest if it is the opener) |
| `DocSync.documentChanged` | backend document change outside a client apply | `DOCUMENT_CHANGE` | `RdDocumentChange` + doc expected/updated |
| `EditorSync` (caret, selection, `beforeDocumentChange` listeners) | backend caret/selection moves | `CARET_CHANGE` / `DOCUMENT_CHANGE` | `RdCaretStateChange` or `RdCaretStateSnapshot` + editor versions |
| `EditorSync.scrollRequested` | backend scrolls | `OTHER` | `RdScrollingRequest(ScrollingInfo(kind, docOffset), editorId)` as a **feature**, expected = updated = current editor version |
| `PatchEngineBackendClientOperationListener`, `AnActionListenerToClientOperationListener` | another remote client's request, or the host IDE's own action or typing ("host action", "host typing"): every **other** remote session opens a session `"Alien operations from client=<id>: <operation>"` | `OTHER` | that operation's changes in documents the receiving client has open |
| `BackendDocumentHost.bindDocument`, `DocSync.sendInitialTextChange`, `BackendEditorHost` editor bind | first bind of a document or editor | `INIT` | `RdResetDocumentChange(text, (old+1, mix(127,text)), old, uniqueId)` then caret snapshot (`sendInitialCaretState`) |
| File reload from disk | document text replaced after a VFS refresh | `DOCUMENT_CHANGE` | an ordinary `RdDocumentChange` (maybe `isWholeTextReplaced = true`). [Inferred: no special reload message in these classes; `BackendDocumentSaveListener.fileContentReloaded` only sends a saved-stamp `DocumentVersionStamp(value, hash)` on another model] |

Versions: as 4.3. Consecutive backend patches chain: the `updated` of one is the `expected` of the next. Apply them in order received.

`getEditorStates(List<TextControlId>)` (RdCall, used by the client's `TrustBackendRecoveringStrategy`) is separate [Seen code, bytecode]: for each id with a `PatchEngineDocumentSynchronizer` it calls `sendInitialTextChange()` (**increments the document version again**, new `RdResetDocumentChange`), and `sendInitialCaretState()` (editor version +1) inside a `sendOnClose=false` session; the resulting `RdPatch` is **returned as the call result**, not fired on `backendPatches`. It logs `warn("... >> Start recovering...")`/`"<< End recovering editor states"` and schedules markup recovery with `invokeLater`.
`resetEditorStates` has **no** endpoint on the backend in these jars. `dropAllRequests` is served (2.5).
Responses and backend patches both come from the EDT through async signals, so the order of `fire` calls is the wire order. [Inferred]

---------------------------------------------------------------------------------------------------

## 6. Ordering, concurrency, batching, modal dialogs

### 6.1 Order, batching, `_lastProcessedRequest`

[Seen code: `Executor.bindWithModel$1`, `executeRequests`, bytecode]
- `bindWithModel`: `requestsWithTime.adviseSuspend(lifetime, protocolScheduler) { requests -> mutex.withLock { executeRequests(requests) } }`. `mutex` is a **static** `kotlinx.coroutines.sync.Mutex` of the class (`access$getMutex$cp`): **all remote clients share one queue**, one `RdRequests` at a time, in arrival order (Kotlin `Mutex` is FIFO [Inferred]). One `Executor` per client session holds `retryTimestampMutex`, `_lastProcessedRequest`, `globalRetryTimestamp`.
- `executeRequests` asserts EDT, calls `BackendClientOperationListener.beforeClientOperation(session, "<ClassName>#<id>" or "Batch request")` (this triggers the alien sessions of 5) and suppresses markup (`"Suppress while request batch is in execution"`). Then:
  - **`requests.size == 1`**, under `retryTimestampMutex` for the whole execution: (a) retry gate (2.5): older -> drop, `afterClientOperation`, return; (b) `if (requestId <= _lastProcessedRequest) Logger.error("RequestId: <id> must be greater than LastProcessedRequestId: <last>")` (**log only, it still runs**); (c) `executeRequest(isBatch=false)`; (d) response `RdSuccessResponse` with a non-null patch -> `Registry.update(patch.expectedItemVersions)`; (e) `responsesWithTime.fire(RdResponses([response], time))`.
  - **`requests.size > 1` (batch)**: **no retry gate**, no ordering log, `BackendRequestsListener.requestExecuted` once, each request via `executeRequest(isBatch=true)`; **one** `RdResponses` with all responses in request order. A `PatchEngineRetryException` in a batch becomes an **empty success** (and `_lastProcessedRequest` is set); `Registry.update` is not called; other exceptions map as in 1.8.
  - Ideate should send **one request per `RdRequests`**: only that path has the retry gate and the usual retry answers.
- `_lastProcessedRequest` (`MutableStateFlow<Long>`, initial `-1`) is set to the request id for: success, failure, no-editor, any non-cancellation `Throwable`, and batch retry. **Not** set for: dropped requests, single-path retry, cancellation retry.
  Reader: `Executor.awaitRequest(id)` = `_lastProcessedRequest.first { it >= id }` (no own timeout). Used by `BackendPatchEngineSynchronizerKt.awaitPatchEngine(timeout = 30 s)` and `DocumentSyncImpl.awaitDocumentSync`: they read `PatchEngineSynchronizationData.lastQueuedRequest` from the coroutine context and wait until that request was processed; backend features that must see the client's latest edits (completion, inline completion, identifier highlighting) use it. A dropped or retried request never advances the flow, so such a wait can hit the 30 s timeout. [Seen code for the wait; where `lastQueuedRequest` is filled is Inferred]

### 6.2 `awaitNoPatches`: requests wait for the backend's own commands

`RdPatchEngine.awaitNoPatches` = `hasActivePatchFlow.first { !it }`; `hasActivePatch` is true while the session stack is non-empty. Backend commands open a session at `commandStarted` (`PatchEngineCommandListener`) and keep it until `commandFinished`. While any backend command (host user's, another client's) is running, `executeRequest` suspends **holding the global mutex**: all clients' requests wait; a command that never finishes blocks everything. A request's own session is closed before the next request starts, so requests do not wait for each other here. [Seen code]

### 6.3 Threads

Processing is on the EDT (`ThreadingAssertions.assertEventDispatchThread()` in `executeRequests`). `RdCallEditorActionRequest` also does `withContext(Dispatchers.EDT)` for the data context and continues on the EDT. `RequestResponseModel.getExtThreading()` is `Default`. [Seen code]

### 6.4 Modal dialog open on the backend

[Mixed]
- A protocol entity can be pumped during a modal dialog via `PermittedModalityProtocolEntities.allowAnyModalityPumpingUnder(lifetime, ext)`, read by `SecureWireWritable.getOverrideScheduler()` (`AnyModalityDispatcher` for `ModalityState.any()`). In the jars read it is called for the actions model (`BackendAsyncActionHost`), `ClipboardModel` (`GuestCopyPasteManager`), popups, run configurations, color chooser, port forwarding, lux dialogs. **Not** for `DocumentsOperationModel` or `RequestResponseModel`. [Seen code: jar scan]
- So `requestsWithTime` is delivered by the default scheduler. [Inferred] While a modal dialog shows, the EDT runs a nested event loop and non-modal tasks wait. Requests are **neither refused nor answered**; they run in order after the dialog closes. No code produces a "busy" answer.
- Explicit modal checks: `RdSaveAllRequest` and `RdSaveSettingsRequest` silently do nothing in a modal context (`canSyncOrSave`); `EditorImpl.processKeyTyped` drops the char while a modal **progress** is active, and the request still gets a (probably empty) success. [Seen code]
- `ClipboardModel` is pumped during modal dialogs, so the client's clipboard content still arrives even if the paste request itself waits. [Seen code]
- Consequence: while the backend is modal the client sees silence; `dropAllRequests` and `getEditorStates` are RdCalls on the same non-permitted ext, so they would queue too. [Inferred]

---------------------------------------------------------------------------------------------------

## 7. Response patch contents

### 7.1 Principle

The response of `RdTypeKeyRequest` / `RdCallEditorActionRequest` is **not a re-run of the speculative patch**. It is a record of what IntelliJ did, from listeners: `RdCmdItem` Start/Finish from `BackendUndoSpy` (every command), caret items from `EditorSync`, `RdDocumentChange` from `DocSync.documentChanged`, features (scroll, dumb-mode notice), `RdStackSizeItem` for undo. The speculative patch only supplies id, name, group, project and undo metadata of the command (foreign command, 1.3). [Seen code]

### 7.2 Caret items around a document change (`EditorSync`)

[Seen code] `beforeDocumentChange` (when not applying a client change): opens a token (reason `DOCUMENT_CHANGE`), sends a **forced** `RdCaretStateSnapshot(documentChangeWrapping = true, state)`. When the change is over (`documentChanged` ends the lifetime) it sends **another forced** `RdCaretStateSnapshot(true, state)` with the new document version in the state. Between them the `RdDocumentChange`. Each snapshot is one editor version step: one document change = **editor `caretModelVersion` +2, document value +1**. Mode CHANGE (plain caret move from the listener) = `RdCaretStateChange(oldState, newState)` (+1); mode SNAPSHOT = `RdCaretStateSnapshot(false, state)`. Inside an all-carets action the state is postponed and sent once at the end. Trace agrees: "1 to 3 for Enter, 3 to 5 for `i`". If the caret listener fired between the two snapshots with a changed state an extra `RdCaretStateChange` would appear; the trace shows none. [Inferred]

### 7.3 Per action

Shape: `[Start, snapshot(wrap), RdDocumentChange, snapshot(wrap), ...more pairs per extra change..., Finish]` + features.

| Action | Request | Backend result |
|---|---|---|
| Type `x` at 215 | `RdTypeKeyRequest('x')` | `Start("::#")`, snapshot, `RdDocumentChange(215, +"x")`, snapshot, `Finish("::#Typing")`; feature `RdScrollingRequest(Relative, caret)`. Editor `n -> n+2`, doc `v -> v+1` |
| Type `(` | same | typed handlers also insert `)`: backend result differs from a one-char guess [Trace, section 11] |
| Type over a selection | same | two document changes: delete selection, insert; each own version (doc +2, editor +4) [Trace] |
| Backspace | `RdCallEditorActionRequest("EditorBackSpace")` | one delete; at column 0 also removes the joined line's indent (second change) [Trace] |
| Enter with auto-indent | `RdCallEditorActionRequest("EditorEnter")` | one change `+"\n" + indent` from IntelliJ's formatter, e.g. `+"\n    "` at 210; doc +1, editor +2 [Trace] |
| Paste | `RdCallEditorActionRequest("EditorPaste")` after the client set `ClipboardModel.content` | the insert, then reformat-on-paste inserts (e.g. `+"    "` at 254 and `+"      "` at 284), caret after the block; one command = one undo step [Trace]. Clipboard read from `GuestCopyPasteManager.currentContent` which `ClipboardModel.content` fills [Seen code] |
| Undo / redo | `RdUndoRedoRequest` | `Start("::Undo #Typing")` (positive id, e.g. 6), per undone change: snapshot, `RdDocumentChange`, snapshot; `Finish`; then `RdStackSizeItem(null, undo, redo)` and one per requested file; feature `RdScrollingRequest`. One undo can revert several changes of one command group [Seen code for stack items; Trace for the rest] |

Each `RdDocumentChange` carries `oldDocumentLength` = length before **that** change, so a client applies them in order.

### 7.4 The `::` prefix

[Seen code] `BackendCmdModelSupport` extends `RdCmdModelSupport("::")`; `toModel(String) = RdCommandName("::" + name)` is used in `getPatchCmdStart` and `getPatchCmdFinish`, which `BackendUndoSpy` calls for **every** command on the backend. Only a prefix is added.

| Command | IntelliJ's name | Name in response |
|---|---|---|
| foreign command from `speculativePatch`, at Start | `#` (client's) | `::#` |
| same command at Finish (name updated by IntelliJ) | `#Typing` | `::#Typing` |
| IntelliJ undo command | `Undo #Typing` | `::Undo #Typing` |
| unnamed (transparent / undo-transparent) command | `` | `::` |

The prefix is not added to names the client wrote and that are merely echoed (`RdApplyPatchRequest` response). Foreign commands keep the **client's id** (negative); commands IntelliJ makes on the backend get positive ids from `CmdIdService`. Transparent commands: `RdPatchCmdStart(id, null, null, name, DEFAULT, isTransparent=true, meta)`.

### 7.5 `RdApplyPatchRequest` response

[Seen code; order Inferred]
1. Cmd items the backend produced while applying: with no foreign command running, a document change goes through `runUndoTransparentAction` and a caret item through `executeCommand(..., "", ...)`, so UndoSpy records `Start("::")`/`Finish("::")` pairs. The client's own `RdCmdItem` Start/Finish call `ForeignCommandProcessor.start/finishCommand`, recorded as `Start("::<clientName>")`/`Finish` with the client's id.
2. Then the echo (1.6 step 6): the client's items in the client's order, names unchanged (no `::`).
Applied client changes are not recorded twice because `DocSync.documentChanged` and `EditorSync.sendCaretState` check `isReceivingChange` and skip. Expected/updated lists = the client's.

---------------------------------------------------------------------------------------------------

## 8. Corrections to `docs/edit-protocol.md` sections 5 to 9

1. **5.2** "Initial editor version: `RdEditorVersion(-1, -1 doc hash of initial -1/0, -1)`". It is `(caretModelVersion = -1, documentHash = 0, hash = -1)`: `documentHash` is the hash of `InitialDocVersion(-1, 0)` = `0` (`EditorVersionsKt`: `new RdEditorVersion(-1, InitialDocVersion.getHash(), -1L)`). Also `computeHash(state)` = `0` for a state without carets (not mentioned). [Seen code]
2. **5.3** "The expected value is the **id of the previously finished command** (-P)". True for what the real client sends, but the backend never checks it: `BackendCmdItemHandler.validate` compares and returns `true` regardless. The backend also never produces `RdCmdItemVersion`. [Seen code, bytecode]
3. **5.4** "Meaning [Inferred from client conflict checks]: the patch applies only if the receiver's current item versions equal `expectedItemVersions`". On the backend this holds **only for `RdApplyPatchRequest`** (document value+hash, editor all three fields, command never). TypeKey and CallEditorAction expected/updated lists are not read. [Seen code]
4. **5.4** "Document: ... Only pushed when old != new (no doc entry in updated for caret-only patches)". Right for `updatedItemVersions`, but every caret item **does** push a `RdPatchDocumentVersion(currentDocVersion)` into `expectedItemVersions` (`EditorSync` `sendChange`). Caret-only patches have a doc entry in expected. [Seen code]
5. **5.5** "`timestampSetModel` ... only `RdCallEditorActionRequest` has it". `RdCallActionRequestBase` (so `RdCallActionRequest`) has it too. Missing in the doc: what the backend does with it (2.4: lazy shift of caret offset and selection, with its retry and failure strings). [Seen code: model `javap`]
6. **6** "After every TypeKey/Backspace/Enter command finishes, the client also sends a transparent empty command ... It advances the 'last command id' chain". The backend ignores that chain (item 2), so the marker is optional for the backend. Also `patch.features` should be empty in `RdApplyPatchRequest` (the backend logs an error otherwise). [Seen code]
7. **6** "ApplyPatch responses contain `Start("::")`, `Finish("::")`, then the original `Start`, items, `Finish`". Consistent; cause now known (7.5): the `::` pair comes from the commands the backend wraps around applying; the original items are an **echo** pushed by `BackendApplyPatchRequestProcessor`. The echo is returned even when an item failed to apply (1.6). [Seen code]
8. **7** "`undoStackSizes = [0, 3] // [global stack size, size for fileIds[0]] [pairing order Inferred...]`". Confirmed: index 0 = global (fileId null), index `i+1` = `fileIds[i]` (`BackendUndoManager.getIndex`). [Seen code]
9. **7** "the backend presumably rejects/retries when they differ". It does neither. The undo runs first; for a mismatching stack the `RdStackSizeItem` is replaced by an undo-corruption item and `Logger.error("undo/redo stacks are out of sync")` is written. If undo/redo is not available nothing runs and corruption items are returned per file; still `RdSuccessResponse`. [Seen code]
10. **7** `undoProjectId = RdProjectId("...")`. The field is nullable (null -> global undo manager). [Seen code]
11. **8** "The patch is the backend's re-execution: the same items as the speculative patch ... `expectedItemVersions` / `updatedItemVersions` are the same as in the request." True only for `RdApplyPatchRequest` (echo). For TypeKey/EditorAction the response is built from what IntelliJ recorded; the speculative patch only gives the command identity. The response has **no** `RdCmdItemVersion` (the speculative patch had one), and its editor/doc versions are the backend's own. [Seen code]
12. **8** "`RdRetryResponse`: set global `retryTimestamp`, resend all pending requests with it". Backend side: the retry carries `incrementGlobalRetryTimestamp()`; every request with an older stamp is **dropped without any answer**. A retry inside a batch becomes an empty success. [Seen code]
13. **8** "for each `RdDocumentChange` check `oldVersion == current version` and `oldDocumentLength == document.length` (otherwise it throws "expected document length")". Only the length check throws (`IllegalStateException("expected document length: X, but actual length: Y")`). A version mismatch only logs `error("Unexpected version: <current>, expected: <change.oldVersion>...")`, then `ensureDocumentVersion` forces the version to `change.newVersion`. [Seen code: `PatchEngineDocumentSynchronizer`]
14. **8** "Version conflict handling [Inferred, no backend code here]: the backend validates `expectedItemVersions` ... on mismatch `RdRetryResponse`". Now seen: only for `RdApplyPatchRequest`; message `"not matched expected versions"`. `RdFailureResponse` comes from a missing action or editor (`"Action with id='X' is not found"`, `"Editor with id='...' is not found"`) and timestamp `Failure` results (`"Editor not found"`, `"Caret count was changed"`, `"No Document Synchronizer"`, `"No Project"`). Timestamp mismatches give **retry**: `"Caret position was changed"`, `"Selection was changed"`, `"The offset was changed beyond recovering"`, `"Range was changed beyond recovering"`. [Seen code]
15. **8** "`RdFailureResponse(message)` / `RdNoEditorFoundResponse` end the request". `RdNoEditorFoundResponse` is sent only when the editor id is "recently unbound" (`isRecentlyUnbound`); otherwise a missing editor is a plain `RdFailureResponse`. [Seen code]
16. **9** "whether the backend demands that chain is Inferred" and "sending an empty patch is [Inferred] possible only if the backend does not require the patch (not verified)". Answered: the backend does **not** demand the command chain; TypeKey/EditorAction accept a null or empty `speculativePatch` (only the first and last `RdCmdItem` are read; none = no foreign command, IntelliJ names the command itself). [Seen code]
17. **9** step 2 "`RdRetryTimestamp(0,0)`". Works only while the backend's global stamp is `(0,0)`. After the first `RdRetryResponse` it is `(1,0)` and requests with `(0,0)` are dropped silently. Take the stamp from the last retry response (and a higher `generation` after `dropAllRequests`). [Seen code]
18. **10** "Backend code not available: version-mismatch behaviour, whether `char` or `speculativePatch` drives the backend's typing, whether an empty `speculativePatch` is accepted, backend-side id counters." Answers: `char` drives typing (`EditorImpl.type`); the patch only supplies command identity; an empty patch is accepted; the backend has no counter for client command ids (it uses the client's id as given via `ForeignCommandProcessor`; its own commands use `CmdIdService`). [Seen code]
19. **8** "In the capture-3 typing window there were **no** `backendPatches` records". Fine for that window, but backend patches are a normal part of the protocol (section 5): backend commands outside a request, host-side typing, other clients' edits, saves and reloads produce them. [Seen code]

Confirmed by the code: document hash formula and examples, editor hash formula and examples, `pushExpected`/`pushUpdated` semantics, scroll feature after typing and undo, `::` names, `RdStackSizeItem` after undo, "one caret snapshot pair = +2 editor version", "trust backend versions".

---------------------------------------------------------------------------------------------------

## 9. Rules for Ideate in short

1. One request per `RdRequests`; `retryTimestamp` = the last one received in an `RdRetryResponse` (start `(0,0)`); do not wait for answers to requests sent before a retry.
2. Typing and editor actions: send an empty or null `speculativePatch` if you do not predict. The backend types at **its** caret. If you predict locally, replace the prediction by the response patch whenever they differ.
3. `RdCallEditorActionRequest.timestampSetModel`: send editor timestamp with caret offset and selection **and the document version (value+hash) they are based on**, a version the backend still keeps (>= the old doc version of its last doc patch, 3.3). Otherwise retry `"The offset was changed beyond recovering"`.
4. `RdApplyPatchRequest` (caret moves): expected doc and editor versions must equal the backend's current ones exactly (doc value+hash; editor all three fields). Retry `"not matched expected versions"` means nothing was applied. Use the `updatedItemVersions` of the last response or backend patch.
5. Keep the document hash chain exactly (4.1) and the editor chain with exactly one `+1` per caret item (4.2). The backend adopts your document `newVersion` blindly and recomputes the editor version itself.
6. A failed apply is not reported (1.6): compare the echoed patch with your state; a wrong `oldDocumentLength` is swallowed on the backend.
7. `RdNoEditorFoundResponse` only after the editor was closed; `RdFailureResponse` for unknown action ids.
8. While a backend modal dialog is open expect silence, not errors.

## 10. Not resolved from these jars

- Write action and command wrapping inside `TypedAction` and the editor actions (platform code outside the jars read) [Inferred].
- Whether `Dispatchers.EDT` without a modality element is `nonModal` for the data-context step of `RdCallEditorActionRequest` [Inferred].
- How `PatchEngineSynchronizationData.lastQueuedRequest` is filled on the client side (not read).
- `RdUndoManagerBase.isUndoAvailable` / `undoOrRedo` internals and the exact `Undo <name>` naming (jar not read) [Inferred].
- `shiftRange` is summarised only.
