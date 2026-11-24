# Plan: the project widget's popup (project selection)

Drafted 2026-11-24. Nothing here is built yet. The goal is the popup IntelliJ opens from the
project widget in the main toolbar: New Project…, Open…, Clone Repository…, then the open projects
and the recent projects, each with its path.

Sources read: IntelliJ 263.7307.200 (`/Users/safeuq/Files/Libraries/intellij-community-263.7307.200`)
and the JetBrains Client distribution of the same build (plugin XML, and `javap` on its classes).
What is marked **inferred** was read from bytecode and not watched in a running client.

## How IntelliJ does it

The popup is built by `ProjectToolbarWidgetAction`
(`platform/platform-impl/src/com/intellij/openapi/wm/impl/headertoolbar/ProjectToolbarWidgetAction.kt`),
action id `main.toolbar.Project`, place `ProjectWidgetPopup` (`ActionPlaces.PROJECT_WIDGET_POPUP`).
`createActionGroup` adds, in this order:

| Part of the popup | Source in IntelliJ | Example |
|---|---|---|
| Update entries, then a separator | `UpdatesInfoProviderManager.getUpdateActions()` | none on a normal day |
| New Project…, Open…, Clone Repository… | the children of the group `ProjectWidget.Actions` | `NewProject` (`JavaActions.xml`, anchor first), `OpenFile` (`PlatformActions.xml`), `Vcs.VcsClone` (`intellij.java.vcs.xml`); each has an `<override-text place="ProjectWidgetPopup"/>` |
| A separator, then IntelliJ IDEA Home | `WelcomeUtils.getGotoWelcomeProjectAction()` | reopens the welcome project |
| "Open Projects" | `RecentProjectListActionProvider.getActions(project)`, the first 100 (`MAX_RECENT_COUNT`), where `OpenProjectSelectionPredicateSupplier`'s predicate is true | `commons`, `~/Files/Projects/XFlow/commons` |
| "Recent Projects" | the same list, where the predicate is false | `xtool`, `~/Files/Projects/XFlow/xtool` |

The two titles are `project.widget.open.projects` and `project.widget.recent.projects` in
`ProjectConceptBundle.properties`. When `ProjectWidgetActionsFilter.shouldHideProjectSwitchingActions`
is true for any extension, everything but the update entries is left out.

### A project row

A row whose action is a `ProjectToolbarWidgetPresentable` is drawn by `ProjectWidgetRenderer`; the
other rows use the list popup's usual renderer.

| `ProjectToolbarWidgetPresentable` | Shown as | Example |
|---|---|---|
| `projectIcon` | the icon, scaled to `userScaledProjectIconSize()` (registry `ide.project.icon.size`, 20), aligned to the top, 8 dp before the text | the purple `X` avatar |
| `projectNameToDisplay` | the first line, in the list's foreground | `xtool` |
| `status.statusText` | after the name, small font, info colour, 4 dp before and 8 after | |
| `status.progressText` | at the right end, with `AnimatedIcon.Default` | |
| `providerPathToDisplay` | its own line with `providerIcon` (or `AllIcons.Welcome.RecentProjects.RemoteProject`) | |
| `projectPathToDisplay` | its own line, `JBFont.smallOrNewUiMedium()`, `UIUtil.getLabelInfoForeground()` | `~/Files/Projects/XFlow/xtool` |
| `branchName` | its own line with `AllIcons.Vcs.Branch` in the info colour | `main` |

Sizes: the row's content has an empty border of 6 dp above and below; each line has 2 dp above and
below (`rowGaps`). A selected row is filled with `ListPluginComponent.SELECTION_COLOR`. A submenu
arrow (`AllIcons.Icons.Ide.MenuArrow`, 6 dp before it) shows when the action is a group. A section
title is a `GroupHeaderSeparator` with `JBUI.CurrentTheme.Popup.separatorLabelInsets()`
(`Popup.separatorLabelInsets`, 3 and 16 by default) on `Popup.BACKGROUND`; the first row's
separator hides its line.

Typing filters the list (`ProjectWidgetSpeedsearchFilter`): a project row matches on
`projectNameToDisplay + " " + projectPathToDisplay + " " + providerPathToDisplay`.

"Press F1 to toggle preview", seen at the bottom of the popup in a screenshot of IntelliJ, is not
set by this class. The only source of that text is `intention.preview.adv.toggle.text`, set by the
intention preview (`PreviewHandler`). Ideate leaves it out.

## What the JetBrains Client does

The widget runs on the client: `main.toolbar.Project` is registered in the client's own
`JetBrainsClientPlugin.xml` with the same class. Two entries of
`intellij.platform.frontend.split.base.xml` (in `plugins/remdev-plugin`) change what it shows:

| Piece | In the client | Effect |
|---|---|---|
| `RecentProjectListActionProvider` | overridden by `ThinClientRecentProjectListActionProvider`: `getActions(Boolean, Boolean, Boolean)` returns `emptyList()`, `createOpenAction` throws `IllegalStateException("Will not open recent projects")` | no "Open Projects" and no "Recent Projects" section. `getActions(project)` reaches the override through `getActions$default`, so it is empty too |
| `ProjectWidgetActionsFilter` | `LightModeProjectWidgetActionsFilter`: true only when `IdeProductMode.isLight` | the top actions stay in a normal session |
| `ProjectWidget.Actions` | `NewProject` and `Vcs.VcsClone` (java-plugin), `OpenFile`, and `ToolboxWelcomeRemdevAction` from the station plugin, after `Vcs.VcsClone` | the top block, plus a Toolbox entry |

So the Gateway client's popup has only the top block (**inferred**). This disagrees with
[`split-protocol.md`](../split-protocol.md), "One backend, several projects, one connection", which
says the client's drop-down gets its list from Gateway through the station plugin
(`RecentProjectsRequest.OpenProject`). The station plugin's `recentProjectsProvider`
(`StationRecentProjectsProvider`, module `intellij.station.recentFiles`) is declared in the
client's `station-plugin.jar`, but its classes are not in the jar, and the override above returns
before any provider is asked. One screenshot of the Gateway client's popup would settle it.

## What Ideate has

- `ui/MainToolbar.kt` draws the widget (avatar, name, chevron) with `ToolbarDropdown`, without an
  `onClick`.
- `session/ActionMenus.kt`: `show(serial, groupId, place, timestamps)` asks the backend for any
  group (`asyncActionsModel.expandGroup`), and `runAction` runs one action
  ([`action-protocol.md`](../action-protocol.md)).
- `session/ClientSession.kt` sees every project of the backend in `solutionModel.solutions`
  (`RdCodeWithMeProject.hostProjectBasePath`, e.g. `…/mac-backend/smoke`). It binds one solution,
  at start (`ClientSession(project = …)`) or when a welcome project is replaced in This Window.
- The backend's File ▸ Recent Projects group, `RecentProjectListGroup` (`RecentProjectsGroup`), is
  marked `ActionRemoteBehaviorSpecification.BackendOnly`. Its children are `ReopenProjectAction`s:
  text = the project's name, description = its path.
- `ui/component/Menu.kt` and `MenuItem` have one-line rows only: no second line, no section title.
- `projectAvatarBrush(name)` and `initials(name)` in `MainToolbar.kt` already draw IntelliJ's
  generated project icon.

## Design

Build IntelliJ's full popup, not the client's reduced one, which has no project selection. Every
section is filled by the backend, and IntelliJ's names are kept.

1. **Top block.** `expandGroup("ProjectWidget.Actions", place = "ProjectWidgetPopup")` with the
   project's timestamp. A chosen item runs with `RdCallActionRequest`; its dialog arrives over Lux.
2. **Open Projects.** The entries of `solutionModel.solutions` other than the shown one, each with
   its name and `hostProjectBasePath`. Choosing one binds the session to that solution.
3. **Recent Projects.** `expandGroup("RecentProjectListGroup")`, without the paths that are already
   open. Choosing one runs the backend's `ReopenProjectAction`; the project then arrives as a new
   solution.
4. **Home.** Left out, as in the client.
5. **UI.** A new `ui/ProjectWidgetPopup.kt`: a `ProjectWidgetRenderer` row and a
   `ProjectToolbarWidgetPresentable` data class with IntelliJ's field names. It reuses
   `popupContainer`, `projectAvatarBrush`, `initials` and the connect screen's search field for
   the filter.

## Steps

- [ ] 1. Live dump on an isolated backend (port 5991, two projects open): what both `expandGroup`
      calls answer, and what `ReopenProjectAction` and `OpenFile` do for a client (a Lux dialog,
      This Window or New Window).
- [ ] 2. `ClientSession`: the open solutions and the recent list in `SessionState`, and
      `switchProject(path)`.
- [ ] 3. `ProjectWidgetPopup.kt`: rows, section titles, the selection colour, the filter.
- [ ] 4. `MainToolbar.kt`: `onClick` on the project widget; the popup opens under it on a desktop
      and in the middle of the screen on a phone.
- [ ] 5. Before and After shots, a dated addendum to `split-protocol.md` correcting the paragraph
      named above, and a row in `pending.md`.

## Unknown until step 1

- Whether `RecentProjectListGroup` expands for a client, and whether the presentation carries the
  path (the description).
- Whether binding another solution in a running session is clean. Today it happens only at start
  and at the welcome project's hand-over.
- Project icons and branch names: the action protocol may not carry them. Without them a row shows
  the generated avatar and no branch line.

## Open decision

Build the full popup (recommended), or match the Gateway client's top block only.

## Addendum (2026-11-24): checked in a running JetBrains Client; the backend builds the popup

The section "What the JetBrains Client does" above is wrong, and "Design", "Steps" and "Unknown
until step 1" are replaced by this addendum. The bytecode was read correctly, but the client's own
`ProjectToolbarWidgetAction` is not what runs: the widget and its popup come from the backend.

How it was checked: a backend of its own on port 5997 (separate settings, projects `alpha` and
`beta` open), JetBrains Client 263.7307.200 started on its join link with
`IJ_JAVA_OPTIONS='-Didea.log.trace.categories=protocol.SEND,protocol.RECV'`. The project widget was
pressed through macOS accessibility (System Events), the popup's rows were read the same way, and
the rd trace was read for the same seconds (06:52:18 IST). Screen capture isn't allowed from the
agent's shell, so there is no picture; the accessibility rows are the evidence.

### What the client's popup shows (**seen**)

| Row | Text |
|---|---|
| 1 | New Project… |
| 2 | Open… |
| 3 | Clone Repository… |
| 4 | IntelliJ IDEA Home |
| 5, under the title "Open Projects" | `beta`, `…/widget-backend/beta` |
| 6 | `alpha`, `…/widget-backend/alpha` |
| below the list | Press F1 to toggle preview |

So the client has the same popup as IntelliJ, with "IntelliJ IDEA Home", the "Open Projects"
title and the advertisement line, and without a Toolbox entry. There was no "Recent Projects"
section only because this backend's settings were new and both of its projects were open.

### How it gets there (**seen** in the trace)

| Step | Message | Example |
|---|---|---|
| The toolbar's items | `AsyncActionsModel.expandGroup` for `composite:MainToolbarGeneralActionsGroup;MainToolbarLeft;…;main.toolbar.Project` | answers `ActionIdAndPresentationModel(id = main.toolbar.Project, PresentationModel(flags = 2179, text = "alpha", icon = AvatarIconModel(targetSize = 16, arcRatio = 0.4, FullProjectAvatarIconPresentationModel(name = "alpha", path = "…/alpha")), description = "…/alpha"))` |
| The widget itself | `AsyncActionsModel.getComponent(ActionComponentRequest(actionId = "main.toolbar.Project", place = "MainToolbar", factoryId = 1, projectId))` | answers a `BeContextActionBehavior`; its `content` has `text = "alpha"`, `leftIcons = [AvatarIconModel]` and the signal `doAction`. The client writes `uniqueId` (23) and `content.uniqueId` (22) |
| A click | the client fires `getComponent.<call>.content.doAction(RectangleModel(x = 362, y = 78, width = 87, height = 26))`: the widget's bounds | 71 ms later the popup arrives |
| The popup | the backend adds `UIAutomationInteractionModel.activePopups[1]`: a `BeAbstractPopup` with `content = WizardPopupContent(step, inputMap = [10 → handleSelection1, 27 → goBack3, …])`, `settings.cancelOnClickOutside = true`, and `context = BePopupPositionComponentContext(ownerAnchor = BOTTOM_LEFT, popupAnchor = TOP_LEFT)` | |
| The rows | `activePopups[1].content.step.items`: one `BeListPopupLine` per row, keys 0 to 5 | a project row's `columnElements` are a `BeStyleControl` holding a grid: the title label ("Open Projects") when the row starts a section, then the icon (`DeferredIconModel`), the name (`RichTextModel` "beta") and the path (`RichTextModel` in `NamedColor("Label.infoForeground")`), with `BeMargin`s between them |

So the backend runs `ProjectToolbarWidgetAction`, `ProjectWidgetRenderer` included, and sends the
result as the same kind of list popup as the welcome screen's New ▾. The avatar's `targetSize = 16`
and `arcRatio = 0.4` match what `MainToolbar.kt` already draws.

### What Ideate has for this

- `session/BackendPopups.kt` already shows `WizardPopupContent` popups (`PopupList` with `wizard`),
  chooses a row and follows substeps. A `PopupRow` is one icon and one text, so a project row's
  second line and a section title are not shown yet.
- Nothing calls `AsyncActionsModel.getComponent` yet.

### Design

1. **Widget.** Ask the backend for the widget: `getComponent` for `main.toolbar.Project` in
   `MainToolbar`, and take the name and avatar from its `content` (`text`, `leftIcons`), in place of
   `SessionState.projectName`.
2. **Click.** Fire `content.doAction` with the widget's bounds. No action group is expanded by
   Ideate, and no recent list is kept: the backend sends all of it.
3. **Popup.** It arrives in `activePopups` and is shown by `BackendPopups`. Extend a row to what
   `ProjectWidgetRenderer` sends: a section title above it, an icon, a name, and info-coloured lines
   under the name. Sizes as in "A project row" above. Show `settings.adText` when it isn't empty.
4. **Place.** Under the widget (`BOTTOM_LEFT` to `TOP_LEFT`) on a desktop; in the middle of the
   screen on a phone, as Ideate's other popups.
5. **After a choice.** Choosing an open project or a recent one is the backend's business: it opens
   or focuses the project. Ideate has to follow to the solution the backend makes current.

### Steps

- [ ] 1. `ClientSession`: `getComponent` for `main.toolbar.Project`; the widget's text and avatar
      in `SessionState`; `projectWidgetClicked(bounds)` fires `doAction`.
- [ ] 2. `BackendPopups` and `BackendPopupView`: a `BeListPopupLine`'s grid read into a title,
      an icon, a name and secondary lines; a test with the row recorded above.
- [ ] 3. `MainToolbar.kt`: the widget from the backend's content, `onClick`, and its bounds.
- [ ] 4. Follow a project switch: on the isolated backend with two projects, choose the other open
      project and a recent one, and record what the backend sends (a new solution, `closeRequested`,
      a This Window or New Window question).
- [ ] 5. Before and After shots, an addendum to `split-protocol.md` for the paragraph named above,
      a row in `pending.md`.

### Unknown

- Where `factoryId = 1` comes from (it wasn't looked up in the trace), and whether `getComponent`
  needs the toolbar's `expandGroup` call first.
- What the backend does for a client after an open or a recent project is chosen (step 4).
- Whether the popup's speed search (typing to filter) runs on the backend for this popup kind.

## Addendum (2026-11-24, second run): choosing a project, filtering, `factoryId`

Same setup as the first addendum (own backend on 5997, traced JetBrains Client), 07:06 to 07:10
IST. All **seen** in the client and its rd trace. This answers the first addendum's "Unknown".

| Question | Answer | Example from the trace |
|---|---|---|
| Where does `factoryId` come from? | The client picks one number per toolbar and sends it in that toolbar's `expandGroup` and in every `getComponent` for it. The backend never sends it. | `1` for the `alpha` window's `MainToolbar`, `5` for the `beta` window's; both in `AsyncActionUpdateRequest(place = "MainToolbar", uiKind = ToolbarKindModel(horizontal = true), factoryId = 1)` and `ActionComponentRequest(actionId = "main.toolbar.Project", factoryId = 1)` |
| Does typing filter on the backend? | No. Typing `alp` left one row (`alpha`) and nothing was sent in those 2 s. The client filters the rows it has. | after the popup opened only `content.selectedValueChanged(2)` was sent |
| How is a row chosen? | The client fires the row's own signal, then closes the popup. | SEND `activePopups[2].content.step.items[5].signal(Unit)`; RECV `activePopups[2].result = OK`; SEND `result = CLOSE` and `activePopups` Remove key 2 |
| What follows choosing an **open** project? | The backend asks the client to bring that project's window forward, 54 ms after the choice. No solution is added or closed. | RECV `solutions[0].projectViewModel.focusProjectWindow(false)`; the client's main window changed from `beta` to `alpha` |
| What follows choosing a **recent** project? | The backend opens it and adds it as a new solution, 55 ms after the choice. No This Window or New Window question came, and the current project stayed open. | RECV `solutions` Add key 2, then `solutions[2].isLoaded = true` 165 ms later; the client showed `alpha` as a new window (a macOS window tab) |
| What does closing a project send? | The backend asks, the client removes the solution. | RECV `solutions[0].closeRequested = RdSolutionCloseOptions(…)`, SEND `solutions` Remove key 0 |

The rows in each state of this run:

| State | Rows after the four fixed ones |
|---|---|
| only `alpha` open | "Open Projects": `alpha` |
| `alpha` and `beta` open, asked from `beta`'s window | "Open Projects": `beta`, `alpha` |
| `alpha` closed from the client, asked from `beta` | "Open Projects": `beta`; "Recent Projects": `alpha` |

The shown project is itself a row under "Open Projects". A project that was only ever open while
the backend was killed did not become recent; closing it from the client did.

### What this adds to the design

- Ideate reads `focusProjectWindow` nowhere yet. It is how the backend says "show this project":
  on it, bind the session to that solution (step 4 of the first addendum).
- After a recent project is chosen, the new entry of `solutionModel.solutions` is the one to show.
  Ideate today leaves a second solution alone unless the shown one is the welcome project.
- Filtering by typing is the client's job: match on the name and the lines under it, as
  `ProjectWidgetSpeedsearchFilter` does.
- `ActionMenus` sends `factoryId = null` today. For the toolbar, pick a number per toolbar and use
  it in both calls.

### Not checked

New Project…, Open… and Clone Repository… were not chosen (each opens a dialog), and neither was
IntelliJ IDEA Home.

## Addendum (2026-11-24, third run): the three top rows, and a project row's exact layout

Same setup (own backend on 5997, traced JetBrains Client, 07:22 to 07:25 IST), with window
screenshots through cmux-cua. All **seen**.

### New Project…, Open…, Clone Repository…

Each is chosen like any row (`activePopups[n].content.step.items[i].signal`), and each answer is
a Lux dialog, which Ideate already shows (`session/LuxDialogs.kt`):

| Row | Arrives as | Title and size in the client |
|---|---|---|
| New Project… (`items[0]`) | `LuxModel.components[3] = LxDialog(modality = true, resizable = true, layoutSource = Backend)`, 316 ms after the choice | "New Project", 800 × 666 |
| Open… | `LuxModel.components[6] = LxDialog` | "Open File or Project", 424 × 483 |
| Clone Repository… | `LuxModel.components[9] = LxDialog` | "Clone Repository", 800 × 679 |

So the top block needs nothing new once the popup's rows can be chosen. The dialogs were cancelled
with Escape; nothing was created, opened or cloned.

IntelliJ IDEA Home was left alone on purpose: it opens `~/IdeaProjects/IdeaHome`, the folder the
user's own IntelliJ uses, and a test backend would write into its `.idea`
(`local-test-backend.md`, "Without a project folder").

### A project row as the backend sends it

The row "beta" under the title "Open Projects" (`BeListPopupLine`, `hasSubstep = false`). It is
`ProjectWidgetRenderer`'s UI DSL panel, translated control by control:

```
columnElements = [
  BeStyleControl                                   // opacity 0.0
    BeGrid (Vertical)
      BeHeader(text = "Open Projects", headerStyle = SEPARATOR)     // only on a section's first row
      BeGrid (Horizontal)
        BeStyleControl(margin = left 16, top 6, right 16, bottom 6) // OnePx units
          BeGridLayoutPanel
            cell gridIndex 0, x 0, y 0, Top,    gaps top 2, right 8, bottom 2:
              BeRichText(text = "", icon = DeferredIconModel(id = 63, baseIcon = EmptyIconModel(20 × 20)))
            cell gridIndex 2, x 0, y 0, Center, gaps top 2, bottom 2:
              BeRichText("beta", NamedColor("List.foreground"))
            cell gridIndex 3, x 0, y 0, Center, gaps top 2, bottom 2:
              BeRichText("…/widget-backend/beta", NamedColor("Label.infoForeground"))
            grids: -1 the root; 0 at (1, 0) the text column; 1 at (0, 0) and (0, 1) its two lines
]
```

- The numbers match `ProjectWidgetRenderer`: `JBUI.Borders.empty(6, 0)`, `rowGaps` 2, the icon's
  right gap 8, the icon at the top.
- No font size is sent for the path (`BeTextSettings.fontSize` and `fontStyle` are not set), though
  IntelliJ draws it with `JBFont.smallOrNewUiMedium()`. Ideate has to apply the small font itself to
  the lines under the name.
- The icon comes in two parts. The row carries only an empty 20 × 20 placeholder; right after the
  popup is loaded the backend fires `ShellModel.deferredIconReady(id = 63, icon =
  AvatarIconModel(targetSize = 20, arcRatio = 0.4, FullProjectAvatarIconPresentationModel(name =
  "beta", path = "…/beta")))`.

### What Ideate's reader does with this today

- `BackendPopups`' row reader (`BackendPopups.kt`, the walk at the end of the file) follows
  `BeGrid`, `BeRichText` and the wrapper controls. `BeGridLayoutPanel` and `BeHeader` appear nowhere
  in the sources, so a project row would come out without its texts and without its title.
- `ClientSession` takes a `DeferredIconModel`'s `baseIcon` and never listens to
  `deferredIconReady`, so the avatar would stay empty. `AvatarIconModel` is what
  `projectAvatarBrush(name)` and `initials(name)` already draw for the toolbar's widget.

### Added to the steps

- Step 2 grows: read `BeGridLayoutPanel` (cells by grid and position: one icon, a first line, the
  lines under it) and `BeHeader` (a section title); listen to `ShellModel.deferredIconReady` and
  draw an `AvatarIconModel`.
- The top rows need no step of their own.

### Still unknown

- Whether `getComponent` answers without the toolbar's `expandGroup` call before it. This can only
  be tried from Ideate's own client, so it belongs to step 1.
- IntelliJ IDEA Home (not chosen, see above).

## Addendum (2026-11-24, 14:50 IST): the rows are fixed; what a click needs; the build plan

This section replaces "Design" and "Steps" above and every step list in the earlier addenda. An
implementer reads this section and nothing else has to be decided.

### Done in this commit: a project row reads and draws correctly

Seen in a running Ideate against a 263.7307.200 backend with `alpha` and `beta` open (the widget's
click was wired by hand for the check, see `What a click needs`):

```
New Project…
Open…
Clone Repository…
⌂ IntelliJ IDEA Home
Open Projects
[B] beta     /private/tmp/…/widget-backend/beta
[A] alpha    /private/tmp/…/widget-backend/alpha
```

| What | Where | How |
|---|---|---|
| A row's cells | `BeRow.of` in `session/BackendPopups.kt` | new branch `is BeGridLayoutPanel`: walks `items` (`BeControlWithGridConstraints.content`). The first cell with text gives `BeRow.fragments` (`beta`); the text of every later cell goes to the new `BeRow.description` (`/projects/beta`) |
| A row's deferred icon | `BeRow.deferredIcon: Long?` | the `id` of a `DeferredIconModel` on a `BeRichText` or `BeIcon`, e.g. 63 |
| The second line and the avatar | `PopupRow.description`, `PopupRow.avatar`, `PopupRow.iconPath` (now Compose state) | `BackendPopups.row(line)` builds every `PopupRow` |
| The real icon | `BackendPopups.bind` | `protocol.shellModel.deferredIconReady.adviseOn(lifetime, protocol.scheduler)`: kept in `readyIcons` (an icon can be ready before its popup is shown) and given to the rows with that id by `showIcon`. An `AvatarIconModel` with a `FullProjectAvatarIconPresentationModel` sets `PopupRow.avatar` to its `name`; any other icon sets `iconPath`. `deferredIconsReclaimed` empties `readyIcons` |
| Drawing | `PopupListView` in `ui/popup/BackendPopupView.kt` | a row with a `description` is two lines high with 6 dp above and below (the backend's `BeStyleControl` margin); the path is 12 sp in `Islands.textSecondary` (`JBFont.smallOrNewUiMedium()`, `Label.infoForeground`); the avatar is 20 dp (`AvatarIconModel.targetSize`), at the top with a 2 dp gap |
| The avatar itself | `ProjectAvatar(name, size, modifier)` in `ui/MainToolbar.kt` | moved out of `MainToolbar` so the toolbar (16 dp) and the row (20 dp) draw the same thing |
| Test | `BackendPopupRowTest` (jvmTest) | a `BeGridLayoutPanel` of `[icon with DeferredIconModel(63), "beta", "/projects/beta"]` gives text `beta`, description `/projects/beta`, deferredIcon 63 |

`BeHeader` was already read (`BeRow.header`, added with the run toolbar's More Actions).

### What a click needs (the open question of addendum 3, now answered)

| Tried from Ideate | Result |
|---|---|
| `getComponent(ActionComponentRequest("main.toolbar.Project", "MainToolbar", 1, projectId))`, nothing before it | answers a `BeContextActionBehavior` whose `content` is a `BeToolbarComboButton` |
| `content.doAction.fire(RectangleModel(80, 8, 87, 26))` on that component | **nothing happens**: no `activePopups` entry (tried twice, also 7 s after the answer) |
| first `expandGroup(AsyncActionUpdateRequest("composite:MainToolbarGeneralActionsGroup;MainToolbarLeft;MainToolbarVCSGroup;ToolboxProjectPopupAction;UnattendedHostMenuGroup;main.toolbar.Project", ActionTimestampSetModel([ProjectTimestampModel(projectId)]), "MainToolbar", ToolbarKindModel(true), 1))`, then `getComponent`, then `doAction` 9 s later | the popup arrives 190 ms after the click: `Backend list popup '': New Project…, Open…, Clone Repository…, IntelliJ IDEA Home, beta, alpha` |

So the backend acts on the widget's click only for a component asked for **after** the main
toolbar's `expandGroup` with the same `factoryId`. The composite id is the one JetBrains Client
sends (trace, 2026-11-24); `factoryId = 1` is the client's own number for this toolbar.

Other facts found on the way:

- `BeAbstractPopupSettings.adText` is `""` for this popup, and "Press F1 to toggle preview" is in no
  rd message: JetBrains Client draws that line itself. Ideate leaves it out.
- The popup's `context` is `BePopupPositionComponentContext(point = BePopupRelativePoint(ownerID =
  UIElementId("44"), ownerAnchor = BOTTOM_LEFT, popupAnchor = TOP_LEFT), position = TOP)`.
  `BackendPopups.place` does not know this context, so the popup shows at the top centre.
- The separator before IntelliJ IDEA Home is a `BeHeader` with an empty text; `PopupListView` draws
  an empty title line under it (a gap IntelliJ does not have).

### The build plan

Rules for the implementer (all standing rules of this repository):

- Work in your own detached worktree from `origin/main`; never commit in the shared checkout; no
  `git stash`, `git reset --hard` or `git clean`. Commit author `Safeuq Mohamed
  <mohamedsafeuq@gmail.com>`, no Claude mention and no `Co-Authored-By` line.
- Keep IntelliJ's names and sizes. No extra padding for touch (list rows stay 24 dp).
- No abbreviated identifiers (`request`, not `req`). `./gradlew spotlessApply` before committing.
- Never touch ports 5993 or 5996 or the user's own IntelliJ. Use your own backend on a free port
  (5997) with its own `-Didea.*.path` folders. Stop what you started, by exact PID.
- Do not choose the row IntelliJ IDEA Home in a test: it opens `~/IdeaProjects/IdeaHome`, which the
  user's own IntelliJ uses.
- If something here does not match what you see, stop and ask; do not guess.

#### Step 1. `session/ProjectWidget.kt` (new file), the widget's click

Mirror `session/RunToolbar.kt` (same package, same style of constructor).

```kotlin
/** The main toolbar's project widget (IntelliJ's ProjectToolbarWidgetAction, `main.toolbar.Project`). */
internal class ProjectWidget(
  private val model: AsyncActionsModel,
  private val projectId: RdProjectId,
  private val log: (String) -> Unit,
) {
  private var lifetime: Lifetime = Lifetime.Terminated
  /** The backend's `BeToolbarComboButton`, asked for once after the first `expandGroup`. */
  private var button: BeToolbarComboButton? = null
  /** The widget's place in the window at the last click, dp. */
  private var bounds: IntRect? = null
  /** True once the widget was clicked in this session; see step 4. */
  var clicked = false
    private set

  fun bind(solutionLifetime: Lifetime)            // lifetime = solutionLifetime; on termination button = null
  fun click(bounds: IntRect)                      // see below
  fun popupPlace(ownerId: String): LxPoint?       // see step 3
}
private const val ACTION = "main.toolbar.Project"
private const val PLACE = "MainToolbar"
private const val FACTORY = 1
private const val TOOLBAR_GROUP =
  "composite:MainToolbarGeneralActionsGroup;MainToolbarLeft;MainToolbarVCSGroup;ToolboxProjectPopupAction;UnattendedHostMenuGroup;main.toolbar.Project"
```

`click(bounds)`, called on the rd thread:

1. `this.bounds = bounds; clicked = true`.
2. `model.expandGroup.start(lifetime, AsyncActionUpdateRequest(TOOLBAR_GROUP,
   ActionTimestampSetModel(listOf(ProjectTimestampModel(projectId))), PLACE, ToolbarKindModel(true),
   FACTORY)).result.advise(lifetime) { … }`; on failure `log("Project widget: toolbar not expanded:
   …")` and return.
3. In its answer: if `button` is null, `model.getComponent.start(lifetime,
   ActionComponentRequest(ACTION, PLACE, FACTORY, projectId))`; the answer's `(control as?
   BeContextActionBehavior)?.content as? BeToolbarComboButton` becomes `button`; anything else is
   logged with its class name and nothing is fired.
4. `button.doAction.fire(RectangleModel(bounds.left, bounds.top, bounds.width, bounds.height))`.

`ClientSession`:

- field `private var projectWidget: ProjectWidget? = null`;
- in `bindSolution`, next to `runToolbar = RunToolbar(…)`: `projectWidget =
  ProjectWidget(protocol.asyncActionsModel, newSolution.projectId, ::log).also {
  it.bind(solutionLifetime) }`, and `solutionLifetime.onTermination { projectWidget = null }`;
- `fun projectWidgetClicked(bounds: IntRect) = onRd { projectWidget?.click(bounds) ?: log("No
  project yet; no project popup") }`, placed next to `runToolbarAction`.

The widget's text stays `SessionState.projectName` (the same name; nothing new in `SessionState`).

#### Step 2. `ui/MainToolbar.kt` and `ui/ClientScreen.kt`, the click and its bounds

- `MainToolbar` gets a parameter after `runConfigurationEntries`:
  `/** The project widget was pressed; [bounds]: its place in the window, dp. */ onProject: (bounds:
  IntRect) -> Unit = {}`.
- The project widget's `ToolbarDropdown(control, Modifier.widthIn(max = 200.dp).padding(end =
  8.dp))` call gets `onClick = { onProject(projectBounds) }`, and its modifier an
  `onGloballyPositioned` that keeps `projectBounds` exactly as the run buttons keep `bounds` (same
  file: `boundsInWindow()`, converted to dp with `LocalDensity`, rounded). Put the
  `onGloballyPositioned` after the `padding(end = 8.dp)` so the 8 dp gap is not part of the bounds.
- `ClientScreen.kt`, the `MainToolbar(` call: `onProject = session::projectWidgetClicked`.

#### Step 3. `session/BackendPopups.kt` and `ui/popup/BackendPopupView.kt`, the popup's place and the empty header

- `BackendPopups` gets a constructor parameter after `iconPath`: `/** Where a popup anchored to a
  client component goes: the window point for the component's id, e.g. "44". */ private val
  componentPlace: (ownerId: String) -> LxPoint?`. `ClientSession` passes `{ ownerId ->
  projectWidget?.popupPlace(ownerId) }` where it builds `BackendPopups` (the lambda reads the field
  when called, so the later assignment is fine).
- `BackendPopups.place`: before the `BePopupPositionDirectTransferContext` line, `if (position is
  BePopupPositionComponentContext) { popup.anchor = componentPlace(position.point.ownerID.id);
  return }`.
- `ProjectWidget.popupPlace(ownerId)`: returns `LxPoint(bounds.left.toDouble(),
  bounds.bottom.toDouble())` (owner BOTTOM_LEFT to popup TOP_LEFT) when `bounds` is set, else null.
  Log `ownerId` next to `button.uniqueId.valueOrNull` once per popup. If they differ in the live
  check, report it; the popup is still placed under the widget.
- `PopupListView`: draw the header's `BasicText` only when `header.isNotEmpty()`; the 1 dp line
  stays.

#### Step 4. Showing the project the user chose

Ideate shows one project per `ClientSession` (its `project` parameter, a host path). A session is
not rebound to another solution: a choice makes a new session over the same link, as a reconnect
does.

What the backend sends after a choice (trace, addendum 2):

| Choice | Message |
|---|---|
| a row under Open Projects | `solutions[key].projectViewModel.focusProjectWindow(false)` on that project's solution, 54 ms later |
| a row under Recent Projects, or a project made or opened through the top rows' dialogs | `solutions` Add of a new key; its `solutionOpenStrategy.description.hostProjectBasePath` is the path |

`ClientSession`:

- constructor parameter before `onAccepted`: `/** The backend asked to show another project, e.g.
  after a row of the project widget's popup: its host path. */ private val onProjectSwitch:
  (hostPath: String) -> Unit = {}`;
- field `private var switchedTo: String? = null` and `private fun switchTo(hostPath: String?)`:
  returns unless `projectWidget?.clicked == true`, `hostPath != null`, and `hostPath != switchedTo`;
  then sets `switchedTo`, logs `Project switch to $hostPath`, calls `onProjectSwitch(hostPath)`;
- in `protocol.solutionModel.solutions.view(lifetime) { … }`, right after `hostPath` is known:
  `newSolution.projectViewModel.focusProjectWindow.adviseOn(solutionLifetime, rdScheduler) { if
  (solution !== newSolution) switchTo(hostPath) }`;
- the last line of that block (`log("Project … added; showing $project")`): add
  `switchTo(hostPath)` after it. The welcome-project branch above it stays as it is.

The `clicked` gate matters: solutions that are simply there at connect (`alpha` and `beta` both
arrive in the first second) must not make the session jump.

`connect/ConnectController.kt`:

- both places that build the session for a link (`connect` and the reconnect's `candidate`) pass
  `onProjectSwitch = { hostPath -> main.launch { switchProject(current, hostPath) } }`;
- new `private fun switchProject(current: Attempt, hostPath: String)`: returns unless `attempt ===
  current` and `current.identity != null`; stops the session (`session?.stop()`), then builds the
  next one exactly as `connect` does but with `Attempt(current.kind, current.link, current.text,
  project = hostPath, openFile = null, resumed = true, editorsKey = "${current.editorsKey}
  :$hostPath")` (no space in the key) and `identity = current.identity`, so the backend takes the
  same client again. Share the session-building lines with `connect` instead of copying them.
  `// ponytail: the first project of a link keeps the link's own editors key, so its tabs are not
  restored after switching away and back; give every project its own key when that matters.`

#### Step 5. Checks, in this order

1. `./gradlew :compose:jvmTest` passes (it includes `BackendPopupRowTest`).
2. Live, on the desktop app, with a backend that has `alpha` and `beta` open (recipe in the memory
   note "Check JetBrains Client live"; start the second project with a second
   `remote-dev-server.sh run <beta>` using the same `-Didea.*.path` folders):
   - click the widget: the popup opens with its top-left corner at the widget's bottom-left; rows as
     in the block at the top of this section; no gap above IntelliJ IDEA Home;
   - click `beta`: the log says `Project switch to …/beta`, a new session connects, the toolbar
     shows `beta`, the Project tree is beta's;
   - open the popup again and click `alpha`: back to alpha;
   - Escape and a click outside close the popup; the run toolbar still works.
3. Take a Before shot (origin/main: the widget does nothing) and an After shot (the open popup) for
   the shared Before/After page.
4. Append a dated addendum here with what was seen, and correct `docs/split-protocol.md` line 877
   with an addendum there (it says recent projects come from the Gateway plugin; the backend sends
   them).

#### Not in this build

- Typing to filter the rows (the client's own speed search; nothing is sent).
- A row with `hasSubstep = true` (not seen in this popup).
- The top rows' dialogs (New Project…, Open…, Clone Repository…) are `LxDialog`s and need no work
  here; they open as any Lux dialog does.

## Addendum (2026-11-24, 15:10 IST): built; what the live check changed in the plan

Steps 1 to 4 of the build plan above are in the code. Checked in the desktop app against a
263.7307.200 backend with `alpha` and `beta` open: one click opens the popup under the widget; the
rows are New Project…, Open…, Clone Repository…, IntelliJ IDEA Home, Open Projects, `[B] beta` and
`[A] alpha` with their paths; choosing `beta` shows beta (toolbar, Project tree), choosing `alpha`
in beta's popup shows alpha again; repeated twice in each direction.

Four things were not as the plan said:

| Plan | Seen | Code |
|---|---|---|
| `ProjectWidget.click` fires `doAction` inside `getComponent`'s answer | that `doAction` is lost: the first click after connecting opened nothing, the second did. rd binds the answered component after the answer's handlers ran | after storing the button, `click(bounds)` runs again: one more `expandGroup` round trip, then `fire` |
| `switchProject` stops the session and starts the next one | the backend declined the next one: `Backend refused the connection: DECLINED`; its log: `Connection with client … was closed in favor of another connection`, then `Connection flow. failed with code DECLINED and message Closed` | `switchProject` only makes the next `Attempt` and calls `reconnect(next, identity, …)`: the new session connects while the old one is open. The first try is still declined after 4 s and closes the old session; the retry 1 s later is accepted. A switch takes about 5 s. `connectionLost` returns at once while `reconnectJob` is active, so the old session's end does not start a second reconnect |
| a project row's icon is a `DeferredIconModel` | in the popup opened from the second project (`beta`) the rows carry the `AvatarIconModel` itself, no deferred icon | `BeRow.avatar` (through `avatarProject(icon)`) is read from the row; `ClientSession.iconPaths` knows `AvatarIconModel` (no path) |
| `focusProjectWindow.adviseOn(…)`; `ownerID.id` | `focusProjectWindow` is an `ISignal` (no `adviseOn`; it is fired on the rd thread); `ownerID` is a `ComponentId`, only `UIElementId` has `id` | `advise`; `(ownerID as? UIElementId)?.id` |

The popup's owner id changes with every popup (21, 51, 81, 111, 141, 171 in one run) and the
button's `uniqueId` is not set, so the two cannot be compared; the popup goes under the widget that
was clicked.

Not checked: Escape and a click outside on this popup (the test tool cannot press them; both are
`BackendPopups`' existing handling of `cancelKeyEnabled` and `cancelOnClickOutside`), a row under
Recent Projects, and the top rows' dialogs from Ideate.

Open: the 5 s a switch takes. The backend keeps a client's first connection until it has declined
the second; whether JetBrains Client's own reconnect avoids that wait is not looked at yet.

## Addendum (2026-11-24, 15:40 IST): the switch takes half a second; Escape; the rows not tried before

### The 5 s switch: the cause and the fix

What the backend did with a second connection of the same client while it still held the first
(its `idea.log`, 2026-11-24 15:07 IST):

```
WireReuseUtil   Local wire exist for client: true      ← the old session's wire
WireReuseUtil   Remote wire exist for client: false    ← Ideate always sends WIRE_EXIST = 0
WireStorage     Terminate wire … @9                    ← the old wire
…               Create a new Wire on a new transport   ← wire @10 for the new connection
RdServerConnectionManager  Connection with client … was closed in favor of another connection
SecureWireOverStreamTransport  Wire … @10  Connection flow. failed with code DECLINED and message Closed
(4.0 s later)   TLS Transport … Connection state changed: <CONNECTED> -> <CLOSED>
```

Closing the old session also failed the new wire, 2 ms after it was made, and the backend closed
the socket only 4 s later; that is when Ideate saw `DECLINED`. With the 1 s pause before the retry
this was the 5 s.

A client can end its own session: `SecureWireOverStreamTransport.onSystemFrameReceived` (the class
is shared by backend and client) sets the wire's state to `DECLINED(reason)` for a
`FORCE_TERMINATE_WIRE` frame from the other side, and the backend then drops the wire at once
(`Wire connection declined (how?), terminating its lifetime immediately with no delays`, `Wire was
successfully removed`, 4 ms after the frame). `reason` is an int32 ordinal of
`ConnectionDeclineReason`: `None` 0, `UserDeclined` 1, `GuestLimitReached` 2,
`OtherControllerLaunched` 3, `HostExit` 4.

| Order tried | Result |
|---|---|
| new connection while the old session is open | declined; retry accepted; 5 s |
| `FORCE_TERMINATE_WIRE(0)` on the old one, new connection 5 ms later | still declined (`closed in favor of another connection`: the old session was not closed yet) |
| `FORCE_TERMINATE_WIRE(0)`, new connection 300 ms later | accepted in 20 ms |

In the code:

- `SplitWire.terminate()` sends `FORCE_TERMINATE_WIRE` with reason 0 and closes; `ClientSession`
  calls it when its lifetime ends (`stop()`), so every deliberate end of a session tells the
  backend, also Back from the IDE screen.
- `ConnectController.switchProject` starts `reconnect(next, identity, …, SWITCH_WAIT_MILLIS)` (500
  ms before the first try), then stops the old session. The job is started first so that
  `connectionLost` (which returns while `reconnectJob` is active) does not take the stopped
  session for a lost connection.
- While the first try runs the popup says "Opening Project / beta" (`ConnectController.switching`)
  instead of "No connection to IDE Backend".

Measured from the row's choice to `Connection accepted`: 617 ms and 614 ms (`alpha` → `beta` →
`alpha`), no declined try.

The 500 ms is a wait, not a signal: the backend gives none before its 4 s socket close. If a
backend is slower than that, the try is declined and retried as before.

### Escape did not close a list popup

`BackendPopupView` handles keys on the popup's own column, which gets them only while something
inside it has the focus. A popup with a text field focuses that field; a list popup (More Actions,
the project widget's) focused nothing, so Escape and the arrow keys reached nothing on the desktop.
Now a popup without a `PopupTextField` requests the focus for itself when it is shown, as IntelliJ
focuses its list (`BeAbstractPopupSettings.isFocused`). Checked: Escape closes the popup (`Backend
popup 8 cancelled`). A click outside already worked (`Backend popup 5 cancelled`).

### Tried from Ideate now

| What | Result |
|---|---|
| New Project… | `Backend dialog 'New Project' (800 × 637)` opens; Escape closes it |
| Open… | `Backend dialog 'Open File or Project' (424 × 454)` opens; Escape closes it |
| Clone Repository… | `Backend dialog 'Clone Repository' (800 × 650)` opens with its URL field focused; Cancel closes it |
| a row under Recent Projects (`beta`, backend started with only `alpha`) | the backend opens it: `solutions` gets a new entry 320 ms after the choice, `switchTo` asks for it, the new session is accepted 540 ms later and shows `beta` |

Still not tried: IntelliJ IDEA Home. Still not built: typing to filter the rows.

## Addendum (2026-11-24, 10:18 IST): typing filters the rows, a switch starts from an empty screen, IntelliJ IDEA Home tried

### Typing filters a list popup's rows

Nothing goes to the backend: in JetBrains Client the filter is IntelliJ's own speed search over the
rows it already has. `PopupLineStep` (`lib/intellij.rd.ui.jar`) answers `isSpeedSearchEnabled`
with `StepPopupContent.isSearchEnabled.value` (true for the project widget's popup) and
`getTextFor` with every text of the line joined, so a project row is found by its name or its path.

| IntelliJ (`WizardPopup`, `ListPopupImpl`, `AbstractPopup`) | Ideate |
|---|---|
| a typed character goes to `mySpeedSearch` | `listKey` in `ui/popup/BackendPopupView.kt` calls `PopupList.type(filter + character)` |
| `mySpeedSearchPatternField` is set as the popup's header while text is held | a 24 dp line above the rows: `expui/general/search.svg` and the text |
| `ListPopupModel` keeps the rows `shouldBeShowing` accepts | `PopupList.shows(row)`: the text is in `row.text` or `row.description`, any case |
| `onSpeedSearchPatternChanged` → `selectFirstSelectableItem` | `type` sets `selected` to the first shown row |
| Escape resets a held filter; the next Escape closes | the same; Backspace takes one character off |
| Up, Down, Enter act on the list in the client | `PopupList.move(-1 / 1)` over the shown rows, Enter chooses `rows[selected]` (before this, Up and Down did nothing in a list popup and Enter chose nothing) |

Two known differences. `shows` is a substring check, where IntelliJ's `SpeedSearch` uses its name
matcher (`bt` finds `beta` there, not here). Only hardware keys type: an on-screen keyboard needs
a text field, which this popup does not have yet.

Checked live (backend on 5997 with `alpha` and `beta`): `bet` leaves the `beta` row, selected;
Escape brings every row back and the popup stays; `bet` + Enter switches to `beta`
(`Backend list popup 1: chose 'beta'`). Test: `BackendPopupRowTest
.typingKeepsTheRowsThatHaveTheTextAndSelectsTheFirst`.

### After a switch back, the old project's tree stayed

Seen live: `alpha` → `beta` → `alpha` showed `alpha` in the toolbar over `beta`'s Project tree,
for good. A reconnect shows each panel of the old session until the new one has sent it
(`session/HandOver.kt`); the tree counts as sent once the new tree shows what the old one showed
(`new.tree.shows(old.tree)`). That is right for the same project after a lost connection, and never
true for another project. Now `ConnectController.reconnectOnce` carries nothing over while
`switching` is set: no `handOverFrom`, no expanded or selected paths of the other project. Checked:
`beta`, `alpha`, `beta`, `alpha` in a row, each with its own name, tree and run configuration.

### IntelliJ IDEA Home

The row opens the welcome project, `<projects folder>/IdeaHome`. The projects folder is
`GeneralLocalSettings.defaultProjectDirectory`, else the system property `idea.project.path`, else
`~/IdeaProjects` (`WelcomeScreenProjectProvider.getProjectsBasePath`). A test backend must be
started with `-Didea.project.path=<scratch folder>`, or it writes into `~/IdeaProjects/IdeaHome`,
which the user's own IntelliJ uses.

Checked with that option: the choice adds `…/projects/IdeaHome` to `solutions` 450 ms later,
`switchTo` asks for it, and the new session is accepted 540 ms after that. Ideate then shows
`IdeaHome` with the welcome project's "Projects and Files" pane (Open, New, Clone Repository, the
two projects). `~/IdeaProjects/IdeaHome` kept its modification time.

Nothing of this plan is open now.

## Addendum (2026-11-24, 10:30 IST): a project's branch is a line of its own

User report: the popup showed `~/Files/Projects/XFlow/commonsmain` and
`~/Files/Projects/XFlow/xtoolhttp-error-handler`, and `commons` had a branch icon where its avatar
belongs. For a project in a git repository `ProjectToolbarWidgetAction`'s renderer adds a third
row, so the `BeGridLayoutPanel` has one more cell:

| Cell | Content | IntelliJ |
|---|---|---|
| first | the avatar (or its `DeferredIconModel`) | the row's icon, 20 px |
| name | `commons` | label |
| path | `~/Files/Projects/XFlow/commons` | `JBFont.smallOrNewUiMedium()`, info colour |
| branch | `main`, with `AllIcons.Vcs.Branch` coloured with the info colour | the same font and colour |

`BeRow.of` joined the text of every cell after the name into one string, and took the first icon
of the whole line for the row, so the branch icon won while the avatar was still deferred. Now each
text cell after the name is a `RowDetail(text, iconPath)` in `BeRow.details` / `PopupRow.details`,
and such a cell's icon is that line's only. `PopupListView` draws one 12 sp line per detail in
`Islands.textSecondary`, the icon 16 dp, tinted the same colour, 4 dp before the text. The filter
looks in every detail, so `main` finds the projects on that branch, as `PopupLineStep.getTextFor`
would.

The test projects before this were not git repositories, which is why it was not seen. Checked live
with `alpha` on `main` and `beta` on `http-error-handler`: three lines each, avatars in place.
The backend sends the branch a few seconds after the project opens; a popup opened before that has
no branch line. Test: `BackendPopupRowTest.aProjectRowKeepsItsPathAndItsBranchApartAndItsOwnIcon`.

## Addendum (2026-11-24, 10:51 IST): a project's own icon, spacing from the row's model, the widget's text and icon

Three user reports, all checked live on a 263 backend (port 5997; `alpha` with `.idea/icon.svg`
on `main`, `beta` on `http-error-handler`).

### Some projects had no icon

A project with `.idea/icon.svg` has no `AvatarIconModel`. Its `DeferredIconModel` resolves to

```
CompositionIconModel(subNodes = [EmptyIconModel(20, 20), ProjectFileIconModel(size = 16,
  data = IconDataModel(identity = 466, isSvg = true, data = <the file's bytes>))], shifts 2, 2)
```

and `ClientSession.iconPaths` logged "Icon type not handled yet: ProjectFileIconModel". Now it keeps
the SVG in `IconJar` under `projectIcon/<identity>.svg` and returns that path. `PopupListView`
draws it 16 dp in the middle of the 20 dp icon cell. A PNG project icon (`isSvg = false`) still
shows nothing.

### Spacing

The backend sends each row's spacing; Ideate used its own numbers. What it sends, from the list
cell's edge:

| Row | `BeStyleControl` margin (left, top, right, bottom) | icon to text | above and below each line |
|---|---|---|---|
| New Project…, Open…, Clone Repository… | 16, 0, 8, 0 | 4 (a second margin, around the icon) | none |
| IntelliJ IDEA Home | 16, 6, 16, 6 | 8 (the icon cell's `gaps.right`) | 2 (the name cell's `gaps.top` / `bottom`) |
| a project | 16, 6, 16, 6 | 8 | 2, for the name, the path and the branch |

`BeRow.of` reads these into `RowInsets(left, top, right, bottom, iconGap, lineGap)`
(`PopupRow.insets`); a row that sends none keeps `RowInsets()` (16, 0, 16, 0, 6, 0), the look
before. The list itself is IntelliJ's own and its numbers are from `JBUI.CurrentTheme.Popup`:

| IntelliJ | Value | Ideate before | Ideate now |
|---|---|---|---|
| `Popup.Selection.leftRightInset`: selection from the popup's side | 8 | 10 | 8 (`Look.listInset`) |
| `Popup.Body.topInsetNoHeader`, `bottomInsetNoAd` | 8 | 10 | 8 |
| `Popup.Selection.innerInsets` + margin: text column from the selection's edge | 16 − 8 = 8 | 8 | `insets.left − 8` |
| icon to text, action row / project row | 4 / 8 | 6 / 6 | from the row |
| project row: above and below | 6 | 6 | from the row |
| project row: around each line | 2 | 0 | from the row |
| one-line row with a margin above and below (IntelliJ IDEA Home) | 6 + 2 + line + 2 + 6 | fixed 24 | as tall as that |

`GroupHeaderSeparator`'s insets are unchanged (`cellInset = Look.rowInset`, 8). Popups that are
not lists keep `Look.inset` (10). Test: `BackendPopupRowTest.aProjectRowBringsItsSpacing`.

### The widget says what the backend's button says

The toolbar showed the project view's root name: `IdeaHome` for the welcome project, and always a
letter avatar. JetBrains Client shows the backend's `BeToolbarComboButton`: its `text` and the
first of its `leftIcons`.

| Project | `text` | first of `leftIcons` |
|---|---|---|
| `beta` | `beta` | the avatar (deferred, then `AvatarIconModel`) |
| `alpha` with its own icon | `alpha` | `CompositionIconModel[EmptyIconModel(16, 16), ProjectFileIconModel(size = 12)]` |
| the welcome project | `IntelliJ IDEA Home` | `ImageIconModel("expui/nodes/homeFolder.svg")` 16 × 16 |

`ProjectWidget.bind` now asks for the button at once (`load`: the toolbar's `expandGroup`, then
`getComponent`), and `show` follows `text` and `leftIcons` into `SessionState.projectWidget`
(`ProjectWidgetLook(text, iconPath, avatar)`). `MainToolbar` shows it, and falls back to
`projectName` and the name's avatar until it is there. An icon-jar icon is 16 dp in the avatar's
20 dp box (the backend's own sizes, 12 in 16, are for its 16 px toolbar icons).
`BackendPopups.whenIconReady(id, use)` gives the widget its deferred icon.
