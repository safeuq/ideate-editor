# One connection for every open project

Stage 2 of docs/plans/project-tabs.md. Written 2026-11-24, 10:57 IST from a read of
`session/ClientSession.kt` (2 347 lines at 3ab4fc0) and the 263.7307.200 model classes. This is the
design and the order of work; each step gets its own section with names down to functions before
it is built (the standing rule for plans), and the "to verify" points are checked live first.

## What the backend gives us

| Fact | Evidence |
|---|---|
| Every open project is an entry of `protocol.solutionModel.solutions` on the one connection | `ClientSession.createModels`, log "Project … added; showing …" for the second project |
| Almost everything a project shows hangs off its `Solution`: `projectViewModel`, `fileEditorsModel`, `editorTabsModel`, `codeWithMeRunConfigurationsModel`, `navigationBarModel`, `solutionLifecycle`, `isLoaded`, structure, usages | the local `bindSolution` in `createModels` |
| An editor names its project: `TextControlId(projectId, documentId, kind, tabIndex, clientId)`; so does a tab: `RdFileEditorCompositeId(projectId, fileId, index, clientId)` | getters of the classes in `lib/intellij.rd.ide.model.generated.jar` |
| A document does not (`RdDocumentId` has no project); its markup is per project: `RdMarkupKey(projectId)`, plus one without a project | `documentsModel.documents.view` in `createModels` |
| Actions take the project in their context: `ActionTimestampSetModel(listOf(ProjectTimestampModel(projectId)))` | `runProjectAction`, `ProjectWidget`, `RunToolbar` |
| JetBrains Client keeps a window per solution on one connection, all live | the product's behaviour; `ThinClientRdProjectViewSession` |

## Where each thing lives

| `BackendConnection` (new, one per link) | `ClientSession` (one per open project) |
|---|---|
| `Connection.open`, `SplitWire`, `Protocol("ThinClient", …)`, `SingleThreadScheduler(lifetime, "rd")`, the connection's `LifetimeDefinition` | a child lifetime of the connection's, ended when its solution is removed |
| `DefaultRegistryModel` set on `highlighterRegistrationHostModel.registry`, and `schemes` | `applyEditorScheme` reads the shared schemes |
| the request sequence: `requestIds`, `retryTimestamp`, `sendRequest`; `responsesWithTime` handed to every session (each knows its pending ids) | `editSessions`, `earlyPatches`, `documentTexts` for the text controls whose `projectId` is this project's |
| `backendPatches`: handed to every session (`EditSession.concerns(patch)` already filters) | consoles, build trees, terminals of this project |
| `documents.view`: sets the project-less markup once; hands each text control to the session of `controlId.projectId` | the project's own markup under `RdMarkupKey(projectId)` |
| `FleetRpc(protocol.fleetRpcModel, …)`, `BackgroundTasks` | `Completion`, `SearchEverywhere`, `TerminalSessions`, `BuildTrees` made with the shared `FleetRpc` and this project's `dataContextBlob` |
| `vfsModel`, `clipboardModel`, `shellModel.deferredIconReady` (`readyIcons`) | `ProjectWidget`, `RunToolbar`, `projectViews`, `toolWindows`, `backendViews`, `statusWidgets`, `breadcrumbs`, structure, usages |
| `LuxDialogs` and `BackendPopups`: one each; what they show goes to the session on screen | `SessionState` (one per project) |
| the list of open projects (`OpenProject`), for the tabs | |

## Order of work

Each step leaves one project working exactly as today, passes `:compose:jvmTest`, and is checked
live with two projects before the next starts.

1. **`BackendConnection` with no change in behaviour.** Move the body of `ClientSession.connect`
   into it. `ClientSession` receives the connection instead of a `JoinLink`. `ConnectController`
   still makes one connection per session.
2. **Shared state moves over**, one group per commit: the registry and schemes; the request
   sequence; `FleetRpc` and `BackgroundTasks`; vfs, clipboard, deferred icons; popups and dialogs.
3. **A session per solution.** `BackendConnection` makes a `ClientSession` for each entry of
   `solutions` and ends it when the entry goes. The session no longer waits for "its" solution
   (`project`, `isShownProject`, the welcome-project hand-off move to the connection). Text
   controls are routed by `controlId.projectId`.
4. **`ConnectController` shows one of them.** It keeps the connection and `shown`; a tab or a row of
   the widget's popup only changes `shown`. `switchProject`, `SWITCH_WAIT_MILLIS` and the terminate
   frame on a switch go. A lost connection reconnects once and every project binds again. The saved
   screen and `OpenEditors.Slot` are per host path.
5. **Shown and hidden.** A hidden project's editors stop saying they are visible and focused
   (`visibleChangedFromFrontend`, `focusChangedFromFrontend`), its tool windows are not asked for
   content, and popups go to the shown project.

## To verify live before step 3

- Do `fileEditorsModel`, `projectViewModel` and the run widget of a second solution answer while
  the first is bound too? (Expected yes: JetBrains Client does it.)
- Which Fleet RPC services need the project in the call, and whether one kernel connection serves
  both projects' completion, Search Everywhere and terminals.
- Whether the backend sends a document's text controls of both projects to this client when the
  same file is open in both.
- What `focusProjectWindow` and `closeRequested` mean for a client that shows both.
