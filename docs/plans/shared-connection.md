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

## Addendum (2026-11-24, 11:09 IST): a second solution answers while the first is bound; step 1 done

Checked live before any refactoring (backend on 5997, `alpha` shown, `beta` also open). For the
solution that is not shown, a throwaway change set `solutionLifecycle.readyForExtsRegistration` and
viewed `projectViewModel.panes` and the Project pane's `items`. The backend answered at once, on
the same connection, while `alpha` stayed bound:

```
…/beta isLoaded true
…/beta pane ProjectPane | PackagesPane | Scope
…/beta item 1 beta | item 2 External Libraries | item 3 Scratches and Consoles
```

So the first "to verify" point holds: two solutions can be bound at once. Also seen in the code:
Fleet RPC names a project by `RdCodeWithMeProject.kernelProjectId`, so its services are per project
already.

Step 1 is built: `session/BackendConnection.kt` has the body of the old `ClientSession.connect`
(`open(onAccepted, onLost, onReady)`), the lifetime, the rd scheduler, `close()` and
`ProtocolMarshallers`. `ClientSession` makes one for itself (`connection`), takes its `lifetime`
and `scheduler` from it, and `stop()` calls `connection.close()`. No change in behaviour: connect,
a tab click and the switch were checked live.

## Addendum (2026-11-24, 11:40 IST): every open project now uses the one connection

Built and checked live (backend on 5997 with `alpha` and `beta`, both project tabs clicked back
and forth, a file edited and completion used in each).

What was built:

- `ClientSession` has a new constructor parameter `sharedConnection: BackendConnection? = null`.
  The first session opens the connection; a session for another project gets the first one's
  `connection`, calls `BackendConnection.attach(sessionLifetime, onLost, onReady)` and binds its
  own solution on the protocol that is already there. Its lifetime is nested in the connection's;
  `stop()` closes the connection only in the session that opened it.
- `ConnectController.switchProject(current, hostPath)` no longer disconnects. It keeps the opening
  session in `opener` and the others in `joined` (key: host path, e.g. `/work/beta`), makes a
  joined session the first time a project is shown, and afterwards only changes which session is
  on screen. `stopSessions()` stops all of them. `SWITCH_WAIT_MILLIS` and the `switchedTo` guard
  are gone.
- The log shows one `Connection accepted` for both projects, and
  `Joined the open connection for /work/beta, models created` for the second.

Things that exist once per connection, each found by a failure when two sessions made their own:

| Thing | Where it lives now | What went wrong with one per session |
|---|---|---|
| highlighter registry and colour schemes | `BackendConnection.registry(protocol)`, `schemes` | schemes arrive once; the joined session's editor stayed empty because `state.scheme` was null. A joined session now copies `connection.schemes` and applies the current theme's |
| request id sequence, retry timestamp | `requestIds`, `retryTimestamp` | two sessions would reuse ids on the same wire |
| `CommandChain` | `commands` | the backend's command order is per client, not per project |
| `FleetRpc` | `fleet(protocol)` | a second one took over the first one's streams |
| completion `responseFlow` | `completionResponses(sessionLifetime, handler)` | the backend ended the earlier flow with `RuntimeException: Reconnection` |
| completion `commandFlow` | `completionCommands` | the backend closed the earlier stream: `Fleet RPC stream … (commandFlow) closed` |
| toolbar `factoryId` | `nextFactoryId()` (1, 2, …) | with the constant `1` in both, alpha's project widget showed "beta" |

Also needed:

- A joined session finds its `projectId` in `solutionModel.solutions` by host path.
- Text controls of another project arrive on the same connection. A session skips them and keeps
  their document ids in `foreignDocuments`, otherwise the other project's file showed up as a tab
  named `document-<id>`.
- The markup model without a project (`RdMarkupKey(null)`) is set by the first session only.

Not checked, or left as it is:

- Backend popups and Lux dialogs are bound per session; a session that is not on screen does not
  show its own.
- The tool window layout is one for all projects.
- Closing the last project, and a tab for IntelliJ IDEA Home.
- Seen once in the opening session, one second after connect, not looked into:
  `Out of step with the backend (backend patch expects [-1, -1] …)` while `alpha.iml` was restored.

Live checks here used the two MCP servers of `docs/mcp/setup.md`; the app has to be started with
`-PagentMcpPort=5979` for `ideate-window` to connect.
