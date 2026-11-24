# Plan: make the run toolbar work

Drafted 2026-11-24. Nothing here is built yet. The goal is the main toolbar's run controls doing
what they do in IntelliJ: the run configuration selector ("Current File ⌄"), Run, Debug, Stop and
More Actions (⋮). Today each of them only reports "isn't supported in this client yet"
(`ui/MainToolbar.kt`, `onUnsupported`).

Sources: IntelliJ 263.7307.200 (`/Users/safeuq/Files/Libraries/intellij-community-263.7307.200`),
and a running JetBrains Client of the same build on a backend of its own (port 5997, separate
settings, project `alpha` with a class `Alpha` and two saved Application configurations, `Alpha`
and `Second`). The client ran with
`IJ_JAVA_OPTIONS='-Didea.log.trace.categories=protocol.SEND,protocol.RECV'`; its controls were
pressed through accessibility (cmux-cua and System Events), 07:12 to 07:16 IST. Everything under
"What the JetBrains Client does" is **seen** in that client and its rd trace.

## How IntelliJ does it

| Piece | Source | What it is |
|---|---|---|
| The run widget | `RedesignedRunToolbarWrapper` (`NewUiRunWidget`, first in `MainToolbarRight`), `platform/execution-impl/src/com/intellij/execution/ui/RedesignedRunWidget.kt` | a toolbar of its own, place `NewUiRunToolbar` (`ActionPlaces.NEW_UI_RUN_TOOLBAR`), showing the group `RunToolbarMainActionGroup` |
| `RunToolbarMainActionGroup` (`ExecutionActions.xml`) | in this order | `RedesignedRunConfigurationSelector`, `compositeResumeGroup` (the debugger's Resume or Pause), `RunToolbarTopLevelExecutorActionGroup` (= `Run`, `Debug`), `Stop`, `MoreRunToolbarActions`, `InactiveStopActionPlaceholder` |
| The selector | `RedesignedRunConfigurationSelector`, marked `ActionRemoteBehaviorSpecification.Frontend` | a button with the selected configuration's icon and name; a click opens `RunConfigurationsActionGroupPopup` over the group `RunConfigurationsActionGroup`; Shift+click runs `editRunConfigurations` |
| The selector's rows | `RunToolbarPopup.kt` (`createRunConfigurationWithInlines`, `createCurrentFileWithInlineActions`) | one row per configuration with inline Run, Debug and Stop buttons, then "Current File", then "Edit Configurations…" |
| More Actions | `MoreRunToolbarActions` (a `TogglePopupAction`) | a popup of the other executors for the selected configuration (Coverage, Profiler), then Configuration ▸ Edit…, Delete |
| The empty slot | `InactiveStopActionPlaceholder` | a disabled, empty 16 px icon that keeps Stop's place while nothing runs |
| Look | `RunWidgetButtonLook`; sizes `JBUI.CurrentTheme.RunWidget.actionButtonWidth()` and `toolbarHeight()` | a running Run button is filled green and Stop is filled red |
| Run hides while paused | `filterOutRunIfDebugResumeIsPresent` | with a debugger Resume shown, `Run` is left out; with an inline Stop, `Debug` is |

## What the JetBrains Client does

The buttons are the backend's actions; only the selector button is drawn by the client from a
model. Nothing here uses Lux.

### The toolbar's state

| What | Message | Example |
|---|---|---|
| The buttons | `AsyncActionsModel.expandGroup(AsyncActionUpdateRequest(actionId = "composite:Debug;MoreRunToolbarActions;Run;RunToolbarMainActionGroup;RunToolbarTopLevelExecutorActionGroup;Stop;compositeResumeGroup", place = "NewUiRunToolbar", uiKind = ToolbarKindModel(horizontal = true), factoryId = 4, timestamps = editor + project))`, asked again whenever the toolbar updates | answers a presentation per action and the children of `RunToolbarMainActionGroup`, `RunToolbarTopLevelExecutorActionGroup` and `compositeResumeGroup` |
| The configurations | `solution.codeWithMeRunConfigurationsModel.runConfigurations` | `"Application.Alpha"`, `"Application.Second"`, each with name, type, icon and `execute` |
| The selection | `codeWithMeRunConfigurationsModel.clientCurrentConfiguration`, set by the backend | `null` shows "Current File"; `"Application.Alpha"` shows "Alpha" |
| What is running | `codeWithMeRunConfigurationsModel.executingConfigurations` | `"Application.Alpha" → 1` while it runs, `0` after Stop |

The presentations in each state (flags: `0x1` enabled, `0x2` visible):

| Action | Nothing selected, no editor | `Alpha` selected | `Alpha` running |
|---|---|---|---|
| `Run` | `0x882`, "Cannot run the current file: the editor is not in focus" | "Run 'Alpha'" | `0x883`, "Rerun 'Application: Alpha'", the rerun icon |
| `Debug` | `0x882`, same text | "Debug 'Alpha'" | `0x883`, "Debug 'Alpha'" |
| `Stop` | `0x880` (hidden) | hidden | `0x883`, "Stop 'Alpha'" |
| `InactiveStopActionPlaceholder` | `0x882` (shown, disabled) | shown | `0x880` (hidden) |
| `ConfigurationXDebuggerResumeAction` | `0x880` | hidden | `0x880`, "Pause 'Alpha'" |
| `MoreRunToolbarActions` | `0x88b` | same | same |

The buttons as the client lays them out (points, from accessibility): selector 114 × 32 with
"Current File" (96 × 32 with "Alpha"), then Run, Debug, More Actions and the placeholder at
34 × 32 each. While running the order is selector, Rerun, Debug, Stop, More Actions, placeholder
slot empty; Rerun is filled green and Stop red, and the selector's icon gets a green dot.

### What each control sends

| Control | Message | What comes back |
|---|---|---|
| Selector, click | `expandGroup(actionId = "composite:RunConfigurationsActionGroup", place = "popup@RunToolbarPopup", uiKind = PopupKindModel(mainMenu = false, searchPopup = false), factoryId = null, timestamps = [ProjectTimestampModel])` | 47 ms later: rows "Alpha", "Second", "Current File", "Edit Configurations…" (`editRunConfigurations`). A configuration row is a group with a generated id, e.g. `d@:487:RunToolbarPopupKt$createRunConfigurationWithInlines$1:<uuid>`, flags `0x8b3`, its icon, the description "Select Java application configuration 'Alpha'" and the client property `sub.popup.run.configuration.unique.id = "Application.Alpha"`. The answer's `inlineActions` give each row's Run, Debug and Stop buttons (`RunSpecifiedConfigExecutorAction`, `StopConfigurationInlineAction`, `InlineXDebuggerResumeAction`, `RunCurrentFileExecutorAction`) |
| A row, chosen | `RdCallActionRequest(actionId = <the row's id>, actionPlace = "popup@RunToolbarPopup", presentation = <as shown>, timestamps = [ProjectTimestampModel], inputEventModel = null)` on `documentsOperationModel.requestResponseModel` | 61 ms later the backend sets `clientCurrentConfiguration = "Application.Alpha"`; the next toolbar answer says "Run 'Alpha'" |
| Run | `RdCallActionRequest(actionId = "Run", actionPlace = "NewUiRunToolbar", timestamps = editor + project + control)` | 19 ms later `registerStartedConfiguration("Application.Alpha")`; then the Run, Services and Build tool windows are added; `executingConfigurations["Application.Alpha"] = 1` after 1.6 s |
| Stop | `RdCallActionRequest(actionId = "Stop", actionPlace = "NewUiRunToolbar")` | `executingConfigurations["Application.Alpha"] = 0` |
| More Actions | `RdCallActionRequest(actionId = "MoreRunToolbarActions", actionPlace = "NewUiRunToolbar", inputEventModel = ScreenPointMouseEvent(x = 1274, y = 75, componentRect = the button's bounds))` | the backend adds `UIAutomationInteractionModel.activePopups[1]` with `content = ActionPopupContent(step)`. Rows while idle: Run 'Alpha', Debug 'Alpha', Run 'Alpha' with Coverage, Profile 'Alpha' with 'IntelliJ Profiler', then under "Configuration": Edit…, Delete. While running the first row is Rerun 'Application: Alpha' |

Debug was not pressed. It is the same kind of button as Run (`Debug` in the same group and place).

## What Ideate has

- `ui/MainToolbar.kt`: a fixed "Current File" dropdown and four fixed icons (Build, Run, Debug,
  More actions), all calling `onUnsupported`. Build is not part of IntelliJ's run widget.
- `session/ClientSession.kt`: `runConfiguration(id, executorId)` starts a configuration through
  `codeWithMeRunConfigurationsModel.runConfigurations[id].execute`, and `runProjectAction(actionId)`
  runs a backend action for the project. `terminal-and-consoles-spec.md` 8.3 lists the model's
  members.
- `session/ActionMenus.kt`: `show(serial, groupId, place, timestamps)` expands a group into a menu,
  and `runAction(actionId, place, timestamps)` runs an action after asking for its presentation. It
  sends `PopupKindModel`, `factoryId = null` and `inputEventModel = null`, and it doesn't read
  `inlineActions`.
- `session/BackendPopups.kt` shows `WizardPopupContent` popups. `ActionPopupContent` appears
  nowhere in the sources.
- The Run tool window, its console and its own toolbar already work.

## Design

Follow the client: the backend decides what every button shows and does.

1. **State.** A `RunToolbar` in the session asks `expandGroup` for the composite id above at
   `NewUiRunToolbar` with `ToolbarKindModel(horizontal = true)` and a `factoryId` of its own, and
   publishes the visible children of `RunToolbarMainActionGroup` in order: text (the tooltip), icon,
   enabled. It asks again when `clientCurrentConfiguration`, `executingConfigurations` or the
   selected editor changes, and after one of its own actions has run.
2. **Selector button.** Its name and icon come from `clientCurrentConfiguration` looked up in
   `runConfigurations`; "Current File" when it is null.
3. **Selector popup.** `ActionMenus.show("composite:RunConfigurationsActionGroup",
   "popup@RunToolbarPopup", [ProjectTimestampModel])`, shown with the existing `Menu`. A chosen row
   runs as any menu item does. Inline Run, Debug and Stop buttons on a row are a later step.
4. **Run, Debug, Stop.** `ActionMenus.runAction("Run" | "Debug" | "Stop", "NewUiRunToolbar",
   editor + project timestamps)`. The existing `runConfiguration(id)` stays for other callers; the
   toolbar uses the actions, so "Current File", Rerun and the disabled states come for free.
5. **More Actions.** Run `MoreRunToolbarActions` with a `ScreenPointMouseEvent` of the button's
   bounds, and show the `ActionPopupContent` popup that arrives through `BackendPopups`.
6. **Look.** Buttons 34 × 32 in the toolbar's units, as measured; the running Run button filled
   green and Stop filled red as `RunWidgetButtonLook` paints them, with the colours from the
   current theme's `RunWidget.*` keys; the placeholder keeps Stop's slot. Build leaves the row,
   since IntelliJ has no Build button there (it is in the Build menu and on a shortcut).

## Steps

- [ ] 1. `session/RunToolbar.kt`: the composite `expandGroup`, the published buttons, and the
      refresh triggers. A test with the three recorded states (nothing selected, `Alpha` selected,
      `Alpha` running).
- [ ] 2. `SessionState`: the selector's name and icon from `clientCurrentConfiguration` and
      `runConfigurations`.
- [ ] 3. `MainToolbar.kt`: the buttons from the published list; Run, Debug and Stop wired to
      `runAction`; Build removed from the row.
- [ ] 4. The selector popup through `ActionMenus.show`; check live that choosing a row changes the
      selector and that "Current File" and "Edit Configurations…" behave (the second opens a Lux
      dialog).
- [ ] 5. `ActionMenus`: an optional `inputEventModel`; `BackendPopups`: `ActionPopupContent`; then
      More Actions.
- [ ] 6. `RunWidgetButtonLook`'s running and stop fills, the selector's running dot, in Islands
      Dark and Islands Light.
- [ ] 7. Inline Run, Debug and Stop on the selector's rows (`inlineActions`).
- [ ] 8. Before and After shots on desktop and on a phone upright (the row is narrow there), a row
      in `pending.md`, and the measured facts moved into `action-protocol.md` as a dated addendum.

## Unknown

- What `ActionPopupContent.step` holds, and how much of `BackendPopups`' `WizardPopupContent` code
  it can share. Only the popup's arrival and its rows were recorded.
- Whether `expandGroup` with a plain group id (`RunToolbarMainActionGroup`, as `ActionMenus` sends
  today) answers the same as the `composite:` form, and whether a null `factoryId` is accepted for
  a toolbar.
- Whether the backend needs the `ControlTimestampModel` the client adds to Run's timestamps.
- The debugger states (Resume, Pause, Run hidden while paused) were not exercised.
- The exact `RunWidget.*` theme keys and values for the green and red fills.
