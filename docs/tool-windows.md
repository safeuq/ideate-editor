# Tool windows and the Project view's panes

Which tool windows the bar shows, and the Project tool window's view selector (Project, Packages, Project Files, …). Checked 2026-11-17 against a Mac backend (IntelliJ IDEA 263.7307.200, the `smoke` project) with the JetBrains Client 263.7307.200 jars decompiled.

## 1. The tool windows the backend has

`solution.toolWindowModel` (an extension of `Solution`, `ToolWindowModel_GeneratedKt`) has:

| Field | What |
|---|---|
| `availableToolWindows: RdMap<String, BeIdeaToolWindow>` | every tool window the backend registered, by id |
| `active: RdProperty<RdActiveToolWindow?>` | the active tool window (null on the Mac backend) |
| `activeClients` | which tool window each other client has open (Code With Me) |

Each `BeIdeaToolWindow` has `toolWindow: BeToolWindow` (title, icon, `position`, `sideTool`, content as BeControls), `available`, `isShowStripeButton`, `isVisibleOnLargeStripe`, header and gear actions. Example values from the Mac backend:

| id | title | icon | position | available |
|---|---|---|---|---|
| `Commit` | Commit | `expui/toolwindows/commit.svg` | LEFT | false (no version control) |
| `Structure` | Structure | `expui/toolwindows/structure.svg` | LEFT, `sideTool` | not sent |
| `Version Control` | Version Control | `expui/toolwindows/vcs.svg` | BOTTOM | true |
| `Problems View` | Problems | `expui/toolwindows/problems.svg` | BOTTOM | not sent |
| `Pull Requests` | Pull Requests | `org/jetbrains/plugins/github/expui/pullRequests.svg` (plugin) | LEFT | not sent |
| `Database` | Database | `icons/expui/dbms.svg` (plugin) | RIGHT | true |
| `Learn` | Learn | `expui/toolwindows/learn.svg` | LEFT | true, `isShowStripeButton` false |

Not in the map: Project, Run, Debug, Terminal, Services, Notifications. JetBrains Client registers those itself (frontend modules), so Ideate adds Project on its own.

`isShowStripeButton` is true for almost all of them (the backend runs headless with the old layout logic); `isVisibleOnLargeStripe` was never sent on the Mac backend.

Plugin icons come with their SVG: `ImageIconModel.imageSources.sources` lists `path` and `body` for `pullRequests.svg`, `pullRequests_dark.svg`, … Ideate keeps the `_dark` one (`IconJar.remember`), since the client's icons jar has only the platform's icons.

## 2. Where the order comes from

The backend doesn't send a layout. `ToolWindowInfoModel` (anchor, order, visible, weight) exists in the model, but only `RdToolWindowUtilsKt` converts it, and nothing sends it. JetBrains Client's `FrontendToolWindowHost.bindModel` registers each `availableToolWindows` entry with its own `ToolWindowManager` (anchor from `BeToolWindow.position`), and that manager places them by the new UI's default layout (`DefaultToolWindowLayoutBuilderImpl.addPlatformDefaultsV2`) and the user's changes, kept on the client side. `window.layouts.xml` is on the settings sync's block list (`FileBasedSettingsSyncBlacklistKt`), so it isn't copied either way.

The backend keeps its own layout in its workspace file (`config/workspace/<project id>.xml`, component `ToolWindowManager`, `layoutV2`). On the Mac backend it is exactly the V2 default:

```
left:   Project (order 0, visible), Commit (1), Structure (2, side_tool)
bottom: Version Control (0), Problems (1), Problems View (2), Terminal (3), Services (4)
right:  Notifications (0), AIAssistant (1), Database (2), Gradle (3), Maven (4)
```

Ideate does what JetBrains Client does with no saved changes (`toolWindowButtons` in `ToolWindows.kt`):

- a window gets a button when it is available (`available` not false) and is in that default layout, or `isVisibleOnLargeStripe` is true; buttons follow the layout's order;
- each window goes in one of six places from its side (`BeToolWindow.position`) and IntelliJ's split flag (`BeToolWindow.sideTool`): left top/bottom, bottom left/right, right top/bottom; Structure is split, so it sits under Project, as in IntelliJ (since 2026-11-18; before, Ideate kept it on the right). How each screen shape shows the places: [`mobile-layout.md`](mobile-layout.md);
- every other available window is under ⋯ ("More tool windows", `MoreSquareStripeButton`), by name.

### Stripe button tooltips (2026-11-23)

A mouse over a stripe button shows IntelliJ's `HelpTooltip` (`HelpTooltip.kt`, platform-api) with the window's name, as `SquareStripeButton.updateToolTipText` sets it up: no delay (`setInitialDelay(0)`, `setHideDelay(0)`), 5 dp from the button on the side facing the islands (`Alignment.RIGHT` on the left bar, `LEFT` on the right bar; above on the horizontal bar, `Alignment.TOP`). It hides when the mouse leaves or presses the button. Look: `HelpTooltip.smallTextBorderInsets` 8/12/9/12 dp, corner radius 4 (`ToolTip.borderCornerRadius`), background and border `feedback-bg` = gray-40 (`ToolTip.background`, `ToolTip.borderColor` in `ManyIslandsDark.theme.json`), text `text-default` in the 13 sp UI font. The code is `HelpTooltip` in `ui/component/HelpTooltip.kt`, ready for other buttons.

- **Shortcut:** IntelliJ adds the activate shortcut (`ActivateToolWindowAction`, e.g. `⌘1`) in `ToolTip.shortcutForeground`. `BackendToolWindow` carries no shortcuts, so the title is alone; `HelpTooltip` already takes a `shortcut` for when the backend sends keymap data.
- **Not done:** the shadow IntelliJ draws, tooltip descriptions and links, and a long press on touch (a tap shows nothing).

Mac backend result: left top Project, ⋯; left bottom Structure; bottom left Version Control, Problems; right top Database. Under ⋯: Bookmarks, Coverage, Database Changes, Database Output, Endpoints, Learn, Profiler, Pull Requests. Commit, AI Chat and Gradle are unavailable there.

Ideate doesn't keep the user's own layout (moving a window to another side); add it when Ideate can move windows.

## 3. The Project view's panes

`solution.projectViewModel` (`ProjectViewModel`) has `panes: RdList<RdProjectViewPaneBase>`, `currentPane: RdProperty<String>` and `activate`. Each pane has `id`, `name`, `weight`, `icon`, `popupActionGroup`. On the Mac backend:

| id | class | name | weight | sub ids |
|---|---|---|---|---|
| `ProjectPane` | `RdProjectViewAsyncPane` | Project | 0 | |
| `PackagesPane` | `RdProjectViewBePane` | Packages | 1 | |
| `Scope` | `RdProjectViewBePane` | Scopes | 4 | Project Files, Production, Tests, Markdown, Open Files, Scratches and Consoles |

A sub id is a string such as `Scope 'Project Files'; set:Project Files; class com.intellij.psi.search.scope.ProjectFilesScope`, with a `name` and `icon` (`RdPaneSubId`).

The selector lists the panes by weight, with a pane's sub ids in its place, as IntelliJ's new UI combo does: Project, Packages, Project Files, Production, Tests, Markdown, Open Files, Scratches and Consoles. Choosing one sets `currentPane` (and fires `setSubId` with the sub id first). The backend keeps a pane's expanded rows across reconnects.

`ProjectPane` is the tree Ideate already draws from `RdProjectViewItem`s. The other two send their tree as BeControls: `control` = BeDynamicControlContainer → BeContextActionBehavior → BeBehaviorControl → `BeTreeGrid`. Each row (`BeTreeGridLine`) is BeGrid [BeRichText]: the text in styled parts, e.g. `smoke` (bold) and ` /private/tmp/…/smoke` in a `NamedColor`. No icons are sent. A collapsed row's children are one placeholder row (a BeSpacer) until Ideate sets `isExpanded` true; then the backend sends the real children. `isSelected` selects a row; `BeTreeGrid.enter` opens the selected row's file. The backend opens the editor itself.

## 4. Editors the backend opens itself

Such an editor arrives like any other (`backendDeferredComposites`, then its document), but Ideate never asked for its path. `FileEditorCompositeModel.fileId` (an `RdFileIdNew`, e.g. backendId 938) gives it: `vfsModel.findFile` with `RdFindFileParams(id = fileId, path = null)` answers an `RdFileSnapshot` whose `properties.path` is the file's path. Until that answer the tab is named `document-938`. `vfsModel.startSyncing` with the id doesn't add the file to `vfsModel.files`.

## 5. Library files from the Project tree (2026-11-19)

A Project tree row under External Libraries carries the path of a file inside the JDK image or a jar,
e.g. `/Users/…/.sdkman/candidates/java/21.0.6-tem!/java.base/java/util/ArrayList.class`. `vfsModel.findFile`
with that path answers null (Ideate showed "Backend has no file …"): a path alone is looked up on disk.

Ideate now asks the Project pane to open such a row, as JetBrains Client's double-click does:
`RdProjectViewAsyncPane.navigate(RdProjectViewNavigation(ids = [nodeId], requestFocus = true,
openInRightSplit = false))`. The backend opens the editor itself (`GuestFileEditorManager` logs "Opening
remote editor for file=ArrayList.class … selectAsCurrent=true"), and Ideate follows it as in section 4.
Files on disk (no `!/` in the path) still open through `findFile` and `requestComposite`. Checked on the Mac
backend: `ArrayList.class` opened from External Libraries ▸ < 21 > ▸ java.base.

## 6. Header buttons (2026-11-19)

The backend sends no header buttons for Project or Structure. A probe of `BeIdeaToolWindow` on the Mac backend
saw Structure's `headerToolbar = []` and `headerToolbarWest = []`, and Project's were never set. JetBrains
Client draws these buttons itself, so Ideate does too (`headerActions` in `ClientScreen.kt`,
`HeaderAction`/`ToolWindowPanel` in `ToolWindows.kt`):

| Window | Button | Icon | What Ideate does |
|---|---|---|---|
| Project (Project view only) | Select Opened File | `expui/general/locate.svg` | `RdProjectViewAsyncPane.expandByPath(<selected editor's path>)`. The answer is the file's row id. Ideate opens the rows above it and selects it. JetBrains Client does the same for a file it has no `RdFileId` for (`ThinClientProjectViewPaneBinding.select`, else `expandById`). |
| Project | Expand Selected | `expui/general/expandAll.svg` | `expand` for the selected row and every folder under it. A row that arrives under one of them opens too. The backend can answer `expand` before it sends the rows (seen for `.idea` ▸ `codeStyles`), so the answer can't be used as the signal. |
| Project | Collapse All | `expui/general/collapseAll.svg` | Closes every row in Ideate. The backend keeps what it loaded. |
| Structure | Expand All / Collapse All | same icons | Done in Ideate only. Collapse All leaves the root open, so the file's top level stays in view. |
| both | Hide | `expui/general/hide.svg` | The same as tapping the window's stripe button while it shows. On a phone held upright, closing the last window on a side page goes back to the editor. |

Not drawn yet: Project's ⋮ Options menu (flatten packages, show members, and so on; the backend sends none of
these actions), and Structure's sorters, filters and Logical/Physical tabs. For those, see
[structure-protocol.md](structure-protocol.md), "Logical and Physical".

Checked on the Mac backend, 16:22–16:24 IST: Select Opened File opened `src ▸ demo` and selected `Main`.
Expand Selected on `smoke` opened `.idea ▸ codeStyles`, `src ▸ demo`, and so on. Both Collapse All buttons,
Expand All and Hide work.

## 7. "Projects and Files": the welcome project's pane (2026-11-19)

A backend started without a project opens IntelliJ's welcome project, `~/IdeaProjects/IdeaHome`, when a client
joins. JetBrains Client then shows "Projects and Files" in the Project window: Open, New ▾, Clone Repository, a
project search field and the recent projects.

What the backend sends (probe on the Mac backend, port 5994, 17:55–17:59 IST):

| Pane id | Name | Class | Weight |
|---|---|---|---|
| `NonModalWelcomeScreenProjectPane` | Projects and Files | `RdProjectViewBePane` | −10 |
| `ProjectPane` | Project | `RdProjectViewAsyncPane` | 0 |
| `PackagesPane` | Packages | `RdProjectViewBePane` | 1 |
| `Scope` | Scopes | `RdProjectViewBePane` | 4 |

- `currentPane` starts null. Ideate sets it to `ProjectPane` itself (`createProjectView`), so it never shows
  this pane by default. JetBrains Client shows it.
- The pane's `control` is a `BeneluxView`: a BeControl that only names an `LxPanel` the backend paints. It is
  the same kind of Lux panel Ideate already paints inside popups (`BackendPopups`, New ▸ File's "already
  exists" hint). So the buttons and the project list are drawn by the backend, not described as controls.
- Choosing the pane in Ideate today shows an empty panel, because `ProjectViewPanes` looks only for a
  `BeTreeGrid`.

Not looked at yet: where the editor area's New Markdown File, Open File and Open Terminal buttons come from.

### Built (2026-11-20)

Checked on the Mac test backend (port 5994, no project) with the desktop client, 00:17–00:31 IST.

- **The pane.** Ideate shows the backend's default pane when it arrives (`isDefaultPane`), and draws the
  `BeneluxView`'s `LxPanel` in the Project window (`LuxPaneView`). Its layout source is the frontend, so the
  panel takes the tool window's size; taps, the wheel and finger drags go to it as Lux events.
- **A choice in a list popup runs only after the client removes the popup.** New ▾ is a `WizardPopupContent`
  list. A tap fires the row's `signal`, as JetBrains Client's `DefaultLineStep.onChosen` does. The backend
  (`BeWizardPopupContentProvider.executeOrOpenChildPopup`) gets `FINAL_CHOICE`, keeps the action as the
  popup's final runnable and closes the popup: result `CLOSE`. It runs the action only when the popup's entry
  leaves `activePopups` (`BackendBeAbstractPopup`, `adviseRemoveItem`). JetBrains Client removes the entry on
  `CLOSE` (`ThinClientPopupsHost.showBePopup`). Ideate only hid it, so New ▸ Markdown File closed the list and
  created nothing. It now removes the entry too: Markdown File creates `Untitled.md` in the backend's
  `HomeFiles` folder and opens it.
- **Opening a recent project in This Window.** The backend asks "Open Project" (New Window, This Window), adds
  the chosen project to `solutionModel.solutions`, then fires `closeRequested` on the welcome project's
  `Solution`. JetBrains Client answers by removing that solution from the map itself
  (`ThinClientRdProjectViewSession.closeSolution`, log "Remove protocol solution"), then closes its project.
  Ideate ignored `closeRequested`, so it stayed on IdeaHome. It now removes any solution the backend closes,
  and shows the new project once the welcome project is gone; the toolbar takes the new name.

| Step | Before | After |
|---|---|---|
| New ▸ Markdown File | list closes, no file | `Untitled.md` opens |
| smoke in the recent list, This Window | IdeaHome stays; log "smoke added; showing null" | smoke's tree, its saved tab `Other.java`, toolbar "smoke" |

- **Where New ▾'s list goes (2026-11-20, 07:00 IST).** Its popup model's `context` is a
  `BePopupPositionDirectTransferContext`: `luxId = 3` (the welcome panel), `x = 17`, `y = 91`, `position =
  TOP`, the point just under the New button inside the panel. Ideate put every backend popup at the top of the
  window. It now places a popup with this context at the panel's window position (its `LxPanel.position`,
  which `ProjectViews.resizePanel` sets) plus the point, kept inside the window. Popups without it (New ▸
  File's name popup: active window, TOP) stay at the top.

- Parts of the panel not drawn after a reconnect (fixed 2026-11-20). When the pane's size changes,
  `LuxSurface` starts over on a new, empty bitmap (`remember(dialog.id, pixelSize)`), but the backend then
  sends only the parts that changed, so the buttons and the search field stayed blank while the recent
  projects (repainted for other reasons) showed. Now the pane asks for a whole frame
  (`LxPanel.requestRepaint`) as soon as the new bitmap exists, as JetBrains Client's `forceHostToRepaint`
  does. Asking earlier, on every `size` change, painted into the old bitmap and left the panel fully
  blank. Checked on the Mac: two reconnects to a running backend, whole panel drawn both times.

Still open:
- The panel not drawn after a dialog closed (seen once, 2026-11-19) has not been seen again; not retested
  with the fix above.
- The editor area's buttons when nothing is open (New Markdown File, Open File, Open Terminal): JetBrains
  Client draws them (`WelcomeScreenEmptyStateProvider`) and calls `WelcomeScreenFeatureApi.onClick` over
  Fleet RPC; Ideate shows its own hint instead.

## 8. Backend tool windows: Gradle's tree and action toolbar (2026-11-20)

Checked against the Mac test backend (port 5991) on a small Gradle project (`settings.gradle.kts` with an
`app` module and a `hello` task, Gradle 9.6.0 from the local cache), with probes in the desktop client and
the JetBrains Client 263.7307.200 jars decompiled (`intellij.platform.frontend.split.jar`
`com.jetbrains.thinclient.toolWindow.*`, `intellij.rd.ui.jar` `IdeToolbarViewControl`,
`BackendIdeActionViewControlKt`, `intellij.rd.client.jar` `BeContextIdTimestampModelProvider`).

**The client says when a window shows.** `FrontendToolWindowHost` sets `BeToolWindow.state` to
`ActiveAndFocused`, `Active` or `Hidden` as the window opens and closes (a window never shown isn't told
it is hidden). The backend builds the content only after that: IntelliJ's
`AbstractExternalSystemToolWindowFactory.createToolWindowContent` runs then.

**The content is a list of tabs.** `BeToolWindow.content` is a `BeTabbedControl` (`tabs`, `selectedIndex`).
Gradle first adds a tab with only the label "Initializing Gradle projects data…", then removes it and adds
the view once its projects are loaded (`removeAllContents` + `addContent`). A client that connects later gets
only the second. The view's tab:

```
BeStyleControl (dataId 73)
  BeGrid
    BeIdeToolbar (dataId 72; place "Gradle View Toolbar", target "73",
                  group = GroupModel("ExternalSystemView.ActionsToolbar"))
    BeGrid
      BeDynamicControlContainer (71) → BeContextActionBehavior (70) → BeBehaviorControl → BeTreeGrid
```

`BeIdeToolbar` is IntelliJ's `createActionToolbar(place, group, horizontal)` from
`ExternalProjectsViewImpl.initTree`; `target` is the component whose data the actions read
(`setTargetComponent(this)`), the whole Gradle panel. Rows: `BeTreeGridLine` cells are a `BeGrid` with a
`BeRichText` that carries the icon itself (`BeRichText.icon`), e.g. `icons/expui/gradle.svg` on
"gradle-smoke", `expui/build/taskGroup.svg` on "Tasks", `icons/newui/task.svg` on "assemble". The task icon
comes without its SVG: it is in the client's own `intellij.platform.externalSystem.impl.jar`, not the
icons jar. `BeIdeaToolWindow.headerToolbar` was empty for Gradle; `additionalGearActions` had 4 items.

**The toolbar is an ordinary action group.** The client turns `BeIdeToolbar.group` into its proxy AnAction
(`ActionsKt.fromModel`) and runs the usual async update, so Ideate asks as it does for menus:

```
asyncActionsModel.expandGroup(AsyncActionUpdateRequest(
  actionId          = "ExternalSystemView.ActionsToolbar",
  timestampSetModel = ActionTimestampSetModel([
                        ProjectTimestampModel(projectId),
                        BeToolWindowIdTimestampModel("Gradle"),   // BeToolWindowIdTimestampModelProvider
                        BeContextIdTimestampModel(73, null)]),    // the target's dataId
  place             = "Gradle View Toolbar", …))
```

`BeContextIdTimestampModelProvider` sends the `dataId` of the nearest BeControl above the context
component (`ConverterStorage.getComponentId`); for a toolbar that is its target. The backend knows the
tree's selection itself (`BeTreeGridLine.isSelected`). Answer on the Mac backend: Sync All Gradle Projects,
Select Project Data To Import, Download Sources | Link Gradle Project, Unlink Gradle Project (disabled) |
Execute Gradle Task, Toggle Offline Mode | Analyze Dependencies…, Show Diagram… | Expand All, Collapse All |
Build Tool Settings (a popup group: Auto-Sync Settings…, External Settings…). Composite Build
Configuration was invisible (flags `0x880`). Expand All ran and the backend expanded the tree, which
Ideate follows.

**Ideate** (`session/BackendToolWindowViews.kt`, `BeTreeRows.kt`, `ui/ToolWindows.kt`
`ToolWindowToolbar`): any backend tool window on screen gets `state` Active; the selected tab's toolbar
and tree are drawn like IntelliJ's (`ToolBar.horizontalToolbarInsets` 5/7, 22 dp buttons, 7 dp
separators; a group with an icon opens its items below it). The toolbar is asked for again when a row is
selected and 0.5 s after one of its actions ran. The tree code is shared with Packages and Scopes.

Not done: the 4 gear actions (`additionalGearActions`); a toggle's pressed look (Toggle Offline Mode comes
with flags `0x88b`, the others `0x883`; which bit means "selected" isn't checked); the output of a task a
double tap runs goes to the Run tool window, which Ideate doesn't draw yet.

## 9. Run and Build: tabs and the backend-painted panel (2026-11-21)

Checked on the Mac test backend (port 5991) on `smoke` (a plain Java module with `Main`, `Slow` and `Forever`
run configurations) and `gradle-smoke`. The wire facts are in `terminal-and-consoles-spec.md` 7.2 and 8.2
(with their 2026-11-21 phase 2 addenda); this is what Ideate does with them.

- **Window and button.** Run, Debug and Build have bottom stripe buttons after Services (`DEFAULT_LAYOUT` in
  `ui/ToolWindows.kt`), shown while the backend lists the window as available. When a run starts the backend
  registers Run already `ActiveAndFocused`; `BackendToolWindowViews` turns that (and any later non-Hidden
  state Ideate did not set) into `SessionState.showRequest`, and `ClientScreen` presses the window's button.
  Build sends no state, so it opens with its button.
- **Tabs.** `BackendToolWindowView` has `tabs` (each `BeTab.tabName`), `selectedTab`, `closeable` and `panel`.
  They are chips in the header after the title, like Terminal's (`HeaderTab` in `ui/HeaderTabs.kt`, shared by
  both), for the windows in `RUN_WINDOWS`. A tap sets `BeTabbedControl.selectedIndex`; the backend then
  builds (or rebuilds) that tab's content. The × shows on the selected tab and under the pointer when
  `isCloseable`, and removes the tab from `BeTabbedControl.tabs`; see the spec for the Cancel limit.
- **The panel.** The selected tab's `BeneluxView` becomes a `LuxPanelHost` (`session/LuxPanelHost.kt`, the code
  that was in `ProjectViewPanes`, one per shown panel) and `LuxPaneView` draws it filling the content area
  below the header. `LuxPaneView` takes `onResize`, `onRepaint` and `send` instead of the session's functions,
  so the Project view's "Projects and Files" pane and the tool windows use the same view.
- **Client-drawn controls.** `LuxPanelHost` follows `panel.localComponents`: a console
  (`BeConsoleViewImpl`, looked through `BeContextActionBehavior` and `BeGrid`) becomes a `PanelConsole` that
  `LuxPaneView` draws with `ConsoleEditor` at the entry's `bounds` (backend pixels = dp, from the panel's top
  left); the Build tree (`BeSplitComponentControl "BuildTree"`) is an empty `PanelBuildTree` until phase 3;
  the port bar draws nothing; anything else is logged once.
- **Icons.** `LuxDialogs.bind` stores the pixels of every Lux component's frames, so a toolbar's icons that
  were first sent in a frame Ideate doesn't draw (the backend's main window) are not "unknown cached images".

Tests: `compose/src/jvmTest/.../session/BackendTabsTest.kt` (the tab name mapping) and
`desktop/src/test/.../RunTabsLiveTest.kt` (live, skipped without `DRIVER_LINK`): `runTabs`, `closeRunningTab`,
`terminateRunningTab` on `smoke`, `buildTab` on `gradle-smoke`.

## Addendum (2026-11-23): Lux tool windows no longer go blank during a swipe

**Cause.** `LuxPaneView` measured its pane with `boundsInWindow()`, which is cut at the window's edge. While a page slid out (phone upright, `SwipePager`), the visible width shrank every frame, e.g. 393 → 371 → 340 dp, and each width went to the backend as the panel's size (`LuxPanelHost.resize` → `panel.size`). Each new size gave `LuxSurface` a new, empty bitmap until the backend had laid the panel out and painted it again, so the Database window (a Lux panel) went blank for the whole slide (screen recording on the Pixel 3a emulator: blank from the 8th frame of the swipe).

**Fix.** The pane is measured whole: `positionInWindow()` plus `coordinates.size`, so a slide only moves the panel (`position`), and its size stays. When the size really changes (the page's rounded corners come back while it moves), `LuxSurface` copies the old pixels into the new bitmap, so the old picture stays until the backend's paint arrives. Checked with the same recording: the Database window stays drawn in every frame of the slide.

## Addendum (2026-11-24, 12:45 IST): `SwipePager` is now `SlidePager`

`SwipePager` (private, in `ui/ClientScreen.kt`) no longer exists. The island pages of a phone held
upright are slid by the generic `SlidePager` in `ui/SlidePager.kt`, which also slides the open
projects on a phone. The behaviour described above is unchanged; its parameters and how each of
the two uses fills them are in `docs/plans/project-tabs.md`, the addendum of 2026-11-24, 12:40 IST.

## Addendum (2026-11-25, 23:35 IST): the Problems window's File tab

The Problems tool window (backend id `Problems View`) now has content: the File tab, drawn by
Ideate from the selected editor's highlighters, as JetBrains Client does (`HighlightingPanel`; the
registry key `problems.view.split.enabled` is `true` by default on client and backend). Code:
`ui/ProblemsToolWindow.kt`, rows from `ProblemsView.highlightingProblems`
(`session/ProblemsView.kt`), drawn with `ProjectTreeView`.

Checked live on a scratch backend (port 5997) with a Java file holding one unresolved type, one
unused variable and one `System.out.println`:

| Row | Example | IntelliJ source |
|---|---|---|
| File | `Main.java  <folder>  3 problems`, file icon from the editor tab | `FileNode.update` |
| Problem | severity icon, `Cannot resolve symbol 'Strin'`, grey ` :6` | `ProblemNode.update` |

- The backend sent `info` for all three: ERROR (400), WARNING (300) and a third below WARNING
  (`'System.out.println()' call can be replaced with a logpoint`), each with its own icon from
  `DefaultRegistryModel.severities`. They list in that order.
- A single click selects only; a double click or Enter moves the caret to the problem's start
  (seen: `Variable 'unused' is never used` → caret 5:18). IntelliJ does the same:
  `ProblemsViewState.autoscrollToSource` is `false` by default.
- The stripe button keeps one icon. IntelliJ has `ToolWindowProblemsEmpty` for "no problems", but
  the new UI maps both to `expui/toolwindows/problems.svg` (`PlatformIconMappings.json`), so there
  is nothing to switch.

Differences from IntelliJ, still open:
- the file row shows the folder as the backend names it; IntelliJ writes the home folder as `~`
  (Ideate does not know the backend's home folder);
- no "Project Errors" tab, toolbar, context menu or quick fixes yet, and no "Analyzing…" text
  (`docs/plans/problems-view.md`, phases 3 and 4);
- text of two problems at one position is ordered without case, not by IntelliJ's natural compare.

## Addendum (2026-11-25, 23:40 IST): the welcome project's editor buttons

When the welcome project (IntelliJ IDEA Home) is shown and no file is open, the editor area now
shows JetBrains Client's three buttons instead of "Double-tap a file in the Project tool window to
open it". The plan, with the decompiled classes, is `docs/plans/welcome-editor-buttons.md`.

| Button | Feature key | Who runs it | Checked live (scratch backend, 263.7307.200) |
|---|---|---|---|
| New Markdown File | `New.Markdown.File` | backend, `WelcomeScreenFeatureApi.onClick` over Fleet RPC | opens `Untitled.md` in an editor tab |
| Open File | `Open.File` | backend, same call | the backend's "Open File or Project" dialog (424 × 454) |
| Open Terminal | `terminal.toolwindow` | Ideate, as JetBrains Client's `IdeaTerminalFeatureFrontend` | shows the Terminal tool window with a new shell |

- Code: `ui/WelcomeFeatureGrid.kt` (`FeatureButtonModel`, `IDEA_FEATURE_BUTTONS`, `FeatureGrid`,
  `DisclosureButton`), `ClientSession.welcomeFeatureClick` and `showToolWindow`,
  `SessionState.welcomeProject`.
- Sizes match JetBrains Client's screenshot: buttons 48 tall, 16 apart, corner radius 8, icon 14
  from the left, 12 between icon and text, fill `#12FFFFFF` over the editor (`#28292B` on
  `#191A1D`).
- Pending row 33's "panel not drawn after a dialog closed": after Cancel in the Open File dialog,
  the welcome panel, the editor area and the Terminal were all drawn again. Not reproduced.
- Not done: the row under the buttons (Plugins, Theme ▾, Keymap ▾,
  `createAdditionalComponents`).

## Addendum (2026-11-26, 00:30 IST): the Problems window's Project Errors tab

The Problems window has its second tab, "Project Errors" (`ProblemsTab` in
`ui/ProblemsToolWindow.kt`; the header shows both tabs as chips). Its rows come from Fleet RPC, as in
JetBrains Client (`FrontendProblemsViewProjectErrorsRoot`): `ProjectErrors` in
`session/ProblemsView.kt` subscribes once per project and keeps `SessionState.projectErrors`.

The call, read from the decompiled `intellij.platform.problemView.shared.jar` and
`intellij.platform.problemView.backend.jar` (build 263.7307.200):

| Part | Value |
|---|---|
| Service | `com.intellij.platform.problemsView.shared.ProblemsViewApi` |
| Method | `getProjectErrorsFlow`, argument `projectId` = `{"id": "<kernel project id>"}` |
| Result | a stream; each element is a list of `ProblemEventDto` |
| Event `type` | `com.intellij.analysis.problemsView.toolWindow.splitApi.ProblemEventDto.Appeared` and `.Updated` with `problemDto`; `.Disappeared` with `problemId` |
| `problemDto` `type` | `….splitApi.FileProblemDto` (`id`, `text`, `description`, `icon`, `filePath`, `fileId`, `line`, `column`) or `….splitApi.GenericProblemDto` (`id`, `text`, `description`, `icon`) |

On the backend, `BackendProblemsViewApi.getProjectErrorsFlow` returns the events of
`ProjectErrorsCollector` (`intellij.platform.problemView.collector.jar`), in batches, with the
problems so far replayed first (`FlowWithHistory`); an unknown project id gets an empty stream.

What was checked live, and what was not:

- Checked: the call returns a stream id (`getProjectErrorsFlow -> "usmrljlard1fk2qqhuft"`), the tab
  shows, and with no rows it says "No errors found by the IDE" (`problems.view.project.empty`).
- Not checked: rows from a real backend. The scratch backend sent no event at all, even with a
  syntax error typed into the open Java file (`int x = ;`), so the collector had nothing to send.
  What makes the backend's collector hold a problem (a build, or its file-level problem tracking)
  is still to find. Until then the row code rests on `ProjectErrorsTest`, which feeds it events in
  the shape above.

Rows as built: problems without a file first; then a row per file (name, grey folder, grey
"N problems") with its problems by line and column, each with the backend's icon, the text and a
grey ` :<line + 1>`. A double click opens the file; it does not yet move the caret to the line.
