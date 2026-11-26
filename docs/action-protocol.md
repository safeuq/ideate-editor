# Context menus and actions over rd

How JetBrains Client 263.7307.200 gets a context menu from the backend and runs the chosen item,
and how Ideate does the same (`session/ActionMenus.kt`). Found by reading the client's and the
backend's jars (static analysis), then tested from the desktop client against the isolated Mac
backend on port 5991 (2026-11-17).

## Not the models with "Action" in their name

| Model | What it is in 263 |
|---|---|
| `ActionsModel` (`ActionExecuteRequest`, `ActionExecutionReport`, …) | Unused: no class of the client or the backend refers to it. |
| `solution.actionPopupSession` (`ActionPopupSession`) | Only the editor's Alt+Enter hint. |
| `ActionPopupContent`, `ActionStepPopupContent` | List popups of the BeControl popup system. |
| **`protocol.asyncActionsModel`** (`AsyncActionsModel`) | **Menus and toolbars: what to show.** |
| **`documentsOperationModel.requestResponseModel`** | **Running an action** (`RdCallActionRequest`), the same channel as typing. |
| `protocol.luxModel` | Backend dialogs drawn on the client (not done in Ideate). |

## 1. Asking for a menu

The client names an action group and a place, and sends what is selected as *timestamps*: there
is no data context on the wire. The backend turns the timestamps into its own data context (the
node's file, PSI element, module), runs every action's update in it and answers.

```
asyncActionsModel.expandGroup(AsyncActionUpdateRequest(
  actionId          = "ProjectViewPopupMenu",
  timestampSetModel = ActionTimestampSetModel([
                        ProjectTimestampModel(solution.projectId),     // RdProjectId("23888a6f-…:3M6rEz4v…")
                        ProjectViewTimestampModel("ProjectPane", [17]) // keys of RdProjectViewAsyncPane.items
                      ]),
  place             = "ProjectViewPopup",
  uiKind            = PopupKindModel(mainMenu = false, searchPopup = false),
  factoryId         = null))
→ AsyncActionGroupExpandResponse(
    presentations = [ActionIdAndPresentationModel("CopyAbsolutePath", PresentationModel(flags = 0x883, text = null, …)), …],
    children      = [GroupIdAndChildrenModel("CutCopyPasteGroup", [ActionModel("$Cut"), ActionModel("$Copy"), GroupModel("CopyReferencePopupGroup"), …]), …],
    inlineActions = [])
```

| Menu | Group | Place | Context |
|---|---|---|---|
| Project tool window | `ProjectViewPopupMenu` (the pane's `popupActionGroup`) | `ProjectViewPopup` | project + `ProjectViewTimestampModel("ProjectPane", [node id])` |
| Structure tool window | `StructureViewPopupMenu` | `StructureViewPopup` | the editor's: `EditorTimestampModel` with the caret, `FileEditorTimestampModel`, … (`EditSession.actionTimestamps()`) |
| Editor | `EditorPopupMenu` | `EditorPopup` | the editor's, as for Structure: the caret is moved to the click first (`ClientSession.editorMenu`) |

A Structure node has no timestamp of its own, in JetBrains Client too, so the backend can't know
which node was right-clicked. Ideate moves the caret to the node first (selecting a node already
does), so the backend's actions see the element at the caret: right-clicking `main(String[]): void`
gave the menu for `main`.

### Reading the answer

- `children[group]` lists a group's items: `ActionModel` (and `ToggleActionModel`,
  `EditorActionModel`), `GroupModel`, `SeparatorModel`, `ActionStubModel` (blocked for clients:
  skipped).
- `presentations[id]` is the item's state in this context. Its `text`, `icon` and `description`
  are **null when they equal the action's template**, which is the `presentation` inside the
  `ActionModel`. Example: `$Cut` came with `text = null` here and `"Cu_t"` in its template. Without
  falling back to the template, Cut, Copy and Paste showed as blank rows.
- `PresentationModel.flags`: `0x1` enabled, `0x2` visible, `0x10` a submenu (popup group); most
  items also carry `0x80` and `0x800`. `0x883` is a visible, enabled item; `0x880` a hidden one; `0x893`
  a visible submenu.
- A group without `0x10` (e.g. `CutCopyPasteGroup`) shows its items in place, with no submenu.
- Text carries the mnemonic as `_`: `"Optimi_ze Imports"` shows as "Optimize Imports" (`__` is a
  real underscore).
- Shortcuts are in the item's `keymapShortcutsList`, per keymap name (`"$default"`, `"macOS"`, …),
  as AWT key codes and modifier masks: `KeyStrokeModel(keyCode = 0x43, modifiers = 0x140)` is `⇧⌘C`
  on a Mac and `Shift+Meta+C` elsewhere (`KeyStrokes`, `KeyStrokesTest`).

### Submenus

The backend fills a submenu's `children` only when that group is asked for (JetBrains Client asks
when the submenu opens). Ideate's menu panel takes all entries up front, so `ActionMenus` asks for
each visible submenu after the first answer and shows the menu when the last answer is in. On the
Mac backend the Project menu for the project root took **3 requests and 329 ms**. The first menu
after connecting took a few seconds while the backend warmed up.

`CLIENT_MENU_DUMP=1` in the client's environment logs every item as
`group > actionId 'text' flags=0x…`, e.g. `CutCopyPasteGroup > CopyPaths 'C_opy Paths' flags=0xc80`.

## 2. Running an item

```
requestResponseModel.requestsWithTime.fire(RdRequests([RdRequestHolder(requestId,
  RdCallActionRequest(
    timestampSetModel = <the menu's timestamps>,
    actionId          = "CopyAbsolutePath",
    actionPlace       = "ProjectViewPopup",
    presentation      = <the PresentationModel the menu showed>,
    inputEventModel   = null,
    isInsideCommand   = false,
    command           = null),
  …)], now))
→ responsesWithTime: RdSuccessResponse(requestId, patch)
```

The backend runs an action only if the presentation sent back has the enabled bit (`0x1`), so the
client returns the one it showed. Tested: Copy Path/Reference ▸ Absolute Path on the project root
answered in 160 ms, and the backend's copy came back over `ClipboardModel` to the Mac clipboard as
`/…/mac-backend/smoke`.

**An action's document changes come only in the success response's `patch`**, not on the
document's `backendPatches`. Seen 2026-11-17 on the Mac backend (port 5992): Comment with Line
Comment on line 6 of `Main.java` answered `RdSuccessResponse` with
`[caret(141), change(129, -'//', +''), caret(139), caretChange(187)]`. Before Ideate applied that
patch, the backend's file had the comment and Ideate's text didn't (its highlighting went off by
two characters). `ActionMenus` now hands a non-empty success patch to every edit session it
concerns (`EditSession.concerns`, then `backendPatch`). The text, caret and highlighting then
match: the comment removed, the caret on the next line, as IntelliJ moves it.

## 3. What Ideate greys out

A backend dialog opens on the backend's screen, where nobody sees it, and holds the backend's UI
thread until it closes. Ideate doesn't show backend dialogs yet (Lux), so these items are greyed:

- text ending in `...` or `…` (Rename…, Delete…, Compare With…, Show History…);
- everything under New (`WeighingNewGroup`, `NewGroup`): Java Class, File and the others ask for
  a name;
- ids seen to open a dialog, popup or tool window without the ellipsis: `ReformatCode`,
  `OptimizeImports` (options on a folder), `$Delete` (its text is "Remove Module" on a module),
  `FindUsages` (results in a tool window), `DownloadBackendFileToClient`, `OpenModuleSettings`,
  `MoveModuleToGroup`, `RecentChanges`.

The backend already leaves out actions that only run on the client (e.g. `OpenInRightSplit`).

The editor's menu also greys (seen with `CLIENT_MENU_DUMP=1`, 2026-11-17):
- `ShowIntentionActions` (Show Context Actions opens the Alt+Enter popup) and
  `CompareClipboardWithSelection` (a diff window);
- the groups `EditorPopupMenu.GoTo` and `UML.EditorGroup` (popups), `AnalyzeMenu` (tool windows),
  `RunContextPopupGroup` and `RunContextGroupMore` (Run/Debug 'Main.main()' would run the program
  with its output in a backend tool window), and `FoldingGroup` (Ideate doesn't draw folds yet).

Kept local in the editor's menu: the backend's `$Cut`, `$Copy`, `$Paste`, `EditorPaste` and
`EditorPasteSimple` items keep their text and place, but run Ideate's own cut, copy and paste, which
go through the system clipboard and the clipboard sync (docs/edit-protocol.md). `MenuItem.id` carries
the action id for this. The backend hides Cut and Copy when nothing is selected, as IntelliJ does.

What the editor's menu showed on `greeting` in `Main.java` (7 requests, 244 ms): Show Context Actions,
Paste, Copy / Paste Special ▸ (Copy Reference, Paste as Plain Text, Paste from History…), Find
Usages, Go To ▸, Folding ▸, Analyze ▸, Rename…, Refactor ▸, Generate…, Run 'Main.main()' ▸, Debug
'Main.main()' ▸, More Run/Debug ▸, Local History ▸, Compare with Clipboard, Diagrams ▸, Create Gist….

`CLIENT_EDITOR_MENU_GROUP=CodeMenu` (a debug aid) shows another group in the editor's place, e.g.
IntelliJ's Code menu, to try actions that edit the document (Comment with Line Comment, Comment
with Block Comment, Auto-Indent Lines, Rearrange Code).

## 4. By touch

Tested on the Pixel 3a emulator (API 34, phone portrait) against a Mac backend at bb3bc92,
2026-11-17. Touch needed nothing beyond what ContextMenuArea already does (docs/intellij-look.md,
section 13); no fix came out of the test.

| Where | Long press on | Menu | What ran |
|---|---|---|---|
| Project | `Main`, `Other` | ProjectViewPopupMenu, 3 to 6 requests, 164 to 449 ms | Copy Path/Reference… ▸ Absolute Path: Android's clipboard toast showed `/private/tmp/…/smoke/src/demo/Main.java`; Reload from Disk: `SynchronizeCurrentFile ran` |
| Structure | `main(String[]): void` | StructureViewPopupMenu, 5 requests, 92 ms | nothing (Back) |
| Editor | `greeting` on line 6 | EditorPopupMenu, 7 requests, 254 ms | nothing (Back) |

What held in each case:
- The row (Project, Structure) or the caret (editor) moves to the pressed spot before the menu
  asks the backend, so the menu is for what was pressed. In the editor the caret landed in
  `greeting` (its usages lit up) with no selection, and the pager stayed on the editor page.
- The menu opens centred over the tool-window page (rows were 40 dp then; IntelliJ's 24 dp since
  2026-11-20), and scrolls by dragging when it
  is taller than the screen (the Project menu is: Reload from Disk is below the fold).
- Submenus (Copy Path/Reference… ▸) open in place, centred too.
- Back closes the menu only; the page under it stays.
- A dialog group's submenu (Go To ▸, Folding ▸, Analyze ▸) opens, its items are grey.

Not tested: Comment with Line Comment through `CLIENT_EDITOR_MENU_GROUP`, since an environment
variable can't be set for an Android app. The desktop app covers that path.

Test-tool notes:
- Two `adb shell input tap` calls are too slow for a double tap (each starts a JVM), so a
  double tap on a Project row doesn't open the file. Select the row, then `input keyevent
  KEYCODE_ENTER` (ProjectTreeView opens a leaf on Enter).
- A long press is `input swipe X Y X Y 1200`.
- Connect only once the backend has finished starting (about a minute after its join link
  prints). A client that joined 25 s in got no project, and kept the controller seat, so the next
  try was refused (`USER_DECLINED`) until the backend restarted (2026-11-17).

## Not done

- Backend dialogs over Lux (`luxModel.components`: `LxDialog`, `LxFrame`), which would enable the
  greyed items.
- `uiAutomationInteractionModel.contextMenu`: a menu the backend pushes, in the same
  `AsyncActionGroupExpandResponse` shape.

## Addendum (2026-11-24): the run widget in the main toolbar

What JetBrains Client 263.7307.200 does was traced by the intellij-fable session (docs/plans/run-toolbar.md). Ideate now does the same:

| Control | Ideate sends | Example answer |
|---|---|---|
| The buttons | `expandGroup("composite:Debug;MoreRunToolbarActions;Run;RunToolbarMainActionGroup;RunToolbarTopLevelExecutorActionGroup;Stop;compositeResumeGroup", place "NewUiRunToolbar", ToolbarKindModel(true), factoryId = null)` | idle: Run, Debug, More, `InactiveStopActionPlaceholder`; running: Rerun, Debug, Stop, More |
| Selector name | nothing; `clientCurrentConfiguration` looked up in `runConfigurations` | `null` → "Current File"; `"Gradle.forever"` → "forever" |
| Selector popup | `expandGroup("composite:RunConfigurationsActionGroup", "popup@RunToolbarPopup", [ProjectTimestampModel])`; the children come under the plain id `RunConfigurationsActionGroup` | the configuration rows, "Current File", "Edit Configurations…" |
| Run, Debug, Stop | `update`, then `RdCallActionRequest(id, "NewUiRunToolbar")` with the editor's timestamps (or the project's alone); no `ControlTimestampModel` needed | `executingConfigurations[id]` goes to 1, then to 0 after Stop |

A `null` factoryId is accepted for a toolbar. The buttons are asked for again when `clientCurrentConfiguration` or `executingConfigurations` change, when the selected editor changes, and after one of the buttons' actions has run.

`PresentationModel.flags` are IntelliJ's `Presentation.myFlags` copied as they are (`ActionsKt` copies them through a VarHandle on `myFlags`). Their bits are `IS_ENABLED` 0x1, `IS_VISIBLE` 0x2, `IS_KEEP_POPUP_IF_REQUESTED` 0x4, `IS_KEEP_POPUP_IF_PREFERRED` 0x8, `IS_POPUP_GROUP` 0x10, `IS_PERFORM_GROUP` 0x20, `IS_HIDE_GROUP_IF_EMPTY` 0x40, `IS_DISABLE_GROUP_IF_EMPTY` 0x80 and `IS_TEMPLATE` 0x1000. A run configuration's row is 0x8b3, a popup group that is also performed when chosen, so Ideate's menu shows it as an item that runs.

Build is not in the row any more, because IntelliJ's run widget has no Build button. More Actions still says "not supported" until `ActionPopupContent` is handled.

## Addendum (2026-11-24): the run widget's More Actions, debugger states and fills

**More Actions.** A click on More Actions sends `RdCallActionRequest("MoreRunToolbarActions", "NewUiRunToolbar", inputEventModel = ScreenPointMouseEvent(…, componentRect = the button's bounds))`. Ideate sends the bounds in window dp. The backend then adds a popup, `activePopups[n] = BeAbstractPopup(content = ActionPopupContent(step), context = BePopupPositionScreenPointContext(x, y))`, whose point is in the same coordinates as the bounds that were sent. `ActionPopupContent.step` is an `ActionStepPopupContent`: a `StepPopupContent` with a `place` added. Its lines are `BeActionPopupLine`s, which are `BeListPopupLine`s that also carry the `action`. What a line shows is in its `columnElements`, for example a `BeLabel` icon followed by the `BeRichText` "Rerun 'Application: Alpha'". The action's template text ("R_un") is not what is shown. A `BeHeader(SEPARATOR)` in a line starts a titled section, such as "Configuration" above Edit… and Delete. Choosing a row fires `items[i].signal`; the client sends no `RdCallActionRequest` for it (seen by intellij-fable in a traced JetBrains Client). The backend answers `result` OK, then CANCEL, then CLOSE, and the client then removes the popup. This is the same sequence as for a `WizardPopupContent`.

**Running buttons.** `PresentationModel.clientProperties` carries `NameEnumValue(enum = "ExecutorActionStatus", name = "EXECUTOR_ACTION_STATUS", ordinal)` on Run, Debug and `ConfigurationXDebuggerResumeAction`. The ordinals are NORMAL 0, LOADING 1 and RUNNING 2. `RunWidgetButtonLook` fills a button when it is enabled and either RUNNING or Stop:

| Button | Fill | Icon |
|---|---|---|
| RUNNING Run or Debug | `RunWidget.runningBackground` (dark `#338555`, light `#4E9D6C`) | `RunWidget.runningIconColor` (white) |
| Stop | `RunWidget.stopBackground` (dark `#C54E58`, light `#E4656E`) | `RunWidget.runningIconColor` |
| Other Run or Debug | none | `RunWidget.runIconColor` (dark `#4E9D6C`) |
| Anything else | none | `RunWidget.iconColor` (dark `#C3C5CB`) |

The fill blends `RunWidget.hoverBackground` on hover and `RunWidget.pressedBackground` when pressed.

**Debugger.** `filterOutRunIfDebugResumeIsPresent` (RedesignedRunWidget.kt) removes Run when a debugger Resume or Pause button is visible. Otherwise it removes Debug when an inline Stop is visible. The backend keeps Run visible in its answer, so Ideate applies the same filter. Pause and Resume are both `ConfigurationXDebuggerResumeAction`; only its presentation changes.

**Stop while debugging (open).** `StopAction` is `ActionRemoteBehaviorSpecification.FrontendOtherwiseBackend`. JetBrains Client updates Stop itself, from its own run sessions (`StopAction.getActiveStoppableDescriptors`), and doesn't use the backend's answer. While a Gradle task was being debugged, the backend answered `Stop` 0x880 (hidden) for the whole session, so Ideate shows no Stop button then. For a plain Run the backend's answer shows Stop. To fix this, Ideate needs the list of running sessions that the client builds its Stop from.

**Updates.** While Run or Debug reports RUNNING or LOADING, Ideate asks for the buttons again every 500 ms, as IntelliJ's toolbar updates on a timer. A Gradle debug session changes no `executingConfigurations` entry, so without this nothing would show Pause, Resume or the end of the session.

## Addendum (2026-11-24): run widget icons are drawn from their stroke variants

`RunWidgetButtonLook.paintIcon` passes every button's icon through `toStrokeIcon(icon, colour)`
(`platform/util/ui/src/com/intellij/ui/icons/stroke.kt`). An icon that has a `_stroke.svg` sibling
in the icons jar is drawn from that file, a white outline recoloured to the colour. Other icons
keep their shape, and only their known stroke colours change:

| Button | File drawn | Colour (Islands Dark, idle) |
|---|---|---|
| Run | `expui/run/run_stroke.svg` (outline, 1.5 wide) | `RunWidget.runIconColor` (green) |
| Debug | `expui/run/debug_stroke.svg` | `RunWidget.runIconColor` |
| Stop | `expui/run/stop_stroke.svg` | `RunWidget.runningIconColor` (white, on red) |
| Rerun | `expui/run/restart.svg` (no stroke file; one `#6C707E` shape) | white on green while running |

Before this, Ideate tinted `run.svg` and `debug.svg` whole, so the pale inner fill (`#F2FCF3`) took
the tint too and Run and Debug showed as solid green shapes. `IconButton` now loads the
`_stroke.svg` file when it has a tint and the jar has one.

## Addendum (2026-11-24): list popups fit their rows and highlight the row under the pointer

More Actions (`ActionPopupContent`) and the project widget's popup go through `BackendPopups.showList`
and `PopupListView`. That view was capped at New ▸ File's 300 dp and painted only the row the
backend selects on Up and Down. A list popup (`PopupList.listPopup`) now follows IntelliJ's
`ListPopupImpl`:

| | Before | Now |
|---|---|---|
| Width | at most 300 dp; "Profile 'xtool [spotlessApply]' with 'IntelliJ Profiler'" wrapped and was cut by the 24 dp row | the longest row (`IntrinsicSize.Max`, as `Menu`), at least the popup's `minimumSize` (`BeControlSizeCustom`, 310 for More Actions, `MINIMAL_POPUP_WIDTH`) |
| Row text | could wrap | one line, ellipsis when the window is narrower |
| Highlight | `isSelected` from the backend only | the hovered row becomes `selected`, so hover and Up/Down share one highlight |

## Addendum (2026-11-24): list popup rows take their icon from a label too

JetBrains Client's `PopupLineStep.getIconFor` returns the icon of the first `BeAbstractText`
descendant of the line that has one, and `BeLabel` is a `BeAbstractText`. A More Actions row
(`BeActionPopupLine`) carries its icon on an empty label, traced by intellij-fable for "Run 'Alpha'":

```
columnElements = [BeStyleControl → BeGrid → BeGrid → BeGrid[ BeStyleControl → BeLabel(text = "", icon = ImageIconModel("expui/run/run.svg")), BeSpacer ], BeGrid[ BeRichText("Run 'Alpha'") … ]]
```

`BeRow.of` read icons only from `BeIcon` and `BeRichText`, so these rows had none. It now reads a
label's icon too.

A row without an icon (Edit…, Delete) gets an empty 16 dp slot once any row of the list has an
icon, as `PopupFactoryImpl.ActionItem` gives such an action `EmptyIcon.create(maxIconWidth,
maxIconHeight)`. Its text then lines up with "Run 'Forever'" above it. The "Configuration" header
stays at the left inset.

## Addendum (2026-11-24): GroupHeaderSeparator, run button widths, the selection log

**Section headings.** IntelliJ draws the heading above a group of list rows with one component,
`GroupHeaderSeparator`, in list popups (`PopupListElementRenderer.createSeparator`), combo boxes
(`GroupedComboBoxRenderer`), completion (`LookupCellRenderer`) and tab lists (`JBTabsImpl`). Ideate
now has the same as `ui/component/GroupHeaderSeparator.kt`, used by `PopupListView` (More Actions'
"Configuration", the project popup's "Open Projects" and "Recent Projects", and their plain lines):

| Part | IntelliJ (new UI) | Value |
|---|---|---|
| Line | `Popup.separatorInsets` (default 4,8), 1 px, `Popup.separatorColor` | dark `popup-border` #33353B, light `dialog-border` #E9EAEE (theme `"*"` map) |
| Caption | `Popup.separatorLabelInsets` (default 3,16) | 3 above and below, 16 from the cell |
| Font | `RelativeFont.BOLD.derive(JBFont.smallOrNewUiMedium())` | bold, 12 next to the 13 UI font |
| Colour | `Popup.separatorForeground` | `text-secondary` |
| First row | `setHideLine(myCurrentIndex == 0)` | caption only |

The insets are from the list cell; a row's selection is `Popup.Selection.leftRightInset` (8) in
from it, so in Ideate (rows at the selection's edge) the line spans the rows and the caption starts
with their icons.

**Run buttons.** The 34 × 32 measured in JetBrains Client is the button component with its border:
`RunWidget.actionButtonWidth.compact` 26 plus `RunWidget.toolbarBorderDirectionalGap.compact` 4 on
each side (normal: 30 and 2). `RunWidgetButtonLook` paints the hover, pressed and running fills
inside the border, so the highlight is 26 × 26, not 34 wide. The More Actions anchor still uses the
whole 34.

**Selection log.** `RunToolbar` logs `Run selection: <clientCurrentConfiguration> → <name>` each
time it shows the selection, e.g. `Gradle.forever2 → forever2`, or `null → (not in the 5
configurations)` for Current File. Choosing forever2 and then Current File in the selector changed
the label both times on the test backend (2026-11-24).

## Addendum (2026-11-24): HTML in menu texts

A presentation text can be HTML, which Swing labels render. The run configurations popup's
"All Configurations" row comes as
`<html>All Configurations&nbsp;&nbsp;<a style="color:#9fa2a8;">15</a></html>` (IntelliJ's
`RunToolbarPopup.kt`: the count in `StatusBar.Widget.FOREGROUND`). Ideate printed it as it is.

Compose has `AnnotatedString.fromHtml` only in androidx Compose on Android, where it runs
`android.text.Html.fromHtml(…, FROM_HTML_MODE_COMPACT)`; Compose Multiplatform 1.12.2 has none in
common or desktop code (1.7.3's desktop one threw `UnsupportedOperationException`). The user chose
Android's engine on both platforms, so `ui/component/HtmlText.kt` ports it into the shared code:
TagSoup 1.2.1 (the parser `android.text.Html` uses) and `HtmlToSpannedConverter`, writing each span
as the style Compose's `Html.android.kt` maps it to. `swingText(text, colored)` sends text starting
with `<html>` through it, and `MenuRow` draws every menu label with it.

| Input | Shown |
|---|---|
| `<html>All Configurations&nbsp;&nbsp;<a style="color:#9fa2a8;">15</a></html>` | All Configurations  15 (Android ignores the style of an `<a>` without `href`, so 15 is not grey as in IntelliJ) |
| `<b>Bold</b>` | bold |
| `<span style="color:#9fa2a8">grey</span>` | grey |

Not ported: Compose's `<annotation>` handler and its list bullets, fonts by system name.

## Addendum (2026-11-26, 10:20 IST): the main menu

Seen while planning pending row 42 (docs/plans/main-menu.md has the full tables), on a test backend
with `Main.java` open and in a traced JetBrains Client 263.7307.200.

| What | Value |
|---|---|
| The client's top-level request | `expandGroup("composite:BuildMenu;MainMenu;MainMenu__customized", place "MainMenu", PopupKindModel(mainMenu = true, searchPopup = false), factoryId = null)`, about every 500 ms |
| The client's request when View opens | one `expandGroup` with a `composite:` id of that menu's backend groups (`…;ViewAppearanceGroup;ViewMenu;ViewRecentActions;ViewSource`), same place and kind |
| Choosing File ▸ Save All | `RdCallActionRequest(actionId = "SaveAll", actionPlace = "MainMenu", presentation = the one shown, inputEventModel = null)` |
| A toggle's state | `PresentationModel.clientProperties` holds `NameBooleanValue(name = "selected", value)`, e.g. `EditorToggleUseSoftWraps`: `false` |
| Ideate asking for plain `MainMenu` and every visible submenu | 41 requests, 188 ms, 764 entries: File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, VCS, Help |
| Not in the backend's answer | Window, the application menu, View ▸ Tool Windows, File ▸ New / Open… / Recent Projects: the client's own actions |

41 is one more than `ActionMenus.MAX_REQUESTS` (40), so the main menu needs a higher limit.

## Addendum (2026-11-26, 10:35 IST): the main menu built (docs/plans/main-menu.md, phases 1 to 3)

Checked live on an own backend (IntelliJ 263.7307.200, port 5997, project `demo` with
`src/Main.java`, no JDK configured), desktop app from the worktree with hot reload, on the Mac.

### What the code does

| Part | Where |
|---|---|
| The tree | `ClientSession.refreshMainMenu()`: `ActionMenus.expand("MainMenu", place "MainMenu", the selected editor's context, PopupKindModel(true, false), maxRequests = 200)`, top level kept as `SubMenu`s, published as `SessionState.mainMenu` |
| A chosen item | `runMainMenuAction(actionId)`: `$Undo`, `$Redo` are Ideate's own; anything else `ActionMenus.runAction` (update, then run if enabled) with the context at the click; then a refresh |
| Ideate's own items | Navigate starts with Search Everywhere, Class…, File…, Symbol… (`searchEverywhere.open(tab)`), only while Search Everywhere is there; Cut, Copy, Paste through the editor's `withLocalClipboard` (also inside Edit ▸ Paste, which is a submenu here) |
| Toggles | `MenuItem.checked` from `ToggleActionModel` + `NameBooleanValue("selected")`; the popup shows `expui/actions/checked.svg`, the macOS bar a `JCheckBoxMenuItem` |
| macOS | `desktop/…/MacMenuBar.kt`: a `JMenuBar` on the editor window, given through `LocalMainMenuBar` (IdeateLocals); accelerators from `MenuItem.keyStroke` (one-stroke shortcuts, never Enter) |
| Elsewhere | `ui/MainMenuButton.kt`: `IconButton` with `expui/general/windowsMenu@20x20.svg` at 20 dp, first in the main toolbar, popup through the run selector's `DropDownMenu` (AppPopup, `Menu`); Alt+\ opens it (not on macOS). `CLIENT_MAIN_MENU_BUTTON=1` shows it on a Mac too |
| Refreshes | the project appears, the selected editor changes, `executingConfigurations` changes, an item or a run widget button ran, a macOS menu opens, the window gains the focus, the button is pressed. No 500 ms timer |

### Found

| Finding | Detail |
|---|---|
| Inline groups come back empty | Build's `Java.BuildMenu` (flags `0x1883`: not a popup) had no children in the `BuildMenu` answer, so Build showed only Groovy Resources. `ActionMenus.expand` now also asks for a visible inline group whose children are missing. The main menu then takes **110 requests, 40 to 170 ms** (was 41); Build shows Build Project, Build Module 'demo', Recompile 'Main.java', Rebuild Project, Build Artifacts…. Context menus get the same rule (still capped at 40) |
| `&&` in labels | Edit ▸ `Emoji && Symbols`: `ActionMenus.label` now turns `&&` into `&` |
| A `JMenuBar` on the custom-title-bar window | goes to the system bar; the window's content (full-size content, toolbar in the title bar) is unchanged. The bar reads: Apple, MainKt (the app name under hotRun), File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, VCS, Help |
| Accelerators | kept. ⌘C on a selection then ⌘V in the editor pasted once; the log had no `Running $Copy` / `$Paste` |
| A bar rebuilt while a menu is open | held back until the menu closes, then applied after 500 ms so the chosen item's action is not removed first |
| AppleScript `click menu item` | works only while the app is frontmost, and needs ~1.5 s after it becomes frontmost: the focus refresh rebuilds the bar and the first lookup fails |
| File menu | the backend does send New, Open… and Recent Projects (the plan's 09:56 table said they were missing) |
| Project tabs | each project's session has its own tree; with `demo` (Main.java open) Build shows "Build Module 'demo'", "Recompile 'Main.java'"; after switching to `beta` (no editor) "Build Module", "Rebuild", "Recompile"; back on `demo` the first again |
| Icons on Android | `android/build/generated/client-assets/icons.jar` has `expui/general/windowsMenu@20x20.svg`, `_dark`, `expui/actions/checked.svg`, `_dark` |

### Greyed out (added to `ActionMenus.UNSHOWN_ACTIONS`)

Tried from the macOS bar with the caret on `System.out`:

| Item | Id | What happened |
|---|---|---|
| View ▸ Quick Definition | `QuickImplementations` | ran; log `Backend popup 2: not shown inside: BeDynamicControlContainer` |
| View ▸ Show Siblings, Parameter Info, Type Info, Context Info | `ShowSiblings`, `ParameterInfo`, `ExpressionTypeInfo`, `EditorContextInfo` | ran; nothing reached Ideate |
| View ▸ Quick Type Definition | `QuickTypeDefinition` | not tried; same kind as Quick Definition |
| View ▸ Show Bytecode | `BytecodeViewer` | ran; nothing shown |
| Navigate ▸ Type Hierarchy | `TypeHierarchy` | ran; `Tool window Hierarchy state ActiveAndFocused`, which Ideate doesn't draw. `MethodHierarchy`, `CallHierarchy` greyed with it (not tried) |

Shown and working: Code ▸ Generate… (list popup), Surround With…, Refactor ▸ Refactor This…, VCS ▸ VCS Operations Popup…, Run ▸ Run… (all `activePopups` lists), File ▸ Invalidate Caches… (Lux dialog; Cancel closed it), Navigate ▸ Implementation(s), Help ▸ Learn IDE Features (`Tool window Learn state Active`; not checked whether Ideate shows it). Insert Live Template… ran with nothing logged; left enabled, not checked on screen.

Items that act on the backend's machine are left as the real client has them. Tried: Invalidate Caches… (dialog, cancelled). Not tried: Show Log, Edit Custom VM Options…, Close Project, Power Save Mode.

### Known limits

- The context is always the selected editor's (else the project's), never a focused Project tree row.
- Windows and Linux desktop were not run; the button was checked on the Mac with `CLIENT_MAIN_MENU_BUTTON=1`. Alt+\ is off on macOS, so it was not tried. The phone was not run.
- Ideate's own Navigate items have no accelerator in the macOS bar (their keys are handled in `ClientScreen`; an accelerator would run them twice), so the bar shows no shortcut for them.
- "Menu … not built: Task finished in Cancelled state" shows in the log for a few `New` groups when refreshes overlap; the next refresh fills them.
