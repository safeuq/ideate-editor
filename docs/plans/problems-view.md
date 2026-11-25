# Plan: the Problems tool window and problems in the editor

Pending row 44 (intellij-backend-android `docs/pending.md`). Written 2026-11-26 by the
`intellij-fable` session. Nothing here is built yet.

## Goal

1. The **Problems** tool window with its **File** tab (problems of the file in the editor) and its
   **Project Errors** tab.
2. In the editor: marks on the scrollbar for each problem (IntelliJ's error stripe), the problem's
   text when the pointer rests on it, and the counter at the editor's top right corner (IntelliJ's
   inspection widget, "traffic light").

Wave underlines for errors and warnings are already drawn (`drawEffect` in `ui/EditorView.kt`,
`TextEffect.WAVE_UNDERSCORE`), so they are not part of this plan.

## What JetBrains Client does (decompiled, build 263.7307.200)

Sources are the client's own jars, decompiled with IntelliJ's Fernflower. Class names below are the
ones to mirror.

### The tool window is drawn by the client, not sent by the backend

The registry key `problems.view.split.enabled` is `true` by default on both sides:

| Where | Declaration |
|---|---|
| JetBrains Client | `intellij.frontend.split.customization.jar!META-INF/JetBrainsClientPlugin.xml`: `<registryKey key="problems.view.split.enabled" defaultValue="true" description="Enable split implementation of File, Project Errors and Code Analysis tabs in Problems View. Key needs to be set in both client and host."` |
| IntelliJ IDEA Ultimate (the backend) | `intellij.idea.ultimate.customization.jar!META-INF/plugin.xml`, same line |

With the key on, `ProblemsViewToolWindowContentReplacer` (`intellij.platform.problemView.frontend.jar`)
replaces the content of each tab of the tool window with id `Problems View` by a client-side panel,
picked by tab name through `FrontendProblemsViewContentProvider.matchesTabName`:

| Tab (bundle key, text) | Client panel | Where its rows come from |
|---|---|---|
| `problems.view.highlighting` = "File" | `FrontendHighlightingPanel` extends `HighlightingPanel` (id `CurrentFile`) | the highlighters the client already has for the selected editor's document |
| `problems.view.project` = "Project Errors" | `FrontendProjectErrorsPanel`, root `FrontendProblemsViewProjectErrorsRoot` | Fleet RPC `ProblemsViewApi.getProjectErrorsFlow` |

The backend's tool window list calls it id `Problems View`, title `Problems`, icon
`expui/toolwindows/problems.svg`, position BOTTOM (`docs/tool-windows.md`).

### File tab: problems are the document's highlighters that carry a `HighlightInfo`

`ProblemsViewHighlightingWatcher` (`intellij.platform.problemView.ui.jar`) listens to the document's
markup model and turns each highlighter into a `HighlightingProblem` when `isValid` holds:

```
info = HighlightInfo.fromRangeHighlighter(highlighter)
valid = info != null && info.description != null && info.severity.myVal >= level
        && !ProblemViewSuppressor.isSuppressedInProblemsView(highlighter)
```

On the wire the same data is `DefaultHighlighterModel.info: HighlightInfoModel?`
(`intellij.rd.ide.model.generated.jar`), in the per-project document markup Ideate already reads
(`docs/split-protocol.md`, "Highlighting in the editor"):

| `HighlightInfoModel` field | Type | Example |
|---|---|---|
| `type.severityId` | `String` | `ERROR`, `WARNING`, `WEAK WARNING`, `INFORMATION` |
| `type.severityVal` | `Int?` | IntelliJ's `HighlightSeverity.myVal`: ERROR 400, WARNING 300, WEAK WARNING 200, INFORMATION 10 |
| `type.attributesKey` | `TextAttributesKeyModel` | `ERRORS_ATTRIBUTES` |
| `description` | `String?` | `Cannot resolve symbol 'Strin'` |
| `tooltip` | `String?` | the same as HTML, `<html>Cannot resolve symbol 'Strin'</html>` |
| `inspectionId`, `toolId` | `String?` | `UnusedDeclaration` |
| `suppressOnErrorStripe` | `Boolean` | |
| `hasQuickFixes` | `Boolean` | |

The severities themselves (name, order, icon, count text) come once on
`HighlighterRegistrationHostModel.severities` as `HighlightSeverityModel(id, value, icon,
displayName, displayCapitalizedName, countMessageTemplate)`; Ideate ignores it today
(`docs/split-protocol.md`: "also gets `demoTextTagsMap`, `colorSettingsPage` and `severities`;
ignored").

How a row looks and sorts:

| What | Rule | Source |
|---|---|---|
| File row | file icon, name, then grey `  <parent folder, ~ for home>`, then grey `  N problems` (`problems.view.file.problems`: "1 problem" / "N problems") | `FileNode.update` |
| Problem row | severity icon, text, then grey ` :<line + 1>` | `ProblemNode.update` |
| Problem text | `info.description`; when it has a line break and does not start with `<html>`, only the first line plus `…` | `HighlightingProblem.getText` |
| Order with "sort by severity" on (the default) | severity value high to low, then line, then column, then text (natural compare) | `ProblemsViewNodeComparator.compareI`, `comparePosition` |
| Empty tree | no editor: "Open file in editor to see problems"; analysis running: "Analyzing <file name>"; none: "No problems in <file name>" | `HighlightingPanel`, `ProblemsViewBundle.properties` |
| Stripe button icon | `expui/toolwindows/problemsEmpty.svg` with no problems, `expui/toolwindows/problems.svg` with some | `ProblemsViewIconUpdater.getIcon` (`AllIcons.Toolwindows.ToolWindowProblemsEmpty` / `ToolWindowProblems`) |

### Project Errors tab: one Fleet RPC flow

`com.intellij.platform.problemsView.shared.ProblemsViewApi` (`intellij.platform.problemView.shared.jar`):

| Method | Arguments | Returns |
|---|---|---|
| `getProjectErrorsFlow` | `projectId: ProjectId` | `Flow<List<ProblemEventDto>>` |
| `loadQuickFixes` | `projectId`, `fileId: VirtualFileId`, a `Long` | `QuickFixModelDto?` |
| `executeQuickFix`, `discardQuickFixModel` | `projectId`, `quickFixModelId` | `Unit` |

`ProblemEventDto` is `Appeared`, `Disappeared` or `Updated`, each carrying a `ProblemDto`:

| DTO | Fields |
|---|---|
| `FileProblemDto` | `id: String`, `text: String`, `description: String?`, `icon: IconId`, `filePath: String`, `fileId: VirtualFileId`, `line: Int`, `column: Int` |
| `GenericProblemDto` | `id`, `text`, `description`, `icon` |

Empty text: `problems.view.project.empty` = "No errors found by the IDE".

### The counter at the editor's corner

`FrontendTrafficLightRenderer` (`intellij.rd.client.jar`, extends IntelliJ's `TrafficLightRenderer`)
takes the analysis state from the backend's daemon model for the editor: `state:
BackendDaemonStateEnum` (one value is `HIDDEN`), `highlightingProgressState:
BackendHighlightingProgressStateEnum`, `message`, `heavyProcessType`, and a `restart` signal. The
error counts per severity are counted on the client from the same highlighters
(`TrafficLightRenderer.errorCounts`).

### Not in IntelliJ: gutter marks for problems

Pending row 44 mentions "gutter marks". IntelliJ puts no icon in the gutter for an error or
warning; the gutter has line markers (run, override), which Ideate already draws. So this plan adds
none. To be confirmed with the user when the row is reported.

## Design

Names follow IntelliJ. All paths are under
`compose/src/commonMain/kotlin/io/github/safeuq/ideate/client/`.

### Phase 1: carry the problem with each highlight (session)

| File | Change |
|---|---|
| `session/SessionState.kt` | New `@Serializable data class HighlightInfo(severityId: String, severity: Int, description: String?, toolTip: String?, toolId: String?, inspectionId: String?, hasQuickFixes: Boolean, suppressOnErrorStripe: Boolean)`. `Highlight` gets `val info: HighlightInfo? = null`. New `@Serializable data class HighlightSeverity(id: String, value: Int, iconPaths: List<String>, displayName: String, countMessageTemplate: String?)`. `SessionState` gets `val severities: List<HighlightSeverity> = emptyList()`, sorted by `value`, highest first |
| `session/ClientSession.kt` | `RawHighlight` gets `info: HighlightInfo?`, filled in `HighlighterElement.toRaw()` from `(model as? DefaultHighlighterModel)?.info`; `severity` is `type.severityVal ?: 0`. The place that turns `RawHighlight` into `Highlight` passes `info` through. Where `HighlighterRegistrationHostModel` is created, advise `severities` and publish the list into `SessionState.severities` (icons through the existing `iconPaths`) |
| `session/ProblemsView.kt` (new) | Pure code, no rd types: `data class HighlightingProblem(highlightId: Long, offset: Int, line: Int, column: Int, text: String, description: String, severity: Int, severityId: String, hasQuickFixes: Boolean)`; `fun highlightingProblems(file: EditorFile, level: Int): List<HighlightingProblem>` applying `ProblemsViewHighlightingWatcher.isValid` (description not null, `severity >= level`) and `HighlightingProblem.getText`; `val ProblemsViewNodeComparator: Comparator<HighlightingProblem>` as in the table above. `line` and `column` are 0-based, counted in `file.text` |

To confirm while building, and write into `docs/split-protocol.md` in the same commit:
- the `level` the File tab passes: read `ProblemsViewHighlightingFileRoot` in the decompiled
  `intellij.platform.problemView.ui.jar` (the constructor call of `ProblemsViewHighlightingWatcher`);
- the exact property type of `HighlighterRegistrationHostModel.severities` (`javap` on the
  generated jar);
- with `CLIENT_HIGHLIGHT_DUMP`, the `info` of a real error and a real warning from a scratch backend.

Check: a JVM test `ProblemsViewTest` in `compose/src/jvmTest` for `highlightingProblems` (filter,
first-line text with `…`, `<html>` text kept whole) and the comparator (ERROR before WARNING, then
line, column, text).

### Phase 2: the tool window with the File tab (UI)

| File | Change |
|---|---|
| `ui/ToolWindows.kt` | `val PROBLEMS = ToolWindow("Problems View", "Problems", "expui/toolwindows/problems.svg", Place.BOTTOM_LEFT)` next to `PROJECT`, `STRUCTURE`, `TERMINAL`; added to the `own` list, so the window exists even before the backend lists it. The stripe icon is `problemsEmpty.svg` while the selected file has no problems |
| `ui/ProblemsToolWindow.kt` (new) | `@Composable fun ProblemsToolWindow(state: SessionState, session: ClientSession)`. Tabs "File" and "Project Errors" in the header, built the way the Terminal's tabs are. File tab = `HighlightingPanel`: one file row (always expanded) and its problem rows, drawn with the same row height, indent, hover and selection as `ProjectTreeView.kt` (reuse its row composable; do not write a second tree row). Double click or Enter on a problem moves the caret to `offset` in the editor and focuses it, through the function the usages popup uses to navigate. Empty texts as in the table above |
| `ui/ClientScreen.kt` | In the `when` that picks a window's content (near line 727 to 782): `window == ToolWindow.PROBLEMS -> ProblemsToolWindow(state, session)`; add `ToolWindow.PROBLEMS.id` to the ids excluded from backend-drawn windows (near line 270) |

Sizes: nothing new. Rows stay at the tree's height (no extra padding for touch); the grey parts use
the tree's grey (`SimpleTextAttributes.GRAYED_ATTRIBUTES` equivalent already in `ProjectTreeView`).
Severity icons come from `SessionState.severities[..].iconPaths`.

Check, live on a scratch backend (port 5997, `-Didea.project.path=<scratch folder>`), with a Java
file that has one error and one warning: the File tab lists both, error first; fixing the error in
the editor removes its row; double click moves the caret. Compare the rows side by side with
IntelliJ's own Problems window for the same file. Before/After shots.

### Phase 3: error stripe, hover text, counter (UI, editor)

| Part | Change |
|---|---|
| Error stripe | `session/EditorScheme.kt`: `TextStyle` gets `errorStripeColor: Long?` from `IdeaTextAttributes.errorStripeColor`. `ui/EditorEffects.kt` (or a new `ui/ErrorStripe.kt` if it passes 100 lines): marks at the editor's right edge for each highlight with `info != null`, `!info.suppressOnErrorStripe` and a stripe color, placed by line as IntelliJ's `ErrorStripeMarkersModel` / `EditorMarkupModelImpl` do. Mark width, minimum height and the gap rules are read from `EditorMarkupModelImpl` in intellij-community and recorded in `docs/intellij-look.md` before coding. A click on a mark moves the caret there |
| Hover text | Pointer resting on a highlight with `info.toolTip` shows it in the existing tooltip component (`ui/component/HelpTooltip.kt`, `HtmlText.kt`), highest severity first when ranges overlap. Desktop only for now; the phone has no hover |
| Counter | New rd binding for the editor's daemon model (`FrontendDaemonModel`: `state`, `highlightingProgressState`, `message`) into `EditorFile`; a small widget at the editor's top right with one icon and count per severity that has problems, a tick when there are none, as `TrafficLightRenderer` shows. Needs a trace of the real client first to see where the daemon model is bound |

Phase 3 is three separate commits in that order; each has its own Before/After.

### Phase 4: Project Errors tab

| File | Change |
|---|---|
| `session/ProblemsView.kt` | `class ProjectErrors`: subscribes to `com.intellij.platform.problemsView.shared.ProblemsViewApi` / `getProjectErrorsFlow(projectId)` through `FleetRpc.subscribe`, as `session/SearchEverywhere.kt` does for its flows; keeps a map `id -> ProblemDto` updated by `Appeared`, `Updated`, `Disappeared`; publishes `SessionState.projectErrors: List<ProjectProblem>` (`FileProblemDto` → file path, line, column; `GenericProblemDto` → text only). Subscribes when the tab is first shown, as `ToolWindowLazyContent` does |
| `ui/ProblemsToolWindow.kt` | Project Errors tab: file rows grouping their problems, generic problems at the root; empty text "No errors found by the IDE"; double click opens the file at line and column |

The JSON shape of `ProblemEventDto` (kotlinx.serialization class discriminator, `IconId`,
`VirtualFileId`) is taken from a trace of the real client and written into a new section of
`docs/tool-windows.md` before coding.

### Left out, as later rows

Quick fixes (`loadQuickFixes`, `executeQuickFix`), the toolbar (sort, severity filter, preview), the
context menu (`ProblemsView.Frontend.TreePopup`), the "Code Analysis" tab, and group by tool.

## Standing rules for whoever builds a phase

- Never touch ports 5993 or 5996 or the user's own IntelliJ; kill only by exact PID; stop every
  backend and app you started.
- A detached worktree from `origin/main`; author "Safeuq Mohamed", no Claude lines; rebase and push
  to `main`.
- Every finding goes into the docs in the same commit, as a dated addendum; `docs/pending.md` row 44
  is updated with each phase; UI phases add Before/After shots to the shared page.
- No abbreviated identifiers, `final`-style immutability (`val`), fail fast on missing input.
- Keep IntelliJ's names: `HighlightInfo`, `HighlightSeverity`, `HighlightingProblem`,
  `ProblemsViewNodeComparator`, `HighlightingPanel`, `ErrorStripe`, `TrafficLightRenderer`.

## Addendum (2026-11-25, 16:10 IST): phase 1 built, two corrections

Phase 1 is built (`Highlight.info`, `SessionState.severities`, `session/ProblemsView.kt`,
`ProblemsViewTest`). Two things above were wrong:

- `INFORMATION` is 10, not as the table implies the lowest listed value: `TEXT ATTRIBUTES` is 11 and
  the File tab's level is 12, so `INFORMATION` highlighters are not listed.
- `severities` is on `DefaultRegistryModel`, not on `HighlighterRegistrationHostModel`.

`highlightingProblems` takes `(text, highlights, level)` instead of an `EditorFile`, so the test
needs no editor state. Details in `docs/split-protocol.md`, addendum of the same time.

## Addendum (2026-11-25, 23:40 IST): corrections

The plan's header says it was written 2026-11-26; it was written 2026-11-25. The addendum above headed "16:10 IST" was written at about 23:00 IST on 2026-11-25. Phase 2 (the File tab) is built and checked live: `docs/tool-windows.md`, addendum of 2026-11-25, 23:35 IST. One change from the plan: `ToolWindow.PROBLEMS` is not added to the `own` list in `toolWindowButtons`, because the backend always lists `Problems View`; and the stripe icon does not switch, because the new UI maps the "empty" icon to the same file.

## Addendum (2026-11-26, 00:20 IST): plan for the counter at the editor's corner

Status of the phases above: the File tab, scrollbar marks with their click, the hover text and the
Project Errors tab (rows, red icon, open at the line) are built and checked live. This addendum is
the detailed plan for the counter. Nothing of it is built yet.

### What IntelliJ shows, and from what

Sources read: `TrafficLightRenderer.kt` (`getStatus`, `errorCounts`, `incErrorCount`),
`AnalyzerStatus.kt`, `EditorMarkupModelImpl.kt` (`TrafficLightAction`, its status component,
`LEFT_RIGHT_INDENT = 5`, `INTER_GROUP_OFFSET = 6`), `InspectionsGroup.kt`, and JetBrains Client's
`FrontendTrafficLightRenderer` (decompiled, `intellij.rd.client.jar`).

| Fact | Where | Value |
|---|---|---|
| Counts | `TrafficLightRenderer.incErrorCount` | one per highlighter whose `HighlightInfo.severity` is above `TEXT_ATTRIBUTES` (value 11), per severity. Counted on the client from the document's highlighters; the backend sends no counts |
| Items | `getStatus` | one item per severity with a count above 0, most severe first: the severity's icon and the count as text, tooltip `countMessageTemplate` filled in |
| No items, analysis finished | `getStatus` | one icon, `AllIcons.General.InspectionsOK` (`expui/general/inspections/inspectionsOK.svg`), title "No problems found" (`no.errors.or.warnings.found`) |
| No items, analysis running | `getStatus` | `AllIcons.General.InspectionsEye` with the text "Analyzing…" (`iw.status.analyzing`) |
| Analysis off | `getStatus` | `InspectionsTrafficOff` with "Off" (`iw.status.off`) |
| Analysis paused | `getStatus` | `InspectionsPause` with "Paused" (`iw.status.paused`) |
| Finished or not | `FrontendTrafficLightRenderer` | from the backend: `RdMarkupModel.daemon: RdDaemonModel`, property `state: BackendDaemonStateEnum` |
| Arrows | `EditorMarkupModelImpl` | `NavigationGroup(GotoPreviousError, GotoNextError)` to the right of the items, shown when there are items (`showNavigation`) |
| Two looks | `RedesignedInspectionsManager.isAvailable()` | the experiment `ide.redesigned.inspector` picks `InspectionsGroup`; otherwise `TrafficLightAction`. Which one a 2026.3 client shows is **not known yet** |

`BackendDaemonStateEnum` to IntelliJ's status, as `FrontendTrafficLightRenderer` maps it:

| `state` | Shown as |
|---|---|
| no value yet, or `HIDDEN` while the project is not loaded | paused, reason "Loading…" |
| `UNSUPPORTED`, `DISABLED` | off |
| `SUSPENDED` | paused, reason `RdDaemonModel.message` |
| `IN_PROGRESS_LOCAL`, `IN_PROGRESS_GLOBAL` | running (`errorAnalyzingFinished = false`) |
| `INVALIDATED`, `UP_TO_DATE`, `HIDDEN` once loaded | finished |

Classic look (`TrafficLightAction`'s component): 5 px at each end, 6 px between items, 1 px between
an icon and its count, the count in the label font two points smaller, colour
`ActionButton.iconTextForeground` (default: the context help grey), the button look's hover and
press backgrounds. It sits at the top right of the editor, over the text, left of the scrollbar.

### Steps

1. **See the real thing first.** Start an own backend and a traced JetBrains Client on the scratch
   project (`check-jetbrains-client-live` way: read the UI through System Events accessibility) and
   record: which look shows, the items' order, the count font size and colour, the arrows, the gap to
   the editor's top and right edges, and what a hover and a click show. Write the findings into
   `docs/intellij-look.md` before any drawing code.
2. **Data** (`session/ClientSession.kt`, `session/SessionState.kt`, `session/ProblemsView.kt`):
   - in `attachMarkup(markupLifetime, documentId, markup, source)`: advise `markup.daemon.state`
     and keep it per document in a new map `daemonStates: MutableMap<RdDocumentId, BackendDaemonStateEnum>`,
     then `publishMarkupLater(documentId)`;
   - new enum `AnalysisState { RUNNING, FINISHED, PAUSED, OFF }` in `SessionState.kt` and a new field
     `EditorFile.analysis: AnalysisState` (default `PAUSED`), filled from the table above;
   - new function `ProblemsView.statusItems(highlights, severities): List<StatusItem>` with
     `data class StatusItem(val severityId: String, val iconPaths: List<String>, val count: Int, val tooltip: String?)`:
     count highlights with `info.severity > 11` per `severityId`, order by the severity's value,
     highest first. No merging of items (`StatusItemMerger`) in the first cut.
   - test `ProblemsViewTest`: two errors, one warning and one hint give `[error 2, warning 1, hint 1]`
     in that order; a `TEXT ATTRIBUTES` highlight is not counted.
3. **Widget** (new `ui/InspectionsWidget.kt`, used from `ui/EditorView.kt` in the editor's `Box`,
   `Alignment.TopEnd`, left of the 18 dp marks strip): a `Row` of icon and count per item with the
   sizes above; the tick alone when there are no items and the state is `FINISHED`; the eye with
   "Analyzing…" while `RUNNING` with no items; half opacity while `RUNNING` with items
   (`translucent`), as IntelliJ paints it. Not shown in the console. Rows keep IntelliJ's sizes on
   touch too.
4. **Arrows**: `GotoPreviousError` and `GotoNextError` through `actions.runAction`, icons
   `AllIcons.Actions.FindAndShowPrevMatchesSmall` and `…NextMatchesSmall`, only when there are items.
5. **Click** on the items: IntelliJ opens the Problems tool window's File tab
   (`TrafficLightAction` → `ShowProblemsViewAction`); here `ToolWindow.PROBLEMS` with
   `ProblemsTab.FILE`.
6. Live check against step 1's notes, Before/After entry, `docs/pending.md` row 44, addendum in
   `docs/intellij-look.md`.

Left for later: the popup with the highlighting level and passes that a hover shows, power save
mode, the redesigned look if step 1 shows the classic one (or the other way round).

## Addendum (2026-11-26, 00:35 IST): counter plan, step 2 done and one correction

- Step 2 (data) is built and the state was seen arriving live; details in `docs/split-protocol.md`,
  addendum of the same time. One change from the plan: `StatusItem.tooltip` is the severity's display
  name, not the filled-in count message, because the message is an ICU choice template
  (`{0, choice, 1#{0} error|2#{0} errors}`) and the client has no formatter for it yet.
- Correction to step 5: a click on the counter does not only open the Problems tool window, it
  toggles it (`TrafficLightAction.actionPerformed` → `analyzerStatus.controller.toggleProblemsView()`).
- Steps 1 and 3 to 6 are open.
