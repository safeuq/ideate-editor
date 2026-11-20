# Status bar

IntelliJ's new UI status bar at the bottom of the IDE screen: the breadcrumbs on the left, the info text or
the newest background task in the middle, the backend's widgets on the right, and the Processes popup. Found in
IntelliJ 263.7307.200's sources and the JetBrains Client jars (decompiled), then checked against the Mac test
backend (2026-11-20). Code: `session/StatusBarWidgets.kt`, `session/Breadcrumbs.kt`, `session/BackgroundTasks.kt`,
`ui/StatusBar.kt`.

## 1. Widgets: `solution.statusBarModel.id2Widgets`

A map of widgets by id. JetBrains Client's `FrontendStatusBarModelListener` adds each to its status bar with
`statusBar.addWidget(widget, model.anchor)`. With `App.java` open on the Mac backend:

| Id | Kind | Shows | Anchor |
|---|---|---|---|
| `Position` | `PredefinedWidgetModel` + `TextWidgetPresentationModel` | `1:1`, tooltip "Go to Line" | `ANY` |
| `LineSeparator` | `CustomWidgetModel`, content `BeTextPanel` | `LF` | `after Position` |
| `Encoding` | `CustomWidgetModel`, `BeTextPanel` | `UTF-8` | `after LineSeparator` |
| `CodeStyleStatusBarWidget` | `CustomWidgetModel`, `BeGrid` of `BeLabel` + `BeTextPanel` | `2 spaces` | `after InsertOverwrite` |
| `ReadOnlyAttribute` | `PredefinedWidgetModel` + `IconWidgetPresentationModel` | `expui/general/unlocked.svg`, tooltip "Make file read-only" | `after InsertOverwrite` |
| `FatalError` | `CustomWidgetModel`, `BeButton` | IDE errors, only when there are some | `after Notifications` |

Also sent, empty here: `GridAggregator`, `GridPosition`, `webDeployment.default.server.widget`,
`LanguageServiceStatusBarWidget`, `InsertOverwrite`, `JSONSchemaSelector`, `largeFileEncodingWidget`. A widget
with no text and no icon is hidden, as IntelliJ hides its empty component.

**Order.** Anchors are IntelliJ's `LoadingOrder` words: `first`, `last`, `before X`, `after X`, `ANY`, joined
with commas. IDEate orders the widgets it has by them; an anchor naming a widget that isn't there
(`after PowerSaveMode`) is ignored, and arrival order breaks ties (`orderByAnchors`, `StatusBarTest`).

**Clicks**, as the client sends them:

| Widget | Sent |
|---|---|
| text or icon (`WidgetPresentationModel.click`) | `MouseEventModel(id = 500 (MOUSE_CLICKED), modifiers = 0, x, y, clickCount = 1, button = 1, ComponentRect(-1,-1,-1,-1) twice, componentId = null)` |
| several values (`MultipleTextValuesWidgetPresentationModel.showPopup`) | the widget's bounds as `RectangleModel` |
| custom (`CustomWidgetModel.click`) | the widget's bounds as `RectangleModel` |

The backend answers with its own UI: Position opens the "Go to Line:Column" dialog (Lux), the others their
backend popups.

## 2. Breadcrumbs: `solution.navigationBarModel`

JetBrains Client's `ThinClientNavBarModelExtension` calls `getNavBarElements(ActionTimestampSetModel)` with
`ActionTimestampModelProvider.createTimestampModelSet("", dataContext)`: the focused editor's context.
With only the project as context the answer is empty. IDEate sends the project plus
`EditSession.actionTimestamps()` (editor, caret, selection). The answer goes from the caret out; IDEate shows
it from the project in:

```
getNavBarElements → [App (expui/nodes/class.svg), demo, java, main, src, app, gradle-smoke (expui/nodes/folder.svg)]
bar               → gradle-smoke › app › src › main › java › demo › App
```

`NavBarElementModel`: `nodeId`, `text`, `icon`, `hasContainingFile`, `canNavigate`, `path`, `presentableUrl`.
IDEate asks again when the backend fires `updateNavBar`, when the selected editor changes, and 300 ms after the
caret rests. A tap on a crumb lists its children (`getChildren(nodeId)`). A chosen item inside a file
(`hasContainingFile`) opens (`navigate(NavBarNavigateArgs(nodeId, requestFocus = true))`); a folder lists its
own children in the same place.

Look (`platform/navbar/frontend/src/ui`): status bar font, a `chevronRight` before every crumb but the first,
2/4 dp around icon and text (`StatusBar.Breadcrumbs.itemBackgroundInsets`), 4 dp icon-text gap.

## 3. Background tasks: Fleet RPC `TaskInfoApi`

`com.intellij.platform.ide.progress.rpc.TaskInfoApi` (platform/progress/shared/src/rpc). `activeTasks()` streams
events; `cancelTask`, `pauseTask` and `resumeTask` take `taskId`. Recorded from the Mac backend:

```
{"type":"…rpc.TaskInfoEvent.TaskAdded","taskId":1,"projectId":{"id":"jiebkhombgkqbk38rv03"},"title":"Scanning files…",
 "cancellation":{"type":"…NonCancellableTaskCancellation"},"suspension":{"type":"…TaskSuspension.NonSuspendable"},
 "status":{"type":"…TaskStatus.Running","source":"SYSTEM"},"visibleInStatusBar":false,"ownerKind":"PROJECT"}
{"type":"…rpc.TaskInfoEvent.ProgressChanged","taskId":1,"state":{"type":"…StepState","fraction":0.99,
 "text":"Scanning files…","details":"Scanning dependencies"}}
{"type":"…rpc.TaskInfoEvent.TaskRemoved","taskId":1}
```

The bar follows IntelliJ's `InfoAndProgressPanel` in its default counter mode (registry
`progresses.show.counter.icon.instead.of.show.link` = true); `inlineProgress` in StatusBar.kt and
`InlineProgressTest` (ideate-editor, 2026-11-20):

- Which task (`setInlineProgressByWeight`): the tasks with `visibleInStatusBar` first, in the order they
  started, then the first one not paused. The backend sends no `statusBarIndicatorWeight`, so the start
  order decides. A chosen task without `visibleInStatusBar` isn't drawn; only the count shows.
- The task (`ProgressComponent`, compact): its text, or its title when it has none; the 104 dp bar in a
  wrapper with 8 before and 4 after; a 2 gap; then pause or resume, and × when it can be cancelled, each with
  2 after. The icons are the new UI's (`PlatformIconMappings.json`: `process/progressPauseSmall` →
  `expui/progress/pause.svg`, a thin ‖ in a faint circle; `process/stopSmall` → `expui/progress/stop.svg`).
  The old `process/progressPauseSmall.svg` is two thick bars.
- The counter (`CounterLabel`): "+2" after the task, "3" without it, nothing for a single task. It always
  takes 4 plus the width of the widest "+0" to "+9", so the bar doesn't move as the count changes.
- Around it: `InlineProgressPanel`'s widget insets, 8 at each side, and `InfoAndProgressPanelImpl`'s wrapper,
  40 before and 4 after. With one task and no widgets, IntelliJ's bar therefore leaves the counter's empty
  width plus 12 after the pause button.
- While the Processes popup is open the task hides and "Hide processes (N)" shows in its place.
- Not drawn: the spinner (`AsyncProcessIcon`) beside a count without a task, and the hover fill.

A tap opens the Processes popup
(`ProcessPopup`): 300 to 500 dp wide over the bar at the right, a row per task with title, full-width bar and
button, and text and details below. A Gradle sync ("Importing 'gradle-smoke' Gradle Project") showed in both.

## 4. Look and layout

`IdeStatusBarImpl` (new UI): 10 dp at the bar's sides, no top border in Islands Dark
(`StatusBar.topBorderWidth` 0). Widgets: 6 dp above and below, 8 dp at the sides
(`StatusBar.Widget.widgetInsets`), text-muted `#9FA2A8`, text-default `#D1D3D9` on hover with
toolbar-bg-hovered `#FFFFFF17` inset 3, 4, 1, 4 (`hoverInsets`). Bars: track control-bg-small `#40434A`,
fill control-brand-bg `#3871E1`. Sizes are the same for mouse and touch.

A phone held upright (decided 2026-11-20) gets one row that scrolls sideways: the breadcrumbs (at most 220 dp,
their end in view), the newest task's short bar with "+N" (no title), Position, then the other widgets. It
shows a notice or failure as info text, not the last log line, which the desktop keeps as a debugging aid.

Checked: `DesktopDriverTest.statusBar` (1200 × 800 and 411 × 860) against the Mac backend with a small Gradle
project; on the emulator, a tap on a crumb listed its children and a tap on `1:1` opened Go to Line.

## Not done

- IntelliJ's own client-side widgets: the memory indicator ("2222 of 7100M"), notifications.
- `FatalError`'s button is shown as text only if it has some; IDE errors aren't listed yet.
- Breadcrumbs' right-click menu and keyboard navigation.
