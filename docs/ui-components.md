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
| `TabCloseButton` | ui/ClientScreen.kt:526 | `InplaceButton` / `CloseTab` in editor tabs | IconButton.kt |
| `Header` | connect/ConnectScreen.kt:477 | welcome screen title row (`IC/platform/platform-impl/src/com/intellij/openapi/wm/impl/welcomeScreen/ProjectsTabFactory.kt`) | none |
| `Notice` | connect/ConnectScreen.kt:469 | `ComponentPanelBuilder` comment text / `JBLabel` with `UIUtil.ComponentStyle.SMALL` | Text.kt (InfoText.kt) |
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
| 2 | `component/Button.kt`: both `DialogButton`s (now one, in MessageDialog.kt), `OutlinedButton`, StartProgress's Cancel | UI session | done, except StartProgress's Cancel (its file is yours: use `OutlinedButton`) |
| 3 | `component/TextField.kt`: `InputField`, `Field`, the FolderChooser path field. `LabeledField` becomes `Column { Text(label); TextField() }` at the call site (Kotlin UI DSL `row(label)`) | UI session | done |
| 4 | `component/RadioButton.kt` + `Checkbox.kt`: `Choice`, with IntelliJ's `radio*.svg`/`checkBox*.svg` (look in the client dist's `lib/*.jar` first; `IconJar` can take a second jar) | UI session | to do |
| 5 | `component/IconButton.kt`: `IconButton`, `ToolbarIcon`, `TabCloseButton` | me (ConnectScreen and FolderChooser call sites: UI session) | to do |
| 6 | `component/PopupContainer.kt` + `Menu.kt`: `MenuPanel` out of ContextMenu.kt; `LookupPopup`'s frame and StartProgress on `PopupContainer` | me (`ProjectMenu` call site: UI session) | to do |
| 7 | `component/LinearProgressBar.kt`: StartProgress's bar | me | to do |
| 8 | `component/SearchTextField.kt`: `SearchRow` | UI session | to do |
| 9 | `dialog/DialogWrapper.kt`: `DialogFrame` and FolderChooser's frame, south panel order; `MessageDialog` moves onto it | UI session | to do |

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

## 5. Split

By file, so the two sessions never edit the same file at once (the "Who" column above):

| Who | Files |
|---|---|
| me | ui/theme, ui/ContextMenu.kt, ui/LookupPopup.kt, ui/ClientScreen.kt (TabCloseButton), ui/ToolWindows.kt, connect/StartProgress.kt, new ui/component files for steps 5 to 7 |
| UI session | connect/SshPage.kt, connect/ConnectScreen.kt, connect/FolderChooser.kt, ui/MessageDialog.kt → ui/dialog, new ui/component files for steps 2 to 4 and 8, ui/dialog |

Step 1 went first and alone; the rest run in parallel.
