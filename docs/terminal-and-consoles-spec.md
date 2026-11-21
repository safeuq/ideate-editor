# Terminal and consoles (spec): how IntelliJ 263 and JetBrains Client build them

Spec, 2026-11-21. What IntelliJ IDEA 2026.3 (263.7307.200) and JetBrains Client 263.7307.200 do in the five
places that show process output: the Terminal, Run, Debug, Build and Services tool windows. It covers both
the UI (what IntelliJ draws, with which settings) and the split-mode wire (what the backend sends a client).
IDEate's plan to build the same is a separate document: `terminal-and-consoles-plan.md`.

Sources:

| Short name | Path |
|---|---|
| IC | `/Users/safeuq/Files/Libraries/intellij-community-263.7307.200` (open sources) |
| client | `~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-…/IntelliJ IDEA.app/Contents`, classes read with `javap -c -p` (`/Users/safeuq/.sdkman/candidates/java/current/bin/javap`) |
| protocol jar | client `lib/intellij.platform.split.protocol.jar` (`com.jetbrains.codeWithMe.model`, `com.jetbrains.rd.ide.model`) |
| model jar | client `lib/intellij.rd.ide.model.generated.jar` (`com.jetbrains.ide.model.uiautomation`: the `Be*` controls) |
| terminal jars | client `plugins/terminal/lib/terminal.jar`, `plugins/terminal/lib/modules/intellij.terminal.frontend.jar` (867 classes), `intellij.terminal.frontend.split.jar` (33) |
| JediTerm | client `lib/intellij.libraries.jediterm.core.jar` (the emulator, 176 classes, Java 11 bytecode) |

Tags: **[Seen code]** = read in the named source or class. **[Seen run]** = recorded against a test backend
(2026-11-21, IntelliJ IDEA 263.7307.200 on macOS, projects `gradle-smoke` and `smoke`, see section 10).
**[Inferred]** = follows from code but not read or run. **[Open]** = not known.

## Contents

1. Where output shows
2. Terminal: the UI (reworked engine)
3. Terminal: the split-mode wire
4. JediTerm core: the emulator API
5. Console view: the UI
6. Console view: the split-mode wire
7. Lux panels with client-drawn controls
8. Run and Debug tool windows
9. Build tool window
10. Services tool window
11. Probe record (2026-11-21)
12. Open questions

---

## 1. Where output shows

| Place | Tool window id | IntelliJ class that draws it | How a split-mode backend sends it (default settings) |
|---|---|---|---|
| Terminal | `Terminal` (bottom) | `TerminalView` (reworked engine) | not a backend tool window; the client registers it. Bytes over rd `TerminalModel` (classic path) or IJent (JetBrains Client's default) |
| Run | `Run` (bottom) | `RunContentManager` → `RunnerLayoutUi` → `ConsoleViewImpl` | backend tool window; each run a `BeTab` whose content is a Lux panel; the console inside it is a client-drawn `BeConsoleViewImpl` |
| Debug | `Debug` (bottom) | same, plus the debugger tabs | **[Open]**, not probed |
| Build | `Build` (bottom) | `MultipleBuildsView` → `BuildView` (tree + console) | backend tool window, same Lux panel; the tree is a client-drawn `BeSplitComponentControl("BuildTree")` fed over Fleet RPC `BuildTreeApi` |
| Services | `Services` (bottom) | `ServiceView` | backend tool window with no tabs; content over Fleet RPC `RunDashboardServiceRpc` |

[Seen run] for the "how" column of Run, Build and Services; [Seen code] for the rest.

---

## 2. Terminal: the UI (reworked engine)

The reworked engine is the default in 263. Paths below are under IC
`plugins/terminal/frontend/src/com/intellij/terminal/frontend` unless named otherwise. [Seen code]

### 2.1 Classes

| Class | Job |
|---|---|
| `toolwindow/TerminalToolWindowTabsManager` (+ `impl/TerminalToolWindowTabsManagerImpl`) | owns the tool window's tabs: create, close, rename, keep across restarts. Sets `toolWindow.setTabActions(TerminalToolwindowActionGroup)` and `setTitleActions(TerminalEmulatorBadgeAction)` |
| `toolwindow/TerminalToolWindowTab` | one tab: a `Content` holding one `TerminalView` |
| `TerminalSessionsManager` | starts sessions and keeps them by id |
| `view/TerminalView` | the widget. Fields: `component`, `gridSize`, `title: TerminalTitle`, `outputModels: TerminalOutputModelsSet(regular, alternative, active)`, `textSelectionModel`, `sessionState`, `keyEventsFlow`, `workingDirectoryFlow`, `shellIntegrationDeferred`; `sendText(text)` |
| `view/impl/TerminalViewImpl` | makes the regular output editor at once and the alternate-buffer editor the first time a program switches to it (vim, less, top); shows the active one |
| `TerminalOutputModel` | the text and styles of one buffer: `textLength`, `lineCount`, `cursorOffset`, `screenTopOffset`, `startOffset`, `firstLineIndex`, `getText(start, end)`, highlightings, OSC 8 links. Old lines are trimmed from the top, so offsets count from `startOffset` |
| `view/impl/TerminalOutputModelController` | applies `TerminalContentUpdatedEvent(text, styles, startLineLogicalIndex, …)` to the model |
| `TerminalSession` | `getInputChannel()` (bytes, resize, signals) and `getOutputFlow()` of `TerminalOutputEvent`: `ContentUpdated`, `CursorPositionChanged`, `StateChanged`, `Beep`, `SessionTerminated`, `InitialState`, shell integration events |
| `view/impl/TerminalEditorFactory` | makes the output and alternate editors (2.3) |
| `view/impl/TerminalCursorPainter` | draws the cursor (2.5) |
| `view/impl/TerminalKeyEventsHandlerImpl`, `TerminalKeyEncodingManager` | key events to bytes with JediTerm's `TerminalKeyEncoder` (2.6) |
| `view/impl/TerminalMouseEventsHandlerImpl` | mouse reports to programs that ask for them; selection otherwise |
| `view/impl/TerminalSearchSession` | Find in the terminal |
| `view/impl/TerminalLineWrapPositionStrategy` | wraps at the grid width, not at word ends |

### 2.2 Tool window, tabs, actions

From IC `plugins/terminal/resources/META-INF/terminal.xml` and `frontend/resources/intellij.terminal.frontend.xml`:

| Item | Value |
|---|---|
| Tool window | `<toolWindow id="Terminal" anchor="bottom" icon="…TerminalIcons.OpenTerminal_13x13">`; the new UI icon is `icons/expui/toolwindow/terminal.svg` in `plugins/terminal/lib/terminal.jar` (also `terminal@20x20.svg`, `_dark` variants) |
| Tab actions (`TerminalToolwindowActionGroup`) | New Tab `Terminal.NewTab` (⌘T on macOS keymaps, Ctrl+Shift+T on `$default`, Ctrl+Shift+` on VSCode keymaps), then `TerminalNewPredefinedSession` (icon `AllIcons.Toolbar.Expand`, the ▾ of predefined shells) |
| Gear (`Terminal.ToolWindowActions`) | Engine ▸ (`Terminal.UseReworkedEngine`, `Terminal.UseClassicEngine`, `Terminal.UseNewTerminalEngine`), Settings (`Terminal.Settings`) |
| Title action | `TerminalEmulatorBadgeAction` |
| Context menu (`Terminal.ReworkedTerminalContextMenu`) | Find, New Tab, Close Tab │ Copy (`Terminal.CopySelectedText`), Paste, Paste from History, Select All │ Clear Buffer, Page Up, Page Down │ Line Up, Line Down, … |

Tab title, from IC `platform/execution-impl/src/com/intellij/terminal/TerminalTitle.kt` (`buildTitle`):

| userDefinedTitle | applicationTitle | defaultTitle | Tab shows |
|---|---|---|---|
| `null` | `null` | `null` | `Local` (bundle key `terminal.default.title`) |
| `null` | `null` | `"Remote"` | `Remote` |
| `null` | `"vim build.gradle.kts"` | `"Remote"` | `vim build.gradle.kts` (cut in the middle to 30 characters with `StringUtil.trimMiddle`) |
| `"Server"` | `"vim …"` | `"Remote"` | `Server` |

A `tag`, when set, is added as `"<title> (<tag>)"`. The full title (tooltip, `buildFullTitle`) does not cut.

### 2.3 The output editor

From `TerminalEditorFactory.createOutputEditor` and IC
`plugins/terminal/src/org/jetbrains/plugins/terminal/block/ui/TerminalUiUtils.kt` (`createOutputEditor`):

| Setting | Value |
|---|---|
| Kind | `EditorFactory.createEditor(document, project, EditorKind.CONSOLE)`; `isRendererMode = true` (read-only, no read-only hint); undo off (`UndoUtil.disableUndoFor`) |
| Scrolling | `isScrollToCaret = false`; no horizontal scroll bar; scroll pane border empty. The alternate editor has no scroll bars at all |
| Gutter | no line numbers, no gutter icons, no folding outline; gutter background not painted; free painter area hidden |
| Other | `isShowingSpecialChars = false`, `isRightMarginShown = false`, `isCaretRowShown = false`, `isBlockCursor = false` (the terminal paints its own cursor); context menu group `Terminal.ReworkedTerminalContextMenu` |
| Wrapping | soft wraps on (`setUseSoftWrapsQuiet`), `isUseCustomSoftWrapIndent = false`, `TerminalLineWrapPositionStrategy`, grid-mode optimizations on, soft wraps under the scroll bar, `EmptySoftWrapPainter` (no wrap marks) |
| Spacing | an empty block inlay of `TerminalUi.blockTopInset` = 6 px above the first line and `TerminalUi.blockBottomInset` = 6 px below the last line (`VerticalSpaceInlayRenderer`); the alternate buffer has none |
| Colors | `TerminalColorScheme` over the global editor scheme (2.4) |
| Font | from `JBTerminalSystemSettingsProviderBase`: `fontPreferences`, `lineSpacing`, `columnSpacing` (as `characterGridWidthMultiplier`), `terminalFontSize`; Settings ▸ Tools ▸ Terminal, defaults follow the editor font |
| Copy on select | `CopyOnSelectionHandler` when the setting is on |

Other `TerminalUi` values: `blockLeftInset` 9, `blockRightInset` 12 (block terminal only), `alternateBufferLeftInset` 4
(`SimpleTerminalView`).

### 2.4 Colors

From IC `platform/execution-impl/src/com/intellij/terminal/JBTerminalSchemeColorPalette.kt` and
`ConsoleHighlighter.java`:

| Terminal color | Scheme key |
|---|---|
| default foreground | foreground of `CONSOLE_NORMAL_OUTPUT`, else the scheme's default foreground |
| default background | color `CONSOLE_BACKGROUND_KEY`, else the scheme's default background |
| ANSI 0–7 | `CONSOLE_BLACK_OUTPUT`, `CONSOLE_RED_OUTPUT`, `CONSOLE_GREEN_OUTPUT`, `CONSOLE_YELLOW_OUTPUT`, `CONSOLE_BLUE_OUTPUT`, `CONSOLE_MAGENTA_OUTPUT`, `CONSOLE_CYAN_OUTPUT`, `CONSOLE_GRAY_OUTPUT` |
| ANSI 8–15 | `CONSOLE_DARKGRAY_OUTPUT`, `CONSOLE_RED_BRIGHT_OUTPUT`, `CONSOLE_GREEN_BRIGHT_OUTPUT`, `CONSOLE_YELLOW_BRIGHT_OUTPUT`, `CONSOLE_BLUE_BRIGHT_OUTPUT`, `CONSOLE_MAGENTA_BRIGHT_OUTPUT`, `CONSOLE_CYAN_BRIGHT_OUTPUT`, `CONSOLE_WHITE_OUTPUT` |
| 256-color and 24-bit (`\e[38;2;r;g;bm`) | used as given |

The 16 keys inherit: the eight base ones fall back to `CONSOLE_NORMAL_OUTPUT`, each bright one to its base
(`CONSOLE_RED_BRIGHT_OUTPUT` → `CONSOLE_RED_OUTPUT`), `CONSOLE_DARKGRAY_OUTPUT` → `CONSOLE_GRAY_OUTPUT`.

### 2.5 The cursor

`TerminalCursorPainter`:

| Shape (from the program, else the setting) | Focused | Not focused |
|---|---|---|
| block (`BLINK_BLOCK`, `STEADY_BLOCK`) | filled cell; the character under it drawn in the background color | an outlined cell (`EmptyBlockCursorRenderer`) |
| underline | a 2 px (`JBUIScale.scale(2f)`) bar at the cell's bottom | same |
| vertical bar | a 2 px bar at the cell's left | same |

- Blinks only when focused and the shape is a blinking one, with the editor's `caretBlinkPeriod`.
- Color: the foreground of the text under the cursor, with contrast adjusted; the scheme's default
  foreground when there is no text style.
- Default shape from Settings ▸ Terminal (`TerminalUiSettingsManager.CursorShape`: BLOCK, UNDERLINE, VERTICAL),
  blinking when `editorSettings.isBlinkCaret`.
- Hidden when the program hides it (`\e[?25l`).

### 2.6 Keys

- Each key becomes bytes through JediTerm's `TerminalKeyEncoder.getCode(keyCode, modifiers)`, with AWT key
  codes (`KeyEvent.VK_*`) and modifiers. Programs switch arrows and keypad to "application" mode
  (`\e[?1h`, `\e=`); the encoder follows (`arrowKeysApplicationSequences()`, `keypadApplicationSequences()`).
- Examples: Up = `\e[A`, or `\eOA` in application mode; Enter = `\r`; Backspace = `\x7f`; Ctrl+C = `\x03`;
  Tab = `\t`; Esc = `\e`.
- Typed text goes as UTF-8. Paste is wrapped in `\e[200~` … `\e[201~` when the program turned bracketed
  paste on (`\e[?2004h`).
- Typing scrolls the output to the bottom. [Inferred]

### 2.7 The alternate buffer

`\e[?1049h` switches to the alternate screen (full-screen programs); `TerminalViewImpl` shows the alternate
editor (no scroll bars, no history), and `\e[?1049l` switches back with the regular history intact.

**Addendum 2026-11-21 (phase 4, how IDEate draws it):** IDEate has its own small renderer
(`ui/terminal/TerminalView.kt`), not the editor's: a Compose canvas over a copy of JediTerm's buffer
(`session/terminal/TerminalScreen.kt`). Seen working against the live backend (PNGs in the phase 4 report):
colors from the scheme keys of 2.4, the 6 dp space above the first line and below the last, a cell grid in the
editor font (JetBrains Mono 12, line height = font height rounded up, line spacing 1.0), a filled block cursor
with the character under it in the background color, scrolling the history, drag selection, Copy / Paste /
Select All / Clear Buffer. Not done yet (marked `ponytail:` in the code): mouse reports to programs, the
terminal font and spacing settings, the editor's caret blink period (500 ms is used), find in the terminal,
IntelliJ's two-step Clear Buffer (IDEate drops the history only). The alternate buffer keeps the same grid as
the regular one (IntelliJ gives it no 6 dp space), so entering vim doesn't resize the shell. The Escape,
Ctrl, Tab and arrow keys of a phone's keyboard are the keyboard bar (plan 6.1); the tab title rule of 2.2 is
`terminalTabTitle`.

---

## 3. Terminal: the split-mode wire

### 3.1 Two paths

| | Classic split path | Reworked engine's path (JetBrains Client's default) |
|---|---|---|
| Where the shell runs | backend machine | backend machine, through the IJent agent |
| Who starts it | backend `BackendTerminalHost$MyProtocolListener` → `BackendTerminalManager.createWidgetForSpecificId(sessionId, …, shellCommand)` (`intellij.terminal.backend.split.jar`) | client: `LocalTerminalDirectRunner` over Eel; in split mode `RdProtocolEelMachine` deploys IJent with Fleet RPC `IjentDeployRemoteApi.deployIjent(IjentTlsServerBootstrap(pem))` → `IjentConnectionResult(forwardedPort, osFamily, arch, parentPidToWatch)`, then gRPC over mutual TLS |
| Wire | rd `TerminalModel.sessions`, raw bytes both ways | gRPC process API; generated protobuf classes in the closed `lib/intellij.platform.ijent.impl.jar` (1,372 classes in `…/ijent/impl/proto`), no `.proto` files in IC |
| Who emulates | the client (JediTerm) | the client (JediTerm or Ghostty) |
| Agent binaries | — | client `lib/ijent/ijent-aarch64-apple-darwin-release` (12.5 MB), `ijent-aarch64-unknown-linux-musl-release` (13.3 MB), `ijent-x86_64-unknown-linux-musl-release` (15.6 MB) |

`ReworkedTerminalModel` (`solution.reworkedTerminalModel`) is not the reworked engine's wire: its only field
is `portForwardingSessions: RdMap<Int, SessionPortForwarding>`. [Seen code]

### 3.2 rd `TerminalModel`

`Solution.terminalModel` (extension `com.jetbrains.codeWithMe.model.TerminalModel_GeneratedKt.getTerminalModel`).
[Seen code]

```
TerminalModel
  sessions: RdMap<String, RdTerminalSession>          // key e.g. "SessionId#1"

RdTerminalSession(origin: RdTerminalSessionOrigin {Host, Client}, shellCommand: List<String>?)
  title: RdProperty<RdTerminalTitle(userDefinedTitle: String?, applicationTitle: String?, defaultTitle: String?)>
  size: RdOptionalProperty<RdTerminalSize(width: Int /*columns*/, height: Int /*rows*/)>
  scroll: RdOptionalProperty<Int>
  closed: RdOptionalProperty<Boolean>
  outputConnected: RdOptionalProperty<Boolean>
  portForwardingSession: RdProperty<SessionPortForwarding>
  initialState: RdProperty<BeTerminalScreenState?>
  input: RdSignal<List<Byte>>      // async signal: client → backend, already-encoded key bytes
  output: RdSignal<List<Byte>>     // async signal: backend → client, raw PTY output

BeTerminalScreenState(cursorX, cursorY, historyLines: List<BeTerminalLine>, screenLines: List<BeTerminalLine>)
BeTerminalLine(entries: List<BeTerminalLineEntry(text, style: BeTerminalEntryStyle(foreground: BeTerminalColor,
  background: BeTerminalColor, optionsMask))>)
BeTerminalColor(index, r, g, b)
```

`input` and `output` are async signals: advise them with a scheduler (`adviseOn(lifetime, scheduler)`).

### 3.3 JetBrains Client's classic implementation

`intellij.terminal.frontend.split.jar`, package `com.jetbrains.thinclient.terminal`. [Seen code]

| Class | What it does |
|---|---|
| `ThinClientTerminalHost` | `bindWithModel(TerminalModel)`; `getOrCreate(lifetime, id, shellCommand)`: builds `RdTerminalSession(Client, shellCommand)`, sets `title = RdTerminalTitle(…)`, puts it into `sessions` under `"SessionId#…"`; when `lifetime` ends, removes the entry. Sessions the backend adds (`origin = Host`) are adopted by id |
| `ThinClientTerminalProcess` | a `Process` over one session: `getInputStream()` reads `output`, `getOutputStream()` fires `input`; `destroy()` sets `closed = true` |
| `ThinClientTerminalRunner$ThinClientTtyConnector` | JediTerm `TtyConnector` over the process; `resize(TermSize)` sets `size = RdTerminalSize(columns, rows)` when it is the size controller (the session's origin decides) |
| `ThinClientTerminalRunner` | the classic engine's runner (`CustomTerminalRunnerProvider`); binds the tab title to `title` |

The emulator runs as in a local classic terminal: JediTerm's `TerminalStarter(JediTerminal, TtyConnector,
TtyBasedArrayDataStream, …)` reads the connector and feeds `JediEmulator` (section 4).

### 3.4 What the backend does [Seen run]

| Client does | Backend answers |
|---|---|
| puts `"SessionId#probe1" → RdTerminalSession(Client, null)` with `size = (100, 30)` and `outputConnected = true` | within 0.4 s: `title = RdTerminalTitle(null, null, "Remote")`, `initialState = BeTerminalScreenState(1, 1, [], [])`, then the login shell's startup output in chunks of up to 1,024 bytes |
| fires `input` = `echo hi\r` = `[101, 99, 104, 111, 32, 104, 105, 13]` | the shell echoes each key (1–3 byte chunks), then `hi\r\n` and a new prompt |
| — the shell prints `\e]2;echo hi\a` and later `\e]2;user@host:~/gradle-smoke\a` | `title.applicationTitle` = `"echo hi"`, then `"echo"` (from `\e]1;`), then the prompt's text: the backend reads OSC 0/1/2 itself |
| `printf '\033]0;probe-title\007'` | `applicationTitle = "probe-title"` |
| sets `size = (60, 20)`, then runs `stty size` | output `20 60`: the backend resizes the PTY |
| sets `closed = true` | the map entry stays until the client removes it; the shell ends (see the addendum below) |

- The shell is the backend user's login shell (`shellCommand = null`); its working folder is the project root.
- No IntelliJ shell integration: no `\e]1341;…` codes. (IntelliJ's own scripts print
  `\e]1341;command_finished`, `\e]1341;command_history`, `\e]1341;aliases_received`, `\e]1341;clear_invoked`.)
  OSC 133 (`\e]133;A\a` prompt start, `B` input start, `C;` command start, `D;0` command end) and iTerm2's
  OSC 1337 (`CurrentDir=…`, `RemoteHost=…`) appear only when the user's own shell setup prints them.
- `defaultTitle` is `"Remote"`, so a tab with no program title shows `Remote`.

**Addendum 2026-11-21 (phase 4, closing and a first tab) [Seen run]:**

| Client does | Result |
|---|---|
| `echo $$` in the tab, then sets `closed = true` and nothing else | the shell process (the pid it printed) was gone within 5 s: **`closed = true` ends the shell** |
| then removes the map entry | nothing more to end; the entry is only bookkeeping |
| types `exit` in a tab | within 5 s the tab was gone from the client: the backend closes the session itself (it sets `closed` or removes the entry; the test could not tell which, because IDEate drops the tab on either) |
| types text while the login shell is still printing its banner (this Mac's zsh prints a long banner and a two-line prompt) | the text is lost: the shell clears typed-ahead input before its first prompt. A client has nothing to fix; a test must wait until the screen stops changing |

IDEate's `TerminalSessions.close(id)` therefore sets `closed = true` and then removes the entry, as JetBrains
Client does when its widget goes away. Row 4 of section 12 is answered. Opening the Terminal window before
the project is bound has to wait: the `terminalModel` extension exists only on the bound solution
(`SessionState.terminalsReady`).

---

## 4. JediTerm core: the emulator API

`lib/intellij.libraries.jediterm.core.jar`, packages `com.jediterm.terminal`, `com.jediterm.terminal.model`,
`com.jediterm.terminal.emulator`, `com.jediterm.core`. Its only AWT use is two default methods
`TtyConnector.resize(java.awt.Dimension…)`. [Seen code]

| Class | Members used |
|---|---|
| `TtyConnector` (interface) | `read(char[], offset, length): Int` (blocks), `write(ByteArray)`, `write(String)`, `isConnected()`, `resize(TermSize)` (default), `waitFor()`, `ready()`, `getName()`, `close()` |
| `TerminalStarter(JediTerminal, TtyConnector, TerminalDataStream, TerminalTypeAheadManager?, TerminalExecutorServiceManager)` | `start()` (the read-and-emulate loop; call on its own thread), `requestEmulatorStop()`, `sendBytes(bytes, userInput)`, `sendString(text, userInput)`, `postResize(TermSize, RequestOrigin)`, `getCode(keyCode, modifiers)`, `close()`. It is also the `TerminalOutputStream` the terminal answers through |
| `TtyBasedArrayDataStream(TtyConnector)` | the data stream over a connector (keeps escape sequences whole across reads) |
| `TerminalExecutorServiceManager` (interface) | `getSingleThreadScheduledExecutor()`, `getUnboundedExecutorService()`, `shutdownWhenAllExecuted()` |
| `model.JediTerminal(TerminalDisplay, TerminalTextBuffer, StyleState)` | the terminal state machine: `getCursorX()`/`getCursorY()` (1-based), `getSize()`, `resize(TermSize, RequestOrigin)`, `setTerminalOutput(TerminalOutputStream)`, `getStyleState()` |
| `model.TerminalTextBuffer(width, height, StyleState, maxHistoryLines)` | `lock()`/`unlock()` around reads, `getHistoryLinesCount()`, `getScreenLinesCount()`, `getLine(index)` (negative = history), `isUsingAlternateBuffer()`, `getWidth()`, `getHeight()` |
| `model.TerminalLine` | `getText()`, `isWrapped()`, `getEntries(): List<TextEntry>` / `forEachEntry`; `TextEntry` has `getText()`, `getStyle(): TextStyle` |
| `TextStyle` | `getForeground()`, `getBackground()`: `TerminalColor` (index 0–255, or RGB), `hasOption(Option.BOLD/ITALIC/UNDERLINED/INVERSE/DIM/HIDDEN)` |
| `TerminalDisplay` (interface the UI implements) | `setCursor(x, y)`, `setCursorShape(CursorShape)`, `beep()`, `scrollArea(top, height, dy)`, `setCursorVisible(Boolean)`, `useAlternateScreenBuffer(Boolean)`, `getWindowTitle()`/`setWindowTitle(String)`, `getSelection()`, `terminalMouseModeSet(MouseMode)`, `setMouseFormat(MouseFormat)`, `ambiguousCharsAreDoubleWidth()`; defaults `onResize`, `setBracketedPasteMode`, `getWindowForeground/Background` |
| `TerminalKeyEncoder(Platform)` | `getCode(keyCode, modifiers): ByteArray?`, `arrowKeysApplicationSequences()` / `arrowKeysAnsiCursorSequences()`, `keypadApplicationSequences()` / `keypadAnsiSequences()`, `setAltSendsEscape`, `setMetaSendsEscape` |
| `core.util.TermSize(columns, rows)`, `RequestOrigin {User, Remote}` | sizes |

**Addendum 2026-11-21 (phase 4, what the jar does when used from Kotlin) [Seen code, Seen run]:**

- `TerminalStarter`'s fifth constructor parameter (the type-ahead manager) is annotated non-null, and a
  `null` there fails to compile unless the call has `@Suppress("NULL_FOR_NONNULL_TYPE")`. It is only used
  when `sendBytes` / `sendString` get `userInput = true`, which would throw a `NullPointerException`; IDEate
  passes `false` always, so no manager is needed.
- `com.jediterm.core.input.KeyEvent` (`VK_UP = 38`, `VK_ENTER = 10`, `VK_ESCAPE = 27`, `VK_F1 = 112`, …) and
  `com.jediterm.core.input.InputEvent` (`SHIFT_DOWN_MASK = 64`, `CTRL_DOWN_MASK = 128`, `META_DOWN_MASK = 256`,
  `ALT_DOWN_MASK = 512`) are the AWT-free constants `getCode(keyCode, modifiers)` takes; the AWT masks
  (1, 2, 4, 8) are not the ones it reads. `JediTerminal.getCodeForKey` follows `\e[?1h` (Up becomes `\eOA`).
- The jar calls slf4j (`org.slf4j.Logger`); the client's lib folder has no slf4j jar, but SSHJ brings
  `slf4j-api`, which prints "No SLF4J providers" once and logs nothing.
- `TerminalLine` entries hold NUL (`'\u0000'`) for cells nothing was written to, and a wide character is
  followed by a filler `'\ue000'` (`CharUtils.DWC`); both take a column.
- The cursor position JediTerm reports is 1-based; `TerminalTextBuffer.getLine(i)` with `i < 0` counts back from
  the newest history line.
- The only AWT references in the jar are the three `TtyConnector.resize(java.awt.Dimension…)` default
  methods; the desktop never calls them. On Android the build (`:android:assembleDebug`) gives no warning
  for the missing class; whether ART loads `TtyConnector` without `java.awt.Dimension` is not yet run on a
  device (plan 6.3).

---

## 5. Console view: the UI

`ConsoleViewImpl`, IC `platform/consoleView/impl/src/com/intellij/execution/impl/ConsoleViewImpl.kt`, and
`ConsoleViewUtil.java` in the same folder. [Seen code]

### 5.1 The editor (`ConsoleViewUtil.setupConsoleEditor`)

| Setting | Value |
|---|---|
| Kind | `editorFactory.createViewer(document, project, EditorKind.CONSOLE)` |
| Gutter | line marker area and folding outline as the caller asks (both off for a plain run console); line numbers off; gutter background not painted |
| Other | indent guides off, no extra page at the bottom, no additional columns or lines, right margin off, caret row off, special characters off |
| Font | the console font: `UISettingsUtils.getScaledConsoleFontSize()`, the scheme's console font family |
| Highlighter | `NullEditorHighlighter` (colors come only from the printed content types) |
| Scroll bar | background = the editor background |

**Addendum 2026-11-21 (IDEate's `ConsoleEditor`, `EditorView(kind = CONSOLE)`):** no line numbers, gutter icons,
folding, indent guides, sticky lines, caret or caret row; background `CONSOLE_BACKGROUND_KEY` (editor background
when the scheme has none); typing does nothing. Text can be selected and copied (selection kept in the composable,
not in an edit session). The view follows the output while it is scrolled to the end and stays put when the user
scrolled up (the second part is not tested). Not done: the console font (editor font used), the toolbar of 5.3, input to the process, links.
The gap before the text is one gutter gap (4 dp); not compared against IntelliJ pixel by pixel.

### 5.2 Content types

Each printed piece has a `ConsoleViewContentType` (IC `platform/ide-core/src/com/intellij/execution/ui/ConsoleViewContentType.java`):

| Content type | TextAttributesKey |
|---|---|
| `NORMAL_OUTPUT` (stdout) | `CONSOLE_NORMAL_OUTPUT` |
| `ERROR_OUTPUT` (stderr) | `CONSOLE_ERROR_OUTPUT` |
| `SYSTEM_OUTPUT` (the command line, "Process finished with exit code 0") | `CONSOLE_SYSTEM_OUTPUT` |
| `USER_INPUT` (typed into the console) | `CONSOLE_USER_INPUT` |
| `LOG_DEBUG_OUTPUT`, `LOG_VERBOSE_OUTPUT`, `LOG_INFO_OUTPUT`, `LOG_WARNING_OUTPUT`, `LOG_ERROR_OUTPUT` | same names; `LOG_EXPIRED_ENTRY` too |
| ANSI colors in output | the 16 `CONSOLE_*_OUTPUT` keys of 2.4 |

Background: color key `CONSOLE_BACKGROUND_KEY`.

### 5.3 Toolbar and input

- `createConsoleActions()`: Up the Stack Trace / Down the Stack Trace (`CommonActionsManager` previous/next
  occurrence), Soft-Wrap (`ToggleUseSoftWrapsToolbarAction(SoftWrapAppliancePlaces.CONSOLE)`), Scroll to the
  End (`ScrollToTheEndToolbarAction`), Print (`Print`), Clear All; the run tab adds its own (Rerun, Stop, …).
- Typing: when the process reads stdin, typed text goes to it and shows as `USER_INPUT`.
- File paths, URLs and stack frames become links through console filters (`Filter`), followed by a click.

---

## 6. Console view: the split-mode wire

### 6.1 The control and the text control

A console in a backend tool window reaches the client as a `BeConsoleViewImpl(editorId: TextControlId)`
(model jar, `com.jetbrains.ide.model.uiautomation`). JetBrains Client builds `FrontendConsoleViewImpl`
(extends `ConsoleViewImpl`) from it with `TextControlId.toEditor()` and maps markup to content types with
`ConsoleContentTypeHandler` / `ConsoleTextControlExtensionProcessor`
(`plugins/remdev-plugin/lib/modules/intellij.platform.execution.frontend.split.jar`). [Seen code]

What arrived [Seen run]:

```
documentsOperationModel.documents[RdAbstractDocumentId("ed73181b-f882-4ed2-bd73-83e4c4f74437")]
  extensions: MarkupDocumentExtension, UndoParametersDocumentExtension
  textControls[KernelAwareTextControlId(
      kernelEditorId = "g9ojv29tmj8vgr2tv7gf",
      documentId = RdAbstractDocumentId("ed73181b-…"),
      tabIndex = 1,
      kind = Console,
      clientId = "68C7D7…-1795233107098",
      projectId = RdProjectId("23888a6f-…:3MKvw2UQ…"))]
    extensions: PermanentHeaderComponentTextControlExtension, MarkupTextControlExtension,
                CustomContextMenuExtension, HasTemplateTraversalPolicyTextControlExtension,
                ConsoleTextControlExtension(consoleInfo: TextControlConsoleInfo)
```

- The document id is an `RdAbstractDocumentId` (no file), unlike a file editor's `RdDocumentIdWithFileId`.
- `kind` is the enum `TextControlLocation` (`Unknown`, `EditorTab`, `ToolWindow`, `StandaloneWindow`,
  `Console`, `Preview`, `Diff`, `Custom`), `TextControlId.getKind()`. [Seen code]
- The text control appears at the same moment as the Lux panel that holds the console (section 7).

### 6.2 Text

Text comes as ordinary document patches on `documentsOperationModel.requestResponseModel.backendPatches`:

| Console | Patches seen |
|---|---|
| Build Output (a finished Gradle build) | one `RdResetDocumentChange` with the whole text: `9:30 AM: Executing ':app:classes :app:testClasses'…\n\n> Task :app:compileJava UP-TO-DATE\n…` |
| Application run "Main" | `RdResetDocumentChange(text = "", newVersion = (0, 127))`, carets, then one `RdDocumentChange(offset = 0, newText = "/…/bin/java -javaagent:…/idea_rt.jar=50186 -Dfile.encoding=UTF-8 …")`, then **nothing more in 60 s**, although the program printed `just a simple greeting` and `22` |

**[Open]** Why the program's own output never came. The client in that run did not create an edit session for
the console (it had no handling for `RdAbstractDocumentId`) and so never confirmed the document version.
**[Inferred]** The backend waits for that before sending more.

**[Open]** How content types travel: as markup highlighters on the document's `MarkupDocumentExtension` (the
likely way, since `ConsoleContentTypeHandler` maps highlighters), with which attribute key names.

**Addendum 2026-11-21 (phase 1, answers both [Open] items above):**

- **Output does not stop.** The backend sends every piece the program prints as its own `RdDocumentChange`,
  without waiting for the client. Run on `Slow` (prints `tick 1` … `tick 6` 0.7 s apart, then a stderr line):

  ```
  RdResetDocumentChange(text = "", newVersion = (0, 127))
  RdDocumentChange(offset = 0,   len = 407, v0 -> v1)   the command line and "tick 1\n"
  RdDocumentChange(offset = 407, len = 7,   v1 -> v2)   "tick 2\n"       0.6 s later
  …
  RdDocumentChange(offset = 442, len = 24,  v6 -> v7)   "done with an error line\n"
  RdDocumentChange(offset = 466, len = 35,  v7 -> v8)   "\nProcess finished with exit code 0"
  ```

  The 2026-11-21 probe saw "nothing more in 60 s" because the client had no handling for it: for a document
  without an edit session `ClientSession` kept the patches in `earlyPatches` and applied only
  `RdResetDocumentChange`. The [Inferred] reason (the backend waiting for the client to confirm a version) was
  wrong: with `ConsoleDocuments` applying the changes, nothing is sent back and the whole output arrives, also
  when the Run tool window is not shown, no Lux panel is visible, and the client does not report the text
  control as visible (checked each way on 2026-11-21). A short program such as `Main` finishes in about
  100 ms, so its first change already holds the command line and the whole output.
- **A console is a text control with `kind == Console`** whose document has an `RdAbstractDocumentId`; the
  client keeps its text from the patches alone (no edit session, nothing is sent back). The control's
  `MarkupTextControlExtension` and the document's `MarkupDocumentExtension` are set up as for an editor.
- **Content types are markup highlighters on the document-level markup model** (the one the client adds with
  `RdMarkupKey(projectId)`; the editor-level one stayed empty). Each is a `DefaultHighlighterModel` with
  `layer = 1001` and one `textAttributesKey`, covering exactly the printed piece:

  ```
  document:20  CONSOLE_SYSTEM_OUTPUT  0-400     '/Users/…/bin/java -javaagent:… demo.Slow'
  document:19  CONSOLE_NORMAL_OUTPUT  400-407   'tick 1\n'
  document:46  CONSOLE_ERROR_OUTPUT   442-466   'done with an error line\n'
  document:50  CONSOLE_SYSTEM_OUTPUT  466-501   '\nProcess finished with exit code 0'
  ```

  `EditorScheme.style` resolves them in Islands Dark (stderr `0xFFF75464`, stdout `0xFFBCBEC4`). A highlighter arrives a moment after its text (the "Process finished" one after the
  patch), so a console is published again when its markup changes.

---

## 7. Lux panels with client-drawn controls

Lux = the backend paints a Swing component and the client replays the draw operations (IDEate's
`docs/lux-protocol.md`). In a tool window, a Lux component is a `BeneluxView(luxId)` control pointing at
`luxModel.components[luxId]: LxPanel`.

### 7.1 The handshake

A panel draws nothing until the client gives it a screen, a place and visibility, as JetBrains Client's
`BeneluxViewControl` does (`intellij.platform.frontend.split.jar`, `com.jetbrains.thinclient.unattendedHost`):

```
panel.graphicsConfiguration.set(<a RemoteGraphicsConfigurationModel>)   // once
panel.position.set(LxPoint(x, y))                                       // where it is on screen
panel.size.set(LxSize(width, height))                                   // when layoutSource == Frontend; else follow preferredSize
panel.visible.set(true)
panel.requestRepaint.start(lifetime, Unit)                              // ask for a whole frame
panel.graphics.drawOperations → batches to replay
panel.events.fire(LxEvent)                                              // mouse, keys, scroll back
```

At 900 × 300 the Run panel drew 97–135 operations and the Build panel 115–121. [Seen run]

### 7.2 Client-drawn controls inside a panel

`LxPanel.localComponents: RdMap<LxLocalComponentId, LxLocalComponent>`. An `LxLocalBeControl` entry carries
`control: BeControl` and `bounds: RdOptionalProperty<LxRect>` (inherited from `LxLocalComponent`). The client
draws that control itself, at `bounds` inside the panel. [Seen code]

The backend swaps in a local control (`LuxBackendLocalBeControlBinder.accept`) for: components flagged
`RemoteTransferUIManager.setWellBeControlizable` (`SplitComponentId`), `ConsoleViewImpl`, `EditorComponentImpl`
in non-modal windows, and JCEF panels. [Seen code]

Seen in Run and Build panels [Seen run]:

| Local control | What it is |
|---|---|
| `BeGrid(vertical)[BePortForwardingPanel(projectId, consoleId) (Fit), BeConsoleViewImpl(editorId) (Fill)]` | the console, with a port-forwarding bar above it (empty unless a port is forwarded) |
| `BeSplitComponentControl(placeId = "BuildTree", modelUid = 5, projectId)` | the Build tree (section 9.3) |
| `BeContextActionBehavior(actions = [], content = …)` around either of the above | the same, in the Build tool window |

`bounds` printed `<not initialized>` at the moment the entry was added. **[Open]** whether it is set later,
after layout.

---

## 8. Run and Debug tool windows

### 8.1 IntelliJ

- `RunContentManager` adds one tab per run (`RunContentDescriptor`) to the Run or Debug tool window.
- The tab is a `RunnerLayoutUi` (IC `platform/execution-impl/src/com/intellij/execution/ui/layout/impl/RunnerLayoutUiImpl.java`):
  inner tabs (Console; for Debug also Debugger, Threads & Variables), a toolbar (Rerun, Stop, …) and the
  `ConsoleView`. For a Gradle task the content is a build view (tree + console) instead.

### 8.2 Split mode

`run.toolwindow.split.enabled` is false by default (`RunContentManagerImpl.isSplitRun()`); Fleet
`RunSessionsApi` / `ProcessHandlerApi` are only for that flag. [Seen code]

What arrives [Seen run]:

```
solution.toolWindowModel.availableToolWindows["Run"]          // registered on the first run
  .toolWindow.content: BeTabbedControl(tabsPlacement = TOP, selectedIndex = 0)
    tabs[0] = BeTab(
      tabName = BeLabel("Main"),          // the configuration's name; "hello" for the Gradle task
      isCloseable = true, isPinned = false, hasOccurenceNavigator = true,
      content = BeGrid[ BeGridElement(BeneluxView(luxId = 1), size = Fill) ])
    emptyContent = BeEmptyText("Nothing to show")
luxModel.components[1] = LxPanel
  localComponents[0] = LxLocalBeControl(BeGrid[BePortForwardingPanel(consoleId = 0), BeConsoleViewImpl(…)])
  // a Gradle run has one more before it: LxLocalBeControl(BeSplitComponentControl("BuildTree", modelUid = 5))
```

The models made for the other flag stay silent with default settings: `backendRunContentModel`
(`showRunDebugToolwindow: Signal<RunDebugToolwindowId>`, `portForwardingSessions`), `BackendRunProcessModel`
(executionId, displayName, configurationTypeId/Name, executorId, runnerId, `ExecutionConsoleKind`,
`runnerLayoutUiModel`, processId, `contentToolWindowId`, `consoleTextControlId`, exitCode, consoleOutput,
guestInput, terminateFromFrontend), `RunnerLayoutUiModel`, `testsRunContentModel`. No class in the protocol
jar holds a `BackendRunProcessModel`; JetBrains Client reads it from an `ExecutionEnvironment` user-data key.
[Seen code, Seen run]

Gradle tasks also report to `solution.cWMExternalSystemModel` (read by JetBrains Client's
`ThinClientExternalSystemHost`): `onStatusChangeBuild` (`StartBuildEventModel`, `ProgressBuildEventModel`,
`StartEventModel`, `OutputBuildEventModel`, `MessageEventModel`, `FinishEventModel`, `FinishBuildEventModel`),
`onTaskOutput(text, stdOut)`, `onSuccess`, `onEnd`. The Lux panel and the Build tree carry the same. [Seen run]

### 8.3 Starting a run

`solution.codeWithMeRunConfigurationsModel` [Seen code, Seen run]:

```
runConfigurations: RdMap<String, CodeWithMeRunConfigurationModel>
  "Application.Main" → (name "Main", typeId "Application", factoryId, isExternalSystem false, icon, …)
  "Gradle.hello"     → (name "hello", typeId "GradleRunConfiguration", isExternalSystem true, …)
  .execute: RdCall<CwmRunConfigurationExecuteArgs(executorId), Unit>   // executorId "Run" or "Debug"
executingConfigurations: RdMap<String, Int>        // "Application.Main" → 1 while running, then 0
clientCurrentConfiguration: RdProperty<String?>    // the run widget's selection, e.g. "Gradle.hello"
getRunSelectorActionsGroup: RdCall<Unit, ActionRegistrationModel>
```

`execute.start(lifetime, CwmRunConfigurationExecuteArgs("Run"))` answered `Unit` in about 50 ms; the Run
tab appeared about 2 s later (after the build step for an Application run). An invalid configuration opens
the backend's "Edit Configuration" dialog (a Lux dialog) instead.

---

## 9. Build tool window

### 9.1 IntelliJ

`MultipleBuildsView` holds one `BuildView` per build (IC `platform/buildView/src/com/intellij/build`): a tree
of the build's steps on the left (`BuildTreeConsoleView`), the selected node's console on the right. The tab
is "Build Output" (a Gradle sync is "Sync").

Tree rows (`buildView/frontend/src/BuildTreeView.kt`, the cell renderer): [Seen code]

| Part | Look |
|---|---|
| icon | the node's icon (status: running spinner, success, warning, error) |
| title | when set: `"<title>: "` in bold |
| name | regular, e.g. `successful`, `:app:hello` |
| hint | gray, after a space, e.g. ` At 21/11/26, 9:30 AM` |
| duration | right-aligned, small font, gray (selection color when selected), formatted by `NlsMessages.formatDuration(durationMs)`; only when the registry key `build.toolwindow.show.inline.statistics` is on |

The root is hidden (`setRootVisible(false)`); a running node's duration ticks from its start time.

### 9.2 Split mode

`build.toolwindow.split` is false by default (IC `platform/ide-core-impl/resources/intellij.platform.ide.core.impl.xml`),
so `AbstractViewManager` makes the ordinary `MultipleBuildsView` on the backend and `BuildViewApi`
(`getBuildViewEventsFlow`, `setBuildContentPinned`, `disposeBuildContent`, `notifyTooWindowActivated`)
sends nothing. [Seen code, Seen run] What arrives instead:

```
availableToolWindows["Build"]                                   // registered on the first build
  tabs[0] = BeTab(tabName "Build Output", content = BeGrid[BeneluxView(luxId = 3)])
luxModel.components[3] = LxPanel
  localComponents[2] = LxLocalBeControl(BeContextActionBehavior(BeSplitComponentControl("BuildTree", modelUid = 7)))
  localComponents[3] = LxLocalBeControl(BeContextActionBehavior(BeGrid[BePortForwardingPanel(consoleId = 1), BeConsoleViewImpl(…)]))
```

### 9.3 The Build tree over Fleet RPC

`BeSplitComponentControl(placeId, modelUid, projectId)` names a component the client makes itself
(`SplitComponentProvider`, extension `frontend.splitComponentProvider`). For `placeId = "BuildTree"` that is
`BuildTreeComponentProvider` → `BuildTreeView`, whose model id is `BuildViewId(uid = modelUid,
modelIsOnClient = false)`; `UID` is a typealias for `Int` (IC `platform/core-api/src/com/intellij/platform/rpc/Id.kt`). [Seen code]

`BuildTreeApi` (IC `platform/buildView/src/com/intellij/platform/buildView/BuildTreeApi.kt`, Fleet service
`com.intellij.platform.buildView.BuildTreeApi`):

| Call | Use |
|---|---|
| `getTreeEventsFlow(buildViewId): Flow<BuildTreeEvent>` | the tree |
| `getNavigationFlow(buildViewId): Flow<BuildTreeNavigationRequest>` | the backend asks to select or open a node |
| `onSelectionChange(buildViewId, selectedNodeId: Int?)` | the client's selection; the backend shows that node's console |
| `onNavigationContextChange(buildViewId, context: BuildTreeNavigationContext)` | for previous/next problem |

Events seen [Seen run], argument `{"uid": 5, "modelIsOnClient": false}`:

```json
{"type": "com.intellij.build.BuildTreeFilteringState", "showSuccessful": false, "showWarnings": true}
{"type": "com.intellij.build.BuildNodesUpdate", "currentTimestamp": 1795233640959, "nodes": [
  {"id": 1, "parentId": 0, "icon": {"serializedValue": "Te772qZN9kcAAAAAAAAAAAAAAAA="}, "title": "hello",
   "name": "running…", "hint": null,
   "duration": {"type": "com.intellij.build.BuildDuration.InProgress", "startTimestamp": 1795233640684},
   "navigatables": [], "autoExpand": true, "hasProblems": false,
   "visibleAlways": true, "visibleAsSuccessful": true, "visibleAsWarning": false}]}
{"type": "com.intellij.build.BuildTreeExposeRequest", "nodeId": 2, "alsoSelect": false}
{"type": "com.intellij.build.BuildNodesUpdate", "nodes": [{"id": 3, "parentId": 1, "title": null,
   "name": ":app:hello", "duration": {"type": "com.intellij.build.BuildDuration.Fixed", "durationMs": 3}, …}]}
{"type": "com.intellij.build.BuildNodesUpdate", "nodes": [{"id": 1, "name": "successful",
   "hint": "At 21/11/26, 9:30 AM", "duration": {"type": "…BuildDuration.Fixed", "durationMs": 1251}, …}]}
```

- A `BuildNodesUpdate` adds or replaces nodes by `id`; `parentId = 0` is under the hidden root.
- `currentTimestamp` is the backend's clock; `BuildTreeView` keeps `timeDiff` to tick in-progress durations.
- `visibleAlways` / `visibleAsSuccessful` / `visibleAsWarning` together with the filtering state decide
  whether a row shows (successful nodes hide while `showSuccessful` is false, unless `visibleAlways`).
- `icon.serializedValue` is a serialized `IconId`; the base64 contains the icon path as UTF-16, e.g.
  `expui/status/info.svg`, `expui/run/testPassed.svg`. The short value `Te772qZN9kcAAAAAAAAAAAAAAAA=` is the
  running (animated) icon. **[Open]** the exact layout of the serialized form.

---

## 10. Services tool window

- IntelliJ: `ServiceView`: a tree of services (run configurations of dashboard types, Docker, databases) on
  the left, the selected one's content on the right; for a run configuration, its `RunContentDescriptor`.
- Split by default: `services.view.split.enabled` = true, `services.view.split.run.luxing.enabled` = true. [Seen code]
- Fleet `RunDashboardServiceRpc` (IC `platform/execution.dashboard/src/com/intellij/platform/execution/dashboard/splitApi/RunDashboardServiceRpc.kt`):
  `getSettings`, `getServices(projectId): Flow<List<RunDashboardServiceDto>>`, `getStatuses`,
  `getCustomizations`, `getConfigurationTypes`, `getNavigateToServiceEvents`, `getLuxedContentEvents`,
  `startLuxingContentForService(projectId, id): ComponentDirectTransferId?`, `pauseLuxingContentForService`,
  `getAvailableConfigurations`, `getExcludedConfigurations`, `setNewExcluded`, `restoreConfigurations`,
  `hideConfigurations`, `getRunManagerUpdates`, `editConfiguration`, `copyConfiguration`, `hideConfiguration`,
  `updateConfigurationFolderName`. Also `RunDashboardManagerRpc`, `ServiceViewRpc`.
- [Seen run]: the backend registers `Services` as a backend tool window with no tabs ("Nothing to show");
  `getServices({"id": <kernel project id>})` answered `[]` in projects with only Application and Gradle
  configurations.

---

## 11. Probe record (2026-11-21)

How the [Seen run] facts were recorded, so they can be checked again:

- Backend: IntelliJ IDEA 263.7307.200 `remote-dev-server.sh run <project> --listenOn 127.0.0.1 --port 5991`
  with its own config/system/log folders (`/Users/safeuq/Files/Projects/Java/JBR/local-test-backend.md`).
- Projects: `gradle-smoke` (`settings.gradle.kts` with `include("app")`; `app/build.gradle.kts` with
  `plugins { java }` and a `hello` task that prints `hello`; a shared run configuration
  `.idea/runConfigurations/hello.xml` of type `GradleRunConfiguration` for `:app:hello`) and `smoke` (one Java
  module `smoke.iml`, `src/demo/Main.java` printing two lines, a temporary Application configuration `Main`).
- Client: IDEate's `ClientSession` in a headless JUnit test (no window), with a temporary logging class.
  Steps: open a terminal session at 5 s, run a configuration at 25 s, run the action `CompileDirty`
  (Build Project) at 70 s, dump tool window contents and Lux local controls with rd's `printToString()`.
- The probe code is listed in the plan, appendix A.

## 12. Open questions

| # | Question | Where it matters |
|---|---|---|
| 1 | ~~Why a running program's console got only its first change (6.2)~~ answered 2026-11-21: the client dropped the changes; the backend sends them all (6.2 addendum) | plan phase 1 |
| 2 | ~~Which markup carries console content types, and its key names (6.2)~~ answered 2026-11-21: document-level markup, `DefaultHighlighterModel` with `CONSOLE_*_OUTPUT` keys (6.2 addendum) | plan phase 1 |
| 3 | Whether `LxLocalComponent.bounds` is set after layout (7.2) | plan phase 2 |
| 4 | ~~Whether `closed = true` ends the terminal's shell (3.4)~~ Answered 2026-11-21: yes, the shell is gone within 5 s | plan phase 4 |
| 5 | The serialized `IconId` layout (9.3) | plan phase 3 |
| 6 | Debug tool window: not probed | plan phase 6 |
| 7 | Services content: needs a project with a dashboard service, e.g. Spring Boot | plan phase 5 |
