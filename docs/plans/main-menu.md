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
