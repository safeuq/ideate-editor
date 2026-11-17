# Context menus and actions over rd

How JetBrains Client 263.7307.200 gets a context menu from the backend and runs the chosen item,
and how IDEate does the same (`session/ActionMenus.kt`). Found by reading the client's and the
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
| `protocol.luxModel` | Backend dialogs drawn on the client (not done in IDEate). |

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
| Editor | `EditorPopupMenu` | `EditorPopup` | the editor's, as for Structure (not wired yet) |

A Structure node has no timestamp of its own, in JetBrains Client too, so the backend can't know
which node was right-clicked. IDEate moves the caret to the node first (selecting a node already
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
when the submenu opens). IDEate's menu panel takes all entries up front, so `ActionMenus` asks for
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

The success response's `patch` is not applied yet. None of the enabled items changes a
document yet. Apply it to the open editors when one does.

## 3. What IDEate greys out

A backend dialog opens on the backend's screen, where nobody sees it, and holds the backend's UI
thread until it closes. IDEate doesn't show backend dialogs yet (Lux), so these items are greyed:

- text ending in `...` or `…` (Rename…, Delete…, Compare With…, Show History…);
- everything under New (`WeighingNewGroup`, `NewGroup`): Java Class, File and the others ask for
  a name;
- ids seen to open a dialog, popup or tool window without the ellipsis: `ReformatCode`,
  `OptimizeImports` (options on a folder), `$Delete` (its text is "Remove Module" on a module),
  `FindUsages` (results in a tool window), `DownloadBackendFileToClient`, `OpenModuleSettings`,
  `MoveModuleToGroup`, `RecentChanges`.

The backend already leaves out actions that only run on the client (e.g. `OpenInRightSplit`).

## Not done

- Backend dialogs over Lux (`luxModel.components`: `LxDialog`, `LxFrame`), which would enable the
  greyed items.
- The editor's menu from the backend (`EditorPopupMenu`, same code; the editor's own menu is local
  today).
- `uiAutomationInteractionModel.contextMenu`: a menu the backend pushes, in the same
  `AsyncActionGroupExpandResponse` shape.
