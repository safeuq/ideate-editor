# Project tabs, and several projects open at once

User request (2026-11-24): with several projects open, Ideate shows a tab per project as IntelliJ
does on macOS, and switching between them must not reconnect ("Trying to reconnect" was seen on a
switch).

Two stages. Stage 1 is the tab bar over today's switch. Stage 2 makes one connection serve every
open project, which is what removes the reconnect.

## Why a switch reconnects today

| Fact | Where |
|---|---|
| One `ClientSession` opens one connection and shows one project (`project: String?`, `solution`) | `session/ClientSession.kt` `connect`, `bindSolution` |
| The backend lists every open project on that one connection: `protocol.solutionModel.solutions` | `ClientSession.createModels`, `solutions.view` |
| A switch makes a new `ClientSession` over the same link and identity | `ConnectController.switchProject` |
| The backend refuses a second connection of the same client while the first wire is there; the refusal takes 4 s | docs/split-protocol.md, addendum "a client ends its session with FORCE_TERMINATE_WIRE" |
| So the old session sends `FORCE_TERMINATE_WIRE(0)` and the new one starts 500 ms later (`SWITCH_WAIT_MILLIS`) | `SplitWire.terminate`, `ConnectController.reconnect` |

The 500 ms is a wait, not a signal. On a backend that needs longer to drop the old wire the first
try is refused (4 s), the retry shows "No connection to IDE Backend / Trying to reconnect…
(attempt 2)". That is the user's report. No wait length is safe; only one connection for all
projects (stage 2) is.

## IntelliJ's project tabs (what to match)

Source: `platform/platform-impl/src/com/intellij/ui/mac/WindowTabsComponent.java`,
`platform/platform-api/src/com/intellij/ui/tabs/impl/singleRow/WindowTabsLayout.java`,
`IslandsUICustomization.paintProjectTab` and `getProjectTabContentInsets`,
`JBUI.CurrentTheme.MainWindow.Tab`, build 263.7307.200.

| Part | IntelliJ | Value |
|---|---|---|
| Place | `MacWinTabsHandlerV2` adds the bar to the frame, under the main toolbar, over the content | full window width |
| Height | `TAB_HEIGHT` | 30 |
| Bar background | `IslandsUICustomization.getMainBackgroundColor()` | `MainWindow.background` (`Islands.windowBackground`) |
| Tab width | `WindowTabsLayout.applyTabLayout` | `barWidth / count` each; the last tab also takes the remainder |
| Tab text | the frame's title (the project's name), `label.setAlignmentToCenter(true)` | centred, one line, cut with … |
| Font | `UiDecoration(JBFont.medium(), …)` | the label font one size down: 12 sp, regular |
| Content insets | `getProjectTabContentInsets()` in Islands | left 4, right 15 |
| Selected tab | `paintProjectTab`: `fillRoundRect` 1 px in from the tab's left and right, full height, arc `Island.arc` 10 | corner radius 5 dp, colour `MainWindow.Tab.selectedBackground`, else `ToolWindow.background` |
| Hovered tab | the same shape | `MainWindow.Tab.hoverBackground`, else `MainToolbar.Dropdown.transparentHoverBackground` |
| Line between tabs | only with three or more tabs, at the right edge of a tab that is not the last, when neither this tab nor the next is selected or hovered | 1 px wide, from 5 below the top to 5 above the bottom, `MainWindow.Tab.separatorColor`, else `#D3D5DB` light / `#43454A` dark |
| Text colour | `MainWindow.Tab.foreground(selection, hovered)` | selected `MainWindow.Tab.selectedForeground` (`#C9CCD6` light theme default / `#CED0D6` dark); hovered `MainWindow.Tab.hoverForeground` (same defaults); else `MainWindow.Tab.foreground` (`#A8ADBD` / `#B4B8BF`) |
| Close button | `actionPanel` at the tab's left (`BorderLayout.WEST`, `emptyLeft(6)`), shown while the tab is hovered (`ide.mac.os.wintabs.show.closeaction.on.hover`, true) | icon `expui/general/closeSmall.svg`, hovered `expui/general/closeSmallHovered.svg`, 16 dp, 6 dp from the tab's left edge |
| When shown | macOS merges the project windows into tabs | Ideate: when two or more projects are open on the backend |

The Islands theme files set none of the `MainWindow.Tab.*` keys, so the fallbacks above are what
IntelliJ paints.

## Stage 1: the tab bar over today's switch

Standing rules for whoever builds this: work in the worktree you are given, never in the shared
checkout; no `git stash`, `reset --hard` or `clean`; commit author
`Safeuq Mohamed <mohamedsafeuq@gmail.com>`, no Claude mention and no Co-Authored-By line; build
with `JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"` and
`./gradlew --offline …`; run `./gradlew --offline -q spotlessApply :compose:jvmTest` before a
commit; no abbreviated identifiers; no extra padding for touch (sizes stay IntelliJ's); every
finding goes into this file as a dated `## Addendum (YYYY-MM-DD, HH:MM IST): …` section, existing
text is not edited; never use backend ports 5993 or 5996 or the user's own IntelliJ; a test backend
that may open IntelliJ IDEA Home is started with `-Didea.project.path=<scratch folder>`; stop what
you start.

### 1. State: the open projects

File `compose/src/commonMain/kotlin/io/github/safeuq/ideate/client/session/SessionState.kt`.

- Add, above `data class SessionState`:

  ```kotlin
  /**
   * A project open on the backend, e.g. `OpenProject("beta", "/work/beta")`. [hostPath] is its
   * folder there (`RdCodeWithMeProject.hostProjectBasePath`); [name] is the folder's name.
   */
  data class OpenProject(val name: String, val hostPath: String)
  ```

- Add to `SessionState`, after `projectWidget`:

  ```kotlin
  /** Every project open on the backend, in the order the backend listed them. */
  val openProjects: List<OpenProject> = emptyList(),
  /** The [OpenProject.hostPath] of the project this session shows; null until one is bound. */
  val shownProject: String? = null,
  ```

Neither is a hand-over panel (`session/HandOver.kt`): they always come from the current session.

### 2. Session: fill the list, show and close a project

File `session/ClientSession.kt`.

- New field next to `switchedTo`: `private val openSolutions = mutableMapOf<String, Solution>()`
  (host path → solution; rd thread only).
- In `createModels`, inside `protocol.solutionModel.solutions.view(lifetime) { solutionLifetime,
  solutionKey, newSolution -> … }`, right after `val hostPath = description?.hostProjectBasePath`:

  ```kotlin
  if (hostPath != null) {
    openSolutions[hostPath] = newSolution
    val opened = OpenProject(hostPath.trimEnd('/').substringAfterLast('/'), hostPath)
    mutableState.update { it.copy(openProjects = it.openProjects + opened) }
    solutionLifetime.onTermination {
      openSolutions.remove(hostPath)
      mutableState.update { state ->
        state.copy(openProjects = state.openProjects.filterNot { it.hostPath == hostPath })
      }
    }
  }
  ```

- In the local `fun bindSolution(…)`, next to `projectId = newSolution.projectId`:
  `mutableState.update { it.copy(shownProject = hostPath) }`.
- New public functions, next to `projectWidgetClicked`:

  ```kotlin
  /** A project tab was chosen: a new session shows [hostPath], as after the widget's popup. */
  fun showProject(hostPath: String) = onRd {
    if (hostPath == mutableState.value.shownProject || hostPath == switchedTo) return@onRd
    switchedTo = hostPath
    log("Project switch to $hostPath (tab)")
    onProjectSwitch(hostPath)
  }

  /** A project tab's close button: the backend's Close Project action for that project. */
  fun closeProject(hostPath: String) = onRd {
    val closing = openSolutions[hostPath] ?: return@onRd log("No open project at $hostPath")
    actionMenus?.runAction(
      "CloseProject",
      "MainMenu",
      ActionTimestampSetModel(listOf(ProjectTimestampModel(closing.projectId))),
    ) {
      log("CloseProject is disabled for $hostPath")
    }
  }
  ```

- When the shown project is closed (`solutionLifetime.onTermination` in `bindSolution`, where
  `solution === newSolution`) and `openSolutions` still has another entry, call
  `onProjectSwitch(<the first remaining host path>)` and set `switchedTo` to it. Check live what
  the backend does first: it may add the welcome project (IntelliJ IDEA Home) when the last real
  project closes. Write what was seen into an addendum.

### 3. The tab bar

New file `compose/src/commonMain/kotlin/io/github/safeuq/ideate/client/ui/ProjectTabs.kt`:

```kotlin
/** Each tab's width for [count] tabs in [total]: equal, the last takes the remainder. */
internal fun projectTabWidths(total: Int, count: Int): List<Int>

@Composable
fun ProjectTabs(
  projects: List<OpenProject>,
  shown: String?,
  onShow: (hostPath: String) -> Unit,
  onClose: (hostPath: String) -> Unit,
  modifier: Modifier = Modifier,
)
```

- A `Row` 30 dp high, full width, background `Islands.windowBackground`; each tab gets its width
  from `projectTabWidths` (a custom `Layout` or `Modifier.weight(1f)` with the same result).
- Tab: every value from the table above. Hover with
  `MutableInteractionSource().collectIsHoveredAsState()`. The close icon is drawn only while the
  tab is hovered; it must not move the centred text (overlay it in a `Box`). Click on the tab →
  `onShow(hostPath)`; click on the close icon → `onClose(hostPath)`.
- Text: `LocalClientFonts.current.label()` at 12 sp, `maxLines = 1`,
  `overflow = TextOverflow.Ellipsis`, centred inside the content insets (left 4, right 15).
- New tokens in `ui/theme/Islands.kt`, each `color("<key>")` with the fallback from the table when
  the theme has no value (look at how `color` treats a missing key and add a fallback form if it
  has none): `projectTabSelectedBackground`, `projectTabHoverBackground`, `projectTabSeparator`,
  `projectTabForeground`, `projectTabSelectedForeground`, `projectTabHoverForeground`.

File `ui/ClientScreen.kt`: in the `Column(Modifier.fillMaxSize())` that starts with
`MainToolbar(…)`, directly after the `MainToolbar` call:

```kotlin
if (state.openProjects.size > 1)
  ProjectTabs(state.openProjects, state.shownProject, session::showProject, session::closeProject)
```

### 4. The switch never says "Trying to reconnect"

File `connect/ConnectController.kt`, where `StartProgress("Opening Project", project, …)` is
shown: drop `&& attemptNumber == 1`, so every try of a switch says "Opening Project / beta".
(`switching` is cleared when the session is accepted or ended.)

### 5. Test

`compose/src/jvmTest/kotlin/io/github/safeuq/ideate/client/ui/ProjectTabsTest.kt` (or next to the
existing UI tests): `projectTabWidths(1000, 3) == listOf(333, 333, 334)`,
`projectTabWidths(900, 2) == listOf(450, 450)`, `projectTabWidths(10, 0) == emptyList()`.

### 6. Live check

Backend on port 5997 with two projects (`alpha`, `beta`), as in docs/plans/project-widget-popup.md.
Check and screenshot: the bar appears under the toolbar with two tabs, the shown project's tab is
the selected one; a click on the other tab switches (tree, toolbar name and selected tab change);
hover shows the hover fill and the close icon; the close icon closes that project and its tab goes;
with one project left the bar is gone. Before/After shots go to the shared artifact entry.

## Stage 2: one connection for every open project

Goal: both projects stay live; a tab click only changes which one is on screen. No new connection,
so nothing can be refused.

What `ClientSession.createModels` binds today, and where it has to live then:

| Bound once per connection (shared) | Bound per project |
|---|---|
| `Connection`, `SplitWire`, `Protocol`, the rd `SingleThreadScheduler` | `solution`, `projectId`, `shownSolutionLifetime` |
| `highlighterRegistrationHostModel.registry` and the colour schemes | the Project view (`projectViewModel`, `pane`, `projectViews`), `SessionState.tree` |
| `documentsOperationModel`: `documents`, `requestResponseModel` (`backendPatches`, `responsesWithTime`), the request id sequence (`requestIds`, `retryTimestamp`) | open editors and tabs (`compositeIds`, `tabOrder`, `editSessions` of this project's documents), `OpenEditors.Slot` |
| `fleetRpcModel` (`FleetRpc`: one kernel connection) | run widget (`RunToolbar`), project widget (`ProjectWidget`), tool windows (`toolWindows`, `backendViews`), status bar widgets, breadcrumbs, structure, usages, consoles, build trees, terminals |
| `vfsModel`, `clipboardModel`, `shellModel` (deferred icons) | `SessionState` as a whole: one per project |
| `UIAutomationInteractionModel.activePopups`, Lux dialogs: they belong to the project whose action opened them | |

Design to work out in its own detailed plan before any code:

1. A `BackendConnection` class owns the left column and outlives project switches.
2. A project-level class (today's `ClientSession` minus the left column) is made per entry of
   `solutions`, each with its own `SessionState`.
3. Documents, patches and responses are routed to the project that owns the document. To find out
   first: how a document or text control names its project (`RdDocumentId`, `TextControlId`,
   `RdMarkupKey(projectId)`), and which Fleet RPC services take a project id.
4. `ConnectController` keeps the connection and a map host path → project session; the screen
   shows the chosen one. `switchProject`, `SWITCH_WAIT_MILLIS` and `SplitWire.terminate`'s use in a
   switch go away. A real connection loss reconnects once and binds every project again.
5. The hand-over (`session/HandOver.kt`) then works per project.

This touches most of `ClientSession.kt` (about 2 300 lines) and `ConnectController.kt`. It is done
in steps that each keep one project working, with the full test suite and a live check per step.

## Addendum (2026-11-24, 16:35 IST): Stage 1 built and checked live

Built as planned: `OpenProject`, `SessionState.openProjects` and `shownProject`;
`ClientSession.openSolutions`, `showProject`, `closeProject`; `ui/ProjectTabs.kt` with
`projectTabWidths` and `ProjectTabs`; six `Islands.projectTab*` tokens; the bar under
`MainToolbar` in `ClientScreen`; "Opening Project" on every try of a switch; `ProjectTabsTest`.

Small differences from the plan:

- `Islands.color(key)` throws on a missing key, so two private fallback forms were added:
  `color(key) { fallback }` and `color(key, light, dark)` (e.g. `0xFFD3D5DB` / `0xFF43454A` for
  the separator), chosen by `LafManager.currentTheme.isDark`.
- Tab widths come from `BoxWithConstraints` and `Modifier.width` per tab, not a custom `Layout`.
- The hovered tab is kept in the bar (`hoveredPath`), because the separator rule needs to know
  whether the next tab is hovered.
- When the shown project's lifetime ends, the switch goes to the first other entry of
  `openSolutions` (the shown one is filtered out, since its own `onTermination` runs before the
  removal registered earlier).

Live check, backend on port 5997 with `alpha` and `beta` (1200x800 window, dark theme):

| Check | Result |
|---|---|
| Bar under the toolbar, two tabs, shown project selected | pass: `alpha` selected (rounded `ToolWindow.background` fill), `beta` plain, each half the width |
| Click on the other tab | pass: log `Project switch to …/beta (tab)`, tree, widget (`beta`) and run widget changed, `beta` tab selected; no "Trying to reconnect" |
| Hover | pass: the hovered unselected tab gets the grey hover fill and `closeSmall` 6 dp from its left; text stays centred |
| Close icon on the shown project (`beta`) | pass: `Running CloseProject`, then `Backend closed project …` (the backend sends `closeRequested`, the entry leaves `solutions`), then `Shown project closed; switching to alpha`; the new session shows `alpha` and the bar is gone (one project left) |

What the backend did when the shown project closed: it closed only that project and kept the
connection; `alpha` stayed in `solutions`. The old session logged
`FAILED: java.lang.IllegalStateException: Connection closed` while it was torn down for the switch;
the new session came up normally half a second later.

Not checked: closing the last project. With one project there is no tab bar, and the project
widget's popup (New Project, Open, Clone Repository, IntelliJ IDEA Home, Open Projects, Recent
Projects) has no Close Project entry, so Stage 1 has no way to close it from Ideate. Whether the
backend then adds the welcome project (IntelliJ IDEA Home) is still open. The welcome project, if
open next to a real one, would also get a tab today (its host path is listed like any other).

## Addendum (2026-11-24, 11:55 IST): the bar's look corrected; phone layouts switch by a slide

Correction to the table above: the Islands themes do set `MainWindow.Tab` keys. Islands Dark
(`ManyIslandsDark.theme.json`) has `foreground: text-muted`, `selectedForeground: text-default`,
`selectedBackground: tool-window-bg`, `hoverBackground: tab-bg-hovered`,
`separatorColor: toolbar-border`. `Islands.projectTab*` already read these keys first, so the
colours were right; the comment in `Islands.kt` was wrong and is fixed.

What did not match IntelliJ, and is changed in `ui/ProjectTabs.kt`:

| | Before | Now (as IntelliJ) |
|---|---|---|
| bar background | flat `Islands.windowBackground` | none: IntelliJ registers the bar with `registerWindowBackgroundComponent`, so the header's project gradient shows behind tabs that are neither selected nor hovered |
| start inset | 0 | left tool window bar + 1 (`Islands.stripeThickness + 1.dp`) |
| end inset | 0 | right tool window bar + 2 |
| below the tabs | 0 | 2 dp (`JBUI.Borders.empty(0, left, 2, right)`) |

Phone layouts (`ScreenLayout.PORTRAIT` and `ScreenLayout.COMPACT`) have no tab bar; `ClientScreen`
shows `ProjectTabs` only in `ScreenLayout.WIDE`. Projects are switched there by:

- the project widget's popup: choosing a project that is already open shows it (no reopening,
  see `shared-connection.md`);
- sliding sideways on the title bar (`Modifier.projectSwipe(projects, shown, onShow)` given to
  `MainToolbar`): more than 48 dp to the left shows the next project, to the right the previous
  one, as the toolbar of Chrome on Android. `neighbourProject(projects, shown, step)` picks it and
  returns null past either end (`ProjectTabsTest.neighbourStopsAtBothEnds`).

Checked live with alpha and beta at 1200×800 dp (bar) and 420×800 dp (no bar, a slide to the right
went from beta to alpha, a slide to the left on the last project did nothing).

Not done: the page does not move with the finger while sliding (Chrome shows the neighbouring tab
sliding in); the switch happens when the finger lifts. A tablet held upright is `PORTRAIT` too, so
it gets no bar either.

## Addendum (2026-11-24, 12:05 IST): the tab's corner radius is 10 dp, not 5

`paintProjectTab` fills with arc `Island.arc`. The code default is 10, but the Islands themes set
`"Island": { "arc": 20 }` (16 in compact mode; High Contrast 0), and the value is a diameter. So
the corner radius is 10 dp (8 compact), the same as the islands. `ProjectTab` now uses
`Islands.islandRadius` in place of the fixed `RoundedCornerShape(5.dp)`.

## Addendum (2026-11-24, 12:20 IST): on a phone, a project switch slides

On phone layouts (`ScreenLayout.PORTRAIT` and `ScreenLayout.COMPACT`) the project switched to
slides in and the other one slides out, title bar included, as the island pages do when swiped but
edge to edge, with no gap between the two screens. It applies to every switch there: a slide on
the title bar and a project chosen in the project popup.

- `ConnectController.switchProject` sets `switchedForward`: true when the new project comes later
  in `openProjects` than the one shown (it slides in from the right), false otherwise (from the
  left). A slide to the left on the title bar shows the next project, so the screen follows the
  finger.
- `EditorApp` (same file): `BoxWithConstraints` gives the layout with `screenLayout`. In
  `ScreenLayout.WIDE` it calls `ClientScreen` as before (IntelliJ's tabs switch at once). Otherwise
  `ClientScreen` sits in `AnimatedContent(session)` with `slideInHorizontally` and
  `slideOutHorizontally`, both `spring(stiffness = Spring.StiffnessMedium)`: the spring
  `SwipePager` moves its pages with (`Animatable.animateTo`'s default).
- The session sliding out shows its own last state (`shownSession.state`); both screens share
  the tool window state.

Checked live at 420 × 800 dp with alpha and beta, frames taken about 0.1 s into each switch: both
directions show the two screens side by side with no gap.

Known limits:

- The first switch to a project slides in its empty screen ("Connecting…"), because its session is
  made at that moment; from the second switch on its real screen slides in.
- The screen does not follow the finger during the slide on the title bar; it moves when the
  finger lifts.
- `ClientScreen` is now composed once per session on a phone, so what it remembers (the page
  shown, the notice line) starts again on a switch: the project switched to opens on its editor.
  A tablet turned between upright and sideways changes between the two branches, with the same
  effect.

## Addendum (2026-11-24, 12:40 IST): one generic pager for the island pages and the projects

The project screens now follow the finger, and the tool window swipe and the project slide are
the same code: `ui/SlidePager.kt`. `SwipePager` in `ClientScreen.kt` and the `AnimatedContent`
slide in `EditorApp` are gone.

`SlidePager<K>(current, neighbour, order, onShow, canSlide, modifier, keep, alwaysRounded, page)`:

| Parameter | Meaning | Island pages (`ClientScreen`, upright phone) | Projects (`EditorApp`, phone layouts) |
|---|---|---|---|
| `K`, `current` | the page's key | `Page.LEFT`, `Page.MIDDLE`, `Page.RIGHT` | the project's `ClientSession` |
| `neighbour(step)` | the page on the left (`-1`) or right (`1`) of `current`, or null | a side page when `windows.canShow(side)` | `neighbourProject(...)` then `ConnectController.projectSession(hostPath)` |
| `order(K)` | lower is further left; the side a page comes in from when `current` changed without a drag | `ordinal` | index in `openProjects` |
| `onShow(K)` | a drag ended on that page | sets `page` | `ConnectController.showProject(hostPath)` |
| `canSlide(down, dragX)` | may this drag slide the pages; `down` in window px | false while the content can scroll that way | true when the drag starts on the title bar (`ProjectSwipeArea`) |
| `keep` | pages composed while off screen | all three | none |
| `alwaysRounded` | gap and corners at rest too | a page with two islands | never |
| `page(key, radius)` | the content; `radius` is 0 at rest and `Islands.islandRadius` while sliding | gives it to the islands (`LocalIslandRadius`) | clips the whole project screen with it |

What every user of it gets: the pages follow a sideways drag; while a finger drags or a page still
moves, the pages are `Islands.islandGap` apart and `radius` is on; a drag past a third of the
width, or a flick faster than 1000 px/s, moves one page, anything less springs back; a change of
`current` made elsewhere (the toolbar's back button, a project chosen in the project popup)
slides the same way.

How it places pages: `PagerSlots` gives a slot (an Int, one screen apart) to the pages on screen
only: the current page, and the one sliding in or out. A page the finger brings in takes the slot
beside the current one (`placeBeside`), and keeps it when it becomes current (`show`), so nothing
jumps at that moment; a page shown from elsewhere takes the slot on the side `order` says. The
screen's place is one `Animatable` in slots. Tests: `PagerSlotsTest`.

For the projects, on top of that (`EditorApp`):

- The backdrop is `Islands.islandBackground`, and a sliding project screen has a 1 dp outline in
  `Islands.controlBorder` that fades in and out with the corners. The backdrop alone is nearly the
  editor's colour, so without the outline the gap did not show between two editors.
- The tool windows inside a project screen get no gap or corners from this slide.
- `MainToolbar` reports the title bar's top and bottom into `LocalProjectSwipeArea`; the pager
  takes drags that start between them. The old `Modifier.projectSwipe` (switch when the finger
  lifts) is removed.
- `ConnectController`: `sessionFor(current, hostPath)` (taken out of `switchProject`) finds or
  makes a project's session without changing the screen; `projectSession`, `projectPath` and
  `showProject` are what the pager calls. `switchedForward` is gone (`order` replaces it).

Checked live at 420 × 800 dp with alpha and beta: the island pages still slide between Project
and the editor; a drag on the title bar slides between alpha and beta both ways with the gap, the
corners and the outline; at rest there is no gap, corner or outline.

Limits:

- A neighbouring project that has no session yet gets one when a drag first goes towards it, so
  its empty screen ("Connecting…") is what slides in the first time.
- The live check used the window tool's `drag`, which presses, moves and lifts in one step: the
  frames show the pages between the two places after the finger lifted, not a finger held half
  way. Not tried on a phone.

## Addendum (2026-11-24, 12:47 IST): a sliding project screen keeps its width

The user saw the project screen get narrower while it slid: `SlidePager` padded every page by
the gap on both sides (`padding(horizontal = gap)`), so a 420 dp screen was laid out 404 dp wide
during the slide and its content moved.

`SlidePager` has a new parameter `inset: Boolean = true`:

| `inset` | Page width while sliding (420 dp screen, 8 dp gap) | Distance between pages | Used by |
|---|---|---|---|
| `true` | 404 dp (the gap is taken out of the page on both sides) | 412 dp | the island pages in `ClientScreen` |
| `false` | 420 dp (the page keeps its size; the gap stands outside it) | 428 dp | the projects in `EditorApp` |

The outline of a sliding project is still drawn over the outer 1 dp of the screen, not outside
it: the pager clips to its bounds and a page is as tall as the pager, so an outline outside the
page would never show along the top and the bottom. It takes no room, so it resizes nothing.

Checked live at 420 × 800 dp with alpha and beta: mid-slide, alpha's search icon stands 19 dp
from alpha's right edge, the same as at rest, and the gap, the corners and the outline show. The
island pages are unchanged; the user asked for a preview before they change too.

## Addendum (2026-11-24, 12:58 IST): correction to the widths above

The 12:47 addendum used an 8 dp gap. `Islands.islandGap` is 6 dp (4 dp in compact mode), so on a
420 dp screen the numbers are:

| `inset` | Page width while sliding | Distance between pages |
|---|---|---|
| `true` | 408 dp (was written as 404) | 414 dp (was written as 412) |
| `false` | 420 dp | 426 dp (was written as 428) |
