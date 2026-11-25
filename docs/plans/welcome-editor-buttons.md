# Plan: the welcome project's editor buttons

Pending row 33 (intellij-backend-android `docs/pending.md`). Written 2026-11-25 by the
`intellij-android (runtime)` session.

## Goal

When the welcome project (IntelliJ IDEA Home) is shown and no file is open, the editor area shows
JetBrains Client's three buttons in its centre instead of Ideate's text "Double-tap a file in the
Project tool window to open it":

| Button | Icon (New UI path) | Click |
|---|---|---|
| New Markdown File | `expui/fileTypes/markdown.svg` | backend: `WelcomeScreenFeatureApi.onClick(projectId, "New.Markdown.File")` |
| Open File | `expui/general/open.svg` | backend: `WelcomeScreenFeatureApi.onClick(projectId, "Open.File")` |
| Open Terminal | `expui/fileTypes/shell.svg` | client: show Ideate's Terminal tool window |

Out of scope: the bottom row (Plugins, Theme ▾, Keymap ▾, `createAdditionalComponents`). It is a
follow-up row.

## What JetBrains Client does (263.7307.200, decompiled)

| Piece | Class (jar) | What it does |
|---|---|---|
| Empty editor area | `WelcomeScreenEmptyStateProvider` (`idea-frontend-split-customization.jar`), an `editorEmptyStateComponentProvider` ordered first | Shown only for a welcome-experience project; builds the body with `WelcomeScreenRightTabImplKt.prepareDefaultBody` → `WelcomeScreenRightTabImpl.createFeatureGrid` |
| Button models | `IdeaWelcomeRightTabContentProvider.getFeatureButtonModels(project)` (`java-ide-customization.jar`) | Three `FeatureButtonModel`s: text from `idea.non.modal.welcome.screen.{new.markdown.file,open.file,open.terminal}`, icons `AllIcons.FileTypes.Markdown`, `AllIcons.Actions.MenuOpen`, `AllIcons.Debugger.Console`, feature keys `New.Markdown.File`, `Open.File`, `terminal.toolwindow` |
| Click | `featureButtonOnClick` | `WelcomeScreenFeatureApi.getInstance().onClick(project.projectId(), key)` |
| Client-side features | `MergedWelcomeScreenFeatureApi.onClick` | If `WelcomeScreenFeatureFrontend.getForFeatureKey(key)` exists it runs on the client: `IdeaTerminalFeatureFrontend` = `WelcomeScreenToolwindowFeatureFrontend("terminal.toolwindow", "Terminal")` → `ToolWindowManager.getToolWindow("Terminal").activate()`. Otherwise the backend API is called |
| Backend features | `NewMarkdownFileFeatureBackend`, `OpenFileFeatureBackend` (backend's java-ide-customization plugin) | Run on the backend; whatever they open (an editor, a file chooser) reaches the client the usual way |
| Grid | `createFeatureGrid` | One `DisclosureButton` per model, `arrowIcon = null`, `buttonHeight = JBUI.scale(compact ? 36 : 48)`; grid cell gap `UnscaledGaps(right = 10)`; rows of `getButtonsPerRow` |
| Button | `DisclosureButton` + `DarculaDisclosureButtonUI` | Left-aligned; `leftMargin` 14, `rightMargin` 12, `iconTextGap` 12, `arc` 16, border insets `JBInsets(3)` (the fill is drawn inside them), minimum width 72; fill `DisclosureButton.defaultBackground`, hover `hoverOverlay`, pressed `pressedOverlay` drawn over it |

Fleet RPC call (same shape as Completion's):

```json
{"service": "com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeScreenFeatureApi",
 "method": "onClick",
 "args": {"projectId": {"id": "m3nniruk9k7j4qsibjui"}, "featureKey": "Open.File"}}
```

Measured in JetBrains Client (window 1400×1000 pt, Islands Dark): buttons drawn 48 tall (54 with the
border insets), gap between drawn buttons 16 (10 cell gap + 2×3 insets), editor `#191A1D`, button
fill `#28292B` (= `#12FFFFFF` over the editor), text the default UI font, `Label.foreground`.

## Changes

### `session/SessionState.kt`

- `SessionState.welcomeProject: Boolean = false` — true while the shown project is the welcome
  project.

### `session/ClientSession.kt`

- In `bindSolution`, next to `copy(shownProject = hostPath)`: `copy(welcomeProject =
  isWelcomeProject(newSolution))`.
- `private var kernelProjectId: String? = null`, set in `bindSolution` from
  `description?.kernelProjectId`, cleared with the solution.
- New, near `openTerminal()`:

  ```kotlin
  /** `WelcomeScreenFeatureApi.onClick`: runs a welcome feature (e.g. "Open.File") on the backend. */
  fun welcomeFeatureClick(featureKey: String) = onRd {
    val projectId = checkNotNull(kernelProjectId) { "No kernel project id" }
    checkNotNull(fleet) { "No Fleet RPC" }.call(
      WELCOME_FEATURE_API, "onClick",
      buildJsonObject { put("projectId", buildJsonObject { put("id", projectId) }); put("featureKey", featureKey) },
    )
  }
  ```

  `WELCOME_FEATURE_API = "com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeScreenFeatureApi"`.
- `fun showToolWindow(windowId: String)`: `mutableState.update { it.copy(showRequest =
  ShowRequest(windowId, (it.showRequest?.serial ?: 0) + 1)) }` — the same request the backend's
  "activate tool window" makes, so `ClientScreen`'s existing `LaunchedEffect(state.showRequest)`
  opens it.

### `ui/WelcomeFeatureGrid.kt` (new, about 90 lines)

Names mirror IntelliJ's:

```kotlin
/** IntelliJ's `FeatureButtonModel`. */
internal data class FeatureButtonModel(val text: String, val iconPath: String, val featureKey: String)

/** `IdeaWelcomeRightTabContentProvider.getFeatureButtonModels`. */
internal val IDEA_FEATURE_BUTTONS = listOf(
  FeatureButtonModel("New Markdown File", "expui/fileTypes/markdown.svg", "New.Markdown.File"),
  FeatureButtonModel("Open File", "expui/general/open.svg", "Open.File"),
  FeatureButtonModel("Open Terminal", "expui/fileTypes/shell.svg", TERMINAL_FEATURE_KEY),
)
internal const val TERMINAL_FEATURE_KEY = "terminal.toolwindow"

/** `WelcomeScreenRightTabImpl.createFeatureGrid`: one row, wrapping when narrow. */
@Composable internal fun FeatureGrid(models: List<FeatureButtonModel>, onClick: (FeatureButtonModel) -> Unit)

/** IntelliJ's `DisclosureButton` without the arrow, as the welcome grid uses it. */
@Composable internal fun DisclosureButton(text: String, iconPath: String, onClick: () -> Unit)
```

- `FeatureGrid`: `FlowRow(horizontalArrangement = spacedBy(FEATURE_GAP, CenterHorizontally),
  verticalArrangement = spacedBy(FEATURE_GAP))`, `FEATURE_GAP = 16.dp` (cell gap 10 + insets 3+3).
  On a phone the row wraps instead of IntelliJ's `getButtonsPerRow`.
- `DisclosureButton`: `Row` height `48.dp`, `RoundedCornerShape(8.dp)` (arc 16 → radius 8),
  background `Islands.color("DisclosureButton.defaultBackground")`, hover/pressed overlays
  (`DisclosureButton.hoverOverlay`, `DisclosureButton.pressedOverlay`) drawn over it
  (`hoverable` + `MutableInteractionSource`), padding start 14, end 12, icon 16, gap 12, text in the
  default label font (`LocalClientFonts.current.label(Islands.text)`), `minWidth 72.dp`.
  Clickable with `Role.Button`.
- Icon: `LocalIcons.current.painter(iconPath)` into `Image(…, Modifier.size(16.dp))`, as
  `TabCloseButton` in `ClientScreen.kt` does. No new helpers.

### `ui/ClientScreen.kt`

In the `else` branch of the editor area (no editors), before the existing text:

```kotlin
if (state.welcomeProject) FeatureGrid(IDEA_FEATURE_BUTTONS) { model ->
  if (model.featureKey == TERMINAL_FEATURE_KEY) session.showToolWindow(ToolWindow.TERMINAL.id)
  else session.welcomeFeatureClick(model.featureKey)
} else BasicText(…existing…)
```

### Tests

`compose/src/jvmTest/…/ui/WelcomeFeatureGridTest.kt`: the three models' keys and icon paths
resolve through `IconJar.svg` (each icon exists for both themes).

### Docs

`docs/tool-windows.md`: new section at the end (append-only), "Welcome project's editor
buttons", with the table above and the live result.

## Live check (scratch backend on 5994)

1. Before: Ideate connected to the welcome project, empty editor area.
2. After: the three buttons; sizes against JetBrains Client's screenshot.
3. Each button: New Markdown File opens an editor (or the backend's dialog), Open File shows the
   backend's file chooser, Open Terminal shows the Terminal tool window.
4. Retest pending row 33's "panel not drawn after a dialog closed" with the dialog Open File shows.
