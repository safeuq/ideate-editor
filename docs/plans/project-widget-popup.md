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
