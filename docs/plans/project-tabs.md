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
