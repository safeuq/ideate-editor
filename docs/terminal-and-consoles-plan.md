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
read or run. **[Seen run]** = recorded against the test backend. **[Open]** = not known yet; the step that
settles it is named.

## Summary

| # | Place | What draws the output in IntelliJ | Transport IDEate would use | New protocol work | Size |
|---|---|---|---|---|---|
| 0 | Protocol probe | — | the test backend, logged | none, it only records | done (section 0) |
| 1 | Terminal tool window | `TerminalView`: a console-kind editor fed by a terminal emulator | rd `TerminalModel` (classic split path), JediTerm core in IDEate | bind `TerminalModel.sessions` | large |
| 2 | Console view (shared by 3, 4, 5) | `ConsoleViewImpl`: a console-kind editor over a document | `BeConsoleViewImpl(editorId)` inside a Lux panel; the text control (`kind = Console`) over the edit protocol | Lux local BeControls; console text controls | medium |
| 3 | Run and Debug tool windows | `RunContentManager` tabs, each a `RunnerLayoutUi` (`RunTab`) with a console | a backend tool window ("Run"): `BeTab` → `BeneluxView` → Lux panel with the console as a local BeControl | none beyond 2 | medium |
| 4 | Build tool window | `MultipleBuildsView` → `BuildView`: tree left, console right | a backend tool window ("Build"), same Lux panel; the tree is `BeSplitComponentControl("BuildTree")` → Fleet RPC `BuildTreeApi` | one Fleet flow | medium |
| 5 | Services tool window | `ServiceView`: tree left, the selected service's content right | Fleet RPC `RunDashboardServiceRpc` (the backend tool window "Services" stays empty) | Fleet flows | large |

Suggested order after the probe: 2, 1, 3, 4, 5. 2 first: Run and Build are nothing but a Lux panel around a
console, so once 2 works they mostly come free. 1 is independent of the others.

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

### Results (2026-11-21)

Run against the test backend (IntelliJ IDEA 2026.3, 263.7307.200) with a headless IDEate session and a
temporary `ConsolesProbe` that only logs (not committed). Projects: `gradle-smoke` (Gradle 9.6.0, task
`:app:hello`, run configuration "hello") and `smoke` (plain Java module, Application configuration "Main").
User and host names below are replaced by `user@host`. [Seen run] for everything in this section.

**Terminal: the classic path works.** Putting a session into `solution.terminalModel.sessions` starts a
shell on the backend machine within 0.4 s:

| Step | What came back |
|---|---|
| IDEate puts `"SessionId#probe1" → RdTerminalSession(origin = Client, shellCommand = null)` with `size = (100, 30)`, `outputConnected = true` | `title = RdTerminalTitle(null, null, defaultTitle = "Remote")`, `initialState = BeTerminalScreenState(cursorX = 1, cursorY = 1, [], [])` |
| — | `output`: the login shell's own startup (the user's zsh prompt, `\e]133;A\a`, `\e[?2004h`), 1 KB chunks |
| `input` fires `echo hi\r` (`[101, 99, 104, 111, 32, 104, 105, 13]`) | the shell echoes each key, then `hi\r\n` and a new prompt |
| the shell prints `\e]2;echo hi\a` | the backend parses it itself: `title.applicationTitle = "echo hi"`, then `"echo"`, then the prompt's `"user@host:~/gradle-smoke"` |
| `printf '\033]0;probe-title\007'` | `applicationTitle = "probe-title"` |
| `size = (60, 20)`, then `stty size` | `20 60`: the backend resizes the PTY |
| `closed = true` | the backend keeps the map entry; the client removes it [Inferred: JetBrains Client does] |

No `\e]1341;` codes: the backend's classic start adds no IntelliJ shell integration. The OSC 133 (command
marks) and 1337 (current folder) codes seen came from the user's own zsh setup, so IDEate gets them only on
shells that print them. `defaultTitle` is "Remote", not "Local".

**Run: not `BackendRunProcessModel`.** Neither `backendRunContentModel.showRunDebugToolwindow` nor any
`BackendRunProcessModel` came, for a Gradle run or for an Application run. Instead the backend registers
"Run" (and "Build", "Services") in `toolWindowModel.availableToolWindows` on the first run, like Gradle's
window, and the run is a tab there:

```
Run  BeToolWindow.content = BeTabbedControl
  BeTab(tabName "Main", isCloseable, hasOccurenceNavigator = true)
    BeGrid → BeneluxView(luxId = 1)            // a Lux panel the backend paints: toolbar, layout
      LxPanel.localComponents:                 // after the client shows it (graphics configuration, size, visible)
        0: LxLocalBeControl(BeGrid[
             BePortForwardingPanel(consoleId = 0),            // Fit
             BeConsoleViewImpl(editorId = KernelAwareTextControlId(kind = Console, tabIndex = 1, …))  // Fill
           ])
```

A Gradle run ("hello") is the same panel with one more local control before the console:
`BeSplitComponentControl(placeId = "BuildTree", modelUid = 5)`. The Lux panels drew 97–135 operations at
900 × 300. Start it from IDEate with `codeWithMeRunConfigurationsModel.runConfigurations["Application.Main"]
.execute(CwmRunConfigurationExecuteArgs("Run"))` (answers `Unit`; `executingConfigurations` counts it).

**Build: the same structure.** `build.toolwindow.split` is false by default
(IC `platform/ide-core-impl/resources/intellij.platform.ide.core.impl.xml`), so `BuildViewApi` sends nothing
and "Build" is a backend tool window: tab "Build Output" → `BeneluxView` → Lux panel with
`BeContextActionBehavior(BeSplitComponentControl("BuildTree", modelUid))` and
`BeContextActionBehavior(BeGrid[BePortForwardingPanel, BeConsoleViewImpl])`.

**The Build tree comes over Fleet RPC.** `BuildTreeApi.getTreeEventsFlow(BuildViewId)` with
`{"uid": <modelUid>, "modelIsOnClient": false}` (`UID` is an `Int`) streams the tree, e.g.:

```
{"type": "com.intellij.build.BuildTreeFilteringState", "showSuccessful": false, "showWarnings": true}
{"type": "com.intellij.build.BuildNodesUpdate", "nodes": [{"id": 1, "parentId": 0, "title": "hello",
  "name": "running…", "duration": {"type": "…BuildDuration.InProgress", "startTimestamp": 1795233640684},
  "icon": {"serializedValue": "…"}, "autoExpand": true, …}]}
{"type": "com.intellij.build.BuildTreeExposeRequest", "nodeId": 2, "alsoSelect": false}
… {"id": 1, "name": "successful", "hint": "At 21/11/26, 9:30 AM", "duration": {"type": "…Fixed", "durationMs": 1251}}
```

The icon ids decode to paths such as `expui/status/info.svg` and `expui/run/testPassed.svg`.

**Console text arrives as document patches.** Each `BeConsoleViewImpl` text control comes in
`documentsOperationModel.documents` with extensions `ConsoleTextControlExtension`, `MarkupTextControlExtension`,
`PermanentHeaderComponentTextControlExtension`, `CustomContextMenuExtension`. The Build console got its whole
text (`9:30 AM: Executing ':app:classes :app:testClasses'…`). The Application console got
`RdResetDocumentChange("")`, then one `RdDocumentChange` with the java command line, and nothing more within
60 s. **[Open]** Probably the backend waits for the client to confirm the document version before it sends
more, and the probe's edit session didn't; phase 2 step 2 settles it.

**Gradle events also go to `cWMExternalSystemModel`** (`onStatusChangeBuild`, `onTaskOutput`, `onSuccess`,
`onEnd`), which JetBrains Client's `ThinClientExternalSystemHost` reads. IDEate doesn't need them: the Lux
panel and the Build tree carry the same.

**Services:** "Services" is registered but its `BeTabbedControl` has no tabs ("Nothing to show");
`RunDashboardServiceRpc.getServices` answered `[]` (no Spring Boot, Docker or similar configurations).
Section 5 stays as planned; it needs a project with a service to probe.

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

Transport: the classic split path. `ReworkedTerminalModel` is not the reworked terminal's wire: its only
field is `portForwardingSessions`. The reworked engine's wire is IJent, and for IDEate it would cost:

| Part | What IDEate would need |
|---|---|
| Agent | upload `lib/ijent/ijent-<arch>-…-release` (12.5–15.6 MB per architecture) through `IjentDeployRemoteApi.deployIjent` |
| Trust | a TLS server bootstrap (`IjentTlsServerBootstrap(pem)`) and client certificate for mutual TLS |
| Wire | gRPC (netty shaded) on Android, to a forwarded port |
| API | `intellij.platform.ijent.impl.jar`: 1,372 generated protobuf classes in `…/ijent/impl/proto`; no `.proto` files in IC, so the client jar is the only spec |

The output is still raw PTY bytes that the client emulates, so the UI result is the same. **Deviation, stated:** IDEate's
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

- Settled by the probe (section 0): an `origin = Client` session starts a shell; size set before adding is
  used and later sizes resize the PTY; the backend parses titles; no IntelliJ shell integration.
- **[Open]** Whether `closed = true` ends the shell, or only removing the map entry does.

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

- Settled: the id is `KernelAwareTextControlId(kind = Console, tabIndex = 1)`, it arrives like an editor's,
  and the console sits in a Lux panel as `BeConsoleViewImpl` (section 0).
- **[Open]** Why only the first change came for a running program (section 0).
- **[Open]** The highlighter extension names that carry content types; log the console's markup in step 2.

---

## 3. Run and Debug tool windows

### How IntelliJ builds it

- `RunContentManager` adds one tab per run (`RunContentDescriptor`) to the Run (or Debug) tool window.
- The tab is a `RunnerLayoutUi` (`RunTab`): its own inner tabs (Console, plus Debugger / Threads for Debug),
  a toolbar (Rerun, Stop, …), and a `ConsoleView` in the Console tab. [Seen code: IC
  `platform/execution-impl/…/ui/layout/impl/RunnerLayoutUiImpl.java`]

### How JetBrains Client gets it

**Probe (section 0): the run is a backend tool window tab with a Lux panel; the models below never came.**
The rest of this subsection is what the jars contain. By default (`run.toolwindow.split.enabled` is false)
the run comes as rd models: `BackendRunProcessModel`
(executionId, displayName, configuration type and name, executorId, runnerId, `ExecutionConsoleKind
{TerminalExecutionConsole, BeControlledConsole, ConsoleViewImpl}`, `runnerLayoutUiModel`, processId,
`contentToolWindowId`, `consoleTextControlId`, exitCode, `terminateFromFrontend`) and `RunnerLayoutUiModel
(defaultTabs, contents: RunnerContentModel(component BeControl, actions, displayName, icon, closable, id,
tabIndex, placeInGrid, defaultIsMinimized))`. `ThinClientRunContentHost.adviseOnModel(BackendRunContentModel)`
shows them. [Seen code] Fleet RPC `RunSessionsApi` / `ProcessHandlerApi` is only for the split flag. [Seen code]

### IDEate design

After the probe: the Run tool window is drawn by IDEate's backend tool window code (`BackendToolWindowViews`,
as for Gradle) with two additions:

| IDEate | IntelliJ / JetBrains Client counterpart |
|---|---|
| a `BeneluxView` case in `BackendToolWindowViews` | `BeneluxViewControl`: show the panel at the tab's size, paint it, send input (as `BackendPopups` does for a popup's panel) |
| Lux local BeControls laid out at `LxLocalComponent.bounds` | `LuxBackendLocalBeControlBinder` on the client side |
| `BeConsoleViewImpl` → the `ConsoleView` from section 2 | `FrontendConsoleViewImpl` |

### Unknowns

- **[Open]** Debug: not probed. It likely uses the same panel plus the split debugger (`DebuggerModel`).
- **[Open]** How the local controls' `bounds` arrive (they were not initialized at 900 × 300 in the probe).

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

After the probe: the same Lux panel as Run; the tree is `BuildTreeApi.getTreeEventsFlow` (section 0) drawn
with the Project tree's rows, selection sent back with `onSelectionChange(buildViewId, nodeId)`.

### Unknowns

- Settled: `BuildViewApi` stays silent while `build.toolwindow.split` is false (the default); the tree flow
  works through IDEate's Fleet client.

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

- **[Open]** The services' content: needs a project with a service (e.g. a Spring Boot configuration).

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
2. Order: 2, 1, 3, 4, 5 as above, or the terminal (1) first?
