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
