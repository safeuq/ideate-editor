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
