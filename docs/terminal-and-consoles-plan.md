# Terminal and consoles (plan): Terminal, Run, Debug console, Build, Services

Plan, 2026-11-21. IntelliJ shows process output in five places: the Terminal tool window, the Run and Debug
tool windows, the Build tool window and the Services tool window. This plan reads how IntelliJ 263 and
JetBrains Client 263.7307.200 build each one, and lays out the same structures for IDEate.

**Rule for every design here: look and behave like IntelliJ.** Same tabs, same toolbars, same editor
settings, same action ids. Where IDEate cannot use JetBrains Client's transport, the deviation is stated
and kept below the UI, so the UI classes stay the same shape as IntelliJ's.

Sources:

| Short name | Path |
|---|---|
| IC | `/Users/safeuq/Files/Libraries/intellij-community-263.7307.200` (open sources) |
| client jars | `~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-…/IntelliJ IDEA.app/Contents`, read with `javap -c -p` |
| protocol jar | client jars `lib/intellij.platform.split.protocol.jar` (`com.jetbrains.codeWithMe.model`) |
| terminal jars | client jars `plugins/terminal/lib/modules/intellij.terminal.frontend.jar` (867 classes), `intellij.terminal.frontend.split.jar` (33); backend `intellij.terminal.backend.split.jar` |
| JediTerm | client jars `lib/intellij.libraries.jediterm.core.jar` (the emulator), `intellij.libraries.jediterm.ui.jar` (Swing, not used) |

Tags: **[Seen code]** = read in the named source or class. **[Inferred]** = follows from the code but not
read or run. **[Open]** = not known yet; the step that settles it is named.

## Summary

| # | Place | What draws the output in IntelliJ | Transport IDEate would use | New protocol work | Size |
|---|---|---|---|---|---|
| 0 | Protocol probe | — | the test backend, logged | none, it only records | small |
| 1 | Terminal tool window | `TerminalView`: a console-kind editor fed by a terminal emulator | rd `TerminalModel` (classic split path), JediTerm core in IDEate | bind `TerminalModel.sessions` | large |
| 2 | Console view (shared by 3, 4, 5) | `ConsoleViewImpl`: a console-kind editor over a document | the backend's console is a text control over the edit protocol IDEate already speaks | console content types; text control registration | medium |
| 3 | Run and Debug tool windows | `RunContentManager` tabs, each a `RunnerLayoutUi` (`RunTab`) with a console | rd `BackendRunContentModel` / `BackendRunProcessModel` / `RunnerLayoutUiModel` | **[Open]** where the run model arrives | large |
| 4 | Build tool window | `MultipleBuildsView` → `BuildView`: tree left, console right | Fleet RPC `BuildViewApi` + `BuildTreeApi`; console as Lux | Fleet flows; a Lux id from `ComponentDirectTransferId` | large |
| 5 | Services tool window | `ServiceView`: tree left, the selected service's content right | Fleet RPC `RunDashboardServiceRpc` + `ServiceViewRpc`; content as Lux | Fleet flows | large |

Suggested order: 0, 2, 1, 3, 4, 5. 2 first because 3, 4 and 5 all end in a console, and its editor surface
is the one the terminal reuses. 1 is the most asked for and has no unknown transport. 3 waits on the probe.

---

## 0. Protocol probe

One run against the test backend (port 5991, the small Gradle project), with logging only, to settle the
[Open] items below before any UI is written:

| Step on the backend side | What to log in IDEate |
|---|---|
| Nothing; just connect | `solution.terminalModel.sessions` (entries and their fields), `reworkedTerminalModel.portForwardingSessions` |
| Ask for a terminal: put an `RdTerminalSession(origin = Client, shellCommand = null)` into `sessions` | whether the backend starts a shell, the first `output` bytes, `title`, `initialState` |
| Run the `hello` Gradle task (double tap in the Gradle tree already runs it) | `backendRunContentModel`, any `BackendRunProcessModel`, `RunnerLayoutUiModel` contents, new `TextControlId`s, new Lux windows, Fleet calls the backend makes |
| Build ▸ Build Project (through the action protocol) | `BuildViewApi.getBuildViewEventsFlow(projectId)` events, if the Fleet client can open the flow |
| Show Services (`BeToolWindow.state = Active` for "Services") | `RunDashboardServiceRpc.getServices(projectId)` and the content events |

Output: a new section in `split-protocol.md` (or this doc's addendum) with the recorded values, the same way
the Lux recording spike was written (`lux-protocol.md` section 6).

---

## 1. Terminal tool window

### How IntelliJ builds it (the "reworked" engine, the default in 263)

All under IC `plugins/terminal/frontend/src/com/intellij/terminal/frontend`. [Seen code]

| IntelliJ class | What it is |
|---|---|
| `toolwindow/TerminalToolWindowTabsManager` (+ `impl/…Impl`) | owns the tool window's tabs: create, close, rename, persist. Sets `toolWindow.setTabActions(TerminalToolwindowActionGroup)` and `setTitleActions(TerminalEmulatorBadgeAction)` |
| `toolwindow/TerminalToolWindowTab` | one tab: a `Content` holding one `TerminalView` |
| `TerminalSessionsManager` | starts sessions, keeps them by id |
| `view/TerminalView` | the widget: `component`, `gridSize`, `title: TerminalTitle`, `outputModels: TerminalOutputModelsSet(regular, alternative, active)`, `textSelectionModel`, `sessionState`, `keyEventsFlow`, `sendText` |
| `view/impl/TerminalViewImpl` | builds the regular output editor at once, the alternate-buffer editor when first needed, swaps them when the program switches buffers (vim, less, top) |
| `TerminalOutputModel` | the text and styles of one buffer: `textLength`, `lineCount`, `cursorOffset`, `screenTopOffset`, `firstLineIndex`, highlightings, OSC 8 links. Old lines are trimmed from the top, so offsets are relative to `startOffset` |
| `view/impl/TerminalOutputModelController` | applies `TerminalContentUpdatedEvent(text, styles, startLineLogicalIndex, …)` to the model |
| `TerminalSession` | `getInputChannel()` (bytes, resize, signals) and `getOutputFlow()` of `TerminalOutputEvent`s: `ContentUpdated`, `CursorPositionChanged`, `StateChanged`, `Beep`, `SessionTerminated`, `InitialState`, shell integration events |
| `view/impl/TerminalEditorFactory` | the editor: see the table below |
| `view/impl/TerminalCursorPainter` | draws the terminal's own cursor (block, underline or bar; blinking per settings) |
| `view/impl/TerminalKeyEventsHandlerImpl`, `TerminalKeyEncodingManager` | turns key events into bytes with JediTerm's `TerminalKeyEncoder` (application cursor keys, keypad mode) |
| `view/impl/TerminalMouseEventsHandlerImpl` | mouse reporting to programs that ask for it, selection otherwise |
| `view/impl/TerminalSearchSession` | Find in the terminal |

The output editor, from `TerminalEditorFactory.createOutputEditor` and `TerminalUiUtils.createOutputEditor`
(IC `plugins/terminal/src/org/jetbrains/plugins/terminal/block/ui/TerminalUiUtils.kt`): [Seen code]

| Setting | Value |
|---|---|
| Editor kind | `EditorKind.CONSOLE`, renderer mode (read-only, no read-only hint), undo off |
| Scrolling | no scroll to caret; no horizontal scroll bar; the alternate buffer has no scroll bars at all |
| Gutter | no line numbers, no gutter icons, no folding outline, gutter background not painted, no free painter area |
| Other | no caret row, no right margin, no special characters, `isBlockCursor = false` (the cursor is painted by `TerminalCursorPainter`) |
| Wrapping | soft wraps on, `TerminalLineWrapPositionStrategy` (wrap at the grid width, not at word ends), `EmptySoftWrapPainter` (no wrap marks), no custom indent |
| Spacing | an empty block inlay of `TerminalUi.blockTopInset` = 6 px above the first line and `blockBottomInset` = 6 px below the last |
| Colors | `TerminalColorScheme` over the global editor scheme; ANSI colors from the scheme's `TERMINAL_COLOR*` keys |
| Font | `JBTerminalSystemSettingsProviderBase.fontPreferences`, `lineSpacing`, `columnSpacing`, `terminalFontSize` (Settings ▸ Tools ▸ Terminal; defaults follow the editor font) |
| Context menu | `Terminal.ReworkedTerminalContextMenu`: Find, New Tab, Close Tab │ Copy, Paste, Paste from History, Select All │ Clear Buffer, Page Up, Page Down │ Line Up, Line Down … |

Tab title, from `TerminalTitle` (IC `platform/execution-impl/src/com/intellij/terminal/TerminalTitle.kt`):
[Seen code]

| userDefinedTitle | applicationTitle | defaultTitle | Tab shows |
|---|---|---|---|
| `null` | `null` | `null` | `Local` (`terminal.default.title`) |
| `null` | `null` | `"Local (2)"` | `Local (2)` |
| `null` | `"vim build.gradle.kts"` | `"Local"` | `vim build.gradle.kts` (cut in the middle to 30 characters) |
| `"Server"` | `"vim …"` | `"Local"` | `Server` |

Tool window buttons: `TerminalToolwindowActionGroup` = New Tab (`Terminal.NewTab`, ⌘T / Ctrl+Shift+T) and
the ▾ of predefined sessions (`TerminalNewPredefinedSession`); the gear has `Terminal.ToolWindowActions`
(Engine ▸, Settings). [Seen code: `frontend/resources/intellij.terminal.frontend.xml`]

### How JetBrains Client feeds it in split mode

Two paths exist. [Seen code]

| | Classic split path | What 263 uses by default (reworked engine) |
|---|---|---|
| Where the shell runs | backend machine | backend machine |
| Who starts it | backend: `BackendTerminalHost$MyProtocolListener` → `BackendTerminalManager.createWidgetForSpecificId(sessionId, …, shellCommand)` (`intellij.terminal.backend.split.jar`) | client: `LocalTerminalDirectRunner` over Eel; in split mode Eel is `RdProtocolEelMachine`, which deploys the IJent agent through Fleet RPC `IjentDeployRemoteApi.deployIjent(IjentTlsServerBootstrap(pem))` and then talks gRPC over mutual TLS to the forwarded port |
| Where the emulator runs | backend runs JediTerm for its own widget; the client runs its own on the raw bytes (`ThinClientTerminalHost`, `ThinClientTtyConnector`) | client (JediTerm, or Ghostty in `frontend/session/ghostty`) |
| Wire | rd `TerminalModel.sessions: RdMap<String, RdTerminalSession>`, raw bytes both ways | gRPC process API of IJent |
| `ReworkedTerminalModel` | — | only `portForwardingSessions: RdMap<Int, SessionPortForwarding>` |

`RdTerminalSession` fields, with example values (the values are the pattern, not recorded yet):

```
key           "SessionId#1"                         // ThinClientTerminalHost ids look like this
origin        Client                                // Host = the backend opened it, Client = we asked
shellCommand  ["/bin/zsh", "--login", "-i"]         // or null for the backend's default shell
title         RdTerminalTitle(userDefinedTitle = null, applicationTitle = null, defaultTitle = "Local")
size          RdTerminalSize(width = 120, height = 32) // columns, rows
input         RdSignal<List<Byte>>                  // keys, already encoded: "ls\r" = [108, 115, 13]
output        RdSignal<List<Byte>>                  // raw PTY output, escape sequences included
outputConnected  true once the client listens       // the backend buffers until then [Inferred]
initialState  BeTerminalScreenState(cursorX, cursorY, historyLines, screenLines)  // for a late joiner
closed        true when either side closes the tab
```

### IDEate design

Transport: the classic split path. IJent would need IDEate to deploy and run a native agent, speak gRPC
with mutual TLS and forward a port, and its UI result would be the same. **Deviation, stated:** IDEate's
terminal uses `TerminalModel` bytes; everything above the byte stream matches the reworked engine.

Emulator: JediTerm core (`intellij.libraries.jediterm.core.jar`, Java 11 bytecode). Its only AWT use is the
deprecated default `TtyConnector.resize(Dimension)`, which IDEate never calls, so it runs on Android.
[Seen code: `javap` over the 176 classes] Same emulator JetBrains Client uses for the reworked engine's
JediTerm mode, so escape sequence handling matches.

New IDEate classes, named after IntelliJ's and doing the same job:

| IDEate | IntelliJ counterpart | Job |
|---|---|---|
| `session/terminal/TerminalSession` | `TerminalSession` | wraps one `RdTerminalSession`: `send(bytes)`, `resize(columns, rows)`, `close()`, output events |
| `session/terminal/TerminalEmulatorDriver` | `TerminalSessionController` + JediTerm's `TerminalStarter` | feeds output bytes into `JediEmulator` / `JediTerminal`, writes replies (cursor reports) back to `input` |
| `session/terminal/TerminalOutputModel` | `TerminalOutputModel` | snapshot of `TerminalTextBuffer` (history + screen) as text runs with styles, `cursorOffset`, `screenTopOffset`; one for the regular buffer, one for the alternate |
| `session/terminal/TerminalSessionsManager` | `TerminalSessionsManager` | binds `TerminalModel.sessions`; opens sessions with `origin = Client`; adopts `origin = Host` ones (a terminal the backend opened, e.g. Open in Terminal) |
| `ui/terminal/TerminalView` | `TerminalViewImpl` | the composable: the output editor, the alternate editor when active, the cursor, selection, key input, Find |
| `ui/console/ConsoleEditor` | `TerminalUiUtils.createOutputEditor` / `ConsoleViewUtil.setupConsoleEditor` | the shared read-only console-kind surface from section 2, with the settings in the table above |
| `ui/terminal/TerminalToolWindow` | `TerminalToolWindowTabsManager` + `TerminalToolWindowTab` | tabs with the `TerminalTitle` rule, New Tab, ▾, close, the gear |

Keys: physical keyboards go through JediTerm's `TerminalKeyEncoder` as in IntelliJ. The soft keyboard
sends text; IntelliJ has no row of Esc/Ctrl/Tab/arrow keys, so one would be an addition (question below).

### Steps

1. JediTerm core as a dependency of `:compose` (from the client dist or Maven `org.jetbrains.jediterm:jediterm-core`, whichever matches 263). Unit test: feed `"\u001b[31mred\u001b[0m\r\n"`, expect a red run "red".
2. `TerminalSessionsManager` + `TerminalSession` on the test backend: open one, send `echo hi\r`, see `hi` in the output bytes. In-process test like the other session tests.
3. `TerminalOutputModel` from `TerminalTextBuffer`; `ConsoleEditor` draws it (section 2 step 1).
4. Cursor painter, key encoder, resize from the editor size in cells (`columns = width / cell width`).
5. Alternate buffer swap (test with `vim` or `less`).
6. Tool window tabs, titles, New Tab, close, gear menu, context menu.
7. Before/After shots (IntelliJ's Terminal next to IDEate's), docs, pending.md.

### Unknowns

- **[Open]** Does the 263 backend still honor an `origin = Client` session? `BackendTerminalHost` binds it
  [Seen code], but the reworked engine might leave the host listener unregistered. Probe step 2 settles it.
- **[Open]** Does the backend need `size` before it starts the shell, and does it apply later sizes to the
  PTY? Probe step 2.
- **[Open]** Shell integration (command blocks, working directory) is IJent-side in the reworked engine;
  over the classic path it is absent. Plan: none, as in JetBrains Client's classic engine.

---

## 2. Console view (shared by Run, Debug, Build, Services)

### How IntelliJ builds it

`ConsoleViewImpl` (IC `platform/consoleView/impl/src/com/intellij/execution/impl/ConsoleViewImpl.kt`). [Seen code]

- The editor: `ConsoleViewUtil.setupConsoleEditor` makes a viewer of `EditorKind.CONSOLE` with no line
  numbers, indent guides, caret row, right margin or extra page at the end; gutter background off; the
  console font (`EditorColorsScheme.getConsoleFontName/Size`); `NullEditorHighlighter`.
- The text: each printed piece has a `ConsoleViewContentType` (`NORMAL_OUTPUT`, `ERROR_OUTPUT`,
  `SYSTEM_OUTPUT`, `USER_INPUT`, `LOG_*`) which is a `TextAttributesKey` such as `CONSOLE_ERROR_OUTPUT`;
  ANSI colors become more content types. File paths and URLs become hyperlinks through filters.
- The toolbar (`createConsoleActions`): Up / Down the stack trace (prev/next occurrence), Soft-Wrap,
  Scroll to the End, Print, Clear All, then the run's own actions.
- Typing: when the process reads input, typed text goes to its stdin as `USER_INPUT`.

### How JetBrains Client gets it

The backend's console is a text control: the client builds `FrontendConsoleViewImpl` (extends
`ConsoleViewImpl`) from a `TextControlId` (`TextControlId.toEditor()`), and maps highlighter extensions to
content types with `ConsoleContentTypeHandler` / `ConsoleTextControlExtensionProcessor`. The ui-automation
model names it `BeConsoleViewImpl(editorId: TextControlId)`. [Seen code:
`intellij.platform.execution.frontend.split.jar`]

So a console's text, styles and hyperlinks come over the same edit protocol that IDEate's editors already use
(document, markup, hyperlinks); only the extension-to-content-type step is new. [Inferred]

### IDEate design

| IDEate | IntelliJ counterpart | Job |
|---|---|---|
| `ui/console/ConsoleEditor` | `ConsoleViewUtil.setupConsoleEditor` | the read-only console-kind surface; reuses `EditorView`'s text drawing without gutter, caret row or line numbers |
| `session/console/ConsoleView` | `FrontendConsoleViewImpl` | one backend console: its text control, content types to colors, hyperlinks, input |
| `ui/console/ConsoleToolbar` | `createConsoleActions` | the six console actions plus the run's actions |

### Steps

1. `ConsoleEditor`: `EditorView` with a settings switch for console kind; test that a console has no gutter
   width and no caret row.
2. Open a console text control from the probe's recorded id; draw it with the content type colors.
3. Hyperlinks (Cmd+Click / tap follows them, as in IntelliJ), input, toolbar.

### Unknowns

- **[Open]** The `TextControlId` kind for a console and whether IDEate's editor host accepts it as is. Probe.
- **[Open]** The highlighter extension names that carry content types. Probe.

---

## 3. Run and Debug tool windows

### How IntelliJ builds it

- `RunContentManager` adds one tab per run (`RunContentDescriptor`) to the Run (or Debug) tool window.
- The tab is a `RunnerLayoutUi` (`RunTab`): its own inner tabs (Console, plus Debugger / Threads for Debug),
  a toolbar (Rerun, Stop, …), and a `ConsoleView` in the Console tab. [Seen code: IC
  `platform/execution-impl/…/ui/layout/impl/RunnerLayoutUiImpl.java`]

### How JetBrains Client gets it

By default (`run.toolwindow.split.enabled` is false) the run comes as rd models: `BackendRunProcessModel`
(executionId, displayName, configuration type and name, executorId, runnerId, `ExecutionConsoleKind
{TerminalExecutionConsole, BeControlledConsole, ConsoleViewImpl}`, `runnerLayoutUiModel`, processId,
`contentToolWindowId`, `consoleTextControlId`, exitCode, `terminateFromFrontend`) and `RunnerLayoutUiModel
(defaultTabs, contents: RunnerContentModel(component BeControl, actions, displayName, icon, closable, id,
tabIndex, placeInGrid, defaultIsMinimized))`. `ThinClientRunContentHost.adviseOnModel(BackendRunContentModel)`
shows them. [Seen code] Fleet RPC `RunSessionsApi` / `ProcessHandlerApi` is only for the split flag. [Seen code]

### IDEate design

| IDEate | IntelliJ counterpart |
|---|---|
| `session/run/RunContentManager` | `RunContentManager` / `ThinClientRunContentHost` |
| `ui/run/RunTab` | `RunnerLayoutUi` (inner tabs from `RunnerContentModel`, toolbar from `actions`) |
| console tab | `ConsoleView` from section 2; a `TerminalExecutionConsole` uses the terminal's view from section 1 |

### Unknowns

- **[Open]** Where `BackendRunProcessModel` lives (no holder found in the protocol jar). Probe step 3.
- **[Open]** Whether `RunnerContentModel.component` for the console is a `BeControl` wrapping the text
  control or a Lux window. Probe step 3.

---

## 4. Build tool window

### How IntelliJ builds it

`MultipleBuildsView` holds one `BuildView` per build: on the left a tree of the build's steps
(`BuildTreeConsoleView`), on the right the console of the selected node. [Seen code: IC
`platform/buildView/src/com/intellij/build`]

### How JetBrains Client gets it

Fleet RPC. [Seen code]

- `BuildViewApi.getBuildViewEventsFlow(projectId)`: `BuildStarted(content, title, treeViewId,
  consoleComponent: ComponentDirectTransferId, processHandler)`, `BuildStatusChanged`, `BuildFinished`,
  `BuildRemoved`, `BuildSelected`; calls `setBuildContentPinned`, `disposeBuildContent`,
  `notifyTooWindowActivated`.
- `BuildTreeApi.getTreeEventsFlow(buildViewId)`: `BuildNodesUpdate(nodes: BuildTreeNode(id, parentId, icon,
  title, name, hint, duration, navigatables, autoExpand, hasProblems, …))`, `ExposeRequest`,
  `FilteringState`; plus navigation and selection calls.
- The console side is a Lux window passed as `ComponentDirectTransferId`.

### IDEate design

`session/build/BuildViewManager` (the flows) and `ui/build/BuildView` (tree + console split, the tree rows
like the Project tree's). The console is a Lux window, which IDEate already paints.

### Unknowns

- **[Open]** Whether IDEate's `FleetRpc` can open these flows as it does `FindInFilesApi`. Probe step 4.
- **[Open]** How a `ComponentDirectTransferId` turns into a Lux window id. Probe step 4.

---

## 5. Services tool window

### How IntelliJ builds it

`ServiceView`: a tree of services (run configurations, Docker, databases) on the left and the selected
service's content on the right. For a run configuration the content is its `RunContentDescriptor`.

### How JetBrains Client gets it

Split by default (`services.view.split.enabled` = true, `services.view.split.run.luxing.enabled` = true):
`RunDashboardServiceRpc` (services, statuses, customizations flows; `getLuxedContentEvents`,
`startLuxingContentForService` → `ComponentDirectTransferId`), `RunDashboardManagerRpc`, `ServiceViewRpc`.
[Seen code]

### IDEate design

`session/services/ServiceViewModel` + `ui/services/ServiceView`; the content area shows the Lux window from
`startLuxingContentForService`.

### Unknowns

- **[Open]** Same Fleet and Lux id questions as section 4. Probe step 5.

---

## Every phase ends with

- a test in the session or UI test set,
- the findings in this doc (dated addendum) or the protocol docs,
- a Before/After entry on the shared page, IntelliJ's window next to IDEate's,
- a row in `intellij-android/docs/pending.md`.

## Questions for the user

1. Phone and tablet soft keyboards have no Esc, Ctrl, Tab or arrows. IntelliJ has no extra key row, so
   adding one breaks "no additions". Add a row above the keyboard (only while a terminal has focus), or leave
   it out?
2. Order: 0, 2, 1, 3, 4, 5 as above, or the terminal (1) straight after the probe?
