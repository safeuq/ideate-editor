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

Not in the map: Project, Run, Debug, Terminal, Services, Notifications. JetBrains Client registers those itself (frontend modules), so IDEate adds Project on its own.

`isShowStripeButton` is true for almost all of them (the backend runs headless with the old layout logic); `isVisibleOnLargeStripe` was never sent on the Mac backend.

Plugin icons come with their SVG: `ImageIconModel.imageSources.sources` lists `path` and `body` for `pullRequests.svg`, `pullRequests_dark.svg`, … IDEate keeps the `_dark` one (`IconJar.remember`), since the client's icons jar has only the platform's icons.

## 2. Where the order comes from

The backend doesn't send a layout. `ToolWindowInfoModel` (anchor, order, visible, weight) exists in the model, but only `RdToolWindowUtilsKt` converts it, and nothing sends it. JetBrains Client's `FrontendToolWindowHost.bindModel` registers each `availableToolWindows` entry with its own `ToolWindowManager` (anchor from `BeToolWindow.position`), and that manager places them by the new UI's default layout (`DefaultToolWindowLayoutBuilderImpl.addPlatformDefaultsV2`) and the user's changes, kept on the client side. `window.layouts.xml` is on the settings sync's block list (`FileBasedSettingsSyncBlacklistKt`), so it isn't copied either way.

The backend keeps its own layout in its workspace file (`config/workspace/<project id>.xml`, component `ToolWindowManager`, `layoutV2`). On the Mac backend it is exactly the V2 default:

```
left:   Project (order 0, visible), Commit (1), Structure (2, side_tool)
bottom: Version Control (0), Problems (1), Problems View (2), Terminal (3), Services (4)
right:  Notifications (0), AIAssistant (1), Database (2), Gradle (3), Maven (4)
```

IDEate does what JetBrains Client does with no saved changes (`toolWindowButtons` in `ToolWindows.kt`):

- a window gets a button when it is available (`available` not false) and is in that default layout, or `isVisibleOnLargeStripe` is true; buttons follow the layout's order;
- each window goes in one of six places from its side (`BeToolWindow.position`) and IntelliJ's split flag (`BeToolWindow.sideTool`): left top/bottom, bottom left/right, right top/bottom; Structure is split, so it sits under Project, as in IntelliJ (since 2026-11-18; before, IDEate kept it on the right). How each screen shape shows the places: [`mobile-layout.md`](mobile-layout.md);
- every other available window is under ⋯ ("More tool windows", `MoreSquareStripeButton`), by name.

Mac backend result: left top Project, ⋯; left bottom Structure; bottom left Version Control, Problems; right top Database. Under ⋯: Bookmarks, Coverage, Database Changes, Database Output, Endpoints, Learn, Profiler, Pull Requests. Commit, AI Chat and Gradle are unavailable there.

IDEate doesn't keep the user's own layout (moving a window to another side); add it when IDEate can move windows.

## 3. The Project view's panes

`solution.projectViewModel` (`ProjectViewModel`) has `panes: RdList<RdProjectViewPaneBase>`, `currentPane: RdProperty<String>` and `activate`. Each pane has `id`, `name`, `weight`, `icon`, `popupActionGroup`. On the Mac backend:

| id | class | name | weight | sub ids |
|---|---|---|---|---|
| `ProjectPane` | `RdProjectViewAsyncPane` | Project | 0 | |
| `PackagesPane` | `RdProjectViewBePane` | Packages | 1 | |
| `Scope` | `RdProjectViewBePane` | Scopes | 4 | Project Files, Production, Tests, Markdown, Open Files, Scratches and Consoles |

A sub id is a string such as `Scope 'Project Files'; set:Project Files; class com.intellij.psi.search.scope.ProjectFilesScope`, with a `name` and `icon` (`RdPaneSubId`).

The selector lists the panes by weight, with a pane's sub ids in its place, as IntelliJ's new UI combo does: Project, Packages, Project Files, Production, Tests, Markdown, Open Files, Scratches and Consoles. Choosing one sets `currentPane` (and fires `setSubId` with the sub id first). The backend keeps a pane's expanded rows across reconnects.

`ProjectPane` is the tree IDEate already draws from `RdProjectViewItem`s. The other two send their tree as BeControls: `control` = BeDynamicControlContainer → BeContextActionBehavior → BeBehaviorControl → `BeTreeGrid`. Each row (`BeTreeGridLine`) is BeGrid [BeRichText]: the text in styled parts, e.g. `smoke` (bold) and ` /private/tmp/…/smoke` in a `NamedColor`. No icons are sent. A collapsed row's children are one placeholder row (a BeSpacer) until IDEate sets `isExpanded` true; then the backend sends the real children. `isSelected` selects a row; `BeTreeGrid.enter` opens the selected row's file. The backend opens the editor itself.

## 4. Editors the backend opens itself

Such an editor arrives like any other (`backendDeferredComposites`, then its document), but IDEate never asked for its path. `FileEditorCompositeModel.fileId` (an `RdFileIdNew`, e.g. backendId 938) gives it: `vfsModel.findFile` with `RdFindFileParams(id = fileId, path = null)` answers an `RdFileSnapshot` whose `properties.path` is the file's path. Until that answer the tab is named `document-938`. `vfsModel.startSyncing` with the id doesn't add the file to `vfsModel.files`.

## 5. Library files from the Project tree (2026-11-19)

A Project tree row under External Libraries carries the path of a file inside the JDK image or a jar,
e.g. `/Users/…/.sdkman/candidates/java/21.0.6-tem!/java.base/java/util/ArrayList.class`. `vfsModel.findFile`
with that path answers null (IDEate showed "Backend has no file …"): a path alone is looked up on disk.

IDEate now asks the Project pane to open such a row, as JetBrains Client's double-click does:
`RdProjectViewAsyncPane.navigate(RdProjectViewNavigation(ids = [nodeId], requestFocus = true,
openInRightSplit = false))`. The backend opens the editor itself (`GuestFileEditorManager` logs "Opening
remote editor for file=ArrayList.class … selectAsCurrent=true"), and IDEate follows it as in section 4.
Files on disk (no `!/` in the path) still open through `findFile` and `requestComposite`. Checked on the Mac
backend: `ArrayList.class` opened from External Libraries ▸ < 21 > ▸ java.base.

## 6. Header buttons (2026-11-19)

The backend sends no header buttons for Project or Structure. A probe of `BeIdeaToolWindow` on the Mac backend
saw Structure's `headerToolbar = []` and `headerToolbarWest = []`, and Project's were never set. JetBrains
Client draws these buttons itself, so IDEate does too (`headerActions` in `ClientScreen.kt`,
`HeaderAction`/`ToolWindowPanel` in `ToolWindows.kt`):

| Window | Button | Icon | What IDEate does |
|---|---|---|---|
| Project (Project view only) | Select Opened File | `expui/general/locate.svg` | `RdProjectViewAsyncPane.expandByPath(<selected editor's path>)`. The answer is the file's row id. IDEate opens the rows above it and selects it. JetBrains Client does the same for a file it has no `RdFileId` for (`ThinClientProjectViewPaneBinding.select`, else `expandById`). |
| Project | Expand Selected | `expui/general/expandAll.svg` | `expand` for the selected row and every folder under it. A row that arrives under one of them opens too. The backend can answer `expand` before it sends the rows (seen for `.idea` ▸ `codeStyles`), so the answer can't be used as the signal. |
| Project | Collapse All | `expui/general/collapseAll.svg` | Closes every row in IDEate. The backend keeps what it loaded. |
| Structure | Expand All / Collapse All | same icons | Done in IDEate only. Collapse All leaves the root open, so the file's top level stays in view. |
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

- `currentPane` starts null. IDEate sets it to `ProjectPane` itself (`createProjectView`), so it never shows
  this pane by default. JetBrains Client shows it.
- The pane's `control` is a `BeneluxView`: a BeControl that only names an `LxPanel` the backend paints. It is
  the same kind of Lux panel IDEate already paints inside popups (`BackendPopups`, New ▸ File's "already
  exists" hint). So the buttons and the project list are drawn by the backend, not described as controls.
- Choosing the pane in IDEate today shows an empty panel, because `ProjectViewPanes` looks only for a
  `BeTreeGrid`.

Not looked at yet: where the editor area's New Markdown File, Open File and Open Terminal buttons come from.

### Built (2026-11-20)

Checked on the Mac test backend (port 5994, no project) with the desktop client, 00:17–00:31 IST.

- **The pane.** IDEate shows the backend's default pane when it arrives (`isDefaultPane`), and draws the
  `BeneluxView`'s `LxPanel` in the Project window (`LuxPaneView`). Its layout source is the frontend, so the
  panel takes the tool window's size; taps, the wheel and finger drags go to it as Lux events.
- **A choice in a list popup runs only after the client removes the popup.** New ▾ is a `WizardPopupContent`
  list. A tap fires the row's `signal`, as JetBrains Client's `DefaultLineStep.onChosen` does. The backend
  (`BeWizardPopupContentProvider.executeOrOpenChildPopup`) gets `FINAL_CHOICE`, keeps the action as the
  popup's final runnable and closes the popup: result `CLOSE`. It runs the action only when the popup's entry
  leaves `activePopups` (`BackendBeAbstractPopup`, `adviseRemoveItem`). JetBrains Client removes the entry on
  `CLOSE` (`ThinClientPopupsHost.showBePopup`). IDEate only hid it, so New ▸ Markdown File closed the list and
  created nothing. It now removes the entry too: Markdown File creates `Untitled.md` in the backend's
  `HomeFiles` folder and opens it.
- **Opening a recent project in This Window.** The backend asks "Open Project" (New Window, This Window), adds
  the chosen project to `solutionModel.solutions`, then fires `closeRequested` on the welcome project's
  `Solution`. JetBrains Client answers by removing that solution from the map itself
  (`ThinClientRdProjectViewSession.closeSolution`, log "Remove protocol solution"), then closes its project.
  IDEate ignored `closeRequested`, so it stayed on IdeaHome. It now removes any solution the backend closes,
  and shows the new project once the welcome project is gone; the toolbar takes the new name.

| Step | Before | After |
|---|---|---|
| New ▸ Markdown File | list closes, no file | `Untitled.md` opens |
| smoke in the recent list, This Window | IdeaHome stays; log "smoke added; showing null" | smoke's tree, its saved tab `Other.java`, toolbar "smoke" |

- **Where New ▾'s list goes (2026-11-20, 07:00 IST).** Its popup model's `context` is a
  `BePopupPositionDirectTransferContext`: `luxId = 3` (the welcome panel), `x = 17`, `y = 91`, `position =
  TOP`, the point just under the New button inside the panel. IDEate put every backend popup at the top of the
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
  Fleet RPC; IDEate shows its own hint instead.

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
(`ActionsKt.fromModel`) and runs the usual async update, so IDEate asks as it does for menus:

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
IDEate follows.

**IDEate** (`session/BackendToolWindowViews.kt`, `BeTreeRows.kt`, `ui/ToolWindows.kt`
`ToolWindowToolbar`): any backend tool window on screen gets `state` Active; the selected tab's toolbar
and tree are drawn like IntelliJ's (`ToolBar.horizontalToolbarInsets` 5/7, 22 dp buttons, 7 dp
separators; a group with an icon opens its items below it). The toolbar is asked for again when a row is
selected and 0.5 s after one of its actions ran. The tree code is shared with Packages and Scopes.

Not done: the 4 gear actions (`additionalGearActions`); a toggle's pressed look (Toggle Offline Mode comes
with flags `0x88b`, the others `0x883`; which bit means "selected" isn't checked); the output of a task a
double tap runs goes to the Run tool window, which IDEate doesn't draw yet.
