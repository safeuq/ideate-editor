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
