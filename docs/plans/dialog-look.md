# Plan: dialogs with a title bar and dividers (pending row 55)

Written 2026-11-26, 18:30 IST. Nothing in this plan is built yet.

## The request

The user sent a tablet screenshot of "Connect to SSH" and wrote:

> This window & 'Choose IDE and project' should be proper dialogs instead of popups: Title bar,
> divider, content, divider, action buttons. We should use existing dialog model if available, or
> create a new model copying the structure from IntelliJ sources.

So the order of parts, top to bottom, is:

```
┌──────────────────────────────────────────────┐
│ Connect to SSH                             × │  title bar
├──────────────────────────────────────────────┤  divider
│ Username:                                    │
│ [                                          ] │  content (center panel)
│ …                                            │
│ error text, when there is one                │
├──────────────────────────────────────────────┤  divider
│      [Check Connection and Continue] [Cancel]│  action buttons (south panel)
└──────────────────────────────────────────────┘
```

## The existing dialog model

There is one already, so no new model is needed. Both windows are drawn by the same function:

| What | Where |
|---|---|
| `DialogWrapper(title, actions, onCancel, modifier, width, maxHeight, error, contentPadding, scrollable, content)` | `compose/src/commonMain/kotlin/io/github/safeuq/ideate/client/ui/dialog/DialogWrapper.kt`, line 63 |
| `DialogAction(label, enabled, onClick)` | same file, line 46 |
| `DialogWindow` (Escape cancels, Enter runs the first action) | same file, line 125 |
| `DialogButtons` (12 dp apart, default button last on a Mac) | same file, line 161 |
| `Modifier.dialogFrame()` and `DialogLook` (width 475, corner 10, outline `#393B40`, `titleHeight` 32, `sideInset` 14, `southTop` 12, `bottomInset` 11) | same file, lines 186 and 195 |

Everything that calls it:

| Caller | Title |
|---|---|
| `connect/SshPage.kt` line 256 | Connect to SSH |
| `connect/SshPage.kt` line 377 | Choose IDE and Project |
| `connect/SshPage.kt` line 419 | Authentication |
| `connect/FolderChooser.kt` line 142 | the folder chooser (`contentPadding` 0, `scrollable` false) |
| `desktop/…/desktop/ComponentGallery.kt` line 152, `SampleDialog` | the gallery's sample "Connect to SSH" |

Two more places draw a dialog without calling `DialogWrapper`:

- `ui/lux/LuxDialogView.kt`, `LuxDialogWindow` (line 162): a dialog the backend sends. It has its
  own copy of the title row (lines 184 to 192), the same 32 dp left-aligned semibold text.
- `ui/dialog/MessageDialog.kt`: IntelliJ's alert (icon, title, message, buttons). It has no title
  bar on purpose, as IntelliJ's alert has none.

What `DialogWrapper` draws today, and why it reads as a popup: the title is a 32 dp row of
semibold text on the same background as the content, with no line under it, and the buttons sit
12 dp under the content with no line above them.

## How IntelliJ does it

Sources: `/Users/safeuq/Files/Libraries/intellij-community-263.7307.200`. Paths below are inside it.

### Two styles of dialog

`platform/platform-api/src/com/intellij/openapi/ui/DialogWrapper.java` has
`enum DialogStyle {NO_STYLE, COMPACT}` (line 183) and `getStyle()` (line 759, default `NO_STYLE`).

| | `NO_STYLE` (most dialogs) | `COMPACT` |
|---|---|---|
| Used by | Rename, most forms | Settings (`SettingsDialog`), Run/Debug Configurations (`EditConfigurationsDialog`), the new project wizard (`AbstractWizard`), Clone (`VcsCloneDialog`) |
| Content pane border (`createContentPaneBorder`, line 637) | empty, 8 top, 12 left, 8 bottom, 12 right | none; on Windows a 1 px line at the top in `JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()` |
| South panel border (`createSouthPanel`, lines 841 to 848) | empty, 8 on top | a 1 px line on top, then empty 8 top and bottom, 12 left and right |
| South panel line colour | none | `DialogWrapper.southPanelDivider` if the theme has it, else `OnePixelDivider.BACKGROUND` |
| South panel background (`SouthPanel.getBackground`, line 2427) | the dialog's | `DialogWrapper.southPanelBackground` if the theme has it, else the dialog's |
| Gap between buttons | `BASE_BUTTON_GAP` 12 | 12 |

"Title bar, divider, content, divider, action buttons" is the `COMPACT` style as Windows draws
it. That is what this plan copies. Example to compare against: IntelliJ's Settings dialog.

The pane is built in `DialogWrapper.init` (lines 1484 to 1538): `myRoot` with `createTitlePane()`
at the north (null by default), `centerSection` in the middle holding `createNorthPanel()` and
`createCenterPanel()`, and `southSection` at the south holding the error text
(`myErrorText`, centre) with `createSouthPanel()` under it. So the error text is above the south
panel's line, not below it.

### The title bar

On macOS the system draws it: about 28 pt high, the title centred and bold, the three round
buttons at the left. IntelliJ draws nothing there.

On Windows, and on Linux without the system's title bar, IntelliJ draws its own:
`platform/platform-impl/src/com/intellij/openapi/wm/impl/customFrameDecorations/header/DialogHeader.kt`,
put above the content by `CustomFrameDialogContent` (`BorderLayout`, header at `NORTH`), chosen in
`DialogWrapperPeerImpl.setContentPane` (line 1384).

| Part | Value | Source |
|---|---|---|
| Layout | product icon, then the title label taking the rest of the width, then the window buttons at the right | `DialogHeader.init` |
| Height | 40, or 32 in compact mode | `CustomWindowHeaderUtil.getPreferredWindowHeaderHeightUnscaled`: `HEADER_HEIGHT_NORMAL` 40, `HEADER_HEIGHT_COMPACT` 32 |
| Product icon padding | 12 at the left and 12 at the right | `productIcon.border = JBUI.Borders.empty(0, H, 0, H)`, `CustomHeader.H` = 12 |
| Title label | the default label font (not bold), left-aligned, 5 above and below | `LABEL_BORDER = JBUI.Borders.empty(V, 0)`, `CustomHeader.V` = 5 |
| Title colour | `Panel.foreground` when the window is active, `Label.disabledForeground` when not | `DialogHeader.updateActive` |
| Background | `TitlePane.background`, falling back to the panel background | `CustomHeader.getHeaderBackground` → `JBUI.CurrentTheme.CustomFrameDecorations.titlePaneBackground` |
| Line under the header | none from the header itself (`isBottomNeeded` is false for dialogs); in `COMPACT` the content pane's top line is the divider | `CustomHeader.setCustomFrameTopBorder`, `DialogWrapper.createContentPaneBorder` |

### The colours in the Islands themes

`platform/platform-resources/src/themes/islands/ManyIslandsDark.theme.json` and
`ManyIslandsLight.theme.json`.

| Use | Theme key | Islands Dark resolves to |
|---|---|---|
| Line under the title bar | `Separator.separatorColor` (what `separatorForeground()` reads) | `popup-border` = `layer-1-border` = `gray-40` = `#33353B` |
| Line above the buttons | `DialogWrapper.southPanelDivider` is not in the theme, so `OnePixelDivider.BACKGROUND` = `JBColor.border()` = `Borders.color` | `dialog-border` = `layer-0-border` = `gray-30` = `#26282C` |
| Title bar background | `TitlePane.background` is not in the theme (only `TitlePane.Button.*` is), so the panel background | `dialog-bg` = `layer-0-bg` = `gray-10` = `#191A1C`, the same as the content |
| South panel background | `DialogWrapper.southPanelBackground` is not in the theme | the same as the content |

So in Islands Dark the title bar, the content and the south panel share one background, and the
two lines are what separate them. The two lines have different colours (`#33353B` above,
`#26282C` below); that is IntelliJ's, not a mistake.

### What was not checked live

These values come from reading the sources. Nobody has yet measured a real IntelliJ `COMPACT`
dialog on screen for this plan. Step 6 below does that. Gateway's own "Connect to SSH" is not in
the community sources, so which style Gateway gives it is unknown; the user's request decides it.

## Design

One change in the existing model, no new model, no new parameters on `DialogWrapper`. Every caller
keeps compiling unchanged.

### Decisions for the user (the plan goes ahead with the recommended one)

| # | Question | Recommended | Why |
|---|---|---|---|
| 1 | Title on a Mac: centred as the system draws it, or left as on Windows? | centred and semibold on a Mac; left and regular weight elsewhere (Windows, Linux, Android) | each is what IntelliJ shows on that system |
| 2 | A close (×) button in the title bar? | yes on Windows, Linux and Android, at the right; none on a Mac | Windows has one there; a tablet has no Escape key. On a Mac the system's three round buttons belong to the system, and drawing copies of them is not planned; Cancel and Escape close the dialog |
| 3 | The product icon at the left of the title (Windows only in IntelliJ)? | no | the dialog is drawn inside Ideate's window, which already shows what app it is |
| 4 | Dividers on a Mac too, although IntelliJ's `COMPACT` draws the top one only on Windows? | yes, both lines on every system | the user asked for them |

### New code, all in `ui/dialog/DialogWrapper.kt`

1. In `DialogLook`:
   - change `titleHeight` to `if (IS_MAC) 32.dp else Islands.byDensity(40.dp, 32.dp)` (a `get()`
     property; 32 on a Mac is the value already measured there, 40 and 32 are
     `HEADER_HEIGHT_NORMAL` and `HEADER_HEIGHT_COMPACT`);
   - add `val titleInset = 12.dp` (`CustomHeader.H`);
   - add `val southPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)` (the `COMPACT`
     south panel's `JBUI.Borders.empty(8, 12)`);
   - add `val contentVertical = 12.dp` (the space above and below the content, between the two
     lines; this number is ours, IntelliJ's `COMPACT` dialogs leave it to each dialog);
   - remove `southTop` and `bottomInset`, which the south panel no longer uses.
2. A new composable, used by `DialogWrapper` and by `LuxDialogWindow`:

   ```kotlin
   /** IntelliJ's `DialogHeader`: the title, the close button where the system has one, and the line under it. */
   @Composable
   internal fun DialogTitleBar(title: String, onClose: () -> Unit)
   ```

   It draws a `Column`: first a `Box` of `fillMaxWidth().height(DialogLook.titleHeight)` with
   background `Islands.titlePaneBackground`; inside it the title `BasicText` (one line,
   `TextOverflow.Ellipsis`) and, when not `IS_MAC`, the close button; then the divider.
   - Mac: title at `Alignment.Center`, `fonts.label()` with `FontWeight.SemiBold`, no button.
   - Elsewhere: title at `Alignment.CenterStart` with `padding(start = DialogLook.titleInset)`,
     `fonts.label()` unchanged; the close button at `Alignment.CenterEnd` is the existing
     `IconButton("expui/general/closeSmall.svg", "Close", onClose, hoveredIconPath =
     "expui/general/closeSmallHovered.svg")` from `ui/component/IconButton.kt`, with
     `padding(end = 8.dp)`. The title gets `padding(end = 40.dp)` so long text stops before it.
   - Divider: `Box(Modifier.fillMaxWidth().height(1.dp).background(Islands.separator))`.
3. In `DialogWrapper` (lines 77 to 114), the `Column` becomes, in order:
   1. `DialogTitleBar(title, onCancel)`, replacing the title `Box` of lines 78 to 90;
   2. the content `Column`, with `.padding(top = DialogLook.contentVertical)` added before
      `.padding(contentPadding)`; when `error == null` also `padding(bottom =
      DialogLook.contentVertical)`. A caller that passes `contentPadding = PaddingValues(0.dp)`
      (the folder chooser) gets neither, so its tree still reaches the lines: apply the vertical
      padding only when `contentPadding` is not zero at the start side. Simplest form: a private
      `val padded = contentPadding.calculateStartPadding(LayoutDirection.Ltr) > 0.dp`;
   3. the error text, as now, with `padding(top = 8.dp, bottom = DialogLook.contentVertical)`;
   4. the south divider:
      `Box(Modifier.fillMaxWidth().height(1.dp).background(Islands.southPanelDivider))`;
   5. `DialogButtons(actions, Modifier.padding(DialogLook.southPadding))`.
4. `dialogFrame()` stays as it is (background, corner 10, outline `#393B40`). The title bar's
   background equals the frame's in both Islands themes, so no clipping to the rounded corner is
   needed. If a theme ever sets `TitlePane.background`, add `.clip(DialogLook.shape)` to the outer
   `Column`.

### New colours, in `ui/theme/Islands.kt`

Next to `popupBorder` (line 154), using the private `color(key, fallback)` of line 52:

```kotlin
// Separator.separatorColor = popup-border: the line under a dialog's title bar
// (JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground).
val separator
  get() = color("Separator.separatorColor") { popupBorder }

// DialogWrapper.southPanelDivider, else OnePixelDivider.BACKGROUND = Borders.color = dialog-border.
val southPanelDivider
  get() = color("DialogWrapper.southPanelDivider") { color("Borders.color") }

// TitlePane.background, else the panel's (CustomFrameDecorations.titlePaneBackground).
val titlePaneBackground
  get() = color("TitlePane.background") { islandBackground }
```

### The backend's dialogs, `ui/lux/LuxDialogView.kt`

In `LuxDialogWindow`, replace the title `Box` (lines 185 to 192) with
`DialogTitleBar(dialog.title) { closeWindow(::send) }`, keeping the condition
`!dialog.popup && !dialog.undecorated` around it. Nothing else changes there: the backend draws
that dialog's content and its buttons, including its own south line when the dialog is `COMPACT`,
so Ideate must not add a south divider to it.

### Not changed

- `MessageDialog.kt`: IntelliJ's alert has no title bar and no dividers.
- `DialogWindow`, `DialogButtons`, `DialogAction`: unchanged.
- `SshPage.kt`, `FolderChooser.kt`, `ComponentGallery.kt`: no edits; they get the new look
  through `DialogWrapper`.
- No `DialogStyle` parameter: every Ideate dialog gets the one look. Add the enum with IntelliJ's
  names (`NO_STYLE`, `COMPACT`) only if a dialog ever needs the plain form.

## Steps

One implementer can do all of it in one commit. The files to edit: `ui/dialog/DialogWrapper.kt`,
`ui/theme/Islands.kt`, `ui/lux/LuxDialogView.kt`, and the docs named in step 7.

1. `Islands.kt`: add `separator`, `southPanelDivider`, `titlePaneBackground` as above.
2. `DialogWrapper.kt`: change `DialogLook`, add `DialogTitleBar`, rebuild the `Column` as above.
3. `LuxDialogView.kt`: use `DialogTitleBar`.
4. Build and run the existing tests:
   `JAVA_HOME=~/.sdkman/candidates/java/25.0.5.0+8-tem ./gradlew :compose:jvmTest :desktop:compileKotlin`.
   There is no new unit test: the change has no logic to test beyond the system check, and
   `DialogFitTest` (the backend dialog's width) must still pass.
5. Before and After pictures from the component gallery, which draws `SampleDialog` (with an error
   text): `CLIENT_GALLERY=<file.png> ./gradlew :desktop:run`, once on `origin/main` and once on the
   change. Both go on the Before/After page (https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG).
6. Live checks, in the running app (`-PagentMcpPort=5979`, tell the other sessions first):

   | Check | Expected |
   |---|---|
   | Connect page → SSH → New Connection: "Connect to SSH" | title bar, line, form, line, buttons; Escape and Cancel close it; Enter runs Check Connection and Continue |
   | "Choose IDE and Project" and "Authentication" (need a host that answers; the test host in `docs/ssh-connections.md`) | the same parts; the error text shows above the south line |
   | The folder chooser (Browse in "Choose IDE and Project") | the tree reaches both lines with no doubled line: if the tree's own border and the south line sit together, remove the tree's bottom border in `FolderChooser.kt` |
   | A backend dialog, for example Rename (Shift+F6 in an editor) | the new title bar with the line; the backend's content unchanged; the width still as `DialogFitTest` says |
   | Compact density (the setting that `Islands.density` follows) on Linux or Android | title bar 32 dp; 40 dp in the normal density |
   | Android (emulator, or the S24 after asking intellij-android) | the × closes the dialog; the title bar is 40 dp; the dialog still fits a 393 dp wide phone |
   | Against the real thing | open IntelliJ's Settings (a `COMPACT` dialog) in a JetBrains Client on a test backend and read the south panel's and buttons' positions through accessibility (`cmux-cua` `get_window_state`): the gap from the line to the buttons must be 8, from the buttons to the dialog's bottom 8, at the sides 12. Correct `DialogLook.southPadding` if the real numbers differ, and write what was measured into the docs |

7. Docs, in the same commit, as dated addenda (never edit existing text):
   - `docs/ui-components.md`: the new `DialogTitleBar`, the three colours and their keys, the
     `COMPACT` values and where each comes from, and what was measured in step 6;
   - `docs/intellij-look.md`: the dialog's parts and sizes;
   - this plan: an addendum saying what was built and what differed from the plan;
   - intellij-backend-android `docs/pending.md`: row 55 to the Done table.

## Standing rules for whoever builds this

- Work in a temporary detached worktree from `origin/main`; rebase; push to `main`. Never commit
  in the shared checkout; never use `git stash`, `git reset --hard` or `git clean`.
- Commit author "Safeuq Mohamed <mohamedsafeuq@gmail.com>", no Claude mention, no Co-Authored-By.
  `gh auth switch --user safeuq` before pushing, `gh auth switch --user safeuq-xflowpay` after.
- Tell the other sessions which files you will edit before starting, and before driving the
  screen. Only one app can hold port 5979.
- Never touch backend ports 5993 or 5996 or the user's own IntelliJ. Stop every backend, client
  and emulator you started.
- No extra padding for touch: the title bar is 40 dp (32 compact) on Android too.
- No abbreviated identifiers.

## Not in this plan

- Dragging a dialog by its title bar. A Compose `Dialog` is drawn inside Ideate's window (a
  separate system window on Android) and stays centred.
- The three round window buttons on a Mac.
- The inactive title colour (`Label.disabledForeground`): a dialog here is modal, so it is the
  active one whenever it is visible.
- The product icon in the title bar.
- `MessageDialog`.
