# Plan: the main menu (pending row 42)

Written 2026-11-26, 10:15 IST, by the intellij-fable session. Research and plan only; no code has
been changed.

The request: on macOS the system menu bar (File, Edit, View, …) filled from the backend's main
menu; on every other system IntelliJ's three-line main menu button in the main toolbar, as IntelliJ
shows it on Windows and Linux.

Sources: IntelliJ 263.7307.200 (`/Users/safeuq/Files/Libraries/intellij-community-263.7307.200`),
and two live runs on a test backend of my own (port 5997, project `problems-demo`, `Main.java`
open), 09:56 to 10:02 IST:

1. Ideate asking for the group `MainMenu` (a temporary, uncommitted debug change);
2. the real JetBrains Client 263.7307.200 with
   `IJ_JAVA_OPTIONS='-Didea.log.trace.categories=protocol.SEND,protocol.RECV'`, its menus opened
   through System Events accessibility.

Everything under "What JetBrains Client does" and "What the backend answers Ideate" was **seen** in
those runs.

## How IntelliJ does it

All paths are under `platform/platform-impl/src/com/intellij/`.

| Piece | Source | What it does |
|---|---|---|
| The menu's content | `platform/ide/menu/IdeMenuBarHelper.kt`: `createIdeMainMenuActionGroup()`, `expandMainActionGroup()` | the action group `MainMenu` (`IdeActions.GROUP_MAIN_MENU`), expanded in place `"MainMenu"` (`ActionPlaces.MAIN_MENU`) with `ActionUiKind.MAIN_MENU`; only its child *groups* are kept (File, Edit, …). Every child gets `ALWAYS_VISIBLE_GROUP` (`IdeMainMenuActionCustomizer`) |
| Keeping it fresh | `IdeMenuBarHelper` (`updateRequests.throttle(500)`) | the top level is expanded again on the action timer, at most every 500 ms, and not while a menu is open (`isUpdateForbidden`) or a modal dialog has the focus |
| macOS: the bar | `platform/ide/menu/MacMenuBar.kt` (`createMacMenuBar`), `PeerBasedIdeMenuBarHelper.kt` | a native `ui/mac/screenmenu/MenuBar` peer; `doUpdateVisibleActions` adds one `Menu` per top-level group |
| macOS: one menu | `platform/ide/menu/MacNativeActionMenu.kt` (`createMacNativeActionMenu`) | the menu is filled **when it opens** (`menuPeer.setOnOpen { Utils.fillMenu(…) }`), not before |
| macOS: one item | `platform/ide/menu/MacNativeActionMenuItem.kt` | label without the mnemonic, the first keyboard shortcut as the native accelerator (never Enter: `isEnterKeyStroke`), enabled state, a check mark for a `Toggleable` (`setState`), and `performAction(action, place = "MainMenu", …)` when chosen |
| Other systems: the button | `openapi/wm/impl/customFrameDecorations/header/toolbar/MainMenuButton.kt` | an `ActionButton` with the icon `AllIcons.General.WindowsMenu_20x20` (`expui/general/windowsMenu@20x20.svg`), icon size 20 (`JBUI.CurrentTheme.Toolbar.burgerMenuButtonIconSize()`), text "Main Menu" (`main.toolbar.menu.button`), action id `MainMenuButton.ShowMenu` with the shortcut Alt+Backslash in the `$default` keymap (none in the macOS keymaps) |
| Where the button is | `openapi/wm/impl/headertoolbar/MainToolbar.kt` (`addWidget(mainMenuWithButton, headerContent, HorizontalLayout.Group.LEFT)`) | first on the left of the main toolbar, before the project widget |
| Which mode | `…/header/toolbar/ShowMode.kt`, `UISettingsState.mainMenuDisplayMode` | the default is `UNDER_HAMBURGER_BUTTON` (`ShowMode.TOOLBAR`): only the button shows. The other modes (`MERGED_WITH_MAIN_TOOLBAR`, `SEPARATE_TOOLBAR`) are settings Ideate doesn't have |
| A click, default | `…/header/toolbar/ExpandableMenu.kt` (`switchState`), used when the registry key `ide.main.menu.expand.horizontal` is true (the default) and the system is not macOS | the toolbar's content is covered: a horizontal menu bar (File, Edit, …) with the toolbar's background on the left, 12 px wider than the menus (`HeaderColorfulPanel.getPreferredSize`), and the rest of the toolbar under a cover of the same colour at 60 % opacity (`ALPHA = 255 * 0.6`, `ShadowComponent`). The first menu opens at once (`selectMenu(itemInd = 0)`). A press on the cover, or closing the last menu, removes the bar |
| A click, other | `MainMenuButton.showPopup` (registry key off, or opened by a mnemonic) | one list popup under the button (`showUnderneathOf(button)`) with File, Edit, … as rows, submenus opening on hover (`isShowSubmenuOnHover`), at least 180 wide (`JBUI.CurrentTheme.CustomFrameDecorations.menuPopupMinWidth()`) |

## What JetBrains Client does

The client has its own action manager and its own `IdeMenuBar`; the code above runs in the client
unchanged. The backend's actions are registered in the client as `BackendAction` and
`BackendActionGroup`, and `FrontendActionUpdateInterceptor.expandActionGroup` sends the backend
parts of a group to the backend.

| When | Message | Example |
|---|---|---|
| The top level, about every 500 ms | `asyncActionsModel.expandGroup(AsyncActionUpdateRequest(actionId = "composite:BuildMenu;MainMenu;MainMenu__customized", timestampSetModel = the focused editor's, place = "MainMenu", uiKind = PopupKindModel(mainMenu = true, searchPopup = false), factoryId = null))` | answers the presentations of File … Help (`ToolsMenu` flags `0x893`, `ALWAYS_VISIBLE_GROUP = true`) and the children of `MainMenu`, `MainMenu__customized`, `BuildMenu`, `Java.BuildMenu`, `Groovy.CheckResources`. Before a project is there the timestamp list is empty |
| A menu opens (View) | one `expandGroup` with the backend parts of that menu: `"composite:CodeEditorBaseGroup;CodeEditorViewGroup;CompareActions;…;ViewAppearanceGroup;ViewMenu;ViewRecentActions;ViewSource"`, same place and `uiKind` | sent at 10:01:30,606, about 0.6 s after the click |
| An item is chosen (File ▸ Save All) | `RdCallActionRequest(timestampSetModel = the editor's, actionId = "SaveAll", actionPlace = "MainMenu", presentation = the one the menu showed (flags 2179 = `0x883`, text "_Save All"), inputEventModel = null, isInsideCommand = false, command = null)` | the same request Ideate's `ActionMenus.send` already builds |
| A toggle's check mark | `PresentationModel.clientProperties` holds `NameBooleanValue(name = "selected", value)` | `EditorToggleUseSoftWraps`: `selected = false` |

The client's bar was: Apple, IntelliJ IDEA, File, Edit, View, Navigate, Code, Refactor, Build, Run,
Tools, VCS, Window, Help. Parts of it never touch the backend, because they are the client's own
actions: the application menu, the whole Window menu, and items such as View ▸ Tool Windows,
Recent Files, Quick Switch Scheme… and the font size items; File ▸ New, Open…, Recent Projects.

## What the backend answers Ideate

Ideate has no action manager of its own, so it can only show the backend's part. Asked with the
plain id (not `composite:`), each visible submenu asked for in turn as `ActionMenus.show` does:

| Asked | Answer |
|---|---|
| `expandGroup("MainMenu", place "MainMenu", PopupKindModel(mainMenu = true, searchPopup = false))` and then every visible submenu | the whole tree in **41 requests, 188 ms**: 764 entries in 150 groups |
| Top level | `FileMenu` "_File", `EditMenu` "_Edit", `ViewMenu` "_View", `GoToMenu` "_Navigate", `CodeMenu` "_Code", `RefactoringMenu` "_Refactor", `BuildMenu` "_Build" (`0x8d3`), `RunMenu` "R_un", `ToolsMenu` "_Tools", `VcsGroups` "VC_S", `HelpMenu` "_Help" (all others `0x893`) |
| Not there | Window (the client's own), the application menu, View ▸ Tool Windows, File ▸ New / Open… / Recent Projects |
| `$Undo`, `$Redo` in Edit | `0xc82`: visible, not enabled. The backend can't see Ideate's undo history, so Ideate must run its own |

41 requests is one more than `ActionMenus.MAX_REQUESTS` (40), so today's limit would cut the last
submenu off.

## Design

One piece of session data (the menu tree), shown two ways. Names follow IntelliJ's.

```
backend ── expandGroup("MainMenu") ──> ActionMenus.expand ──> SessionState.mainMenu: List<SubMenu>
                                                                  │
                         macOS desktop: desktop/…/MacMenuBar.kt ──┤  (system menu bar, JMenuBar)
          every other target: ui/MainMenuButton.kt in MainToolbar ┘  (three-line button + Menu popup)
```

Decisions, each with its reason:

1. **The whole tree is fetched up front and kept**, instead of IntelliJ's "top level every 500 ms,
   one menu when it opens". It costs 41 requests and 188 ms, Ideate's `Menu` already wants all
   entries up front, and a Swing `JMenu` in the macOS bar can't be filled from an answer that comes
   after it has opened without a spike of its own. The tree is fetched again at the moments listed
   in step 1.4. `// ponytail:` mark this in the code: no 500 ms timer; add one if a stale item is
   ever seen.
2. **An item runs through `ActionMenus.runAction`** (`update`, then `RdCallActionRequest` only if
   enabled), with the context taken at the click. A kept tree can be a little old; `update` makes
   the backend decide with today's state. This is what `ClientSession.runProjectAction` does today.
3. **macOS uses a Swing `JMenuBar` on the window**, not Compose's `MenuBar { }`. Compose's is the
   same `JMenuBar` underneath, but its `Item` has no check mark fed from data and no open listener.
   The Gradle run task already sets `compose.application.configure.swing.globals=true`, which makes
   a `JMenuBar` show in the system bar (`apple.laf.useScreenMenuBar`).
4. **The button first opens the popup** (`MainMenuButton.showPopup`'s form), on every non-macOS
   target, phones included: it is Ideate's existing `Menu` with no new drawing code, and it is the
   only form that fits a phone. IntelliJ's default on a wide window is the horizontal bar
   (`ExpandableMenu`); that is phase 4 and **needs the user's yes** before it is built.

### Standing rules for whoever builds this

- Work in a temporary detached worktree from `origin/main`; never commit in the shared checkout;
  never `git stash`, `reset --hard` or `clean`. Author "Safeuq Mohamed", no Claude lines.
- Write a checklist before the first edit and report against it.
- New popups go through the existing menu code: `ui/component/Menu.kt` (`Menu`, `MenuRow`,
  `swingText`), `PopupContainer`. No second menu component.
- Rows stay IntelliJ's 24 dp on touch too. No abbreviated identifiers.
- Never touch backend ports 5993 or 5996. Use an own backend (5997 is the usual one) started with
  `-Didea.project.path=<scratch folder>` and own config, system, log and plugins folders. Only one
  app can hold port 5979; tell the other sessions when it is taken and when it is free.
- Each phase ends with its live check, a dated addendum in `docs/action-protocol.md` or
  `docs/intellij-look.md` for what was found, an update of pending row 42, and (phases 2 to 4) a
  Before/After entry on the shared page.

## Phase 1: the data (shared code, no UI)

Files: `session/ActionMenus.kt`, `session/ClientSession.kt`, `ui/ContextMenu.kt`,
`ui/component/Menu.kt`, `jvmTest/…/session/ActionMenusTest.kt` (new if there is none).

1.1 `ui/ContextMenu.kt`: add `val checked: Boolean? = null` to `MenuItem`, after `id`. `null`: not
    a toggle; `true`/`false`: a toggle and its state.

1.2 `session/ActionMenus.kt`:
   - Split `show` into `fun expand(groupId: String, place: String, timestamps:
     ActionTimestampSetModel, uiKind: ActionUiKindModel = PopupKindModel(false, false),
     maxRequests: Int = MAX_REQUESTS, onItem: ((actionId: String) -> Unit)? = null, result:
     (List<MenuEntry>) -> Unit)`; `show(serial, …)` becomes `expand(…) { publish(serial, it) }`.
     `uiKind` goes into every `AsyncActionUpdateRequest` of that menu.
   - `onItem`: when not null, an item's `onClick` calls it with the action id instead of `run(…)`
     (decision 2). Both the `ActionBaseModel` branch and the `isPerformGroup` branch.
   - In the `ActionBaseModel` branch set `checked`: when `child is ToggleActionModel`, the value of
     the `NameBooleanValue` named `"selected"` in `presentation.clientProperties`, or `false` when
     it is missing; otherwise `null`. Add `private const val SELECTED = "selected"`.

1.3 `session/ClientSession.kt`:
   - `SessionState.mainMenu: List<SubMenu> = emptyList()`.
   - `fun refreshMainMenu() = onRd { … }`: context = `actionContext()` (the selected editor's
     timestamps with the project's) or return when there is no project. Calls
     `actionMenus.expand("MainMenu", MAIN_MENU, context, PopupKindModel(true, false), maxRequests =
     100, onItem = ::runMainMenuAction) { entries -> mutableState.update { it.copy(mainMenu =
     entries.filterIsInstance<SubMenu>()) } }`. `filterIsInstance<SubMenu>()` is IntelliJ's
     `postProcessVisibleChildren(…).filterIsInstance<ActionGroup>()`.
   - `private const val MAIN_MENU = "MainMenu"`; use it in `runProjectAction` and `closeProject`
     too, in place of the two string literals.
   - `private fun runMainMenuAction(actionId: String)`: `$Undo` → `undo()`, `$Redo` → `redo()`
     (Ideate's own, see the table above); anything else →
     `actionMenus.runAction(actionId, MAIN_MENU, actionContext() ?: return, done = {
     refreshMainMenu(); runToolbar?.refresh() }, disabled = { log("$actionId is disabled") })`.
   - In the published tree, `$Undo` and `$Redo` are enabled when an editor is selected (the backend
     says disabled): map them in `refreshMainMenu`'s result with a small recursive `copy`, as
     `EditorView.withLocalClipboard` does for `$Cut`.

1.4 Call `refreshMainMenu()` where `runToolbar?.refresh()` is called today (the project is there,
    the selected editor changed: `ClientSession.kt` lines 751 and 1884 at `eafa065`), plus when a
    menu is about to open (phases 2 and 3 call it).

1.5 `$Cut`, `$Copy`, `$Paste`, `EditorPaste`, `EditorPasteSimple`: these must stay Ideate's own
    (docs/action-protocol.md, section 3), and their code lives inside `EditorView`
    (`withLocalClipboard`, `EditorView.kt` line 851). Hoist nothing: `ClientScreen` keeps a
    `remember { mutableStateOf<((List<MenuEntry>) -> List<MenuEntry>)?>(null) }` named
    `localClipboardEntries`, `EditorView` sets it to its `::withLocalClipboard` in a
    `DisposableEffect` (and back to null), and both menu views pass the tree through it. With no
    editor open the backend's items stay as they are (disabled there).

1.6 Test (`ActionMenusTest`, plain data, no backend): a group with a `ToggleActionModel` whose
    presentation has `NameBooleanValue("selected", true)` gives `MenuItem(checked = true)`; an
    `ActionModel` gives `checked = null`; with `onItem` set, `onClick` calls it with the id.

1.7 `ui/component/Menu.kt`, `MenuRow`: a `checked == true` item shows
    `expui/actions/checked.svg` in the icon slot when it has no icon of its own; `checked == false`
    keeps the empty 16 dp slot. (IntelliJ: `ActionMenuItem` with `Toggleable`.)

Live check: `CLIENT_MENU_DUMP=1`, connect, expect the log line `Menu MainMenu: 41 requests, … ms`
(a few more or fewer on another project) and `SessionState.mainMenu` with the 11 menus above.

## Phase 2: macOS, the system menu bar (desktop module)

Files: new `desktop/src/main/kotlin/io/github/safeuq/ideate/client/desktop/MacMenuBar.kt` (the name
of IntelliJ's file); `desktop/…/Main.kt` (`IdeateWindow`, editor window only).

2.1 `@Composable fun MacMenuBar(window: ComposeWindow, menus: List<SubMenu>, onOpen: () -> Unit)`.
    Does nothing unless `IS_MAC`. In a `DisposableEffect(window)`: `window.jMenuBar = JMenuBar()`,
    removed on dispose. In a `LaunchedEffect(menus)` (or `SideEffect` with an equality check):
    rebuild the bar from `menus`.

2.2 Building, `private fun JMenu.fill(entries: List<MenuEntry>)`:

   | Entry | Swing | Notes |
   |---|---|---|
   | `SubMenu` | `JMenu(label)`, `isEnabled = enabled`, filled recursively | |
   | `MenuItem`, `checked == null` | `JMenuItem(label)` | `isEnabled`, `addActionListener { onClick() }` |
   | `MenuItem`, `checked != null` | `JCheckBoxMenuItem(label, checked)` | macOS draws the check mark |
   | `MenuSeparator` | `addSeparator()` | |

   Labels come without the mnemonic already (`ActionMenus.label`). A label starting with `<html>`
   goes through the plain-text form of `swingText` (the native bar doesn't render HTML).

2.3 Shortcuts. `MenuItem.shortcut` is display text (`⇧⌘C`), which Swing can't take. Add
    `val keyStroke: KeyStrokeModel? = null` to `MenuItem` (set in `ActionMenus.shortcutOf`'s
    caller from the same first `KeyboardShortcutModel`, only when it has no second stroke), and set
    `accelerator = KeyStroke.getKeyStroke(keyCode, modifiers)`. Skip Enter (`keyCode == 0x0A`), as
    `MacNativeActionMenuItem` does. **Check live before keeping this** (2.6, third row): an
    accelerator in the macOS bar is live, and it must not fire for keys the editor handles itself.
    If it does, drop the accelerators and show no shortcut on macOS; note it in the addendum.

2.4 Not while a menu is open (`PeerBasedIdeMenuBarHelper.isUpdateForbidden`): every top-level
    `JMenu` gets a `MenuListener`; `menuSelected` sets `open = true` and calls `onOpen()` (which is
    `session::refreshMainMenu`); `menuDeselected` and `menuCanceled` set `open = false` and apply
    the newest tree if one came in meanwhile. A rebuild while `open` is held back.

2.5 `Main.kt`: in the editor window's `IdeateWindow` content, `MacMenuBar(window,
    localClipboardEntries?.invoke(state.mainMenu) ?: state.mainMenu, session::refreshMainMenu)`.
    The connections window gets no menus (IntelliJ's welcome frame has almost none). Leave the
    application menu (About, Quit) as AWT makes it.

2.6 Live check (own backend, `Main.java` open; read the bar with
    `osascript -e 'tell application "System Events" to tell process "java" to get name of every
    menu bar item of menu bar 1'`):

   | Do | Expect |
   |---|---|
   | read the bar | Apple, the app's name, File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, VCS, Help |
   | File ▸ Save All | log `Running SaveAll`, then `SaveAll ran` |
   | ⌘C on a selection in the editor, then ⌘V | pasted once, not twice; log has no `Running $Copy` |
   | Code ▸ Comment with Line Comment on line 6 | `//` appears on line 6 in Ideate and in the backend's file |
   | View ▸ Active Editor ▸ Soft-Wrap | the item shows a check mark the next time the menu opens |
   | Build ▸ Build Project | the Build tool window's output appears, as with `runProjectAction("CompileDirty")` |
   | Edit ▸ Undo after typing a letter | the letter is gone (Ideate's own undo) |
   | an item ending in `…` that opens a dialog (File ▸ Invalidate Caches…) | the Lux dialog shows; Cancel closes it |

## Phase 3: every other target, the three-line button

Files: new `ui/MainMenuButton.kt` (IntelliJ's name); `ui/MainToolbar.kt`; `ui/ClientScreen.kt`.

3.1 `@Composable fun MainMenuButton(menus: List<MenuEntry>, onOpen: () -> Unit, control: Modifier)`
    in `ui/MainMenuButton.kt`: the main toolbar's existing icon button (the one Search Everywhere's
    magnifier uses: size `MainToolbar.Button.size` 30, compact 26; hover fill as the other toolbar
    buttons) with `expui/general/windowsMenu@20x20.svg` at 20 dp
    (`burgerMenuButtonIconSize`), tooltip "Main Menu". Use `IconButton`, as the magnifier does
    (`MainToolbar.kt` line 287 at `eafa065`). Both icons this plan names
    (`expui/general/windowsMenu@20x20.svg`, `expui/actions/checked.svg`) are in
    `intellij.platform.icons.jar` of the desktop client; check that the Android app's icons jar
    has them too before relying on them there (not checked for this plan).

3.2 A click calls `onOpen()` (refresh) and opens a `Popup` anchored under the button's left edge
    (`showUnderneathOf`), holding the existing `Menu(entries = { menus.takeIf { it.isNotEmpty() }
    }, close = …)`: "Loading…" until the first tree is in, then File … Help as submenu rows.
    Submenus open in place with the back row, as every Ideate menu does. Minimum width: the larger
    of `Menus.minWidth` and 180 dp (`menuPopupMinWidth`). Placement goes through the existing
    `PopupPlacement` / `WindowPopupHost`, as the run configuration selector's popup does; copy that
    call, don't write a new positioner.

3.3 `ui/MainToolbar.kt`: new parameters `mainMenu: List<MenuEntry>? = null` (null: no button) and
    `onMainMenu: () -> Unit = {}`. When not null, `MainMenuButton` is the first child of the row,
    before the project widget, with 4 dp after it. The row's start padding stays as it is.

3.4 `ui/ClientScreen.kt`: pass `mainMenu = if (IS_MAC) null else
    (localClipboardEntries?.invoke(state.mainMenu) ?: state.mainMenu)` and `onMainMenu =
    session::refreshMainMenu`. `IS_MAC` is false on Android, so phones and tablets get the button.

3.5 Shortcut: Alt+Backslash opens the popup when not on macOS (`MainMenuButton.ShowMenu`,
    `$default` keymap), where `ClientScreen` handles its other window-level keys.

3.6 Live check. On the Mac the button is hidden, so run the desktop app once with a debug switch
    that forces it (`CLIENT_MAIN_MENU_BUTTON=1` read next to `IS_MAC` in 3.4; keep the switch, it
    is the only way to see the button on a Mac), and once on the emulator or the S24 (ask
    intellij-android before using the S24):

   | Do | Expect |
   |---|---|
   | look at the toolbar | three-line icon left of the project widget, same height and hover fill as the magnifier |
   | click it | popup under it: File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, VCS, Help, each with a chevron |
   | File ▸ Save All | log `SaveAll ran`; popup closed |
   | Edit ▸ Copy with a selection | the system clipboard has the selection (Ideate's own copy) |
   | phone upright | the popup is inside the screen and scrolls when taller than it |

## Phase 4 (needs the user's yes): the horizontal bar on a wide window

IntelliJ's default click behaviour on Windows and Linux (`ExpandableMenu.switchState`). Only for
desktop Windows and Linux windows at least 600 dp wide; phones keep phase 3's popup.

- New `ui/ExpandableMenu.kt`: while open, a `Box` over the main toolbar's content bounds. Left
  part: a row of the top-level labels in the toolbar's background (`MainMenu.background`,
  `MainMenu.foreground`, selected `MainMenu.selectionBackground`; the keys are already in
  `IslandsDarkUiColors.kt` and `IslandsLightUiColors.kt`), 12 dp wider than the labels. Right part:
  the toolbar's background colour at 60 % opacity over the rest of the toolbar; a press on it
  closes the bar.
- The first menu (File) is open at once; its items are the existing `Menu` under the label. Moving
  the pointer onto another label while one is open switches menus, as a Swing menu bar does.
- `MainMenuButton`'s click toggles this instead of the popup when the window is wide enough.

## Not in this plan

- The client's own menus that the backend doesn't have: Window, View ▸ Tool Windows, File ▸ Recent
  Projects, the font size items. Ideate could build some from its own state later (tool windows
  from `ToolWindows.kt`, projects from the project widget's popup).
- Mnemonics (Alt+F opens File): `MainMenuButton.SubMenuShortcutsManager`.
- Icons in the macOS bar (IntelliJ's new UI shows none on the top level).
- IntelliJ's 500 ms refresh (decision 1).
- Items that open popups, frames or tool windows Ideate can't show: they stay greyed through
  `ActionMenus.UNSHOWN_GROUPS` and `UNSHOWN_ACTIONS`. The main menu has many more of them than the
  context menus; add ids to those two sets as the live checks find them, each with the reason, and
  list them in the phase's addendum.

## Addendum (2026-11-26, 10:40 IST): the user's decisions

| Point | Decision |
|---|---|
| Phase 4, the horizontal bar (`ExpandableMenu`) | later. For now the popup of phase 3 is the menu on every non-macOS target, wide Windows and Linux windows included. Build phases 1 to 3 only |
| No 500 ms refresh (decision 1) | accepted |
| macOS shortcuts (step 2.3) | accepted: set the accelerators, check that ⌘C in the editor copies once, and show no shortcuts on macOS if it doesn't |

The user also asked that the existing menu code is reused. It is: the popup is `ui/component/Menu.kt`
(`Menu`, `MenuRow`, `swingText`) in `PopupContainer`, placed by the existing `PopupPlacement`; the
button is `IconButton`; the data is `ActionMenus` with the `MenuEntry` types. The only new drawing
is the check mark in `MenuRow` (step 1.7).

## Addendum (2026-11-26, 10:55 IST): gaps found on a review of this plan

None of these was checked live. Each is to be settled in the phase named, and the answer written
into that phase's addendum.

| # | Gap | Why it matters | Where to settle it |
|---|---|---|---|
| 1 | **Actions Ideate already does itself.** The plan keeps only `$Undo`, `$Redo` and the clipboard items local. Ideate also has its own Search Everywhere, Go to Class / File, find in file and tool window buttons. The menu's backend versions of these (e.g. `GotoClass`, `SearchEverywhere`, `Find`, `ActivateProjectToolWindow`) would open a popup on the backend's screen, where nobody sees it | an item that seems to do nothing, and possibly a popup holding the backend's focus | phase 1: with `CLIENT_MENU_DUMP=1`, list every action id Ideate has a local handler for (search `ClientScreen.kt` and `EditorView.kt` key handling), and map each in `runMainMenuAction` to the local handler, as `$Undo` is |
| 2 | **The list of items to grey out is unknown.** 764 entries; `UNSHOWN_GROUPS` and `UNSHOWN_ACTIONS` were built from three context menus | same as 1 for popups, frames and tool windows Ideate can't show | phases 2 and 3: walk every top-level menu once in the live check, not only the rows in the check tables; grey what opens something unseen |
| 3 | **The context is always the editor's.** JetBrains Client sends the timestamps of whatever has the focus (a Project view node, the editor). `actionContext()` sends the selected editor's, else the project's | File ▸ New or Refactor with a Project tree row selected acts on the editor's file instead of the row | phase 1: note it as a known limit; fix only if Ideate tracks which pane has the focus |
| 4 | **macOS shows the tree of the last refresh.** A refresh asked when a menu opens is held back until it closes (step 2.4), and nothing refreshes when indexing ends or a build finishes | an item can be greyed (or not) wrongly until the next editor change; `runAction`'s `update` protects running, not the look | phase 2: also refresh when the window gains focus and when `executingConfigurations` changes (the run toolbar's trigger). If still stale in the live check, add a refresh every few seconds while the window has the focus |
| 5 | **Items that act on the backend's machine or end the session**: Help ▸ Show Log in Finder, Edit Custom VM Options…, File ▸ Invalidate Caches… (restarts the backend), Close Project, Power Save Mode | surprising on a phone backend; the real client shows them too | phase 2: leave them as the real client has them; record which ones were tried |
| 6 | **Project tabs.** Each open project is its own session with its own `mainMenu` | the macOS bar must show the menus of the session on screen and change with the tab | phase 2 live check: open a second project, switch tabs, read the bar's Build menu in each |
| 7 | **The macOS window has a custom title bar** (`MacTitleBar.kt`, full-size content) | not seen yet that a `JMenuBar` on this window goes to the system bar and leaves the window's content alone | first thing in phase 2, before anything else is built |
| 8 | **Windows and Linux desktop can't be run here** | phase 3 is checked on the Mac through the debug switch and on Android only | say so in the phase 3 addendum |

Checked while writing this: `swingText` returns an `AnnotatedString`, so the plain-text form step
2.2 needs is `swingText(label).text`; the packaged app already gets
`-Dcompose.application.configure.swing.globals=true` (`desktop/build.gradle.kts` line 301), so the
DMG shows the menu bar as the Gradle run does.

## Addendum (2026-11-26, 11:10 IST): correction to gap 1, and how JetBrains Client handles what an action opens

Gap 1 of the 10:55 addendum is wrong as written. Checked against the 09:56 dump of the backend's
answer and the client trace: the backend does **not** send the actions the client does itself, so
they can't open anything on the backend. They are simply missing from Ideate's menu.

| Group in the backend's answer | What it holds | What the real client's menu shows there |
|---|---|---|
| `GoToTargetEx` (Navigate, top) | nothing | Class…, File…, Symbol…, Search Everywhere: the client's own actions |
| `FindMenuGroup` (Edit ▸ Find) | `ToggleScrollToResultsDuringTypingAction`, `StructuralSearchActions`, `XPathView.MainMenu.Search`, `JsonPathEvaluateAction` | plus Find…, Replace…, Find in Files…: the client's own |
| `ViewRecentActions` | `RecentFilesFallback`, `SwitcherIterateItemsFallback`, `RecentChangedFilesFallback` (flags `0x80`: hidden), `RecentChanges` | Recent Files, Recently Changed Files: the client's own |
| `FileMainSettingsGroup` | `ShowProjectStructureSettings` | plus Settings… and Plugins…: the client's own |

How the client knows: every registered action carries `ActionBaseModel.remoteBehaviour`
(`ActionBehaviourSpecificationModel`: `FrontendOnly`, `FrontendThenBackend`,
`FrontendOtherwiseBackend`, `BackendOnly`, `BackendThenFrontend`, `Duplicated`, `Disabled`). In the
trace's registrations: 87 `BackendOnly`, 102 `FrontendOtherwiseBackend`, 81 `FrontendThenBackend`,
66 `FrontendOnly`, 10 `Duplicated`. A frontend action runs in the client and draws its own popup
there (Search Everywhere is `intellij.platform.searchEverywhere.frontend.jar`), asking the backend
only for data.

What a **backend** action opens reaches the client in one of three ways, all already known to
Ideate's docs:

| The backend opens | How the client shows it | Ideate today |
|---|---|---|
| a dialog or another Swing window | Lux: the backend's window is described on `protocol.luxModel` and drawn by the client (docs/lux-protocol.md) | dialogs are shown (`LuxDialogs`) |
| a list popup made from actions or steps | `activePopups` with `ActionPopupContent` / `StepPopupContent` (BeControl; the run widget's More Actions) | shown (`BackendPopups`, `PopupListView`) |
| a tool window's content | that tool window's own model | per tool window |

So the rule for step 1.3 changes: instead of mapping backend ids to local handlers, **Ideate adds
its own items** where the client has its own, and only for what Ideate can do today:

- Navigate, first rows: Search Everywhere, Class…, File…, Symbol… → `session.searchEverywhere?.open(tab)`
  (shown only when `state.searchAvailable`).
- Nothing else is added in this plan; Find…, Recent Files and Settings… stay missing until Ideate
  has them.

Add them in `refreshMainMenu`'s result, next to the `$Undo` mapping, as plain `MenuItem`s with
Ideate's shortcut texts. Gap 2 (grey what opens something Ideate can't show) stands, and is now
only about backend actions whose popup is neither Lux nor an `activePopups` list.

## Addendum (2026-11-26, 11:00 IST): checks after phases 1 to 3 were built, and what is still to build

Phases 1 to 3 are built by intellij-android-ui (`26423df`, `93bbab1`, `1a644d5`; their write-up is
the 10:35 addendum of docs/action-protocol.md). The user then decided three more things. What I
checked for each, on a test backend of my own (port 5998, `problems-demo`, `Main.java` open):

### 1. The context is the focused pane's, as in JetBrains Client

Seen in the traced client, 10:28 to 10:29 IST. The top-level `MainMenu` request carried:

| Focus | `timestampSetModel.models` |
|---|---|
| The editor | `EditorTimestampModel`, `FileEditorTimestampModel`, `ProjectTimestampModel`, `VirtualFileTimestampModel`, `ControlTimestampModel(EditorComponentId)`, `VirtualFileArrayTimestampModel`: what `EditSession.actionTimestamps()` builds |
| The Project tree, row 5 selected | `ProjectTimestampModel(projectId)`, `NavigatablesArrayTimestampModel(navigatables = [])`, `ProjectViewTimestampModel(paneId = "ProjectPane", selectedNodeIds = [5])`, `ControlTimestampModel(ToolWindowComponentId(projectId, id = "Project"))`, `CustomDataTimestampModel([CustomDataSerialized(key = "TOOL_WINDOW_ID", serializedData = "\"Project\"")])` |

To build: `EditorView` and `ProjectTreeView` both have `onFocusChanged` already. Each tells the
session when it gains the focus (`session.paneFocused(EDITOR)` / `(PROJECT)`); the session keeps the
last one, and `actionContext()` returns the Project tree's set (with `tree.selected`) when that was
last. "Last", not "current": opening the menu popup takes the focus away. Refresh the main menu
when the pane changes or the selected row changes. Other tool windows keep the editor's context.

### 2. Backend items that open something Ideate can't show: run and react, plus a short grey list

Not every case can be seen when it arrives, so it is both:

| What the action does | Seen on arrival? | Handling |
|---|---|---|
| a Lux dialog, an `activePopups` list | yes | shown, as today |
| an `activePopups` popup with content Ideate can't draw (Quick Definition: log `not shown inside: BeDynamicControlContainer`) | yes: `BackendPopups` collects `unknown` | cancel it on the backend (`BackendPopups.cancel`), tell the user "<item> is not supported in Ideate yet", log the action id |
| a tool window Ideate doesn't draw (Type Hierarchy: `Tool window Hierarchy state ActiveAndFocused`) | yes | the same message; the tool window stays on the backend, harmless |
| a hint the client draws from another model (Parameter Info, Type Info, Context Info, Show Siblings) | **no**: nothing reaches Ideate | these stay in `ActionMenus.UNSHOWN_ACTIONS`, greyed |

So `QuickImplementations`, `QuickTypeDefinition`, `TypeHierarchy`, `MethodHierarchy`,
`CallHierarchy` can come out of the grey list once the message exists; `ShowSiblings`,
`ParameterInfo`, `ExpressionTypeInfo`, `EditorContextInfo`, `BytecodeViewer` stay in it. Context
menus keep their grey list as it is.

### 3. The client's own items, greyed

The backend sends no names for them, so they come from a fixed table:
`docs/plans/main-menu-client-items.tsv`, 129 rows. How it was made: the real client's menus read
once through accessibility (12 menus, all 46 submenus opened, one level deep), compared with the
tree Ideate's built code gets (a headless `ClientSession`, `Menu MainMenu: 112 requests, 258 ms`).
A client item is in the table when its label is not in Ideate's tree under the same parent, after
removing the client's " (On Host)" suffix and four renames (`New Scratch File` = `Scratch File`,
`Module…` = `New Module…`, `Data Flow to/from Here…` = `Analyze Data Flow to/from Here…`).

| Column | Meaning | Example |
|---|---|---|
| `menu` | the top-level menu; empty for a whole menu | `Edit`; empty for `Window` |
| `parent` | the submenu inside it, or empty | `Find` |
| `label` | the row's text | `Find in Files…` |
| `kind` | `item` or `menu` (a greyed submenu row needs no entries) | `item` |
| `after` | the label it follows under the same parent; empty: first | `Scroll to Results During Typing` |

Rows per menu: View 28, Edit 22, Run 21, File 20, Help 15, Navigate 9, Code 7, Tools 6, and the
whole Window menu (after VCS).

To build: read the table into `session/MainMenuClientItems.kt` as a constant list (no file reading
at run time), and merge it in `refreshMainMenu`'s result: each row becomes a disabled `MenuItem`
(or a disabled `SubMenu` with no entries) placed after its `after` label under its parent; when the
anchor is not in the tree, after the previous row of the table, else at the end. Rows whose `label`
Ideate already shows under that parent (its own Search Everywhere rows) are skipped.

Limits: the table is one client build with this machine's plugins (Database's "Data Source" rows,
Qodana, "Ask Agent"); it does not follow plugins or versions. Second-level submenus were not read.
Items Ideate can already do itself should be wired instead of greyed when someone gets to them:
View ▸ Tool Windows ▸ Project / Structure / Problems / Terminal / Run, Edit ▸ Select All,
Navigate ▸ Next / Previous Highlighted Error, Navigate ▸ File Structure.

### Corrections to earlier parts of this plan

- "41 requests, 188 ms" was the tree without inline groups; the complete tree is 110 to 112
  requests, 40 to 260 ms once warm (1351 ms for the first one after connecting).
- File ▸ New, Open… and Recent Projects **are** in the backend's answer; only Close Project,
  Plugins…, Cache Recovery and the rows in the table are the client's own.
- The accessibility scan at 10:20 to 10:30 IST brought the real client to the front and sent keys
  while another session's Ideate window was on screen. Tell the other sessions before driving the
  screen, as for port 5979.

## Addendum (2026-11-26, 11:20 IST): submenus open to the side in a window (user's request)

Today `ui/component/Menu.kt` replaces the open menu with the submenu and puts a back row on top
(`path`). The user wants this kept only where there is no room; in a window the submenu shows
beside its parent, as in IntelliJ. This is for every menu that goes through `Menu` (main menu
popup, context menus, the run selector), not only the main menu.

IntelliJ, list popups (`platform/platform-impl/src/com/intellij/ui/popup/list/ListPopupImpl.java`,
`ui/popup/WizardPopup.java`):

| What | Source | Value |
|---|---|---|
| Where the child goes | `ListPopupImpl.showNextStepPopup`: `x = container.x + container.width - STEP_X_PADDING`, `y` = the parent row's top | the child's left edge overlaps the parent's right edge by 2 (`WizardPopup.STEP_X_PADDING = 2`); its top is level with the row that opened it |
| No room on the right | `WizardPopup.show`: after `ScreenUtil.moveToFit`, if the child covers the parent, `x = parent.x - child.width - STEP_X_PADDING` | the child goes to the parent's left, 2 px overlap; never off the screen's left edge |
| Opening by hover | `ListPopupImpl` mouse move: `showSubMenu(index, withTimer = true)`, `new Timer(250, …)`, when `isShowSubmenuOnHover` (the main menu popup sets it) | 250 ms after the pointer rests on a submenu row |
| Opening by click or Right / Enter | `showNextStepPopup` without the timer | at once |
| Moving to another row | `disposeChildren()`, unless `isMovingToSubmenu(e)` | the child closes, except while the pointer travels towards it |
| The parent | stays open; the row that opened the child stays selected | |

To build, in `ui/component/Menu.kt` only (plus its callers' one flag):

1. `Menu` gets `cascade: Boolean = false`. `false` is today's behaviour, unchanged (in place, back
   row). Callers pass `cascade = true` exactly when they don't centre the menu: in
   `ContextMenu.kt` that is `!centered` (`centered = menu.byTouch || phone`, the existing rule: a
   finger, or a screen under 600 dp); `DropDownMenu` (run selector, main menu button) passes the
   same phone test. So a phone and a long press keep the in-place form.
2. With `cascade`, a `SubMenu` row does not change `path`. It opens a second `Menu` (same
   composable, so it nests to any depth) in an `AppPopup` placed by a `PopupPositionProvider`:
   right of the parent's column minus 2 dp, top level with the row minus `Menus.verticalInset` (so
   the child's first row lines up with the parent row); if it doesn't fit on the right, left of the
   parent plus 2 dp; then moved up to fit the window's height. Use the existing `PopupPlacement`
   helpers; don't write a second fitting routine.
3. Opening: hover for 250 ms (a `LaunchedEffect(hovered) { delay(250) … }` in the row), or a click
   at once. Only one child per menu: opening another row's child, or hovering a plain item for
   250 ms, closes the open one. Skip IntelliJ's "moving towards the submenu" test at first
   (`// ponytail:` the 250 ms delay already covers a straight move; add the triangle test if the
   child closes under a diagonal move in the live check).
4. The parent row keeps its selection fill while its child is open (`selected = hovered ||
   childOpen`).
5. Choosing an item calls the root's `close`, which closes every level. Escape or a click on the
   parent menu closes only the child; a click outside all menus closes everything (the child's
   `onDismissRequest` must not close the parent when the click was inside the parent).
6. Rows stay 24 dp; no new sizes.

Live check (desktop, own backend): right-click in the editor, hover Refactor: its submenu shows to
the right, top level with the Refactor row, after about a quarter second; move down to Generate…:
the submenu closes; open the menu near the window's right edge: the submenu shows on the left;
main menu button (`CLIENT_MAIN_MENU_BUTTON=1`) ▸ File ▸ New: three levels side by side; choose an
item: all close. On the phone or with a long press: the in-place form with the back row, as today.
Before/After entry on the shared page.
