# Terminal and consoles (plan): what IDEate builds, phase by phase

Plan, 2026-11-21. How IDEate gets the Terminal, Run, Debug, Build and Services tool windows. What IntelliJ
and JetBrains Client do is in `terminal-and-consoles-spec.md` (called "spec" below, e.g. "spec 7.1"); this
document is only IDEate's side: which files change, in which order, how each step is checked.

Each phase is written to be handed to one implementer (a Sonnet subagent) with no other context than this
document, the spec and the code. The coordinator (the session that wrote this plan) reviews each phase.

**Rule for every phase: look and behave like IntelliJ.** Same structure, same settings, same names as the
spec gives. A deviation is allowed only where the spec says JetBrains Client's transport can't be used, and
then it stays below the UI.

## Contents

0. Working rules (read first)
1. Target structure
2. Phases at a glance
3. Phase 1: console text controls
4. Phase 2: Run and Build tabs (Lux panel + client-drawn controls)
5. Phase 3: the Build tree
6. Phase 4: the Terminal tool window
7. Phase 5: Services (probe first)
8. Phase 6: Debug (probe first)
9. Status

Appendix A: probe code. Appendix B: test projects.

---

## 0. Working rules (read first)

### 0.1 Roles

- The **coordinator** plans and reviews. The **implementer** (one subagent per phase) writes the code, the
  tests and the doc updates for one phase, in one worktree, and reports back.
- **Ask, don't guess.** When something in the phase is unclear, contradicts the code, or an [Open] item
  blocks you, stop and end your turn with a report that starts with `QUESTIONS:`: each question, what you
  found, and your proposed answer. Leave the worktree as it is; the coordinator answers and you continue in
  the same worktree. Don't widen the scope of a phase on your own.
- Phases run one at a time unless the table in section 2 marks them independent.
- Report at the end: the commit hashes, the tests added and their results, the checklist of the phase's
  "Done when" items with each one ticked or explained, and the paths of any screenshots.

### 0.2 Read before coding

| What | Why |
|---|---|
| the spec sections named in the phase | the behavior to copy |
| `docs/lux-protocol.md` sections 7–12 | Lux panels, the painter, input |
| `docs/tool-windows.md` sections 1 and 8 | backend tool windows, `BeToolWindow.state`, toolbars |
| `docs/edit-protocol-client.md`, `docs/edit-protocol.md` | document patches, versions, edit sessions |
| `docs/split-protocol.md` "Our own client" | how `ClientSession` binds rd models |
| the files listed in the phase | where the change goes |

### 0.3 Code style

- Kotlin, matching the files around the change. Run `./gradlew spotlessApply` before committing.
- No abbreviated names: `httpResponse`, not `res`; `requestContext`, not `ctx`; no bare `e` in a catch.
  Accepted terms (`id`, `url`, `rd`, `lux`) stay.
- Fail fast: check inputs at the top and stop with a message that names what is missing. Don't substitute
  defaults for missing input.
- Comments and KDoc in plain words, with example values (see `BackendToolWindows.kt` for the house style).
  Say why, not what.
- Smallest change that works: reuse code that exists (the phases name it), no interface with one
  implementation, no options nobody asked for. A known limit gets a `ponytail:` comment naming the limit
  and when to lift it.
- Every non-trivial piece of logic gets one test that fails when the logic breaks.

### 0.4 Git

Repository: `/Users/safeuq/Files/Projects/Java/JBR/ideate-editor` (GitHub `safeuq/ideate-editor`).

```bash
cd /Users/safeuq/Files/Projects/Java/JBR/ideate-editor
git fetch origin main
git worktree add --detach <scratchpad>/wt-<phase> origin/main
cp local.properties <scratchpad>/wt-<phase>/            # needed to build; never commit it
# … work, build, test in the worktree …
rm <scratchpad>/wt-<phase>/local.properties
git -c user.name="Safeuq Mohamed" -c user.email=mohamedsafeuq@gmail.com commit -m "<subject>"
gh auth switch --user safeuq
git fetch origin main && git rebase origin/main && git push origin HEAD:main
gh auth switch --user safeuq-xflowpay
git -C /Users/safeuq/Files/Projects/Java/JBR/ideate-editor worktree remove <scratchpad>/wt-<phase>
```

- Never commit in the shared checkout (`/Users/safeuq/Files/Projects/Java/JBR/ideate-editor` itself); it
  holds the user's own work. Never `git stash`, `git reset --hard`, `git checkout -- <path>` or `git clean`.
- **No AI attribution anywhere:** no `Co-Authored-By`, no "Generated with", no mention of Claude in commits.
- Commit subject: imperative, what changed, e.g. `Consoles: read-only console editors for BeConsoleViewImpl`.

### 0.5 Docs that change with the code (same commit)

- Findings about the protocol or IntelliJ go into the spec as a dated addendum at the end of the relevant
  section ("**Addendum 2026-11-22:** …"), and the matching row of spec section 12 is updated.
- This plan: tick the phase in section 9 (Status) and note what differs from the plan.
- Other docs the phase names (e.g. `docs/tool-windows.md`).
- Outside the repository, the coordinator updates `intellij-android/docs/pending.md` (row 38) and
  `/Users/safeuq/Files/Projects/Java/JBR/coordination.md`; the implementer reports, the coordinator writes.

### 0.6 Build and test

```bash
export JAVA_HOME="/Users/safeuq/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew :compose:jvmTest                       # unit tests (compose/src/jvmTest)
./gradlew :desktop:test --rerun --tests '*Name*'  # in-process tests against a live backend (desktop/src/test)
./gradlew :desktop:compileKotlin :android:assembleDebug   # both clients still build
./gradlew spotlessApply
```

`--rerun` matters: Gradle skips a test whose inputs didn't change, and environment variables are not inputs.
Test output (`println`) is in `desktop/build/test-results/test/TEST-*.xml`.

### 0.7 A live test backend

Follow `/Users/safeuq/Files/Projects/Java/JBR/local-test-backend.md`: an IntelliJ IDEA 263.7307.200
`remote-dev-server.sh run <project> --listenOn 127.0.0.1 --port 5991` with its own config, system and log
folders. Rules:

- **Never touch the user's IntelliJ or the user's own backends.** Use port 5991 and your own folders only.
- The join link (`tcp://127.0.0.1:5991#jt=…`) works once: restart the backend for every client run.
- Stop a backend by its PID only: the line `Current pid: <pid>` in its `remote-dev` log, then `kill <pid>`.
  No `pkill`, no `killall`.
- Stop every backend and client you started as soon as the test is done.
- Don't install anything on the user's phone or tablet; the coordinator asks the user first.

Test projects: Appendix B.

### 0.8 Live tests in-process

Prefer a JUnit test that runs `ClientSession` (and, for UI, `runDesktopComposeUiTest`) in the test JVM over
driving a window. The pattern is `desktop/src/test/kotlin/intellijandroid/client/desktop/DesktopDriverTest.kt`:
skipped unless `DRIVER_LINK` is set, writes PNGs and the semantics tree to `DRIVER_OUT`. Notes:

- `runDesktopComposeUiTest` has no density parameter; provide `LocalDensity` instead. Its waits time out at
  60 s; keep each wait under that.
- For a phone-sized screenshot: `width = 822, height = 1720` with `LocalDensity provides Density(2f)`.

### 0.9 Screenshots for the Before/After page

Every UI change ends with a Before (IntelliJ or IDEate before the change) and an After PNG, same size and
content. Save them under `<scratchpad>/shots/<phase>/` and list the paths in the report; the coordinator
publishes them.

---

## 1. Target structure

New and changed IDEate parts, each named after the IntelliJ / JetBrains Client part it copies
(paths under `compose/src/commonMain/kotlin/intellijandroid/client/`):

| IDEate | Copies | Phase |
|---|---|---|
| `session/console/ConsoleDocuments.kt` | the client side of `FrontendConsoleViewImpl`: one console's text and highlights from document patches | 1 |
| `ui/console/ConsoleEditor.kt` (a mode of `ui/EditorView.kt`) | `ConsoleViewUtil.setupConsoleEditor` (spec 5.1) | 1 |
| `session/LuxPanelHost.kt` (moved out of `session/ProjectViews.kt`) | `BeneluxViewControl` (spec 7.1) | 2 |
| `session/BackendToolWindowViews.kt` gains tabs, `BeneluxView`, local controls | `FrontendGenericToolWindowFactory` + `LuxBackendLocalBeControlBinder` (spec 7.2, 8.2) | 2 |
| `ui/ClientScreen.kt` `BackendToolWindowContent` gains a tab row and the panel | the tool window's content tabs | 2 |
| `session/build/BuildTreeClient.kt` | `BuildTreeViewModelProxy` + `BuildTreeView` (spec 9.3) | 3 |
| `session/terminal/TerminalSessions.kt` | `ThinClientTerminalHost` (spec 3.3) | 4 |
| `session/terminal/RdTtyConnector.kt` | `ThinClientTtyConnector` + `ThinClientTerminalProcess` (spec 3.3) | 4 |
| `session/terminal/TerminalScreen.kt` | `TerminalOutputModel` from JediTerm's `TerminalTextBuffer` (spec 2.1, 4) | 4 |
| `ui/terminal/TerminalView.kt` | `TerminalViewImpl` + `TerminalCursorPainter` + key handling (spec 2.3–2.7) | 4 |
| `ui/terminal/TerminalToolWindow.kt` | `TerminalToolWindowTabsManager` (spec 2.2) | 4 |

---

## 2. Phases at a glance

| Phase | What the user gets | Needs | Size |
|---|---|---|---|
| 1 | Console text shows, colored, in a read-only console editor | — | medium |
| 2 | Run and Build tool windows: tabs, the backend-painted panel, the console inside | 1 | medium |
| 3 | The Build tree inside Run (Gradle) and Build | 2 | small |
| 4 | Terminal tool window | — (independent of 1–3) | large |
| 5 | Services | probe, then 2 | unknown |
| 6 | Debug | probe, then 2 | unknown |

Order: 1 → 2 → 3. Phase 4 shares no code with 1–3 and may run in parallel with them, in its own worktree;
the only shared file is `ui/ToolWindows.kt` (phase 4 adds a `TERMINAL` entry), so whichever lands second
rebases.

---

## 3. Phase 1: console text controls

**Goal:** a backend console (`BeConsoleViewImpl`) shows its text with IntelliJ's console colors in a
read-only console editor, and keeps up while the program prints.

Spec: 5 (console UI), 6 (wire), 12 rows 1–2.

### 3.1 What the code does today

- `ClientSession.createModels` → `documentsModel.documents.view { … document.textControls.view { … } }`
  (`session/ClientSession.kt`, around "document.textControls.view") calls `createEditSession` for every text
  control, then adds markup and fires `visibleChangedFromFrontend(true)`, `focusChangedFromFrontend(true)`
  and a `visibleRange`.
- `createEditSession` calls `EditorIdentity.of(documentId, controlId, …)` (`session/EditSession.kt`), which
  returns null unless the document id is an `RdDocumentIdWithFileId`. A console's id is an
  `RdAbstractDocumentId` (spec 6.1), so today it logs `Cannot edit … unknown editor id type` and no session
  exists.
- `requestResponseModel.backendPatches.advise` stores patches of documents without a session in
  `earlyPatches` and applies only `RdResetDocumentChange` to `documentTexts`. So a console shows at most the
  last reset, never the `RdDocumentChange`s after it.
- `publishDocument` and `EditorFile` are keyed by a file path; a console has none.

### 3.2 Design

- **`session/console/ConsoleDocuments.kt`**: keeps every console text control by its
  `KernelAwareTextControlId.kernelEditorId` (e.g. `"g9ojv29tmj8vgr2tv7gf"`): document id, text, document
  version, highlights. It is fed from the same two places as editors:
  - in `documents.view`, when a text control's `kind == TextControlLocation.Console` (`TextControlId.getKind()`;
    the enum also has `EditorTab`, `ToolWindow`, `Preview`, `Diff`, …), register it here instead of calling `createEditSession`;
  - in `backendPatches.advise`, hand patches whose document is a registered console to it before the
    edit-session logic.
  It applies `RdResetDocumentChange` (text = given text, version = `newVersion`) and `RdDocumentChange`
  (replace at `offset`; check `oldVersion` matches, else log and ask for a reset — see 3.3) and publishes
  `SessionState.consoles: Map<String, ConsoleText>` with `ConsoleText(text, highlights)`.
- **Highlights**: reuse the markup path editors use (`attachMarkup` with a document markup model); the
  backend's highlighters carry text attribute keys such as `CONSOLE_ERROR_OUTPUT` (spec 5.2), which
  `EditorScheme.style(keyChains)` already resolves. Confirm which markup carries them (spec 12 row 2) by
  logging the console's highlighters once; write the finding into spec 6.2.
- **`ConsoleEditor`**: the console mode of `EditorView`, as IntelliJ's console is an editor of
  `EditorKind.CONSOLE`. Add a parameter `kind: EditorKind` (a new enum `EditorKind { MAIN_EDITOR, CONSOLE }`
  next to `EditorFile`, default `MAIN_EDITOR`). In `CONSOLE` mode follow spec 5.1: no line numbers, no
  gutter icons, no folding, no indent guides, no caret row, no sticky lines, no right margin, no extra page
  at the bottom, read-only (no typing, no caret drawn), background from color key `CONSOLE_BACKGROUND_KEY`
  (fall back to the editor background). `ui/console/ConsoleEditor.kt` is then a small composable that builds
  an `EditorFile` (path = `"console:" + kernelEditorId`) from `ConsoleText` and calls `EditorView` in that
  mode. If `EditorView`'s path-keyed state gets in the way, ask the coordinator before restructuring it.
- **Font**: IntelliJ uses the console font. `EditorScheme` has no console font today; use the editor font
  and add a `ponytail:` comment (lift when the scheme's console font is read).

### 3.3 Step 1 first: why output stops (spec 12 row 1)

Before the UI, make the text arrive. On the `smoke` project (Appendix B) run the `Main` configuration from a
headless test (appendix A shows how: `codeWithMeRunConfigurationsModel.runConfigurations["Application.Main"]
.execute.start(lifetime, CwmRunConfigurationExecuteArgs("Run"))`), and make the Lux panel visible with the
spec 7.1 handshake (appendix A has it), because the console component lives in that panel. Expected console
text, in this order:

```
/…/bin/java -javaagent:…idea_rt.jar=… -Dfile.encoding=UTF-8 … demo.Main
just a simple greeting
22

Process finished with exit code 0
```

If only the first line comes with `ConsoleDocuments` applying patches correctly, find what JetBrains Client
sends for a console (decompile `FrontendConsoleViewImpl`, `ConsoleTextControlExtensionProcessor` in
`plugins/remdev-plugin/lib/modules/intellij.platform.execution.frontend.split.jar`, and look for
`visibleRange`, version or acknowledgement traffic), try it, and report to the coordinator with the evidence
before building more. Write the answer into spec 6.2.

### 3.4 Tests

- Unit (`compose/src/jvmTest/.../session/ConsoleDocumentsTest.kt`): a reset `""` at version 0, then a change
  inserting `"java …\n"` at 0, then one inserting `"just a simple greeting\n"` at the end → text equals both
  lines; a change with a wrong `oldVersion` → reported, text unchanged.
- Live (`desktop/src/test/.../ConsoleRunTest.kt`, skipped without `DRIVER_LINK`): run `Main`, wait until the
  console text contains `Process finished with exit code 0`; assert it contains `just a simple greeting`.
- UI: a `runDesktopComposeUiTest` rendering `ConsoleEditor` with fixed text and one `CONSOLE_ERROR_OUTPUT`
  highlight → PNG; no line numbers in the semantics tree.

### 3.5 Done when

- [ ] The live test passes: the full output arrives.
- [ ] Console text controls no longer log `Cannot edit … unknown editor id type`.
- [ ] `ConsoleEditor` matches spec 5.1 (each setting checked) and colors stderr with `CONSOLE_ERROR_OUTPUT`.
- [ ] Editors of files behave exactly as before (`./gradlew :compose:jvmTest` all green).
- [ ] Spec 6.2 and 12 rows 1–2 updated with what was found.

---

## 4. Phase 2: Run and Build tabs

**Goal:** opening Run or Build shows the backend's tabs ("Main", "Build Output"), the selected tab's
backend-painted panel (toolbar, layout) at the tool window's size, and inside it the console from phase 1
where the backend puts it. Clicks and scrolls on the panel reach the backend.

Spec: 7, 8.2, 9.2.

### 4.1 What the code does today

- `session/BackendToolWindows.kt` lists `availableToolWindows`; Run, Build and Services are in that list after
  the first run (spec 8.2). ~~so their buttons already show~~ (wrong, found in phase 2: Run and Build were only
  under ⋯ because they weren't in `DEFAULT_LAYOUT`; phase 2 added them to the stripe, spec 8.2 addendum).
- `session/BackendToolWindowViews.kt` sets `BeToolWindow.state` when a window shows, takes the selected
  `BeTab` (`showTab`) and walks its control tree (`find`) for a `BeIdeToolbar`, a `BeTreeGrid` or a
  `BeLabel`. It ignores `BeneluxView`, so Run and Build show "Tool window not implemented"
  (`ui/ClientScreen.kt` `BackendToolWindowContent`).
- `session/ProjectViews.kt` `showPanel` / `resizePanel` / `repaintPanel` / `panelEvent` already do the spec
  7.1 handshake for one Lux pane, and `ui/lux/LuxDialogView.kt` `LuxPaneView` draws it and sends input.

### 4.2 Design

- **Move** the panel code of `ProjectViews` (`showPanel`, `resizePanel`, `repaintPanel`, `panelEvent`, the
  `luxPanel` / `panelLifetime` fields) into `session/LuxPanelHost.kt`, a class with one instance per shown
  panel. `ProjectViews` keeps working through it (its behavior must not change). `LuxPaneView` takes the
  host's callbacks instead of the `session::projectPanel*` functions.
- **Tabs:** `BackendToolWindowView` gains `tabs: List<String>` (each `BeTab.tabName.text`) and
  `selectedTab: Int`. `BackendToolWindowViews` follows `content.tabs` and `content.selectedIndex`; choosing a
  tab sets `selectedIndex`. Draw them as IntelliJ's tool window content tabs (the "Main" / "hello" labels in
  the tool window header); take a screenshot of IntelliJ's Run window as the reference. Closing a tab:
  `BeTab` has no close signal (only `isCloseable`); **ask the coordinator** before guessing how
  (likely a tab event on `BeTabbedControl` or the action `CloseContent`).
- **The panel:** `find` gains `is BeneluxView` → a `LuxPanelHost` for `luxModel.components[luxId]`, published
  as `BackendToolWindowView.panel`. `BackendToolWindowContent` draws it with `LuxPaneView`, filling the tool
  window below the tab row.
- **Client-drawn controls:** the host also follows `panel.localComponents`. For each `LxLocalBeControl`:
  unwrap `BeContextActionBehavior.content`, then
  - `BeGrid` → its items, top to bottom;
  - `BeConsoleViewImpl(editorId)` → `ConsoleEditor` for `editorId.kernelEditorId` (phase 1);
  - `BePortForwardingPanel` → nothing (`ponytail:` it is empty unless a port is forwarded);
  - `BeSplitComponentControl(placeId = "BuildTree")` → a placeholder until phase 3;
  - anything else → log its class once.
  Place each at `LxLocalComponent.bounds` (backend pixels = dp, relative to the panel). The probe saw
  `bounds` unset when the entry was added (spec 12 row 3): advise it, log when it gets a value, and if it
  never does, stop and report to the coordinator with the log.

### 4.3 Tests

- Unit: `LuxPanelHost` handshake order on fake models is hard; instead keep `ProjectViews`' existing tests
  green and add a test for the tab list mapping (`BeTab` names and `selectedIndex` → `BackendToolWindowView`).
- Live UI (`DesktopDriverTest`-style): on `smoke`, run `Main`, open Run, wait for the tab "Main", wait for
  `just a simple greeting` in the semantics tree (the console text), PNG. On `gradle-smoke`, Build ▸ Build
  Project (action `CompileDirty` through `ActionMenus.runAction`), open Build, PNG.
- Regression: the welcome project's "Projects and Files" pane still draws and takes clicks (an existing
  driver test or a manual check, stated in the report).

### 4.4 Done when

- [ ] Run shows the "Main" tab, the backend panel and the console text; Build shows "Build Output" the same.
- [ ] Clicks on the panel's toolbar reach the backend (e.g. the panel's Rerun runs `Main` again:
      `executingConfigurations["Application.Main"]` goes to 1).
- [ ] Switching tabs works when there are two runs.
- [ ] "Projects and Files" unchanged.
- [ ] Before/After PNGs (IntelliJ's Run window vs IDEate's) saved.
- [ ] `docs/tool-windows.md` gains a section on Run/Build tabs; spec 7.2 / 12 row 3 updated.

---

## 5. Phase 3: the Build tree

**Goal:** `BeSplitComponentControl("BuildTree", modelUid)` shows the build's steps as IntelliJ's Build tree,
and selecting a node shows its output in the console beside it.

Spec: 9.1 (look), 9.3 (wire).

### 5.1 Design

- **`session/build/BuildTreeClient.kt`**: a Fleet RPC client for `com.intellij.platform.buildView.BuildTreeApi`,
  JSON in and out, made like `SearchApi` / `SearchApiDescriptor` in `kernel/SearchKernel.kt` (hand-written
  `RemoteApi` + `RemoteApiDescriptor`, called through the same `serviceProxy`). Appendix A.3 has a working
  descriptor. Calls: `getTreeEventsFlow`, `getNavigationFlow` (flows), `onSelectionChange(buildViewId,
  selectedNodeId: Int?)` (returns `Unit`). Argument: `{"uid": <modelUid>, "modelIsOnClient": false}`.
  The `SearchKernel` connection is per project; give `BuildTreeClient` access to its proxy instead of opening
  a second Fleet client (ask the coordinator if that needs a larger change to `SearchKernel`).
- **The tree**: apply events in order to a map `id → node`: `BuildNodesUpdate` adds or replaces nodes,
  `BuildTreeFilteringState` stores the filter, `BuildTreeExposeRequest` expands to the node (and selects it
  when `alsoSelect`). Hide nodes as spec 9.3 says. Draw with `ProjectTreeView` rows (the same component
  Gradle's tree uses) and the row parts of spec 9.1: bold `"title: "`, name, gray hint, right-aligned gray
  duration (in-progress durations tick using `currentTimestamp`).
- **Icons**: decode `icon.serializedValue` (spec 9.3, base64; the path is in it as UTF-16, e.g.
  `expui/status/info.svg`). Write the layout you find into spec 9.3 (12 row 5). A value with no path is the
  running icon: use `expui/status/running.svg` or the closest existing icon, and say which in the report.
- **Selection**: a click selects a node and calls `onSelectionChange`; the backend then shows that node's
  output in the console (watch the console text change).

### 5.2 Tests

- Unit: the event sequence of spec 9.3 (copy the JSON) → expected visible rows (`hello` › `:app:hello`), with
  `showSuccessful = false` hiding what it should; the duration text for `Fixed(1251)` and for an
  in-progress node.
- Live: run `hello` on `gradle-smoke`, open Run, wait for rows `hello` and `:app:hello`; select `:app:hello`
  and wait for `hello` in the console text.

### 5.3 Done when

- [ ] The tree shows in Run (Gradle task) and Build, with icons, bold titles, hints, durations.
- [ ] Selection changes the console's content.
- [ ] Spec 9.3 icon layout written down.

---

## 6. Phase 4: the Terminal tool window

**Goal:** IntelliJ's Terminal tool window: tabs of shells on the backend machine, each an IntelliJ-style
terminal view; keys, paste, resize, titles, full-screen programs (vim, less, top).

Spec: 2 (UI), 3 (wire), 4 (JediTerm). Transport: the classic rd `TerminalModel` path (the user chose it;
spec 3.1 says why IJent is out).

### 6.1 Design

- **JediTerm on the class path**: add `"intellij.libraries.jediterm.core.jar"` to `protocolJars` in the root
  `build.gradle.kts`. That list already reaches `:compose` (compile), `:compose` tests, `:desktop` and
  `:android` (through `PrepareProtocolJars` in `android/build.gradle.kts`). Check that the Android build
  still works and that nothing on Android calls `TtyConnector.resize(java.awt.Dimension)` (spec 4: the only
  AWT use); if the Android build or the app's start trips over `java.awt.Dimension`, report to the
  coordinator.
- **`session/terminal/TerminalSessions.kt`** (copies `ThinClientTerminalHost`): binds
  `solution.terminalModel.sessions`; `open(): String` puts `RdTerminalSession(Client, null)` under
  `"SessionId#<n>"` with `size` set first (spec 3.4 order); sessions with `origin = Host` (the backend
  opened them, e.g. Open in Terminal) are adopted; `close(id)` sets `closed = true` and removes the entry
  (spec 12 row 4: check whether the shell ends; write it down).
- **`session/terminal/RdTtyConnector.kt`** (copies `ThinClientTtyConnector`): a JediTerm `TtyConnector` over
  one session. `output` (async signal, advise with the rd scheduler) → a blocking queue of chars (decode
  UTF-8 across chunk borders with a `CharsetDecoder`); `read` takes from it and blocks; `write` fires `input`;
  `resize(TermSize)` sets `size`.
- **Emulator**: per session, `JediTerminal(display, TerminalTextBuffer(columns, rows, StyleState(), 5000),
  styleState)` and `TerminalStarter(terminal, connector, TtyBasedArrayDataStream(connector), null,
  executors)`, `start()` on its own thread (spec 4). `display` is IDEate's `TerminalDisplay`: records cursor
  position, cursor shape and visibility, title, alternate-buffer flag, mouse mode, bracketed paste, and asks
  the UI to redraw.
- **`session/terminal/TerminalScreen.kt`**: under `TerminalTextBuffer.lock()`, copies history + screen lines
  into an immutable snapshot: lines of styled runs (text, foreground, background, bold, italic, underline,
  inverse), cursor (column, row, visible, shape), `alternate`. Colors: index 0–15 → the scheme keys of spec
  2.4, 16–255 → the xterm palette, RGB as given; defaults from `CONSOLE_NORMAL_OUTPUT` /
  `CONSOLE_BACKGROUND_KEY`. Published in `SessionState.terminals`.
- **`ui/terminal/TerminalView.kt`**: draws the snapshot with the editor's text drawing (reuse `EditorView`'s
  line drawing in `CONSOLE` mode from phase 1 if phase 1 has landed; otherwise a small Canvas text renderer,
  and say so). Settings of spec 2.3: 6 dp space above the first line and below the last, no gutter, wrap at
  the grid width, no wrap marks, the terminal font (editor font; `ponytail:` until terminal font settings
  are read). The cursor of spec 2.5 (focused block filled with inverted text, unfocused outlined, 2 dp
  underline / bar, blink with the editor's blink period while focused). Resizing: columns = width ÷ cell
  width, rows = height ÷ line height → `TerminalStarter.postResize(TermSize, RequestOrigin.User)` and the
  session `size`.
- **Keys**: hardware keys → AWT key codes → `TerminalStarter.getCode(keyCode, modifiers)` → `sendBytes`;
  typed text (`EditorTextTarget` / soft keyboard) → `sendString`; paste wrapped when bracketed paste is on
  (spec 2.6).
- **Keyboard bar (user's decision, 2026-11-21):** soft keyboards have no Esc, Ctrl, Tab or arrows. They go
  in the **keyboard bar** decided on 2026-11-18 (intellij-android `docs/pending.md` row 30,
  `docs/feature-parity-plan.md` section 1): an extra bar below the tool window bar, shown while the soft
  keyboard is open. This phase builds the bar itself (not its Undo/Redo buttons, which come later) and shows
  the terminal keys in it while a terminal has focus. **Each key is an icon**, no text labels:

  | Key | Icon | Sends |
  |---|---|---|
  | Esc | glyph `⎋` (U+238B, IntelliJ's `MacKeymapUtil.ESCAPE`) | `\e` |
  | Ctrl | glyph `⌃` (U+2303, `MacKeymapUtil.CONTROL`) | sticky: on until the next key, which is sent as Ctrl+key (`a`–`z` → 0x01–0x1a); shown selected while on |
  | Tab | glyph `⇥` (U+21E5, `MacKeymapUtil.TAB`) | `\t` |
  | ← ↑ → ↓ | `expui/general/chevronLeft.svg`, `chevronUp.svg`, `chevronRight.svg`, `chevronDown.svg` | through `TerminalKeyEncoder` (application mode respected) |

  IntelliJ has no icons for Esc, Ctrl and Tab; the glyphs are the ones IntelliJ itself shows for those keys
  in macOS shortcuts (IC `platform/platform-api/src/com/intellij/openapi/keymap/MacKeymapUtil.java`), drawn as
  icons the same size as the SVG ones (16 dp in a 24 dp button, as the toolbar's icon buttons). Before
  building the bar, read how the soft keyboard is detected and where the tool window bar is drawn
  (`ui/ClientScreen.kt`, `ui/EditorTextTarget.kt`); ask the coordinator if the bar's place is unclear.
- **`ui/terminal/TerminalToolWindow.kt`**: IDEate registers the window itself, as JetBrains Client does
  (spec 1): add `ToolWindow.TERMINAL = ToolWindow("Terminal", "Terminal",
  "icons/expui/toolwindow/terminal.svg", Place.BOTTOM_LEFT)` to `ui/ToolWindows.kt` and include it in
  `toolWindowButtons` like `PROJECT` (it is already in `DEFAULT_LAYOUT`). Content: tab row with the titles of
  spec 2.2 (`TerminalTitle` rule; default title from the backend, "Remote"), the New Tab button (+, icon
  `expui/general/add.svg`), close on each tab; opening the window with no tab opens one.
- **Context menu**: Copy, Paste, Select All, Clear Buffer (spec 2.2), done locally; the rest later.

### 6.2 Tests

- Unit: `RdTtyConnector` decodes a UTF-8 character split across two `output` chunks; `TerminalScreen` from a
  `JediTerminal` fed `"\u001b[31mred\u001b[0m plain\r\n"` → a red run `red` and a default run ` plain`; the
  title rule of spec 2.2 (all four rows of its table).
- Live (`TerminalLiveTest`, skipped without `DRIVER_LINK`): open a session, send `echo hi\r`, wait until the
  screen snapshot has a line `hi`; resize to 60×20, send `stty size\r`, wait for `20 60`; send
  `printf '\033]0;probe-title\007'\r`, wait for the title `probe-title`; `vi\r` then wait for the alternate
  buffer flag, `:q\r` and wait for it to clear.
- UI: PNG of a terminal with `ls -la --color` output next to IntelliJ's Terminal on the same backend.

### 6.3 Done when

- [ ] All live checks above pass; the shell keeps working after a resize.
- [ ] Colors, cursor and spacing match spec 2.3–2.5 (Before/After PNGs).
- [ ] New Tab, close, titles; the window has its bar button at bottom left.
- [ ] The keyboard bar shows with the soft keyboard while a terminal has focus; each key works (unit test
      for the bytes: Ctrl then `c` → `0x03`; ↑ → `\e[A`, or `\eOA` in application mode).
- [ ] `:android:assembleDebug` builds; the coordinator runs it on a device with the user's go.
- [ ] Spec 3.4 / 12 row 4 updated (what `closed` does).

---

## 7. Phase 5: Services (probe first)

Not ready to build. First a probe, done by the coordinator or a subagent with the probe code of appendix A:
a project with a Spring Boot (or other run-dashboard) configuration, then log
`RunDashboardServiceRpc.getServices`, `getStatuses`, `getLuxedContentEvents`, and what
`startLuxingContentForService(projectId, id)` returns, and the `Services` tool window's `BeTabbedControl`.
Write the results into spec 10, then plan the phase here.

## 8. Phase 6: Debug (probe first)

Same: run `Main` with `CwmRunConfigurationExecuteArgs("Debug")` and a breakpoint, dump the `Debug` tool
window (or whatever registers) and its Lux panel's local controls, and `solution.debuggerModel`. Write the
results into spec 8, then plan.

---

## 9. Status

| Phase | State | Commit | Notes |
|---|---|---|---|
| Probe | done 2026-11-21 | bd564bc | spec 11 |
| 1 Console text controls | done 2026-11-21 | the commit "Consoles: read-only console editor…" | Output never stopped: the client dropped the changes (spec 6.2 addendum). `ConsoleEditor` has local selection and copy, follows output at the end, no caret. Live test also runs `Slow` (appendix B) |
| 2 Run and Build tabs | done 2026-11-21 | the commit "Run and Build tabs: …" | Differs from the plan: tabs are chips in the tool window header (like Terminal), `HeaderTab` is shared; `LuxPanelHost` is in `session/` and `LuxPaneView` takes callbacks; Run, Debug and Build get stripe buttons; IDEate follows the backend's `ActiveAndFocused` for Run; icons of the backend's main window frames are kept (else the toolbar is blank); closing a tab removes it from `tabs` (CloseContent was disabled), with the Cancel limit in spec 8.2. No IntelliJ reference PNG |
| 3 Build tree | done 2026-11-21 | the commit "Build tree: …" | Differs from the plan: the rows are `ProjectTreeView`'s with one new part, a right-aligned gray `TreeNode.trailing` (the duration; the name is cut before it). `BuildTreeApi` is reached through `SearchKernel` (no second Fleet client); `getNavigationFlow` and `onNavigationContextChange` are not called (`ponytail:` no source navigation or previous/next problem yet). The plan's live check "rows `hello` and `:app:hello`" can't hold: a finished successful step is hidden while `showSuccessful` is false (spec 9.3 addendum), so the live test shows `:app:hello` through the toolbar's "Show Successful Steps". Needed beyond the plan: `LuxPanelHost` honors `LxLocalComponent.visible` (a step has its own console). Icon layout: spec 9.3 addendum. Running icon: the eight `process/step_N.svg` frames. The package is `session/buildtree`, not `session/build`: `.gitignore` has `build/`, which hides any directory called `build` from `git add -A`. `ConsoleEditor` follows output with `requestScrollToItem` (the old `scrollToItem` measured the list inside a `LaunchedEffect` and made Compose fail with "pending composition has not been applied" when a step's console appeared). The live test's Terminate answer is the Enter key (the dialog's default button), not a click at fixed pixels; it needs the backend setting `processCloseConfirmation` at its default, since "Do not ask again" (`TERMINATE` in `options/ide.general.xml`) closes a running tab without the dialog. No IntelliJ reference PNG |
| 4 Terminal | done 2026-11-21 (desktop checked live; device run left to the coordinator) | 515c149 | terminal keys in the keyboard bar, as icons (user, 2026-11-21). Differs from the plan: own canvas renderer (not `EditorView`'s `CONSOLE` mode); the bar sits between the tool window bar and the status bar in every layout; the soft keyboard is detected with `WindowInsets.ime`; tests also in `desktop/src/test` (`TerminalLiveTest`, `TerminalUiTest`); no IntelliJ reference PNG (this environment can't capture a window). Spec 3.4 and 4 have addenda |
| 5 Services | needs a probe; later (user, 2026-11-21) | | |
| 6 Debug | needs a probe; later (user, 2026-11-21) | | |

---

## Appendix A: probe code

The probe of 2026-11-21 (spec 11), as it was hooked into `ClientSession`. Not committed; reuse the pieces.

### A.1 Hook (in `ClientSession.createModels` → `bindSolution`, after `searchEverywhere` is created)

```kotlin
if (System.getenv("PROBE_CONSOLES") != null)
  ConsolesProbe(
      solutionLifetime,
      newSolution,
      rdScheduler,
      runAction = { actionId ->
        actionMenus?.runAction(
          actionId,
          "MainMenu",
          ActionTimestampSetModel(listOf(ProjectTimestampModel(newSolution.projectId))),
        ) { log("PROBE $actionId disabled") }
      },
      fleetFlow = { fqn, method, argument -> kernel.probeFlow(fqn, method, argument) },
      log = ::log,
      luxModel = protocol.luxModel,
    )
    .start()
```

### A.2 Terminal, run, panels (from `ConsolesProbe`)

```kotlin
// A terminal session (spec 3.4).
val session = RdTerminalSession(RdTerminalSessionOrigin.Client, null)
session.size.set(RdTerminalSize(100, 30))
session.outputConnected.set(true)
solution.terminalModel.sessions["SessionId#probe1"] = session
session.output.adviseOn(sessionLifetime, scheduler) { bytes -> /* raw PTY output */ }
session.input.fire("echo hi\r".toByteArray().toList())

// Run a configuration (spec 8.3).
solution.codeWithMeRunConfigurationsModel.runConfigurations.values
  .first { it.name == "Main" }
  .execute.start(lifetime, CwmRunConfigurationExecuteArgs("Run")).result.advise(lifetime) { /* Unit */ }

// Show every Lux panel and list its client-drawn controls (spec 7).
luxModel.components.view(lifetime) { _, id, component ->
  if (component is LxPanel) {
    if (component.graphicsConfiguration.valueOrNull == null)
      component.graphicsConfiguration.set(LuxDialogs.CONFIGURATION)
    if (component.position.valueOrNull == null) component.position.set(LxPoint(0.0, 0.0))
    component.size.set(LxSize(900, 300))
    component.visible.set(true)
    component.localComponents.view(lifetime) { _, localId, local ->
      if (local is LxLocalBeControl) log("panel $id local ${localId.id}: ${local.control.printToString()}")
    }
  }
}

// Ask a backend tool window to build its content, then dump it.
solution.toolWindowModel.availableToolWindows["Run"]!!.toolWindow.state.set(BeToolWindowState.Active)
log(model.toolWindow.content.printToString())   // import com.jetbrains.rd.util.string.printToString
```

### A.3 A JSON Fleet RPC client for any flow (added to `kernel/SearchKernel.kt`)

```kotlin
// In connect(), right after `val proxy = serviceProxy(client)`:
probeApi = { fqn -> proxy.proxy(JsonApiDescriptor(fqn), BACKEND_ROUTE, InstanceId(fqn)) }

@Volatile private var probeApi: ((String) -> JsonApi)? = null

fun probeFlow(fqn: String, method: String, argument: String? = null): Job = scope.launch {
  ready.await()
  val api = requireNotNull(probeApi)(fqn)
  val parameter = argument?.let { Json.parseToJsonElement(it) } ?: projectId()   // {"id": "<kernel project id>"}
  (api.call(method, arrayOf(parameter)) as Flow<JsonElement>).collect { event -> log("$method: $event") }
}

class JsonApi(private val proxy: suspend (String, Array<Any?>) -> Any?) : RemoteApi<Unit> {
  suspend fun call(method: String, arguments: Array<Any?>): Any? = proxy(method, arguments)
}

class JsonApiDescriptor(private val fqn: String) : RemoteApiDescriptor<JsonApi> {
  private val json = RemoteKind.Data(JsonElement.serializer())
  // The parameter name must be the one in IntelliJ's interface: "buildViewId" for BuildTreeApi, "projectId" for the others.
  override fun getSignature(methodName: String): RpcSignature =
    RpcSignature(
      methodName,
      arrayOf(ParameterDescriptor(if (fqn.endsWith("BuildTreeApi")) "buildViewId" else "projectId", json)),
      RemoteKind.Flow(json, nullable = false),
    )
  override fun clientStub(proxy: suspend (String, Array<Any?>) -> Any?) = JsonApi(proxy)
  override fun getApiFqn() = fqn
  override suspend fun call(impl: JsonApi, methodName: String, args: Array<Any?>): Any? = error("client only")
}
```

### A.4 Headless test that runs it

```kotlin
class ConsolesProbeTest {
  @Test
  fun probe() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    try {
      Thread.sleep((System.getenv("PROBE_SECONDS") ?: "110").toLong() * 1000)
    } finally {
      session.stop()
    }
  }
}
```

## Appendix B: test projects

Make them in your scratchpad; open one per backend run.

`gradle-smoke` (Gradle 9.6.0 wrapper; any recent Gradle works):

```
settings.gradle.kts           rootProject.name = "gradle-smoke"
                              include("app")
app/build.gradle.kts          plugins { java }
                              tasks.register("hello") {
                                group = "demo"
                                description = "Prints hello"
                                doLast { println("hello") }
                              }
.idea/runConfigurations/hello.xml
  <component name="ProjectRunConfigurationManager">
    <configuration default="false" name="hello" type="GradleRunConfiguration" factoryName="Gradle">
      <ExternalSystemSettings>
        <option name="externalProjectPath" value="$PROJECT_DIR$" />
        <option name="externalSystemIdString" value="GRADLE" />
        <option name="taskNames"><list><option value=":app:hello" /></list></option>
      </ExternalSystemSettings>
      <method v="2" />
    </configuration>
  </component>
```

`smoke` also gets `src/demo/Slow.java` (prints `tick 1` … `tick 6` 0.7 s apart with `Thread.sleep(700)`, then
`System.err.println("done with an error line")`) and a second shared configuration `Slow` next to `Main`
(`ConsoleRunTest` runs both). Both configurations are `.idea/runConfigurations/<name>.xml`, type `Application`,
`MAIN_CLASS_NAME` `demo.Main` / `demo.Slow`, module `smoke`.

`smoke` (plain Java; the backend needs a JDK named in `.idea/misc.xml`, e.g. `project-jdk-name="temurin-25"`,
registered in the test backend's `config/options/jdk.table.xml`):

```
src/demo/Main.java      package demo;
                        public class Main {
                          public static void main(final String[] arguments) {
                            String greeting = "just a simple greeting";
                            System.out.println(greeting);
                            System.out.println(greeting.length());
                          }
                        }
smoke.iml               a JAVA_MODULE with <sourceFolder url="file://$MODULE_DIR$/src"/>, inheritedJdk
.idea/modules.xml       lists $PROJECT_DIR$/smoke.iml
.idea/runConfigurations/Main.xml   type="Application", MAIN_CLASS_NAME demo.Main, module smoke
```

Without `smoke.iml` and `modules.xml` the run opens an "Edit Configuration" dialog instead (spec 8.3).
