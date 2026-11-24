# Icons in the light and the dark theme (2026-11-24)

How IntelliJ IDEA 2026.3 (JetBrains Client 263.7307.200) picks and recolours an icon for the active theme,
read from its bytecode (`javap` on the client's jars) and theme files, and what Ideate does today. This is
the reference for the icon part of the light theme (the theme core, `UiTheme` and `LafManager`, is the UI
session's; see coordination.md).

## 1. Which file: the `_dark` suffix

`ImageDescriptorKt.createImageDescriptorList(path, isDark, isStroke, scale)` (intellij.platform.util.ui.jar)
lists the candidate files, tried in order; the first that exists wins. For an SVG the scale doesn't change
the name (`@2x` is for raster images only):

| Request | Files tried | Example for `expui/general/add.svg` |
|---|---|---|
| light | `name.svg` | `add.svg` (fill `#6C707E`) |
| dark | `name_dark.svg`, then `name.svg` | `add_dark.svg` (fill `#CED0D6`) |
| stroke (`isStroke`) | `name_stroke.svg` first, then the list above | — |

`isDark` is global: `AppUIUtil.updateForDarcula(isDark)` calls `JBColor.setDark` and
`IconLoader.setUseDarkIcons(isDark)`, which swaps the global `IconTransform` (its `isDark` field) and clears
the icon caches (`IconUtilKt.updateTransform` → `clearCacheOnUpdateTransform`). The theme's `dark` flag
decides it: `UIThemeLookAndFeelInfoImpl.installTheme` puts `ui.theme.is.dark` and ends with
`AppUIUtil.updateForDarcula(theme.isDark)`.

## 2. Path mappings: the theme's `icons` map

A theme's `"icons"` entries other than `ColorPalette` map one icon path to another. `installTheme` installs
them with `IconLoader.installPathPatcher(theme.patcher)`; the mapped path then goes through the `_dark` rule.

| Theme | Example mapping |
|---|---|
| Islands Light (23 entries, from its parent expUI_light) | `/windows/minimize.svg` → `/expui/windows/minimize.svg`; `/com/intellij/ide/ui/laf/icons/intellij/checkBox.svg` → `/themes/expUI/icons/dark/checkBox.svg` |
| expUI_dark (15 entries) | `/com/intellij/ide/ui/laf/icons/darcula/checkBox.svg` → `/themes/expUI/icons/dark/checkBox.svg` |

So both themes draw check boxes and radio buttons from the same `themes/expUI/icons/dark/*.svg` files
(in intellij.platform.resources.jar); the palette (next section) gives them the light colours.

## 3. Colours: the theme's `icons.ColorPalette`

`UiThemePaletteScopeManager.configureIcons` sorts the palette's keys into scopes, and `getScopeByPath`
picks the scope for each icon by its path:

| Icon path | Scope | How it patches |
|---|---|---|
| `themes/expUI/icons/dark/…` (themes based on ExperimentalDark/Light, as both Islands are) | `UiThemePaletteCheckBoxScope` → `NewThemeCheckboxPatcher` | by element **id**: `PaletteKeys` splits the `id` at `_` into a fill key and a stroke key, e.g. `id="Checkbox.Background.Default_Checkbox.Border.Default"` → fill = palette[`Checkbox.Background.Default`], stroke = palette[`Checkbox.Border.Default`]; a colour's alpha goes to `fill-opacity` / `stroke-opacity` |
| `com/intellij/ide/ui/laf/icons/…treeCollapsed.svg`, `treeExpanded.svg` | trees (`Tree.iconColor`) | by colour value |
| `com/intellij/ide/ui/laf/icons/…` with `check` or `radio` in the name | classic check boxes (`Checkbox.*` keys against the classic colours in `UIThemeKt`, e.g. `Checkbox.Background.Default` = `#FFFFFF`) | by colour value |
| everything else | ui: `#RRGGBB` keys, `Actions.*`, `Objects.*` | by colour value: `SvgKt.newSvgPatcher` replaces `fill` / `stroke` values found in the map |

The two Islands palettes:

| Theme | `icons.ColorPalette` | Effect |
|---|---|---|
| Islands Dark | `"#CED0D6": "icon-default-stroke"` (→ `gray-120` = `#C3C5CB`) | every `_dark` icon's `#CED0D6` becomes `#C3C5CB` |
| Islands Light | 9 `Checkbox.*` keys, e.g. `Checkbox.Background.Default: #FFFFFF`, `Checkbox.Border.Default: #A8ADBD`, `Checkbox.Background.Selected: #3574F0` | only check boxes and radio buttons; ordinary icons keep their light colours (`#6C707E`) |

No `IconColorsOnSelection` in either Islands theme or their parents, so selected rows don't recolour icons.

## 4. What Ideate does

| Part | Before (dark only) | Now |
|---|---|---|
| `IconJar.svg(path, theme)` (compose/…/ui/IconJar.kt) | always `name_dark.svg`, then `name.svg`, then `#CED0D6` → `#C3C5CB` | the §1 rule by `theme.isDark` (default `LafManager.currentTheme`), then the theme's palette; an unknown theme id fails at once |
| `IconJar.remember` (plugin icons sent with their SVGs) | kept only the `_dark` one | keeps both files; `svg` picks by theme |
| `IconJar.extra(path, theme)`: `laf/radio*`, `laf/checkBox*` | dark copies with the dark colours baked in, served raw | the same `themes/expUI/icons/dark/` files with their ids; Light recolours them by id from its 9 `Checkbox.*` keys (§3), Dark has none and keeps them as drawn |
| `extra`: `markdown/link.svg`, `ideate/phone.svg` | one file each, `#C3C5CB` | two files each: `link_dark.svg` as the plugin's (`#CED0D6`, then the Dark palette), `link.svg` as the plugin's light one (`#6C707E`); the phone the same way |
| Painter caches (desktop `DesktopResources.JarIcons`, Android `AndroidResources.SvgIcons`) | by path | by `theme.id` and path; `painter()` reads `LafManager.currentTheme`, which is snapshot state, so the composables that asked redraw on a switch (as `clearCacheOnUpdateTransform`, without clearing: both themes stay cached) |
| Android `icons.jar` asset (android/build.gradle.kts) | every SVG of the client's jars, so both variants are already there | — |

The palettes live in `IconJar.ICON_PALETTES`, keyed by theme id. IntelliJ keeps them in the theme
(`UITheme.colorPatcher`); they can move into `UiTheme` once it reads the theme JSON's `icons` map.
`IconJarTest` checks the id patching and the file choice for both themes.
