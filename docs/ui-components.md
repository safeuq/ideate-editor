# Standard UI components (plan, draft)

Agreed 2026-11-17 between the two sessions working on IDEate's UI. Progress per step is in
section 4.

IDEate builds its widgets by hand, one copy per screen. The goal is one component per IntelliJ
widget, laid out the way IntelliJ's own sources are, with colors and sizes from the Islands Dark
theme keys instead of per-file constants.

IntelliJ sources referenced below are under `intellij-community-263.7307.200/` (abbreviated `IC/`).

## 1. IntelliJ already has a Compose component library: Jewel

`IC/platform/jewel` is JetBrains' Compose UI for IntelliJ. Its layout is the one to copy:

| Jewel path | What it holds | Example |
|---|---|---|
| `ui/src/main/kotlin/org/jetbrains/jewel/ui/component/` | one file per component | `Button.kt` (DefaultButton, OutlinedButton), `TextField.kt`, `RadioButton.kt`, `Checkbox.kt`, `Link.kt`, `Menu.kt`, `PopupContainer.kt`, `LinearProgressBar.kt`, `Divider.kt`, `GroupHeader.kt`, `SearchTextField.kt`, `LazyTree.kt`, `IconButton.kt`, `Tabs.kt`, `Text.kt`, `Typography.kt` |
| `ui/.../component/styling/` | a `XxxStyle(colors, metrics)` per component | `ButtonStyling.kt`: `ButtonColors(background, backgroundHovered, border, ...)`, `ButtonMetrics(cornerSize, padding, minSize, borderWidth)` |
| `ide-laf-bridge/.../bridge/theme/IntUiBridge*.kt` | which theme key fills which style field | `IntUiBridgeButton.kt`: `retrieveColorOrUnspecified("Button.default.foreground")`, `retrieveArcAsNonNegativeCornerSizeOrDefault("Button.default.arc", …)` |

Jewel itself can't be a dependency: it's Compose for Desktop, and 15 files in `ui/` and
`foundation/` import `java.awt` or `javax.swing`, which Android doesn't have. (Pulling it in would
also be a large download over the phone's data.) So we copy its structure and read its bridge
files to know which key feeds what. We do not copy its full style layer (a colors class with a
field per state for every component): one `private object` of key-commented values per component
is enough until a component needs a second variant.

Dialogs aren't in Jewel. They follow the Swing classes: `DialogWrapper` and `AlertMessagesManager`.

## 2. Inventory

Paths are under `compose/src/commonMain/kotlin/intellijandroid/client/`. Lines are at 508aaa2, before
the SSH message dialog (32b2677) and step 1, so they have moved a little since.

| IDEate widget | file:line | IntelliJ counterpart | Jewel file |
|---|---|---|---|
| `DialogFrame` (frame + title) | connect/SshPage.kt:363 | `DialogWrapper` (`IC/platform/platform-api/src/com/intellij/openapi/ui/DialogWrapper.java`) | none |
| FolderChooser's own frame + title | connect/FolderChooser.kt:149 (Dialog), `Chooser` at :328 | `FileChooserDialogImpl` on `DialogWrapper` | none |
| `DialogButton` (SshPage) | connect/SshPage.kt:466 | `DarculaButtonUI` (`IC/platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/DarculaButtonUI.java`) | Button.kt |
| `DialogButton` (FolderChooser) | connect/FolderChooser.kt:304 | same | Button.kt |
| `OutlinedButton` | connect/ConnectScreen.kt:717 | same (not default) | Button.kt `OutlinedButton` |
| StartProgress's Cancel | connect/StartProgress.kt:96 | same, focused (2 px ring) | Button.kt |
| `LabeledField` | connect/SshPage.kt:397 | `JBLabel` over `JBTextField`; `panel { row("Host:") { textField() } }` (Kotlin UI DSL) | Text.kt + TextField.kt |
| `InputField` | connect/SshPage.kt:412 | `JBTextField` + `DarculaTextFieldUI` + `DarculaTextBorderNew` | TextField.kt |
| `Field` | connect/ConnectScreen.kt:705 | same | TextField.kt |
| FolderChooser path field | connect/FolderChooser.kt:221 | same, with the focus ring | TextField.kt |
| `SearchRow` | connect/ConnectScreen.kt:497 | `SearchTextField` (`IC/platform/platform-api/src/com/intellij/ui/SearchTextField.java`) | SearchTextField.kt |
| `Choice` (radio; also used as a check box) | connect/SshPage.kt:443 | `DarculaRadioButtonUI`, `DarculaCheckBoxUI` (icons `radio*.svg`, `checkBox*.svg` via `LafIconLookup`) | RadioButton.kt, Checkbox.kt |
| `IconButton` | connect/ConnectScreen.kt:693 | `ActionButton` (`IC/platform/platform-impl/src/com/intellij/openapi/actionSystem/impl/ActionButton.java`) | IconButton.kt |
| FolderChooser `ToolbarIcon` (toggle) | connect/FolderChooser.kt:173 | `ActionButton` for a `ToggleAction` | ToggleableIconActionButton.kt |
| `TabCloseButton` | ui/ClientScreen.kt:526 | `InplaceButton` (`IC/platform/platform-api/src/com/intellij/ui/InplaceButton.java`): the icon swaps on hover, no background, so not an `ActionButton`; stays as it is | none |
| `Header` | connect/ConnectScreen.kt:477 | welcome screen title row (`IC/platform/platform-impl/src/com/intellij/openapi/wm/impl/welcomeScreen/ProjectsTabFactory.kt`) | none |
| `Notice` | connect/ConnectScreen.kt | `ComponentPanelBuilder` comment text / `JBLabel` with `UIUtil.ComponentStyle.SMALL`. Since 2026-11-23 only for empty lists (`StatusText`-like) and fixed hints; errors, warnings and hints of the connect pages go to `MessageDialog` (`ConnectMessage` with Information/Warning/Error), work in progress to `StartProgress` | Text.kt (InfoText.kt) |
| `Gateway` look object | connect/ConnectScreen.kt:788 | Gateway welcome window (measured) | none |
| `ProjectMenu` (⋮ menu on a project) | connect/ConnectScreen.kt:656 | a popup menu: same as the context menu | Menu.kt |
| `MenuPanel`, `MenuRow`, `Separator` | ui/ContextMenu.kt:214, :284, :349 | `IdeaPopupMenuUI` / `ListPopupImpl` + `PopupListElementRenderer` (`IC/platform/platform-impl/src/com/intellij/ui/popup/list/`) | Menu.kt, PopupContainer.kt |
| `StartProgress` popup | connect/StartProgress.kt:46 | `ProgressDialog` / `DarculaProgressBarUI` (indeterminate) | LinearProgressBar.kt, PopupContainer.kt |
| `LookupPopup`, `LookupRow`, `GroupHeader` | ui/LookupPopup.kt:50, :195, :176 | `LookupUi` + `LookupCellRenderer` (`IC/platform/lang-impl/src/com/intellij/codeInsight/lookup/impl/`) | PopupContainer.kt, GroupHeader.kt |
| `TreeRow` / `ProjectTreeView` (already shared by Project, Structure, FolderChooser) | ui/ProjectTreeView.kt:216, :96 | `Tree` + `DefaultTreeUI` (`IC/platform/ui.tree/src/com/intellij/ui/tree/ui/DefaultTreeUI.java`) | LazyTree.kt |
| `ToolWindowPanel` header | ui/ToolWindows.kt:250 | `ToolWindowHeader` (`IC/platform/platform-impl/src/com/intellij/toolWindow/ToolWindowHeader.kt`) | none |
| `StripeButton`, `ToolWindowBar` | ui/ToolWindows.kt:142, :101 | `StripeButton.kt`, `ToolWindowToolbar` (same folder) | none |
| `MainToolbar` | ui/ToolWindows.kt:162 | `MainToolbar.kt` (`IC/platform/platform-impl/src/com/intellij/openapi/wm/impl/headertoolbar/`) | none |
| `EditorTabs` | ui/ClientScreen.kt:439 | `JBEditorTabs` / `JBTabsImpl.kt` | Tabs.kt |
| `BottomTabs`, `Sidebar` | connect/ConnectScreen.kt:192, :239 | welcome screen's tab list (`TabbedWelcomeScreen.java`) | none |
| 19 hand-built `TextStyle(...)` in 12 files; `DialogStyles` (SshPage.kt:342), `ConnectStyles` (ConnectScreen.kt:768) | | `JBFont.label()`, `JBFont.small()`, `JBFont.h3()` (`IC/platform/util/ui/src/com/intellij/util/ui/JBFont.java`) | Typography.kt |

Left out on purpose: `EditorView`, `EditorLine`, `StickyLines`, `SidewaysScroll`, `SwipePager`,
`HostGroup`, `ProjectRowView`. They're the editor or IDEate-only layout, not a reusable widget.

### Where copies already differ

Theme values from `ManyIslandsDark.theme.json` with names resolved through its `colors` map.

| What | Copies | Theme key → value |
|---|---|---|
| Button border | `#414349` SshPage, FolderChooser; `#4E5157` ConnectScreen | `Button.startBorderColor` = control-border = gray-50 = `#40434A` |
| Text field border | `#4E5157` SshPage, ConnectScreen, FolderChooser | `Component.borderColor` = control-border = `#40434A` |
| Accent (default button, focus ring, checked radio) | `#4670DA` SshPage, FolderChooser; `#4A7CF6` StartProgress bar and ring; `#3574F0` project badge | `Button.default.startBackground` = control-brand-bg = blue-80 = `#3871E1`; `Component.focusColor` = control-brand-border = `#3871E1` |
| Button height | 24 SshPage, FolderChooser; 27 ConnectScreen; 30 StartProgress (with ring) | `Button.minimumSize` 72,28, compact 72,24 |
| Button width | 72 FolderChooser; content + 14+14 SshPage, ConnectScreen; 74 StartProgress | minimum 72 (same key) |
| Button corner | 4 dp everywhere; 5 dp StartProgress | `Button.arc` 8 → 4 dp |
| Field height | 26 SshPage, FolderChooser (24 + ring); none in ConnectScreen (8 dp padding) | `TextField.minimumSize` 49,28, compact 49,24 |
| Field corner | 4 dp | `Component.arc` 8, compact 6 → 4 / 3 dp |
| Field padding | 6,4 SshPage; 8 ConnectScreen | not in ManyIslandsDark (DarculaTextBorderNew insets; to check) |
| Popup background | `#26282C` Menus, Lookups; `#26282B` StartProgress; `#2B2D30` Gateway menu | `Popup.background` = popup-bg = `#26282C` |
| Popup border | `#33353B` Menus, Lookups; `#393B40` StartProgress; `#43454A` Gateway menu | `Popup.borderColor` = popup-border = `#33353B` |
| Popup corner | 8 dp Menus, Gateway menu; 6 dp Lookups; 0 StartProgress | `PopupMenu.borderCornerRadius` 8; completion popup arc 12 → 6 dp |
| Menu row height | 24 Gateway menu; 24 pointer / 40 touch Menus | |
| Dialog outline | `#393B40` SshPage, FolderChooser | no key: the macOS window draws its own border (`#393B40` is measured) |
| Dialog corner | 10 dp SshPage, FolderChooser | macOS window corner; no theme key |
| Selection | `#2E436E` Menus, Gateway sidebar (measured); `#2A4371` Lookups, tree | `*.selectionBackground` = selection-bg-active = blue-50 = `#2A4371` |
| Disabled text | `#777777` Menus; `#5A5D63` Gateway | `ui."*".disabledForeground` = text-disabled = gray-60 = `#4C4F56` (the `*` wildcard rewrites every `.disabledForeground` key, so `MenuItem.disabledForeground` too) |
| Radio / check box | drawn circles, 14 dp, accent dot | SVG icons `radio.svg`, `radioSelected.svg`, `checkBoxSelected.svg`, ... in `IC/platform/platform-impl/resources/com/intellij/ide/ui/laf/icons/darcula/`, 18 px box (`RadioButton.iconSize` 24 includes the 4,4,4,4 insets) |

Decision for review: where a measured value and the theme disagree by a few units (`#414349` vs
`#40434A`, `#4670DA` vs `#3871E1`), the screenshot went through a color-profile change, so the
theme value wins. Where they disagree a lot (Gateway's menu `#2B2D30`/`#43454A`), Gateway was
measured in its own Dark theme; IDEate follows Islands Dark, so the theme value wins there too.

### What is still hand-built (after step 9, 2026-11-17)

Found by searching connect/, ui/ and ui/dialog for `.border(`, `BasicTextField(`, `.clickable`,
`Color(0x` and `RoundedCornerShape(` outside ui/theme and ui/component. Lines are at 04f0664.
"Keep" means it is layout or an IDEate-only view, not an IntelliJ widget we'd share.

| What | file:line | IntelliJ counterpart | Status |
|---|---|---|---|
| Path and status links in project rows (`row.onOpen`, "Authenticate…", "Host unreachable" when clickable) | connect/ConnectScreen.kt:562, :609 | `ActionLink` (Jewel `Link.kt`); `Link.activeForeground` = text-link = blue-100 `#71A1FE` | done: `component/Link.kt` (`ActionLink`, underlined on hover); `IslandsDark.link` is now text-link `#71A1FE` |
| FolderChooser's "Show path" / "Hide path" toggle text | connect/FolderChooser.kt:186 | `ActionLink` | done: `Link` |
| Portrait bottom tabs (This phone, SSH, Link): pill `#393B40`, radius 6 | connect/ConnectScreen.kt:222, :748-749 | the tool window stripe's selected button (see the next row) | keep: gray like the stripe (next row) |
| Tool window stripe button, selected `#393B40`; stripe separator `#393B40` | ui/ToolWindows.kt:151, :293-294 | `SquareStripeButton`; `ToolWindow.Button.selectedBackground` = toolbar-selected-bg-active = `#3871E1`; `ToolBar.separatorColor` = toolbar-border = `#FFFFFF21` | keep `#393B40`, checked on the user's IntelliJ: see the note under this table; the separator stays `#393B40` with it |
| Sidebar rows (Connections list) | connect/ConnectScreen.kt:263 | the welcome screen's tab tree (`TabbedWelcomeScreen`); selection already `selectionBackground` | keep (a list row, like the tree rows) |
| Main toolbar run widget "Current File ⌄" | ui/ToolWindows.kt:209 | `RunWidget` / `RunConfigurationsComboBoxAction` (a toolbar combo button) | keep until run configurations work; then a combo-box-like button |
| Main toolbar project badge `#3574F0` | ui/ToolWindows.kt:187, :297 | `RecentProject.Color1..9.Avatar.Start/End` (a gradient picked per project) | done: `projectAvatarBrush(name)`, the theme's nine gradients, picked as for a non-local project (see the note) |
| Tool window header back chevron | ui/ToolWindows.kt:260 | none in IntelliJ (IDEate's phone layout); looks like an `ActionButton` | could use `IconButton` (22) instead of a 32-wide clickable |
| Editor tab pills, selected `#233657` / `#2C4A82` | ui/ClientScreen.kt:483-494 | `JBEditorTabs` (`EditorTabs.underlinedTabBackground` etc.) | keep (measured on the real client, section 7 of intellij-look.md) |
| Status-bar failure text `#FA6675` | ui/ClientScreen.kt:227 | `Label.errorForeground` = text-error `#F57E84` | done: `IslandsDark.textError` |
| Completion rows | ui/LookupPopup.kt:201-202, :256-257 | `LookupCellRenderer`; `CompletionPopup.foreground`/`matchForeground` already named | keep (one popup, already key-commented) |
| FolderChooser tree border `#2B2D30`, toolbar separator `#393B40` | connect/FolderChooser.kt:197, :244-247 | `FileChooserDialogImpl`'s tree in a `JBScrollPane` (border = `*.borderColor` = tool-window-border `#26282C`); `ToolBar.separatorColor` `#FFFFFF21` | done: new `IslandsDark.toolWindowBorder` `#26282C` and `toolbarSeparator` `#FFFFFF21` |
| Dialog frame outline `#393B40`, radius 10 | ui/dialog/DialogWrapper.kt:179-189 | the macOS window | keep (measured; no key) |

Notes on the rows above (2026-11-17):

- **Stripe selected button.** The user's IntelliJ (Islands Dark) draws a visible but unfocused tool
  window's button `#393B40`: sampled on its Project button, `#393B40`–`#393D42` (the bluish ones
  pick up the project color tint).
  - `SquareStripeButtonLook.getState` returns SELECTED only while the tool window is active
    (`isFocused()` = `toolWindow.isActive`). Only then is `ToolWindow.Button.selectedBackground`
    (the blue `#3871E1`) used.
  - A tool window that is open but unfocused is PUSHED and gets the transparent white
    `ActionButton` background over the stripe's `#26282C`. That blend gives the gray: `#26282C` +
    `#FFFFFF17` = `#393B3F`.
  - IDEate doesn't track which tool window has focus, so the gray stays.
- **Project avatar.** IntelliJ keeps each project's color index in the project's workspace
  (`ProjectWindowCustomizerService`), which the client can't read. `projectAvatarBrush` uses
  IntelliJ's rule for non-local projects instead: `abs(name.hashCode() % 9)`. The colors are
  `RecentProject.ColorN.Avatar.Start`/`End`, from End at the bottom left to Start at the top
  right (`AvatarUtils.generateColoredAvatar`). So `smoke` is Color4 (`#3B92B8`→`#6183EC`), which
  may differ from the color the user's IntelliJ picked for it.
- **Links.** IntelliJ underlines an `ActionLink` under the pointer. The completion popup's typed
  letters keep `CompletionPopup.matchForeground` `#538AF9` in `LookupPopup`; that is not a link.
- **Gallery.**
  - The focused samples (text field, radio button, check box) are rendered one per scene and
    shown as images, because a scene has one focus.
  - The page has a Link row with three avatars and is 760 high now.

## 3. Layout

Mirrors Jewel's `ui/component/` for widgets and IntelliJ's `openapi/ui` for dialogs. Package
`intellijandroid.client.ui.*`, so files sit next to the screens that use them.

```
ui/
  theme/
    IslandsDark.kt        moved from ui/: semantic colors (controlBorder, controlBrandBackground,
                          popupBackground, popupBorder, textDisabled, …), each with its key and
                          how it resolves in a comment; density getters stay here
    Typography.kt         JBFont: label(), small() (comment text), h3() (dialog/page title),
                          editor(); replaces DialogStyles/ConnectStyles and the 19 TextStyle(...)
  component/
    Button.kt             DefaultButton, OutlinedButton             DarculaButtonUI      Jewel Button.kt
    TextField.kt          TextField (focus ring, secret, multi-line) DarculaTextFieldUI   Jewel TextField.kt
    SearchTextField.kt    SearchTextField                           SearchTextField.java Jewel SearchTextField.kt
    RadioButton.kt        RadioButtonRow                            DarculaRadioButtonUI Jewel RadioButton.kt
    Checkbox.kt           CheckboxRow                               DarculaCheckBoxUI    Jewel Checkbox.kt
    Link.kt               Link                                      ActionLink.kt        Jewel Link.kt
    IconButton.kt         IconButton, ToggleIconButton              ActionButton.java    Jewel IconButton.kt
    Divider.kt            Divider, TitledSeparator                  TitledSeparator.java Jewel Divider.kt
    LinearProgressBar.kt  indeterminate + determinate               DarculaProgressBarUI Jewel LinearProgressBar.kt
    PopupContainer.kt     popup background, border, corner          Popup.* keys         Jewel PopupContainer.kt
    Menu.kt               MenuPanel, MenuRow (out of ContextMenu.kt) PopupListElementRenderer Jewel Menu.kt
  dialog/
    DialogWrapper.kt      DialogFrame: title, scrolling body, south panel; buttons in DialogWrapper's
                          order (macOS: sortActionsOnMac, OK rightmost)
    MessageDialog.kt      yours, from AlertMessagesManager.kt, on DialogWrapper.kt
```

Not moved: ContextMenu.kt keeps `ContextMenuArea` (gesture handling) and uses component/Menu.kt.
ProjectTreeView.kt is already the one shared tree; renaming it to component/Tree.kt touches
every screen for no visible gain, so it stays until something else needs a tree.

Each component file starts with the IntelliJ class and theme keys it follows, e.g.

```kotlin
/** IntelliJ's JButton under DarculaButtonUI (IC/.../darcula/ui/DarculaButtonUI.java). */
private object ButtonLook {
  val border = IslandsDark.controlBorder           // Button.startBorderColor = control-border
  val defaultBackground = IslandsDark.controlBrandBg // Button.default.startBackground
  val radius = 4.dp                                // Button.arc 8, halved
  val minWidth = 72.dp                             // Button.minimumSize 72,28 (compact 72,24)
  val height get() = IslandsDark.byDensity(28.dp, 24.dp)
}
```

## 4. Migration order (smallest first)

Each step is one commit and moves callers onto the new file in the same commit. Each is checked on
the desktop app and the emulator before it's pushed.

| Step | What | Who | Status |
|---|---|---|---|
| 1 | `theme/IslandsDark.kt` (moved) with semantic colors, `theme/Typography.kt`; per-file colors switch to theme values | me | done (see below) |
| 2 | `component/Button.kt`: both `DialogButton`s (now one, in MessageDialog.kt), `OutlinedButton`, StartProgress's Cancel | UI session (StartProgress's Cancel: me, in step 7) | done |
| 3 | `component/TextField.kt`: `InputField`, `Field`, the FolderChooser path field. `LabeledField` becomes `Column { Text(label); TextField() }` at the call site (Kotlin UI DSL `row(label)`) | UI session | done |
| 4 | `component/RadioButton.kt` + `Checkbox.kt`: `Choice`, with IntelliJ's `radio*.svg`/`checkBox*.svg` (look in the client dist's `lib/*.jar` first; `IconJar` can take a second jar) | UI session | done |
| 5 | `component/IconButton.kt`: `IconButton`; the main toolbar's Build/Run/Debug/More use it. `ToolbarIcon` and ConnectScreen's `IconButton` move onto it (FolderChooser in step 9, ConnectScreen in step 8). `TabCloseButton` stays (an `InplaceButton`) | me | done |
| 6 | `component/PopupContainer.kt` + `Menu.kt`: `MenuPanel` out of ContextMenu.kt; `LookupPopup`'s frame and StartProgress on `PopupContainer`; Gateway's `ProjectMenu` on `Menu` (step 8) | me | done |
| 7 | `component/LinearProgressBar.kt`: StartProgress's bar; StartProgress's Cancel on step 2's `OutlinedButton` | me | done |
| 8 | `component/SearchTextField.kt`: `SearchRow`; and the rest of ConnectScreen.kt (row icons, `ProjectMenu`, `ConnectStyles`, `Gateway` colors) | me | done |
| 9 | `dialog/DialogWrapper.kt`: `DialogFrame` and FolderChooser's frame, south panel order; `MessageDialog` moves onto it | UI session | done |

### Step 1 (done)

- `ui/IslandsDark.kt` → `ui/theme/IslandsDark.kt` (package `intellijandroid.client.ui.theme`), with
  `UiDensity` and its test. `byDensity(normal, compact)` is public now, for component files.
- New colors, each named after its key: `textDisabled` `#4C4F56`, `textOverAccent`,
  `controlBorder` `#40434A`, `controlBrandBackground` and `focusBorder` `#3871E1`,
  `popupBackground` `#26282C`, `popupBorder` `#33353B`, `selectionBackground` `#2A4371` and
  `selectionInactiveBackground` `#33353B` (these two were `treeSelectionFocused`/`Unfocused`). No
  raw palette (gray-10…): nothing reads one yet.
- `ui/theme/Typography.kt`: `ClientFonts` and `LocalClientFonts` moved here, plus JBFont's sizes as
  functions on `ClientFonts`: `label()` 13, `medium()` 12, `small()` 11, `h4()` 14 bold, `h3()`
  16, `editor()` (JetBrains Mono 12); each takes a color, default `IslandsDark.text`. Used in
  ClientScreen, ContextMenu, LookupPopup, ProjectTreeView, StructureView, ToolWindows and
  StartProgress. `DialogStyles` (SshPage), `ConnectStyles` (ConnectScreen), FolderChooser's and
  MessageDialog's styles move over in steps 2 to 9.
- `IconProvider`, `LocalIcons` and `LocalEditorTextInput` stay in package `ui` (`ui/CompositionLocals.kt`).
- Colors switched to the theme (section 2): button and field borders in SshPage, FolderChooser,
  ConnectScreen and MessageDialog; the accent in the same files; the context menu's background,
  border, selection, shortcut and disabled text; the completion popup's background, border,
  selection and secondary text; Gateway's ⋮ menu, sidebar selection and unreachable-host text;
  StartProgress's background, border, track (`ProgressBar.trackColor` `#40434A`) and fill.
- Checked: desktop app (IDE screen, editor context menu, connect screen) and the emulator (Project
  menu by long press). The visible change is in the menus: disabled items are dimmer
  (`#4C4F56`, was Darcula's `#777777`). Not yet compared with a screenshot of real IntelliJ.

### Step 2 (done)

- `ui/component/Button.kt`: `DefaultButton(text, modifier, enabled, onClick)` and
  `OutlinedButton(...)`, after Jewel's `Button.kt` and `IntUiBridgeButton.kt`. `onClick` is last so
  callers can pass a trailing lambda.
- Values from the theme:

  | Value | Theme key |
  |---|---|
  | border `#40434A` | `Button.startBorderColor` |
  | default `#3871E1`, text white | `Button.default.startBackground` / `.foreground` |
  | disabled text `#4C4F56`, border `#33353B` | `*.disabledText` / `*.disabledBorderColor` |
  | corner 4 dp | `Button.arc` 8 |
  | minimum 72 × 28, compact 72 × 24 | `Button.minimumSize` |
  | padding 14 | `DarculaButtonUI.HORIZONTAL_PADDING` |
  | focused: 2 px `#3871E1` ring outside, border `#191A1C` | `Component.focusColor`, `Button.focusedBorderColor` |

- Callers moved: SshPage (Copy Public Key, Cancel, Check Connection and Continue, Start IDE and
  Connect, Authenticate, …), MessageDialog, FolderChooser (OK, Cancel), ConnectScreen
  (`OutlinedButton` removed). Buttons are now 28 high at normal density (were 24 or 27).
- Checked: desktop, the SSH page and its Connect to SSH dialog.

### Step 3 (done)

- `ui/component/TextField.kt`: `TextField(value, onValueChange, modifier, enabled, secret,
  singleLine, maxHeight, keyboardOptions, keyboardActions)`, after Jewel's `TextField.kt` and
  `IntUiBridgeTextField.kt`.

  | Value | Theme key |
  |---|---|
  | background `#191A1C` | `TextField.background` = control-bg |
  | border `#40434A` | `Component.borderColor` |
  | focused: 2 px of `#3871E1` (the border plus 1 px outside) | `Component.focusColor` |
  | disabled border `#33353B`, text `#4C4F56` | `*.disabledBorderColor`, `*.disabledForeground` |
  | corner 4 dp | `Component.arc` 8 |
  | height 28, compact 24 | `TextField.minimumSize` 49,28 |
  | padding 9 | 8 + the border, as `IntUiBridgeTextField` |

- Text is in the UI font (Inter 13), as in IntelliJ's fields; the copies used the editor's mono
  font.
- Callers moved: SshPage (`InputField` removed; `LabeledField` stays as the label-over-field row),
  ConnectScreen (`Field` removed; Link page's two fields), FolderChooser's path field.
- New debug aid: `CLIENT_GALLERY=<file.png> ./gradlew :desktop:run` renders every shared component
  in its states (`desktop/…/ComponentGallery.kt`) and exits. Checks need no clicking through
  screens, so a misplaced click can't reach a running backend. Add each new component to it.
  Button focus doesn't show there: Compose focuses buttons only from the keyboard.

### Steps 5 to 7 (done)

- `IconButton(iconPath, contentDescription, onClick, modifier, size = 22.dp, iconSize = 16.dp,
  selected = false, hoveredIconPath = null)`, IntelliJ's `ActionButton`: hover `#FFFFFF17`
  (`ActionButton.hoverBackground` = transparent-white-20), pressed and selected `#FFFFFF29`
  (`ActionButton.pressedBackground` = transparent-white-40), corner 4 (`Button.arc` 8). The main
  toolbar's buttons use it at `IslandsDark.toolbarButton`/`toolbarIcon` and now show hover and
  press, which they didn't before. For ConnectScreen's `IconButton` (25.5 × 28, 16 icon) and
  FolderChooser's `ToolbarIcon` (22, toggled `#393B40` measured; the theme's selected is
  `#FFFFFF29`).
- `Modifier.popupContainer(radius)`: `Popup.background`, a 1 px `Popup.borderColor`, rounded.
  Used by `Menu` (8), the completion popup (6) and StartProgress (0, Gateway's square popup).
- `Menu(entries, rowHeight, close)`: the panel that was `MenuPanel` in ContextMenu.kt; the menu
  types (`MenuEntry`, `MenuItem`, `SubMenu`, `MenuSeparator`) stay in ContextMenu.kt (package `ui`)
  so their importers don't change. `ContextMenuArea` and, next, Gateway's ⋮ menu (`ProjectMenu`)
  open it.
- `IndeterminateHorizontalProgressBar(modifier)` (Jewel's name, file `LinearProgressBar.kt`): the
  4 dp track with a sliding segment from StartProgress. Single color; IntelliJ's gradient
  (`indeterminateStartColor` blue-110 → `indeterminateEndColor`) is not done. Replaced on
  2026-11-17, see "Indeterminate bar and narrow StartProgress".
- StartProgress's Cancel is step 2's `OutlinedButton`, focused when the popup opens (Gateway
  shows it with the focus ring; Enter cancels).
- Checked: the component gallery (`CLIENT_GALLERY`), the desktop app (Project menu, toolbar) and
  the emulator (Project menu by long press). Hover and press can't be shown in the gallery's still
  image; the completion popup's frame wasn't opened on screen (background typing doesn't reach the
  window), it uses the same `popupContainer` as the menu.
### Step 4 (done)

- `ui/component/RadioButton.kt` (`RadioButtonRow(text, selected, modifier, enabled, onClick)`) and
  `ui/component/Checkbox.kt` (`CheckboxRow(text, checked, modifier, enabled, onCheckedChange)`),
  after Jewel's `RadioButtonRow` / `CheckboxRow`.
- The icons are the New UI's own, `themes/expUI/icons/dark/radio*.svg` and `checkBox*.svg` in the
  client's `intellij.platform.resources.jar` (the old `laf/icons/darcula/` ones are Darcula's).
  Islands Dark's icon palette only remaps `#CED0D6`, so they draw as shipped: background
  `#2B2D30`, border `#5A5D63`, selected `#3574F0` with a white mark, disabled `#393B40` with a
  `#6F737A` mark. The Android app carries only the icons jar, so the 12 files are inlined in
  `IconJar.EXTRA_ICONS` as `laf/radio.svg`, `laf/checkBoxSelectedFocused.svg`, …
- 24 dp icon (the shape is 16 inside it, `RadioButton.iconSize` 24 with 4 px insets), 4 dp to the
  text (`RadioButton.iconTextGap`, `CheckBox.textIconGap`).
- Callers moved: SshPage's `Choice` (Authentication choices, IDE choice, Save password) is gone.
- IconJar.kt was touched for the extra icons (not in either list in section 5).

### Step 9 (done)

- `ui/dialog/DialogWrapper.kt`:
  `DialogWrapper(title, actions, onCancel, modifier, width, maxHeight, error, contentPadding,
  scrollable, content)`, after IntelliJ's `DialogWrapper.java`. It has the title row, the center
  panel, the error text (`Label.errorForeground` = text-error `#F57E84`, new
  `IslandsDark.textError`) and the south panel.
- `DialogAction(label, enabled, onClick)` stands for `createActions()`. The first is the default:
  filled, and Enter runs it. The south panel puts it last on macOS (`sortActionsOnMac`) and first
  elsewhere, with 12 between buttons (`BASE_BUTTON_GAP`). Escape or a click outside cancels.
- `ui/dialog/MessageDialog.kt` (moved from `ui/`, package `intellijandroid.client.ui.dialog`) uses
  the same window and south panel. Its `MessageOption` is gone; it takes `DialogAction`s.
- The frame values stay measured: no theme key exists, since macOS draws the window. Width 475,
  title 32, sides 14, south panel 12 below the content, 11 at the bottom, corner 10, outline
  `#393B40`.
- Callers moved:
  - SshPage's Connect to SSH, Choose IDE and Project and Authentication. Their buttons became
    `actions` and their error moved to `error`. `DialogFrame`, `DialogLook` and `DialogStyles` are
    gone; labels use `fonts.label()`.
  - FolderChooser now runs on `DialogWrapper(contentPadding = 0, scrollable = false)`, and its
    `ToolbarIcon` became step 5's `IconButton(selected = …)`.
- `CLIENT_GALLERY=<out.png>` now also writes `<out>-dialog.png` (a `DialogWrapper` with fields,
  radio buttons and an error) and `<out>-message.png` (the host key question).

### Step 8 (done)

- `SearchTextField(value, onValueChange, modifier, placeholder = "Search", history = false,
  undecorated = false)`, after IntelliJ's `SearchTextField.java` as `TextFieldWithPopupHandlerUI`
  paints it, and Jewel's `SearchTextField`.
  - The search icon sits before the text: `AllIcons.Actions.Search` = `expui/general/search.svg`,
    or `SearchWithHistory` = `expui/inline/searchHistory.svg` with [history]. The paths come from
    `PlatformIconMappings.json`.
  - History (2026-11-17): `SearchTextField(history = SearchHistory())`.
    - `SearchHistory` keeps 5 searches, newest first. A repeat moves to the top, compared ignoring
      case, and an entry already at the top stays as it is (`SearchTextField.MyModel.addElement`).
    - The text joins the history when the field loses focus and when the popup opens.
    - A click or tap on the icon opens the list under the field: `Menu` with 24 or 40 rows, by
      pointer or touch. Choosing an entry puts it in the field, with the caret at the end.
    - Kept in memory for the app's life. IntelliJ saves it only under a property name, which the
      connect screen's field doesn't have.
    - The connect screen's pages share one `SearchHistory` (`connectSearches`).
- `TextField` keeps its own caret (`TextFieldValue`). Text set from outside puts the caret at the end,
  as Swing's `setText` does; before, a chosen history entry left the caret at the start.
  - Once there is text, a clear icon follows it (`Actions.Close`/`CloseHovered` =
    `expui/general/closeSmall.svg`/`closeSmallHovered.svg`); a click empties the field. The gap
    between icon and text is 2 (`getSearchIconGap`, `getClearIconGap`).
- `TextField` gained Jewel's optional `placeholder` (`text-secondary`, Swing's `emptyText`),
  `leadingIcon`, `trailingIcon` and `undecorated` (no border, background, ring or side padding).
  Existing calls are unchanged.
- ConnectScreen.kt:
  - `SearchRow` keeps its signature (SshPage calls it). Inside it is
    `SearchTextField(history = true, undecorated = true)`, as the welcome screen empties its search
    field's border (`RecentProjectFilteringTree.installSearchField`).
  - The trailing row icons (Reconnect, Open Project, Host Settings, Stop, ⋮) are `IconButton`s,
    one every 25.5 as measured. They now show hover and press.
  - `ProjectMenu` (the ⋮ and gear popups) is `Menu` with pointer rows; Gateway's own popup colors
    (`#2B2D30`/`#43454A`) and its 237 width are gone.
  - `ConnectStyles` maps onto Typography: `body` = `label()`, `secondary` = `label(text-secondary)`,
    `title` = `h3()` semi-bold. `host` stays 15 semi-bold, measured; no JBFont size matches.
    SshPage still takes `ConnectStyles`.
  - `Gateway` colors from keys:
    - `background` `#191A1C` (`WelcomeScreen.Details.background`, `SidePanel.background` =
      dialog-bg; was `#1C1D20`);
    - `divider` `#33353B` (`WelcomeScreen.separatorColor`, rewritten by `*.separatorColor` =
      popup-border; was `#2B2D30`);
    - `running` `#6DB083` (new `IslandsDark.textSuccess` = text-success = green-100; was
      `#6AAB73`);
    - `error` = `IslandsDark.textError`.
  - Still measured, no key: the sizes, the portrait tab pill `#393B40`.
- Checked:
  - Gallery: the three search field states.
  - Desktop: the Link and SSH pages, and the host gear menu opened and closed with nothing chosen.
  - Emulator: the SSH page; typing shows the clear icon, and tapping it empties the field.

## 5. Split

By file, so the two sessions never edit the same file at once (the "Who" column above):

| Who | Files |
|---|---|
| me | ui/theme, ui/ContextMenu.kt, ui/LookupPopup.kt, ui/ClientScreen.kt (TabCloseButton), ui/ToolWindows.kt, connect/StartProgress.kt, new ui/component files for steps 5 to 7 |
| UI session | connect/SshPage.kt, connect/ConnectScreen.kt, connect/FolderChooser.kt, ui/MessageDialog.kt → ui/dialog, new ui/component files for steps 2 to 4 and 8, ui/dialog |

Step 1 went first and alone; the rest run in parallel.

## Indeterminate bar and narrow StartProgress (2026-11-17)

User report with a phone screenshot: the start popup touched both sides of the screen, and the bar
(a blue segment sliding over a grey track) didn't look like IntelliJ's.

**The bar** now paints as `DarculaProgressBarUI.paintIndeterminate` (`IC/platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/DarculaProgressBarUI.java`, not simplified):
no track and no segment; the whole rounded bar is one mirrored gradient that moves right in steps.

| Value | IntelliJ | IDEate |
| --- | --- | --- |
| Frames | `CYCLE_TIME_DEFAULT` 800 ms / `REPAINT_INTERVAL_DEFAULT` 50 ms = 16 | the same; the frame changes every 50 ms, no gliding |
| Gradient | `GradientPaint(x + frame·12, start, x + 16·6 + frame·12, end, cyclic = true)`, step 6 | `Brush.linearGradient(…, TileMode.Mirror)`, same offsets in dp |
| Start color | `ProgressBar.indeterminateStartColor` = blue-110 | `#92B7FF` |
| End color | Islands Dark writes `indeterminateendcolor` (lower case), which UIManager doesn't match, so Experimental Dark's `indeterminateEndColor` = Blue5 applies | `#366ACE` |

One 800 ms cycle moves the gradient 192 dp, one full mirrored period, so the loop has no jump.

**The popup** keeps Gateway's 459 dp when the window has 459 + 2 × 16 dp; narrower, it takes the
width minus 16 dp each side. Example: a 411 dp phone held upright gives a 379 dp popup. There,
instead of the Cancel button (which used to take a row of its own under the detail line), the bar
ends with IntelliJ's compact cancel: `InlineProgressIndicator.createCancelButton()`, an
`InplaceButton` with `AllIcons.Process.Stop` (`process/stop.svg`, a round ×, 14 px, grey at 50 %;
`stopHovered.svg` at 90 % under the pointer), as in IntelliJ's status bar and Background Tasks
popup. Its tap area is 20 × 24 dp with the icon at the right end: 6 dp after the bar, flush with the
detail line's right edge. The user chose this on 2026-11-19 from three options (× after the bar,
Cancel beside the text everywhere, detail and Cancel sharing the last row). Checked in the gallery: `CLIENT_GALLERY=<out>.png`
now also writes `<out>-progress.png` (900 dp) and `<out>-progress-narrow.png` (411 dp).
