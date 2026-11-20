# IntelliJ's look: Islands Dark editor and Project tree

Reference values for `client/` (used by `compose/src/commonMain/kotlin/intellijandroid/client/ui/IslandsDark.kt` and `EditorView.kt`). Researched 2026-11-15. The client takes editor text colors from the backend's color scheme at run time; the tables below are for the chrome around it and for checking.


Legend: **Seen** = read in an installed file or in source at tag `idea/263.7307.200`. **Inferred** = computed from Seen code/values, or not readable without running the IDE.
All lengths are logical px (dp). On Retina (scale 2) multiply by 2 for device px.
Install root: `IDE=<IntelliJ IDEA.app>/Contents`. GH = github.com/JetBrains/intellij-community at tag `idea/263.7307.200`.
Values were read from the installed IDE's jars and from the IntelliJ Community sources at the tag above.

Things I could NOT read (user config is off limits) and that change numbers; confirm with the user:
1. Editor line spacing (default 1.2, `FontPreferences.DEFAULT_LINE_SPACING`). The user may have changed it.
2. Compact mode (IntelliJ's default is off). The user's IntelliJ runs in compact mode (`UI_DENSITY = COMPACT`, seen 2026-11-17), and so does IDEate by default. Both densities are in section 11; values below marked "compact" are the same pairs.
3. Whether line numbers / folding outline / git change markers / breadcrumbs are shown (assumed default: line numbers on, folding outline on, gutter icons on, left free painters (git markers) shown in a git project).

---------------------------------------------------------------------

## 0. How the theme resolves (Seen)

| Layer | File | Notes |
|---|---|---|
| LaF "Islands Dark" | `$IDE/lib/intellij.platform.resources.jar` `themes/islands/ManyIslandsDark.theme.json`; registered in `lib/intellij.platform.ide.impl.jar` plugin xml: `<themeProvider id="Islands Dark" path="/themes/islands/ManyIslandsDark.theme.json" targetUi="islands"/>` | `"parentTheme": "ExperimentalDark"`, `"editorScheme": "Islands Dark"`, `ui.Islands = 1` (many-islands mode on) |
| parent LaF | same jar `themes/expUI/expUI_dark.theme.json` (id ExperimentalDark) then `themes/darcula.theme.json` | provides keys the child does not override |
| color names | `colors` map in ManyIslandsDark.theme.json (gray-10..160, blue-.., etc.) and aliases such as `layer-0-bg` | resolved values in tables below |
| `ui."*"` wildcard | ManyIslandsDark.theme.json `ui["*"]` | rewrites EVERY existing key ending in `.background`, `.foreground`, `.selectionBackground`, `.selectionInactiveBackground`, `.hoverBackground`, `.infoForeground` ... (GH `platform/platform-impl/src/com/intellij/ide/ui/UITheme.kt` `applyTheme`: for key `*.x` all keys ending `.x` are overwritten, specific keys later in the file win). This is why `Tree.background` is NOT Darcula's `#3C3F41` |
| Editor scheme "Islands Dark" | `lib/intellij.platform.resources.jar` `themes/islands/IslandSchemeDark.xml` (registered `lib/intellij.platform.projectModel.impl.jar` `<bundledColorScheme id="Islands Dark" path="/themes/islands/IslandSchemeDark.xml"/>`) | `parent_scheme="Darcula"` |
| parent scheme "Darcula" | `lib/intellij.platform.resources.jar` `DefaultColorSchemesManager.xml` (second `<scheme name="Darcula" ... default_scheme="true">`, line 1562 on) | **Darcula does NOT inherit "Default"**: schemes with `default_scheme="true"` get `parentScheme = null` (GH `platform/editor-ui-ex/src/com/intellij/openapi/editor/colors/impl/AbstractColorsScheme.java` `readScheme`: `if (!isDefault) parentScheme = ...`). So the chain is Islands Dark -> Darcula only; the light-ish `Default` values (e.g. `INSTANCE_FIELD_ATTRIBUTES 660e7a`) are never used |
| attribute lookup rule | GH `.../colors/impl/EditorColorsSchemeImpl.java getAttributes` + `AbstractColorsScheme.getFallbackAttributes` | own scheme, then Darcula; entry with empty `<value>` = "inherit"; if no entry: follow `TextAttributesKey` fallback key (`DefaultLanguageHighlighterColors`, `JavaHighlightingColors` in GH `platform/core-api/.../DefaultLanguageHighlighterColors.java`, `java/java-frontback-psi-impl/.../JavaHighlightingColors.java`) |

Editor `FONT_TYPE`: 0 plain, 1 bold, 2 italic, 3 bold italic. Editor `EFFECT_TYPE` (GH `AttributesFlyweight.toEffectType`): 0 BOXED, 1 LINE_UNDERSCORE, 2 WAVE_UNDERSCORE, 3 STRIKEOUT, 4 BOLD_LINE_UNDERSCORE, 5 BOLD_DOTTED_LINE.

## 1. Fonts (Seen)

All inside the bundled JBR: `$IDE/jbr/Contents/Home/lib/fonts/`
| Use | File | Metrics (Seen, parsed from head/hhea/OS2) |
|---|---|---|
| Editor regular | `JetBrainsMono-Regular.ttf` | unitsPerEm 1000, ascent 1020, descent 300, lineGap 0, advance 600 (monospace, 0.6 em) |
| Editor bold (`FONT_TYPE` 1) | `JetBrainsMono-Bold.ttf` | |
| Editor italic (`FONT_TYPE` 2) | `JetBrainsMono-Italic.ttf` | JetBrains Mono has a true italic |
| Editor bold italic (3) | `JetBrainsMono-BoldItalic.ttf` | |
| other weights present | Thin, ExtraLight, Light, Medium, SemiBold, ExtraBold (+Italic each) | not used by the scheme |
| UI regular | `Inter-Regular.otf` | unitsPerEm 2816, ascent 2728, descent 680, lineGap 0 (0.969 / 0.241 em) |
| UI semibold | `Inter-SemiBold.otf` | used for "bold" UI text; there is no Inter Bold/Medium file |
| UI italic | `Inter-Italic.otf`, `Inter-SemiBoldItalic.otf` | |

Editor font size 12 -> char advance = 0.6 * 12 = **7.2 px** (fractional; fractional metrics are ON on macOS, see section 7). Line number font = editor font size + `editor.gutter.linenumber.font.size.delta` (default -1, `intellij.platform.ide.impl.xml` advancedSetting) = **11** -> advance 6.6 px.
Ligatures: **off** by default (`FontPreferencesImpl.myUseLigatures = false`, `AppEditorFontOptions.USE_LIGATURES = false`). Default family is JetBrains Mono; default size would be 13, user has 12 (GH `FontPreferences.java`).

## 2. Editor

### 2.1 Colors (Islands Dark scheme resolved through Darcula)

| Item | Value | Key | Source |
|---|---|---|---|
| Editor background | `#191A1C` | `TEXT` attr BACKGROUND (= `layer-0-bg`) | Seen `IslandSchemeDark.xml` |
| Default foreground | `#BCBEC4` | `TEXT` attr FOREGROUND; `DEFAULT_IDENTIFIER` also `#BCBEC4` | Seen |
| Caret color | `#CED0D6` | `CARET_COLOR` | Seen |
| Caret width | **2 px** logical (4 device px), bar caret, blinking | registry `editor.caret.width=2` (GH `platform/util/resources/misc/registry.properties`), `EditorCaretPainter` uses `JBUIScale.scale(settings.getLineCursorWidth()) * editor scale` | Seen |
| Caret height | = font metrics height (17 px at JBM 12), centered in the line, i.e. 2 px shorter than the 21 px line at top and bottom (full-line-height caret is off by default) | `EditorView.createNewMetrics` (caretHeight = lineHeight + topOverhang + bottomOverhang) | Inferred (arithmetic in 2.3) |
| Caret row background | `#1F2024` (spans full width, also painted across the gutter) | `CARET_ROW_COLOR`; `EditorGutterComponentImpl.getCaretRowColor/paintBackground` | Seen |
| Selection background (focused) | `#214283` | `SELECTION_BACKGROUND` (Darcula; Islands does not override) | Seen `DefaultColorSchemesManager.xml` |
| Selection background (inactive editor) | `#4C4F56` | `SELECTION_BACKGROUND_INACTIVE` (Darcula) | Seen |
| Selection foreground | none (text keeps its own colors) | `SELECTION_FOREGROUND` empty | Seen |
| Line number | `#4B5059` | `LINE_NUMBERS_COLOR` | Seen |
| Current line number (caret line) | `#A1A3AB` | `LINE_NUMBER_ON_CARET_ROW_COLOR` | Seen |
| Gutter background | = editor background `#191A1C` | New UI uses `EDITOR_GUTTER_BACKGROUND`; Islands sets it empty -> null -> `editor.getBackgroundColor()` (GH `EditorGutterColor.getEditorGutterBackgroundColor`). Darcula `GUTTER_BACKGROUND 313335` is NOT used in New UI | Seen |
| Gutter / editor separator | 1 px vertical line at x = gutterWidth - 3 (+1 on Retina), color `INDENT_GUIDE_COLOR` `#323438`; the last 3 px of the gutter are editor background | `EditorGutterComponentImpl.paint` (`isNewUI` branch), `getWhitespaceSeparatorOffset` = width - 3 | Seen (very low contrast, verify against a screenshot) |
| Indent guide | `#323438`, 1 px solid vertical line at the indent column start, moved 2 px left (`EditorPainter.getIndentGuideShift` = −round(5 × fontSize / 13) / 2); selected (caret on that column inside the block) `#4E5157` | `INDENT_GUIDE`, `SELECTED_INDENT_GUIDE`; `IndentGuideRenderer`, `IndentsModelImpl.getCaretIndentGuide` (Community source 263.7307.200) | Seen |
| Right margin line | `#323438` | `RIGHT_MARGIN_COLOR` | Seen |
| Whitespace symbols | `#6F737A` | `WHITESPACES` | Seen |
| Matched brace | background `#43454A`, bold | `MATCHED_BRACE_ATTRIBUTES` | Seen |
| Folded text | fg `#868991`, bg `#393B40`; border `#2B2D30` | `FOLDED_TEXT_ATTRIBUTES`, `FOLDED_TEXT_BORDER_COLOR` | Seen |
| Identifier under caret | bg `#373B39` | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` | Seen |
| Search result | bg `#2D543F` | `SEARCH_RESULT_ATTRIBUTES` | Seen |
| Scrollbar (Mac overlay) | thumb `#FFFFFF26`, hover `#FFFFFF4D` | `ScrollBar.Mac.thumbColor` | Seen |
| VCS gutter stripes | added `#549159`, modified `#375FAD`, deleted `#868A91` | `ADDED_LINES_COLOR`, `MODIFIED_LINES_COLOR`, `DELETED_LINES_COLOR` | Seen |
| File status text (project tree + tabs) | modified `#70AEFF`, added `#73BD79`, unknown/untracked `#E88F89`, ignored `#D69A6B`, deleted `#6F737A`, merged `#CF84CF`, conflict `#DE6A66`, renamed `#70AEFF` | `FILESTATUS_*` | Seen |

### 2.2 Text attributes, Java, fully resolved

Columns: foreground / background / font style / effect (type + color). "inherits" = no own foreground, uses `#BCBEC4`. The last column shows the scheme and fallback chain that supplied the value (all Seen: resolved against the scheme XML plus the key fallback map from the GH sources).

| Item | Key | Foreground | Background | Font | Effect | Resolved via |
|---|---|---|---|---|---|---|
| Keyword | `JAVA_KEYWORD` | #CF8E6D |  | plain |  | Islands Dark via JAVA_KEYWORD>DEFAULT_KEYWORD |
| String | `JAVA_STRING` | #6AAB73 |  | plain |  | Islands Dark via JAVA_STRING>DEFAULT_STRING |
| Number | `JAVA_NUMBER` | #2AACB8 |  | plain |  | Islands Dark via JAVA_NUMBER>DEFAULT_NUMBER |
| Line comment | `JAVA_LINE_COMMENT` | #7A7E85 |  | plain |  | Islands Dark via JAVA_LINE_COMMENT>DEFAULT_LINE_COMMENT |
| Block comment | `JAVA_BLOCK_COMMENT` | #7A7E85 |  | plain |  | Islands Dark via JAVA_BLOCK_COMMENT>DEFAULT_BLOCK_COMMENT |
| Doc comment | `JAVA_DOC_COMMENT` | #5F826B |  | italic |  | Islands Dark via JAVA_DOC_COMMENT>DEFAULT_DOC_COMMENT |
| Doc tag (@param) | `JAVA_DOC_TAG` | #67A37C |  | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark via JAVA_DOC_TAG>DEFAULT_DOC_COMMENT_TAG |
| Doc tag value | `DOC_COMMENT_TAG_VALUE` | #ABADB3 |  | plain |  | Islands Dark via DOC_COMMENT_TAG_VALUE>DEFAULT_DOC_COMMENT_TAG_VALUE |
| Doc markup (<p>) | `JAVA_DOC_MARKUP` | #68A67E |  | plain |  | Islands Dark via JAVA_DOC_MARKUP>DEFAULT_DOC_MARKUP |
| Valid string escape | `JAVA_VALID_STRING_ESCAPE` | #CF8E6D |  | plain |  | Islands Dark via JAVA_VALID_STRING_ESCAPE>DEFAULT_VALID_STRING_ESCAPE |
| Invalid string escape | `JAVA_INVALID_STRING_ESCAPE` | #CF8E6D |  | plain | WAVE_UNDERSCORE #FA6675 | Islands Dark via JAVA_INVALID_STRING_ESCAPE>DEFAULT_INVALID_STRING_ESCAPE |
| Operator | `JAVA_OPERATION_SIGN` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_OPERATION_SIGN>DEFAULT_OPERATION_SIGN |
| Parentheses | `JAVA_PARENTH` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_PARENTH>DEFAULT_PARENTHS |
| Brackets | `JAVA_BRACKETS` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_BRACKETS>DEFAULT_BRACKETS |
| Braces | `JAVA_BRACES` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_BRACES>DEFAULT_BRACES |
| Comma | `JAVA_COMMA` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_COMMA>DEFAULT_COMMA |
| Dot | `JAVA_DOT` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_DOT>DEFAULT_DOT |
| Semicolon | `JAVA_SEMICOLON` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_SEMICOLON>DEFAULT_SEMICOLON |
| Local variable | `LOCAL_VARIABLE_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via LOCAL_VARIABLE_ATTRIBUTES>DEFAULT_LOCAL_VARIABLE>DEFAULT_IDENTIFIER |
| Parameter | `PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via PARAMETER_ATTRIBUTES>DEFAULT_PARAMETER>DEFAULT_IDENTIFIER |
| Lambda parameter | `LAMBDA_PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via LAMBDA_PARAMETER_ATTRIBUTES>PARAMETER_ATTRIBUTES>DEFAULT_PARAMETER>DEFAULT_IDENTIFIER |
| Reassigned local | `REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES` | #BCBEC4 |  | plain | LINE_UNDERSCORE #84868C | Islands Dark via REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES>DEFAULT_REASSIGNED_LOCAL_VARIABLE |
| Reassigned parameter | `REASSIGNED_PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain | LINE_UNDERSCORE #84868C | Islands Dark via REASSIGNED_PARAMETER_ATTRIBUTES>DEFAULT_REASSIGNED_PARAMETER |
| Instance field | `INSTANCE_FIELD_ATTRIBUTES` | #C77DBB |  | plain |  | Islands Dark via INSTANCE_FIELD_ATTRIBUTES>DEFAULT_INSTANCE_FIELD |
| Instance final field | `INSTANCE_FINAL_FIELD_ATTRIBUTES` | #C77DBB |  | plain |  | Islands Dark via INSTANCE_FINAL_FIELD_ATTRIBUTES>INSTANCE_FIELD_ATTRIBUTES>DEFAULT_INSTANCE_FIELD |
| Static field | `STATIC_FIELD_ATTRIBUTES` | #C77DBB |  | italic |  | Islands Dark via STATIC_FIELD_ATTRIBUTES>DEFAULT_STATIC_FIELD |
| Static final field (constant) | `STATIC_FINAL_FIELD_ATTRIBUTES` | #C77DBB |  | italic |  | Islands Dark via STATIC_FINAL_FIELD_ATTRIBUTES>STATIC_FIELD_ATTRIBUTES>DEFAULT_STATIC_FIELD |
| Class name | `CLASS_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Interface name | `INTERFACE_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via INTERFACE_NAME_ATTRIBUTES>DEFAULT_INTERFACE_NAME>DEFAULT_IDENTIFIER |
| Enum name | `ENUM_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via ENUM_NAME_ATTRIBUTES>CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Abstract class name | `ABSTRACT_CLASS_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via ABSTRACT_CLASS_NAME_ATTRIBUTES>CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Type parameter | `TYPE_PARAMETER_NAME_ATTRIBUTES` | #16BAAC |  | plain |  | Islands Dark |
| Method call | `METHOD_CALL_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via METHOD_CALL_ATTRIBUTES>DEFAULT_FUNCTION_CALL>DEFAULT_IDENTIFIER |
| Static method call | `STATIC_METHOD_ATTRIBUTES` | (inherits text #BCBEC4) |  | italic |  | Darcula |
| Method declaration | `METHOD_DECLARATION_ATTRIBUTES` | #56A8F5 |  | plain |  | Islands Dark via METHOD_DECLARATION_ATTRIBUTES>DEFAULT_FUNCTION_DECLARATION |
| Constructor call | `CONSTRUCTOR_CALL_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via CONSTRUCTOR_CALL_ATTRIBUTES>DEFAULT_FUNCTION_CALL>DEFAULT_IDENTIFIER |
| Constructor declaration | `CONSTRUCTOR_DECLARATION_ATTRIBUTES` | #56A8F5 |  | plain |  | Islands Dark via CONSTRUCTOR_DECLARATION_ATTRIBUTES>DEFAULT_FUNCTION_DECLARATION |
| Annotation name | `ANNOTATION_NAME_ATTRIBUTES` | #BBB529 |  | plain | LINE_UNDERSCORE (no color: not drawn) | Darcula |
| Annotation attribute name | `ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Default text / identifier | `TEXT` | #BCBEC4 | #191A1C | plain |  | Islands Dark |
| Error (ERRORS_ATTRIBUTES) | `ERRORS_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | WAVE_UNDERSCORE #FA6675 | Islands Dark |
| Warning (WARNING_ATTRIBUTES) | `WARNING_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | WAVE_UNDERSCORE #F2C55C | Islands Dark |
| Unused (NOT_USED_ELEMENT_ATTRIBUTES) | `NOT_USED_ELEMENT_ATTRIBUTES` | #6F737A |  | plain |  | Islands Dark |
| Matched brace | `MATCHED_BRACE_ATTRIBUTES` | (inherits text #BCBEC4) | #43454A | bold |  | Islands Dark |
| Unmatched brace | `UNMATCHED_BRACE_ATTRIBUTES` | #F75464 |  | plain |  | Islands Dark |
| Deprecated | `DEPRECATED_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | STRIKEOUT #BCBEC4 | Islands Dark |
| Bad character | `BAD_CHARACTER` | #F75464 |  | plain | WAVE_UNDERSCORE (no color: not drawn) | Islands Dark |
| TODO | `TODO_DEFAULT_ATTRIBUTES` | #8BB33D |  | italic |  | Islands Dark |
| Folded text | `FOLDED_TEXT_ATTRIBUTES` | #868991 | #393B40 | plain |  | Islands Dark |
| Identifier under caret | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` | (inherits text #BCBEC4) | #373B39 | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Write identifier under caret | `WRITE_IDENTIFIER_UNDER_CARET_ATTRIBUTES` | (inherits text #BCBEC4) | #402F33 | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Search result | `SEARCH_RESULT_ATTRIBUTES` | (inherits text #BCBEC4) | #2D543F | plain |  | Islands Dark |

Notes on the table:
- Error and warning use `EFFECT_TYPE 2` = **WAVE_UNDERSCORE** (error `#FA6675`, warning `#F2C55C`; error stripe `#D64D5B` / `#C29E4A`). No foreground change: the text keeps its syntax color and the wave is drawn under it.
- Unused element (grey variable, `NOT_USED_ELEMENT_ATTRIBUTES`): foreground `#6F737A`, no effect (Islands Dark has no strike/underline there).
- Deprecated: `EFFECT_TYPE 3` = STRIKEOUT in `#BCBEC4`.
- `ANNOTATION_NAME_ATTRIBUTES`, `JAVA_DOC_TAG` have `EFFECT_TYPE 1` with no `EFFECT_COLOR`: nothing is drawn (effect needs a color).
- `STATIC_METHOD_ATTRIBUTES` is an explicit Darcula entry that only sets italic, so the static call is italic in the default text color (the `DEFAULT_STATIC_METHOD #57AAF7` fallback is not reached, because the explicit entry has a non-empty value). Inferred from the lookup rule.
- Parameters, locals, class names, interface names, method calls all end at `DEFAULT_IDENTIFIER #BCBEC4`. Only declarations (`#56A8F5`), fields (`#C77DBB`), constants/static fields (`#C77DBB` italic), type parameters (`#16BAAC`) and annotations (`#BBB529`) are colored. Semantic highlighting (rainbow) is off by default.
- Java class/interface/enum/record icons in the Project tree use the node icons in section 4, not text colors.

Wave painting (Inferred from GH `platform/platform-api/src/com/intellij/ui/paint/EffectPainter2D.java`, registry `ide.text.effect.new=true`): drawn from baseline y, available height = font descent of the line (6 px with the metrics in 2.3); height is capped to 3 px (`getMaxHeight`: descent <= 7 -> 3), period = 2 px zig-zag, amplitude 2 px, stroke width 0.7 px (`THIN_STROKE` when height < 6), antialiased; occupies y+3 .. y+6 below the baseline.

### 2.3 Line height (Seen code, Inferred numbers)

Rule (GH `platform/platform-impl/src/com/intellij/openapi/editor/impl/view/EditorView.java createNewMetrics/getLineHeight`; registries `editor.text.xcode.vertical.spacing=false`, `editor.text.vertical.spacing.correct.rounding=false`):

```
fontMetricsHeight = FontMetrics.getHeight()     // int: ascent + descent + leading, each rounded up by Java2D
lineHeight        = ceil(fontMetricsHeight * lineSpacing)       // lineSpacing default 1.2 (FontPreferences.DEFAULT_LINE_SPACING)
descent'          = descent + (lineHeight - fontMetricsHeight) / 2   // integer division
ascent'           = lineHeight - descent'                         // baseline = lineTop + ascent'
```
JetBrains Mono 12: ascent 1.02*12 = 12.24 -> 13, descent 0.30*12 = 3.6 -> 4, leading 0 -> fontMetricsHeight **17**.
- spacing 1.2: lineHeight = ceil(20.4) = **21**; descent' = 4 + (21-17)/2 = 6; ascent' = 15; caret height 17 (2 px margin above and below).
- spacing 1.0: lineHeight 17, ascent' 13, descent' 4.
Verify the FontMetrics rounding on a real run (JBR on macOS can differ by 1 px); the formulas are exact, the integer 17 is computed.
Text x: char i at x0 + i * 7.2 (fractional advances; do not round to ints).

### 2.4 Gutter geometry (New UI layout, logical px; GH `EditorGutterLayout.createNewUILayout` + `EditorGutterComponentImpl`)

Left to right, defaults (non-compact; compact values in parentheses come from `darcula.theme.json` `Editor.Gutter.*.compact`):

| # | Area | Width | Source |
|---|---|---|---|
| 1 | empty annotation area | 4 (compact 0) | `JBUI.CurrentTheme.Editor.Gutter.emptyAnnotationAreaWidth` default 4 |
| 2 | gap before line numbers (`gapAfterVcsMarkersWidth`) | 4 (2) | JBUI.java default 4 |
| 3 | line numbers | `max(14, ceil(stringWidth(maxLineNumber at 11 px JBM)))` -> 14 for up to 2 digits, 20 for 3 digits (6.6 px per digit), 27 for 4 digits. 14 = width of the breakpoint placeholder icon `expui/breakpoints/breakpoint.svg` (14x14) | `getInitialLineNumberWidth`, `calcLineNumbersAreaWidth` |
| 4 | gap after line numbers (`gapAfterLineNumbersWidth`) | 4 (2) | |
| 5 | left free painters (git change stripe lives here) | 10 when shown (8 + 2), else 0 | `getLeftFreePaintersAreaWidth`, `FREE_PAINTERS_LEFT_AREA_WIDTH 8` |
| 6 | icons area (breakpoints, run/override markers) | 17 (`START_ICON_AREA_WIDTH`), grows with wider icons | `myStartIconAreaWidth` |
| 7 | gap after icons (`gapAfterIconsWidth`) | 4 (2) | |
| 8 | right free painters | 0 | New UI: `myRightFreePaintersAreaReserveWidth` |
| 9 | folding area | 11 = fold icon width 9 (`expui/gutter/fold.svg`, 9x9) + 2 | `getFoldingAreaWidth` |
| 10 | extra right free painters + gap | 8 + 1 = 9 | `FREE_PAINTERS_EXTRA_RIGHT_AREA_WIDTH 8`, `areaGap(1)` |

Total (git project, markers shown) = 4+4+L+4+10+17+4+0+11+9 = **63 + L**: 77 with L=14, 83 with L=20, 90 with L=27. Without git markers (item 5 = 0): 67 + L - 4.
Line numbers are **right-aligned**: right edge x = 4 + 4 + L (left of "gap after line numbers"), font 11 px JBM plain, baseline = line top + the editor's ascent' (not the 11 px font's), color per 2.1. Text area starts at gutter width (separate component, no extra padding; the 3 px strip before the separator is inside the gutter). Fold chevron icon `expui/gutter/fold(_dark).svg` is only drawn on hover (alpha context) in New UI.
Line numbers area on a non-real editor without numbers: 14 wide.

IDEate (`EditorView`, 2026-11-19): items 1, 2, 4 and 7 follow the density (`IslandsDark.gutterEmptyAnnotationArea`,
`gutterGap`); before that the numbers started at the island's edge (items 1 and 2 left out) and items 4 and 7 were
always 4. Items 5 (no git markers yet), 8 and 10 are not drawn; the folding area is 11 + 6. Normal density, two-digit
numbers: numbers from 8, text at 64; compact: numbers from 2, text at 54. The desktop takes the density from the
newest `IntelliJIdea*/options/ui.lnf.xml`; JetBrains Client keeps its own settings and runs at normal density when
it has none, so it can show the normal gaps while the desktop IntelliJ (and IDEate) is compact.

## 3. Project tool window (Islands Dark colors resolved)

### 3.1 Colors (Seen, aliases resolved from `colors` in ManyIslandsDark.theme.json)

| Item | Value | Key / alias | Source |
|---|---|---|---|
| Tree background | `#191A1C` | `Tree.background` <- wildcard `*.background` = `tool-window-bg` = `layer-0-bg` | Seen (wildcard rule in `UITheme.kt`) |
| Tree foreground | `#D1D3D9` | `*.foreground` = `text-default` = `gray-130` | Seen |
| Selection background, tree focused | `#2A4371` | `selection-bg-active` = `blue-50` | Seen |
| Selection background, tree not focused | `#33353B` | `selection-bg-inactive` = `gray-40` (`selectionInactiveBackground`) | Seen |
| Selection foreground (focused and not) | `#D1D3D9` | `selectionForeground`/`selectionInactiveForeground` = `text-default`; `Tree.forceFocusedSelectionForeground=false` (Islands) | Seen |
| Hover background (unselected row) | `#FFFFFF10` (white, alpha 0x10 = 6.3%) | `Tree.hoverBackground` = `selection-bg-hovered`; same for inactive tree | Seen |
| Indent guides | off (`Tree.paintLines=false`); if enabled `#33353B` | `Tree.hash` = `tree-indent-guide-border` | Seen |
| Grayed text (e.g. path after module name) | `#73767C` | `SimpleTextAttributes.GRAYED_ATTRIBUTES` -> `Component.infoForeground` <- `*.infoForeground` = `text-secondary` = `gray-80` | Inferred from `NamedColorUtil.getInactiveTextColor` + wildcard |
| Disabled text | `#4C4F56` | `text-disabled` | Seen |
| Directory / module / package / excluded node text | same `#D1D3D9`, no special color, regular weight (excluded folders only change the icon to orange); VCS file status colors apply (section 2.1) | | Inferred (no special attributes found in `ProjectViewRenderer`, `NodeRenderer`) |
| Font | Inter Regular 13 (`Tree.font` = default label font; ide font size 13) | | Seen setting, Inferred mapping |
| Scrollbar | macOS overlay, same as editor | | |

### 3.2 Metrics (logical px)

| Item | Value | Source |
|---|---|---|
| Row height | **24** (compact 22) | `Tree.rowHeight` in ManyIslandsDark.theme.json (`rowHeight`, `rowHeight.compact`) |
| Tree insets (`Tree.border`) | top 4, left 12, bottom 4, right 12 (compact 2,8,2,8) | ManyIslandsDark.theme.json `Tree.border`; applied as Swing component border (Inferred; check `ProjectViewTree` does not override) |
| Indent per level | **19** = `max(controlWidth/2 = 8, Tree.leftChildIndent 7)` + `Tree.rightChildIndent 11` | GH `ClassicPainter.getRendererOffset/getLeftIndent`, `darcula.theme.json` `leftChildIndent 7`, `rightChildIndent 11` |
| Chevron (control) | 16x16 icon box; x = 12 + (depth-1)*19; vertically centered in the row; shown only for non-leaf rows; depth 1 = first visible node (project root; root is hidden, root handles shown) | `ClassicPainter.getControlOffset`, `TreeUtil.getNodeDepth` |
| Renderer origin (icon+text cell) | x = 12 + depth*19 (leaf and non-leaf; for depth d: (d-1)*19 + 19) | `ClassicPainter.getRendererOffset` |
| Node icon | 16x16, at renderer.x + `ipad.left` (2) (+/-1 from the 1 px component border, Inferred) | `SimpleColoredComponent` ipad `(1,2)`, border `empty(1)` |
| Icon-to-text gap | **2** (`JBUIScale.scale(2)`) | `SimpleColoredComponent.myIconTextGap` |
| Resulting text x for depth d | 12 + 19*d + 2 + 16 + 2 = 19*d + 32 (depth 1 -> 51, depth 2 -> 70, depth 3 -> 89) | Inferred |
| Selection / hover highlight | rounded rect from x = 12 (`HORIZONTAL_SELECTION_OFFSET`, = tree left inset) to width - 12, full row height (24); not per-indent, always the same left edge | GH `DefaultTreeUI.paint` (`borderOffset = 12`, `min(helper.getX()+12, insets.left + controlOffset)`); registry `ide.experimental.ui.tree.selection=true` |
| Selection corner | `Tree.Selection.arc = 8` (JBUI default 8, no theme override) is passed as RoundRectangle2D arcWidth/arcHeight, so corner **radius = 4**. Adjacent selected rows merge: top corners squared if the row above is selected, bottom if the row below is | `JBUI.CurrentTheme.Tree.ARC`, `RectanglePainter2D.FILL` (arc diameter), `DefaultTreeUI` lines 361-400. Inferred conclusion (radius vs diameter) |
| Chevron colors | `expui/general/chevronRight_dark.svg` / `chevronDown_dark.svg`: stroke `#B4B8BF`, 1 px, round caps, same icon when selected (`collapsedSelectedIcon` = same path) | Seen |

### 3.3 Tool window header (the "Project" title row)

| Item | Value | Source |
|---|---|---|
| Height | **41** (compact 31); 1 px of it is the bottom line when drawn | `JBUI.CurrentTheme.ToolWindow.defaultHeaderHeight` 41, `darcula.theme.json` `ToolWindow.Header.height.compact 31` |
| Background | `#191A1C` (`ToolWindow.Header.inactiveBackground` = `tool-window-bg`; New UI always uses the "inactive" color) | ManyIslandsDark.theme.json `ToolWindow.Header` |
| Bottom line | only drawn when the content is scrolled / tabbed / has a top toolbar; color `ToolWindow.Header.borderColor` = `tool-window-border` = `#26282C` | `ToolWindowHeader.paintComponent` |
| Title font | Inter Regular 13 (`ToolWindow.Header.font.size.offset = 0`, `JBFont.label()`); color `#D1D3D9` | Inferred from `JBUI.CurrentTheme.ToolWindow.headerFont`, `defaultHeaderFont` |
| Title label insets | left 12, right 16 (`ToolWindow.Header.labelLeftRightInsets` default `0,12,0,16`) | JBUI.java |
| Toolbar insets (right side actions) | left 12, right 8; component border `empty(2,0)` | JBUI.java, `ToolWindowHeader.kt` |

### 3.4 Islands (Seen)

| Item | Value | Source |
|---|---|---|
| Mode | many islands: `ui.Islands = 1` | ManyIslandsDark.theme.json |
| Window background behind islands (`MainWindow.background`, toolbar, stripes, status bar) | `#26282C` (`main-window-bg` = `layer-1-bg`) | Seen |
| Island body (tool windows, editor area) | `#191A1C` (`layer-0-bg`) | Seen |
| Island border color (`Island.borderColor`) | `#191A1C` (`tool-window-bg`); the `alt` variant `#212326` is only for the alternate look | Seen |
| Island corner | `Island.arc = 20` -> corner radius **10** (`arcSizeF = scale(arc / 2)`); compact `arc.compact = 16` -> 8 | `IslandsUICustomization.paintIslandAreaRaw` |
| Gap between islands | `Island.borderWidth = 6` (compact 4): each island leaves `borderWidth/2 = 3` px of window background on every side, so adjacent islands are 6 px apart. Component border insets: tool window `Island.ToolWindow.insets` default 3, editor `Island.Editor.insets` default 2 (not overridden in the theme) | `paintIslandAreaRaw` (`offset = borderWidth / 2`), `createToolWindowBorderPainter`, `createEditorBorderPainter`. Inferred: visual gap ~6, verify with screenshot |
| Outer gap when a stripe is hidden | `Islands.emptyGap` default 4 on the side without stripe | `configureToolWindowPane` |
| Corner fillets | the 4 corner pieces are painted in window background `#26282C` with quadratic curves of size 10 on top of the island rect, plus a border line of `Island.borderColor` with `borderArcLength = 14` (compact 10) | `paintIslandAreaRaw` |
| Inactive window | islands are dimmed with `Island.inactiveAlpha = 0.56` | ManyIslandsDark.theme.json, `islandsInactiveAlpha` |
| Island gradient | window gradient background image `images/gradientBackground-islands-dark.svg` in `lib/intellij.platform.ide.impl.jar` is only used when the islands gradient setting is on (default off for the default Islands themes: Inferred) | |
| Editor tab bar background | `#191A1C` (`EditorTabs.background` = `editor-bg`) | Seen |

## 4. Icons (Seen unless marked)

All in `$IDE/lib/intellij.platform.icons.jar`. In New UI the loader asks for old `AllIcons` paths and `PlatformIconMappings.json` (same jar) redirects to `expui/...`. In a dark theme the loader first tries `<name>_dark.svg`, then `<name>.svg`. Nodes have `_dark` for nearly all; only ~15 nodes (e.g. `folder.svg`) differ in color. Then `icons.ColorPalette` of the theme recolors: ManyIslandsDark maps SVG color `#CED0D6` -> `icon-default-stroke` = `#C3C5CB` (this changes the grey strokes/fills in `_dark` icons: folder stroke, package, text lines). Colors below are the `_dark` file values before that remap.

| Node | Jar path (dark file; light file is the same name without `_dark`) | Main colors in `_dark` | Size |
|---|---|---|---|
| Folder, `.idea` folder, plain dir | `expui/nodes/folder_dark.svg` | fill `#43454A`, stroke `#CED0D6` (-> `#C3C5CB`) | 16x16 |
| Source root (blue folder) `src/main/java` | `expui/nodes/sourceRoot_dark.svg` (mapped from `modules/sourceRoot.svg`) | fill `#25324D`, stroke `#548AF7` | 16 |
| Test root (green folder) | `expui/nodes/testRoot_dark.svg` | fill `#253627`, stroke `#57965C` | 16 |
| Resources root | `expui/nodes/resourcesRoot_dark.svg` | folder + three `#F2C55C` bars | 16 |
| Test resources root | `expui/nodes/testResourcesRoot_dark.svg` | green + `#DB5C5C`/`#F2C55C` bars | 16 |
| Excluded folder (`out`, `target`) | `expui/nodes/excludeRoot_dark.svg` (from `modules/excludeRoot.svg`) | fill `#45322B`, stroke `#C77D55` (orange-brown) | 16 |
| Generated sources root | `expui/nodes/generatedSource_dark.svg` | `#25324D`/`#3574F0` + gear | 16 |
| Package | `expui/nodes/package_dark.svg` | folder outline with dot, `#43454A` fill, `#CED0D6` outline | 16 |
| Module / project root node | `expui/nodes/module_dark.svg` (folder with small blue rounded square, bottom right: `#25324D` fill, `#548AF7` stroke); IDEA project icon `expui/nodes/ideaProject_dark.svg`; Java module `expui/nodes/moduleJava_dark.svg` (blue rounded square) | | 16. Which of the three the root row uses is Inferred (module node: `module_dark`) |
| Java class (also used for a `.java` file that declares a public class, which is what the Project tree shows) | `expui/nodes/class_dark.svg` | circle fill `#25324D`, stroke `#548AF7`, "C" glyph `#548AF7` | 16 |
| Interface | `expui/nodes/interface_dark.svg` | circle `#253627`/`#57965C`, "I" glyph | 16 |
| Enum | `expui/nodes/enum_dark.svg` | `#2F2936`/`#A571E6` | 16 |
| Record | `expui/nodes/record_dark.svg` | `#25324D`/`#548AF7` | 16 |
| Annotation | `expui/nodes/annotation_dark.svg` | `#253627`/`#57965C` | 16 |
| Abstract class | `expui/nodes/classAbstract_dark.svg` | `#25324D`/`#548AF7` + dashed | 16 |
| Java file type (when not shown as a class, e.g. outside sources) | `expui/fileTypes/java_dark.svg` | fill `#45322B`, stroke `#C77D55`, base bar `#C77D55` (a mug) | 16 |
| `.class` file | `expui/fileTypes/javaClass_dark.svg` | `#CED0D6` | 16 |
| `.iml` module file | `expui/fileTypes/ideaModule_dark.svg` (mapping: `fileTypes/ideaModule.svg -> nodes/ideaModule.svg` means this new file replaces the old `nodes/ideaModule`) | fill `#43454A`, stroke `#CED0D6` + small bar | 16 |
| XML | `expui/fileTypes/xml_dark.svg` | fill `#C77D55` (angle brackets) | 16 |
| Text / `.txt` / `.md` fallback | `expui/fileTypes/text_dark.svg` (4 lines, `#CED0D6`); markdown `expui/fileTypes/markdown_dark.svg` (`#548AF7`) | | 16 |
| `.properties` | `expui/fileTypes/properties_dark.svg` | `#CED0D6` | 16 |
| JSON / YAML | `expui/fileTypes/json_dark.svg` (`#B589EC`), `expui/fileTypes/yaml_dark.svg` | | 16 |
| Unknown file | `expui/fileTypes/unknown_dark.svg` | `#43454A`/`#CED0D6` + "?" | 16 |
| Library / JDK | `expui/nodes/library_dark.svg`, `expui/nodes/jdk_dark.svg`, `expui/nodes/libraryFolder_dark.svg` | | 16 |
| Chevron right (collapsed) | `expui/general/chevronRight_dark.svg`: `<path d="M6 11.5L9.5 8L6 4.5" stroke="#B4B8BF" stroke-linecap="round"/>` in 16x16 | | 16 |
| Chevron down (expanded) | `expui/general/chevronDown_dark.svg`: `<path d="M11.5 6.25L8 9.75L4.5 6.25" stroke="#B4B8BF" stroke-linecap="round"/>` | | 16 |
| Tree icon keys | `Tree.collapsedIcon` / `Tree.collapsedSelectedIcon` = `/expui/general/chevronRight.svg`, `Tree.expandedIcon` / `Tree.expandedSelectedIcon` = `/expui/general/chevronDown.svg` | | Seen in ManyIslandsDark/expUI_dark.theme.json |
| Gutter fold chevron | `expui/gutter/fold_dark.svg` 9x9, stroke `#6F737A` (`foldBottom_dark.svg` for the end marker) | | 9 |
| Breakpoint (gutter) | `expui/breakpoints/breakpoint.svg` 14x14 fill `#E55765` | | 14 |

Variant markers on node icons (visibility lock, final, static, test/run mark) are overlay layers drawn on top; use `expui/nodes/finalMark_dark.svg`, `staticMark_dark.svg`, `junitTestMark_dark.svg`, `runnableMark_dark.svg`, `locked_dark.svg` if needed. 

## 5. Rendering details that affect "looks identical"

| Item | Value | Source |
|---|---|---|
| Fractional metrics | ON on macOS Catalina or later (editor and IDE text): glyph advances are not rounded, so JBM 12 advance is 7.2 px, not 7 | `UISettings.getPreferredFractionalMetricsValue` (`isMacOSCatalina`), registry `*.text.fractional.metrics=Auto` | 
| Antialiasing | `UISettingsState.editorAAType/ideAAType` default SUBPIXEL; macOS has no LCD smoothing, so effectively grayscale antialiasing with gamma of Core Text/JBR. Use grayscale AA, no hinting | Seen default, Inferred effect |
| Ligatures | off | section 1 |
| Line spacing | 1.2 default; line height 21 px at JBM 12 (section 2.3) | |
| Caret | 2 px bar, blinks (default 500 ms), color `#CED0D6` | section 2.1 |
| Editor text left edge | char grid starts at gutter width; no extra text padding | section 2.4 |
| Line numbers | right aligned, 11 px, ascent shared with the editor baseline | section 2.4 |
| Tree selection | rounded 4 px radius, inset 12 px left/right, not full width | section 3.2 |
| HiDPI | all values logical, Retina doubles them; separators are `alignToInt` (1 px logical = 2 device px); gutter separator moves +1 logical px on scale > 1 | |
| Window layout | 26282C window, 191A1C islands with 10 px radius and ~6 px gaps | section 3.4 |
| Dark icons | pick `_dark.svg`, then remap `#CED0D6 -> #C3C5CB` | section 4 |
| Text colors with no key | fall back to the default foreground `#BCBEC4` (editor) / `#D1D3D9` (UI) | |
| Static, doc-comment and constant styles | italic comes from real `JetBrainsMono-Italic.ttf` (not synthesized) | section 1 |

## 6. Open items worth one screenshot check
1. Real FontMetrics height for JBM 12 on JBR macOS (expected 17 -> line height 21 at spacing 1.2).
2. ~~Whether the 1 px gutter separator line is visible in 2026.3 Islands Dark~~: yes, see section 12.
3. Whether the user's Project tree uses compact mode (row 22 vs 24) and the gutter has git markers (width +10).
4. Selection corner radius 4 vs 8 (code passes arc 8 as RoundRectangle2D arc width).
5. Icon x inside the renderer (2 or 3 px) from ipad/border.

## 7. Measured against the real JetBrains Client (2026-11-15)

Pixel measurements of the real client (263.7307.200, Islands Dark, default settings, `smoke` project without git) on the Mac. Where these differ from the sections above, these win; `IslandsDark.kt` and `EditorView.kt` use them.

| Item | Code reading above | Measured |
|---|---|---|
| Project tree row height | 24 (compact 22) | **22** |
| Tree selection | inset 12, radius 4 | inset 12, radius 4, fill `#2A4371` (focused) / `#2B4371` |
| Gutter width, 10-line file (L = 14) | 63 + L = 77 with git markers | **56** = line numbers 14 + 4 + icons 17 + 4 + folding 11 + 6; no git markers, no annotation gap before the numbers |
| Text start | right after the gutter | right after the gutter |
| Gutter icon (run triangle) | 17 px icon area | 14 px icon centred in the 17 px area |
| Editor tab | (not covered) | a pill: height 24, fill `#233657`, 1 px border `#2C4A82`, radius 6, in a 32 px row |
| Editor tab close button (added 2026-11-17, not measured) | `UISettings.showCloseButton` and `closeTabButtonOnTheRight` default true: on every tab, on the right | `expui/general/closeSmall.svg` 16 (`closeSmallHovered.svg` under the pointer) in a 20 box, 2 after the name, 4 to the pill's right edge (`EditorTabs.tabInsets.compact` right 4) |
| Line height | 21 | 21 (JetBrains Mono 12, char width 7.2) |

Open item 3 of section 6 is answered: rows are 22 and there are no git markers in a non-git project.

These measurements were most likely taken in compact mode: the row height (22) and the empty annotation area (0) match only the compact values (normal: 24 and 4), and none of them matches only a normal value. The user's IntelliJ is set to compact (2026-11-17). Section 11 uses them as compact values.


## 8. Completion popup, measured (2026-11-15)

From a screenshot of the real client's popup at 2× (`compose/src/commonMain/kotlin/intellijandroid/client/ui/LookupPopup.kt` uses these):

| Item | Value |
|---|---|
| Background, border | `#26282C` (`Popup.background` = `layer-1-bg`), 1 px `#33353B` (`layer-1-border`), radius 6 (arc 12) |
| Row | height 22; item text in the editor font (JetBrains Mono 12), `#BCBEC4` (`CompletionPopup.foreground`) |
| Typed letters in the item text | `#538AF9` (`CompletionPopup.matchForeground`) |
| Tail ("(String s)"), type on the right, group header, footer | `#73767C` (`text-secondary`); header and footer in Inter |
| Selection | inset 3 from the border; `#33353B` grey while the autopopup has not been navigated (SEMI_FOCUSED), `#2A4371` once it has (FOCUSED) |
| Horizontal | icon 16 at 11 from the border, item text at 32, type text ends 17 before the right border |
| Vertical | 2 between border and first row; groups ("Commands") get a 1 px separator 4 below the previous row, then a 22 high header |
| Footer tip | 12 from the left border (`Popup.Advertiser borderInsets 6,12,6,12`) |
| Position | just under the caret line; the item text starts 6.5 to the right of the typed prefix |

## 9. Folder chooser, measured (2026-11-16)

From a 2x screenshot of IntelliJ's "Open File or Project" dialog (`FileChooserDialogImpl`, Islands Dark, macOS), browsing Termux's home. Values in pt (= dp in the app). Used by the Android app's project folder picker (`compose/.../connect/FolderChooser.kt`).

| Part | Value |
|---|---|
| Dialog | 475 × 482, background `#191A1C` (same as the islands) |
| Toolbar | icons 16, one every 26, first box 21 from the left; separator `#363738`; "Hide path" link on the right, 13.5 from the edge |
| Path field | 14.5 from both sides, 68 below the dialog's top, 24 high; focused ring `#4670DA`; a drop-down (path history) at its right end |
| Tree panel | 12.5 from both sides; rows 22, indent 19 (as the Project tree); selection `#33353A`, inset 12 from both panel sides |
| Icons | `expui/nodes/folder.svg`; a folder with `.idea` gets `expui/nodes/ideaProject.svg` (folder with a small badge); toolbar `expui/nodes/homeFolder.svg`, `expui/general/refresh.svg`, `expui/actions/toggleVisibility.svg` |
| Buttons | OK `#4670DA` filled, Cancel outlined `#414349`; each 72 × 24, 12 apart, 15 from the right, 11 from the bottom |
| Browse icon in a text field (`TextFieldWithBrowseButton`) | `expui/inline/browse.svg` |

Left out in the app: Desktop, New Folder, Delete, the path history drop-down, the help button, and the drag-and-drop hint.

**Expand arrows (2026-11-17).** A folder gets ">" only when it holds folders, decided before it is expanded, so an empty folder never shows an arrow that disappears on expand. Until then every folder had an arrow until its own listing came back, which the user saw in "Select Project Folder" ("Expanding an empty dir seems to remove the > icon next to it"). The app can't read Termux's files, so one RUN_COMMAND per expanded folder runs `Termux.LIST_FOLDERS` in Termux's `sh`. For each sub-folder it prints the kind, then whether that sub-folder itself holds visible and hidden folders, then the name:

| Line | Folder | Arrow |
|---|---|---|
| `folder/00/Desktop` | no sub-folders (files only, or empty) | none |
| `folder/10/IdeaProjects` | visible sub-folders | yes |
| `project/01/IdeaHome` | an IntelliJ project (has `.idea`) whose only folder is `.idea` | only when "Show hidden files" is on |
| `folder/11/.gradle` | both | yes |

A folder the shell can't read is printed as `11`, so it keeps its arrow and the error shows when it is expanded. Once a folder's own listing is loaded, that listing decides its arrow (`hasChildFolders` in `FolderChooser.kt`); the top folder `/data/data/com.termux/files` has no parent listing and keeps its arrow until loaded. Checked on the phone over SSH: the lines above are its real output for `~` and `~/IdeaProjects`. `FolderListingTest` also runs the script with the Mac's `/bin/sh` on a temporary folder.

## 10. Connect screen: Gateway's welcome window

The connect screen copies JetBrains Gateway 2026.3's "Welcome to JetBrains Gateway" window and its project ⋮ menu,
measured on 2x screenshots (pt = dp). Code: `compose/.../connect/ConnectScreen.kt`, object `Gateway`.

Row menus (2026-11-17): a project row's ⋮ and its right-click / long-press menu (ContextMenuArea, section 13) show the same items: Open, Stop (a running Termux backend), Remove from Recent Projects, and Gateway's Collect Host and Client Logs greyed out (it needs the host's log folders zipped). An SSH host line has the gear menu's items. Rows of an unreachable host have no menu, as in Gateway.

| Part | Gateway | Ours |
|---|---|---|
| Sidebar | 224 wide; "Connections" label, rows 33 high, icon 17 in; selected row full width `#2E436E` | Same; 32 % of the width, at least 132, on a narrow screen. Rows: This phone (`expui/nodes/ssh.svg`), divider, Link (Markdown plugin's chain icon) |
| Header | Title 25 in, centred 37.5 down, divider at 63.5; New Project 133 × 27, 27.5 from the right | "Recent Termux Projects" / "Connect by Link"; New Project without the drop-down |
| Search | 44 high, `searchHistory` icon 7.5 in, text at 32 | Same; filters the rows by path |
| Host group | Bold host 25 in, centred 31 below the search divider; icons every 25.5, last centred 44.5 from the right | "This phone" with + (New Project) and Refresh; link hosts as `host:port` |
| Project row | 39 high; product icon 25 in, path in link blue at 50; status 18 after the path: "Running" green with a red stop square, or "Last active: 17 minutes ago"; ⋮ | Same, `expui/nodes/ideaProject.svg`; paths under Termux's home shortened to `~/…` |
| ⋮ menu | 237 wide, radius 8, `#2B2D30`, rows 24, text 20 in; Open With, Remove from Recent Projects, Collect Host and Client Logs | Remove from Recent Projects only |

Left out: the logo and version, All Providers, the settings gear, New Project's drop-down, Open With and log collection.

**Portrait** (window taller than wide). Gateway has no portrait layout, and the sidebar took a third of a phone's
411 dp for two entries, squeezing the paths. Upright, the sidebar becomes a tab bar at the bottom, like the main
window's tool window bar ([`mobile-layout.md`](mobile-layout.md)): 40 high, two equal tabs (icon and label), the
selected one on a 32-high `#393B40` pill with radius 6. Project rows put the status under the path (rows 48 high).
Landscape keeps the sidebar, now always 224 wide.

## 11. UI density: normal and compact (2026-11-17)

IntelliJ's Settings | Appearance | UI density has two values, `DEFAULT` and `COMPACT` (`com.intellij.ide.ui.UIDensity`). In compact mode `LafManagerImpl` copies every theme key ending in `.compact` over the key without it, e.g. `Tree.rowHeight.compact` 22 over `Tree.rowHeight` 24; a key without a `.compact` twin stays the same. The setting is saved in `<config>/options/ui.lnf.xml` as `<option name="UI_DENSITY" value="COMPACT" />`; at `DEFAULT` the option is left out.

IDEate has one setting, `IslandsDark.density`, and every size below that IDEate draws reads it. The default is compact. The desktop app reads it at start (never writes) from the newest `IntelliJIdea<version>/options/ui.lnf.xml` (macOS `~/Library/Application Support/JetBrains`, Linux `~/.config/JetBrains`, Windows `%APPDATA%\JetBrains`): option present means that value, a settings folder without the file means normal, and no IntelliJ settings means compact. `IDEATE_UI_DENSITY=normal` or `compact` overrides it. The Android app uses compact.

Key values from `darcula.theme.json` → `expUI_dark.theme.json` → `ManyIslandsDark.theme.json` (each overrides the one before), and code defaults from `JBUI.CurrentTheme` where the theme has only the compact key.

| What | Key | Normal | Compact | In IDEate (`IslandsDark` unless named) |
|---|---|---|---|---|
| Main toolbar button | `MainToolbar.Button.size` | 30,30 (code) | 26,26 | `toolbarButton` |
| Main toolbar icon | `MainToolbar.Button.iconSize` | 20 (code) | 16 | `toolbarIcon`; the project badge too (`RecentProject.Avatar.iconSize` falls back to it) |
| Main toolbar icon insets | `MainToolbar.Icon.insets` | 6,5,4,5 | 3,4,3,4 | `toolbarHeight` = button + top + bottom: 40 / 32 |
| Main toolbar font | `MainToolbar.fontSizeOffset` | 0 | -1 | `toolbarFontSize` 13 / 12 |
| Main toolbar button arc | `MainToolbar.Button.arc` | (code) | 8 | not drawn (no button background) |
| Tool window bar button | `StripeToolbar.Button.size` | 37,40 | 32,32 | `stripeButton` 40 / 32 (square, so normal takes 40) |
| Tool window bar icon | `StripeToolbar.Button.iconSize` | 20 (code) | 16 | `stripeIcon` |
| Tool window bar thickness | `ToolBar.verticalToolbarInsets` | (code 5,7) | 2,4,2,4 | `stripeThickness` = button + 8: 48 / 40 |
| Tool window header height | `ToolWindow.Header.height` | 41 (code) | 31 | `headerHeight` |
| Tool window header font | `ToolWindow.Header.fontSizeOffset` | 0 | -1 | `headerFontSize` 13 / 12 |
| Tree row | `Tree.rowHeight` | 24 | 22 (measured 22) | `treeRowHeight`; also the folder chooser |
| Tree border | `Tree.border` | 4,12,4,12 | 2,8,2,8 | `treeTopInset` 4 / 2. The sides stay 12 (`treeSideInset`): measured 12 in compact (section 7) |
| List row (completion popup) | `List.rowHeight` | 24 | 22 (measured 22) | `Lookups.rowHeight` in `LookupPopup.kt` |
| Completion footer | `CompletionPopup.Advertiser.borderInsets` | 6,12,6,12 | 2,12,2,8 | `Lookups.footerVerticalPadding`, `footerInset` (left), `footerRightInset` |
| Island gap | `Island.borderWidth` | 6 | 4 | `islandGap` |
| Island corner | `Island.arc` | 20 (radius 10) | 16 (radius 8) | `islandRadius` |
| Island border arc | `Island.borderArcLength` | 14 | 10 | not drawn |
| Gutter, empty annotation area | `Editor.Gutter.emptyAnnotationAreaWidth` | 4 (code) | 0 (measured 0) | `gutterEmptyAnnotationArea`; `EditorView.kt` still uses 0 |
| Gutter gaps after VCS markers, line numbers, icons | `Editor.Gutter.gapAfter*Width` | 4 (code) | 2 | not used: the gutter in `EditorView.kt` follows section 7's measurements |
| Editor tabs | `EditorTabs.tabInsets` | -6,8,-6,8 | -2,6,-2,4 | not used: `tabRowHeight`/`tabHeight` are measured (section 7) |
| Editor tabs font | `EditorTabs.fontSizeOffset` | 0 | -1 | not used yet |
| Component arc, button and field heights | `Component.arc`, `Button.minimumSize`, `TextField.minimumSize`, `ComboBox.minimumSize` | 8, 28 | 6, 24 | not used (connect screen sizes are Gateway's, section 10) |

Other `.compact` keys in those themes (popups, find, search everywhere, status bar, run widget, VCS log, diff, debugger tabs, title pane) are for parts IDEate doesn't draw.

Checked 2026-11-17 on the desktop against a Mac backend (snapshots with `CLIENT_SNAPSHOT`): in compact mode the main toolbar and the tool window bar both have 16 px icons, the toolbar is 32 high, tree rows 22, the header 31; with `IDEATE_UI_DENSITY=normal` the icons are 20 in both bars, the toolbar 40, rows 24, the header 41. Before this, the bar used compact sizes, the toolbar a mix (30 buttons with 16 icons), and the tree, header and islands the normal ones.

## 12. Gutter border line and sticky lines (2026-11-17)

**Gutter border line.** New UI (`EditorGutterComponentImpl.paint`, IntelliJ Community 263.7307.200): after the gutter backgrounds, a vertical line the editor's full height, 1 px wide, in the scheme's `INDENT_GUIDE` color (`EditorColors.INDENT_GUIDE_COLOR`), at `getWhitespaceSeparatorOffset()` = gutter width − 3 (+1 px when the IDE scale is above 1; on a Mac it is 1). It is not drawn on the sticky lines panel. Seen in the user's IntelliJ 2026.3 (Islands Dark, a screenshot of the `ideate-editor` window): a thin line between the line numbers and the text, past the last line. In IDEate: `EditorView`'s `drawWithContent`, x = gutter width − 3 dp, drawn over the caret row; the sticky panel covers it.

**Sticky lines.** When the start line of a class, method, … has scrolled out of view, IntelliJ pins it at the top of the editor (`StickyLinesPanel`, `VisualStickyLines`):

| Part | IntelliJ | IDEate |
|---|---|---|
| Scopes | the backend's `STICKY_LINE_MARKER` highlighters in the document markup; start offset = the name (`Big` in `public class Big {`), end offset = the closing brace | `EditorFile.stickyRanges`; `Big.java` (500 methods with nested `if`/`for`) gets 501: the class and each method, none for the `if`/`for` blocks |
| Which ones | scopes of at least 5 lines; of scopes on the same start line, one; sorted by start line (longer scope first); pinned while the start line is above the panel's next row and the end line reaches it; a scope ending under the panel pushes its line up by the overlap and ends the list | `stickyPanel()` in `StickyLines.kt`, a port of `VisualStickyLines.setYLocation`; `StickyLinesTest` |
| Limit | `EditorSettingsExternalizable.STICKY_LINES_LIMIT` 5; the panel stays under half the editor minus two lines | the same |
| A line | the editor's own painting of that line: gutter (line number, icons) and text with highlighting and indent guides | `EditorLine` for that line, without caret and selection |
| Background | `STICKY_LINES_BACKGROUND`, else the editor background; hovered: `STICKY_LINES_HOVERED_COLOR`, else `CARET_ROW_COLOR` | background as IntelliJ; no hover color yet |
| Border | 1 px under the panel in `STICKY_LINES_BORDER_COLOR`, else `RIGHT_MARGIN_COLOR`, else the background | the same |
| Shadow | under the border, `0.25 × line height` in a dark scheme (0.17 light), black alpha 32 (8 light) fading to transparent | the dark values |
| Click | caret to the scope's start offset, `scrollToCaret(RELATIVE)` | caret there, then the line is scrolled to just under the pinned lines above it; the editor takes focus once the session has the new caret |

Checked on the desktop against the Mac backend (port 5992), `Big.java`: scrolled into `method1`, `public class Big {` (line 3) and `int method1(final int value) {` (line 16) were pinned, with the border under them. Tapping the pinned `method3` line put the caret on `method3` with line 40 right under the pinned class line. Tapping the class line put line 3 at the top.

## 13. Context menus (2026-11-17)

`ContextMenuArea` in `ui/ContextMenu.kt` gives any composable IntelliJ's popup menu. When it opens:

| Trigger | Where the menu opens |
|---|---|
| Right click (desktop, Android with a mouse) | at the pointer; moved left or up when there's no room, like a desktop menu |
| Right click on a phone (smallest window side under 600 dp, Android's `sw600dp` line) | in the middle of the window, as there is little room |
| Long press with a finger (phone and tablet) | in the middle of the window |

Rows are IntelliJ's 24 dp however the menu was opened. Until 2026-11-20 a menu opened by a finger had 40 dp
rows, the tool window dropdowns always had them, and New ▸ Java Class's kinds were 32 dp; the user asked for
IntelliJ's sizes everywhere, with no extra padding for touch, now and in anything new.

The gesture is watched in Compose's `Initial` pass, before the content sees it. A right click or a long press is
consumed, so it doesn't also become a tap, a drag or a text selection underneath. A long press is a touch held for
the long-press time (`viewConfiguration.longPressTimeoutMillis`) without moving past the touch slop.

Look, Islands Dark (theme keys from `darcula.theme.json` → `expUI_dark` → `ManyIslandsDark`, code defaults from
`JBUI.CurrentTheme.PopupMenu`):

| Part | Value | Key |
|---|---|---|
| Background | `#26282C` | `PopupMenu.background` = `popup-bg` |
| Border | 1 px `#33353B`, radius 8 | `Popup.borderColor` = `popup-border`, `PopupMenu.borderCornerRadius` 8 |
| Space above and below the items | 6 | `PopupMenu.borderInsets` 6,1,6,1 |
| Hovered row | a band inset 1 top and bottom and 7 from the sides, radius 4, `#2A4371` (was `#2E436E`, not measured; now the theme's, docs/ui-components.md) | `PopupMenu.Selection.outerInsets` 1,7, `.arc` 8; `*.selectionBackground` = selection-bg-active |
| Item text | 6 inside the band, after an 18 px icon column; 13 px Inter | `PopupMenu.Selection.innerInsets` 0,6 (New UI), `Menu.maxGutterIconWidth` 18 |
| Shortcut | right-aligned, `#73767C` | |
| Disabled item | `#4C4F56` (was `#777777`, Darcula's value, which Islands Dark's `*` wildcard replaces) | `MenuItem.disabledForeground` → `*.disabledForeground` = text-disabled |
| Separator | 9 high, a 1 px line in the border color, 10 from the sides | `PopupMenuSeparator.height` 9, `.withToEdge` 10 |

Submenus open in place of the menu, with a row at the top to go back. IntelliJ opens them to the side instead;
in place also works with a finger and in a centred menu. Items are plain data (`MenuItem`, `SubMenu`,
`MenuSeparator`), so they can come from the backend later. `entries` is read while the menu shows, and null shows
"Loading…".

**The editor's menu** (IntelliJ's `EditorPopupMenu`, New UI order). A right click or long press first moves the
caret to that point, unless it is inside the selection, as IntelliJ does.

| Item | In IDEate |
|---|---|
| Cut ⌘X | works: copies the selection, or the caret's whole line with its line break, then deletes it (the backend's `EditorBackSpace`) |
| Copy ⌘C | works: the selection, or the whole line with its line break, to the system clipboard and the backend's `ClipboardModel` |
| Copy / Paste Special ▸ Copy Absolute Path, Copy File Name, Copy as Plain Text | work |
| Paste ⌘V | works: the clipboard goes to the backend's `ClipboardModel`, then its `EditorPaste` runs, as in JetBrains Client (docs/edit-protocol.md, "Paste and the clipboard") |
| Copy / Paste Special ▸ Paste as Plain Text | works: the same, with `EditorPasteSimple` |
| Copy / Paste Special ▸ Paste from History… | greyed out: the backend shows the history in its own popup (the actions protocol) |
| Show Context Actions, Copy Reference, Copy as Rich Text, Column Selection Mode, Find Usages, Go To ▸, Folding ▸, Refactor ▸, Generate…, Run…, Debug…, More Run/Debug ▸, Open In ▸, Local History ▸, Git ▸, Compare with Clipboard | greyed out: they need the backend's actions (see below) |

**The editor tab's menu** (`EditorTabPopupMenu`):

| Item | In IDEate |
|---|---|
| Close ⌘W, Close Other Tabs, Close All Tabs, Close Tabs to the Left, Close Tabs to the Right | work (each tab closes as with its close button); greyed out when there is no such tab |
| Copy Absolute Path, Copy File Name | work |
| Pin Tab, Split Right, Split Down, Split and Move Right, Split and Move Down, Select In…, Open In ▸, Configure Editor Tabs… | greyed out: no pinned tabs, split editors or settings in IDEate yet |

JetBrains Client probably fills these menus from the backend: the rd model has an actions protocol
(`ActionPopupSession`, `ActionPopupContent`, `ActionExecuteRequest`, …). That would bring every greyed item at
once. The menu takes its items as data, so backend items can replace the local lists.

Checked 2026-11-17 against the Mac backend (port 5992):
- **Desktop:** a right click on `greeting` (line 6) opened the menu at the pointer and moved the caret there.
  Copy put `String greeting = "just a simple greeting";` and its line break on the clipboard. A right click on
  the `Main.java` tab opened the tab menu; with one tab, the Close Other/Left/Right items are greyed. Copy File
  Name gave `Main.java`.
- **Emulator (Pixel 3a):** a long press on line 8 opened the editor menu in the middle of the screen with
  40 dp rows and moved the caret to line 8. "Copy / Paste Special" opened in place with its back row.
- **"A right click into an unfocused desktop window doesn't move the caret"** (seen while testing paste) is the
  test tool, not IDEate. The cmux-cua click arrived at (412, 132) px instead of (994, 300): its first click into a
  window that isn't in front converts the screenshot point with the Retina scale (÷ 2) instead of the
  screenshot's 1.307 (1100 ÷ 2 × 1.307 ≈ 719, where the menu opened). IDEate put both the menu and the caret at
  that wrong point (line 4, `main`), as it should for the position it got. A real click goes to the right place.
- **Labels:** on the first version, labels shared the row's free space with the gap before the shortcut, so a
  long label was cut at half the width ("Remove from Recent Pr…", seen by the UI session). Now the label takes
  the room the menu has, and the shortcut sits at the right end.

## 14. Tool window bar, tree indent and headers, measured against Gateway (2026-11-17)

The user compared Gateway (JetBrains Client) with IDEate on the same phone backend, both in compact density, both
screenshots at 2x. Measured in pixels, halved here to dp; code reading from IntelliJ 263 sources.

**Tool window bar** (`SquareStripeButton`, `SquareStripeButtonLook`). IntelliJ paints the highlight inside the
button minus the stripe's icon padding, not over the whole button:

| | IntelliJ / Gateway | IDEate before | Key |
|---|---|---|---|
| Button | 37×40, compact 32×32; no gap between buttons | 40×40 / 32×32 plus 2 on each side (36 apart) | `StripeToolbar.Button.size` |
| Highlight | button minus 5,5,5,2 (top, left, bottom, right), compact 4,5,4,3: 30×30, compact 24×24 | the whole button | `StripeToolbar.Button.leftStripeIcon.padding(.compact)` |
| Corners | arc 12, compact 8 (diameters): radius 6 / 4 | radius 6 | `Button.ToolWindow.arc` (`JBUI.CurrentTheme.Toolbar.stripeButtonArc`) |
| Colour of a shown window | `#FFFFFF29`, `#4A4A4F` on the bar | `#393B40` (hover's shade) | `ActionButton.pressedBackground` = `toolbar-bg-pressed` = `transparent-white-40` |
| Bar width | 35 in compact: the button against the window edge, then 3 to the islands | 40, buttons centred | measured |
| Separator | 1 dp `#FFFFFF21` (`#43454A` on the bar), as long as the highlight and lined up with it, 5 from the buttons on either side (an 11 gap) | `#393B40`, 20 long, 6 from the buttons | `toolbar-border`; measured |

A focused tool window's button uses `ToolWindow.Button.selectedBackground` (`toolbar-selected-bg` = blue-60)
instead; IDEate has no tool window focus yet, so every shown window gets the pressed colour, as Gateway showed.

**Tree indent** (Project and Structure share `ProjectTreeView`). From the island's left edge, in px at 2x:

| Row part | Gateway | IDEate before | IDEate now |
|---|---|---|---|
| Top-level chevron glyph | 26 | 32 | 26 |
| Second-level chevron glyph | 67 | 73 | 67 |
| Icon after the chevron | +33 | +33 | +33 |
| Text after the chevron | +71 | +69 | +71 |

So the chevron box starts at 9 dp in compact (Tree.border's 8 plus 1; normal assumed 13 = 12 + 1, not measured),
not 12, and the text is 3 dp after the icon, not 2. The per-level step (19) was right. The selection band keeps
its measured 12 dp inset (section 7).

**Tool window title.** Bold in the new UI: `ComboContentLayout` and `TabContentLayout` create the name label as
`BaseLabel(ui, ExperimentalUI.isNewUI())`, and `BaseLabel.getFont()` derives `Font.BOLD`. Drawn with Inter
SemiBold (Inter has no Bold file), at the header size (13, compact 12).

**Project avatar** (`AvatarUtils.generateColoredAvatar`, `AvatarUtils.initials`):

| Part | Value |
|---|---|
| Letters | two capitals from camel case (`MyProject` → `MP`); else the first and last word split at the first of space, comma, `-`, `_`, `.`, quotes that gives two words (`xtool-web-app` → `XA`); else the camel case letters, one for a single lower-case word (`xtool` → `X`) |
| Corners | `arcRatio` 0.4 of the size as arc diameter: radius 0.2 × size (3.2 at 16) |
| Font | JetBrains Mono SemiBold (`WEIGHT_DEMIBOLD`) at `13 * size / 20` (16 → 10, 20 → 13) |
| Colour | unchanged: `projectAvatarBrush` (theme gradients, picked like a non-local project) |

**Git status and file colours.** See docs/split-protocol.md, "Project view rows: file status and scope colour".

Not done here: the backend's own tool window list and the ⋯ button, the "Project ▾" view selector (docs/pending.md
#14, #15). The title bar and the project-colour tint are section 15.

## 15. Title bar and project gradient (2026-11-17)

The user asked for Gateway's title bar: no separate title bar, the window's buttons inside the main toolbar row.

**macOS** (`desktop/MacTitleBar.kt`), as IntelliJ's `MacToolbarFrameHeader` and Jewel's `TitleBar.MacOS.kt` do it:

| Step | Call | Value |
|---|---|---|
| Make the title bar transparent and as tall as the toolbar | `JBR.getWindowDecorations().createCustomTitleBar()`, `height =`, `setCustomTitleBar(window, bar)` | `IslandsDark.toolbarHeight`: 40, compact 32 (`CustomHeader` uses the same 40 / 32) |
| Leave the traffic lights their room | `bar.leftInset` (JBR's own figure; IntelliJ hard-codes 78 = 52 + 26) | 0 in full screen, where they are hidden |
| Drag the window, double-click to zoom | nothing: JBR does it for the title bar area | |
| Let the toolbar's controls take clicks | `bar.forceHitTest(true)` while the pointer is over one; set on every press, release, move and drag | the areas come from `TitleBarArea.control`, in window px; the mouse point is scaled by `defaultTransform` (2 on Retina) |

The JBR API comes from `intellij.libraries.jbr.jar` in the same JetBrains Client install as the protocol jars.
On another Java runtime, `JBR.isWindowDecorationsSupported()` is false and the window keeps its native title bar.
So `:desktop:run` runs the app on that install's own JetBrains Runtime (`Contents/jbr/Contents/Home` on macOS,
`jbr` on Linux and Windows; `compose.desktop.application.javaHome`), whatever JDK Gradle runs on. The user ran it
from Gradle on another JDK and still got the native title bar. A standard JDK can only put the content under a
transparent title bar (`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar`): the bar stays 28 pt with
the traffic lights at their usual place, Java's content view takes the mouse so the window can't be dragged there,
and there is no hit test for the toolbar's buttons.
The connect screen has no toolbar, so it gets an empty strip of the toolbar's height that drags the window
(`TitleBarSpacer`).

Checked on the desktop (2026-11-17): the traffic lights sit in the toolbar row; a click on "Current File" reaches
the app ("Run configurations isn't supported"). Not checked: dragging and double-click zoom (the window left the
screen when the user changed Spaces), full screen.

The user's earlier attempt in another project moved the traffic lights by hand through AppKit (`setFrameOrigin`
on `standardWindowButton:` via JNA and `AWTAccessor`) with `apple.awt.fullWindowContent` and
`apple.awt.transparentTitleBar`. It had no title bar height, no hit test and no full-screen handling. AppKit resets
hand-moved buttons on resize and full screen, and Compose content over a transparent title bar takes the drag.

**Android desktop windowing** (API 35+, `android/CaptionBar.kt`), from
[Customizable header insets](https://developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing#customizable_header_insets):

| Step | Call |
|---|---|
| Draw under the caption bar; light caption buttons over the dark toolbar | `setSystemBarsAppearance(APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND, … or APPEARANCE_LIGHT_CAPTION_BARS)` |
| Toolbar at least the caption bar's height; the rest of the app below it | `WindowInsets.captionBar` top; the app's padding is `safeDrawing.exclude(captionBar)` |
| Keep clear of the system's parts (window buttons, app chip) | `rootWindowInsets.getBoundingRects(Type.captionBar())`: each rect counts against the nearer window edge |
| Taps on the toolbar's controls reach the app, the rest drags the window | `Modifier.systemGestureExclusion()` as `TitleBarArea.control` |

Outside desktop windowing the caption bar is 0 and nothing changes. Checked on the API 34 emulator that the phone
layout is unchanged apart from the gradient.

Checked on Android 16 (2026-11-17, emulator `Tablet_API_36`: Pixel Tablet, `system-images;android-36;
google_apis_tablet;arm64-v8a`, port 5558). Desktop windowing on: `adb root`, `setprop
persist.wm.debug.desktop_mode_enabled true`, `settings put global enable_freeform_support 1` and
`force_resizable_activities 1`, reboot; then `adb shell wm shell desktopmode moveTaskToDesk <taskId>` (the id from
`am stack list`). Result:
- the toolbar is in the caption bar, between the system's app menu (left) and minimise, maximise, close (right),
  with light caption icons over the project gradient;
- a tap on "Current File" reaches the app ("Run configurations isn't supported in this client yet");
- a swipe from the empty part of the toolbar, (896, 178) to (1196, 378) px, moved the window by the same 300, 200.

**Project gradient** (`drawProjectGradient` in `ui/TitleBar.kt`; `IslandsGradientPainter.renderFrameGradientImage`):
painted on the window background behind the islands, so it tints the toolbar and the top of the tool window bar.

| Part | Value |
|---|---|
| Across | `main-window-bg` `#26282C` at x = 0 → the project colour blended in at 85% under the project widget's icon centre → `#26282C` 600 dp further right (`RecentProject.MainToolbarGradient.width` 600) |
| Down | fades to `#26282C` over 300 dp (`.height` 300) |
| Project colour | `RecentProject.ColorN.MainToolbarGradientStart`, same index as the avatar: `#664832`, `#5E4724`, `#45522F`, `#335661` (`smoke`), `#394773`, `#613861`, `#503D70`, `#325959`, `#365439` |
| Inactive window | IntelliJ fades it; IDEate doesn't yet |

## 16. Toolbar dropdowns, toolbar start and selection band, measured (2026-11-17)

From the user's screenshots of Gateway and IDEate (with and without JBR's title bar), 2x pixels:

| Item | Gateway | IDEate before | Now |
|---|---|---|---|
| Project widget | a box 25 dp high (the toolbar button's 26 in compact), 10 dp before the avatar, ~8 after the chevron; `toolbar-bg-hovered` `#FFFFFF17` under the pointer | no box: the avatar right after the row's 6 dp padding | `ToolbarDropdown`: `MainToolbar.Dropdown.borderInsets` new UI 5,10,5,6, `toolbarButton` high, radius 4 (`MainToolbar.Button.arc.compact` 8), hover fill. "Current File" uses it too |
| Toolbar start, own title bar, landscape | the row under the title bar starts at the islands' left edge (71 px = the tool window bar) | 6 dp from the window edge | the project widget's box starts at the islands' left edge: `stripeThickness` in landscape, `islandGap` otherwise; after JBR's left inset when the traffic lights share the row |
| Tree selection band, left | 18 px (9 dp) inside the island, so 6 dp before the root's chevron glyph | 24 px (12 dp): no space before the chevron | 9 dp (`treeChevronInset`); the right inset stays 12 |

The traffic lights in Gateway's screenshot are spaced wider (46 px apart, ours 41): JBR spaces them by the title
bar height, so Gateway's bar may be taller than our 32 dp. The crop cut the window's top, so its height couldn't
be measured; IntelliJ's own rule is 40, compact 32 (`CustomWindowHeaderUtil.getPreferredWindowHeaderHeight`).

## 17. Resizable splits (2026-11-18)

`component/SplitPane.kt`, from IntelliJ's `Splitter`, `ThreeComponentsSplitter` and `ToolWindowPane`:

| Rule | IntelliJ | IDEate |
|---|---|---|
| Proportion | first pane ÷ (first + second), 0..1 (`Splitter.myProportion`) | same; room = the split less the island gap |
| Drag | the proportion follows the pointer along the splitter (`DividerImpl.processMouseMotionEvent`) | the first pane grows by the pointer's travel since the press |
| Least size | each pane's minimum size (`getMinProportion`) | 60 dp each; a split under 120 dp doesn't move |
| Out-of-range weight | `ToolWindowPane.normalizeWeight`: ≤ 0 → 0.33, ≥ 1 → 0.67 | same, for given and saved values |
| Mouse zone | 6 px around a 1 px divider (`ide.splitter.mouseZone`) | the island gap plus 4 dp each side (12 dp in compact), wide enough for a finger |
| Pointer | `W_RESIZE_CURSOR` side by side, `N_RESIZE_CURSOR` stacked | the same AWT cursors on the desktop; `PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW` / `TYPE_VERTICAL_DOUBLE_ARROW` on Android (`LocalResizePointers`) |
| Finger | (desktop only) | a pill 32 × 4 dp in `#9DA0A8` on the border from the moment a finger presses it until it lifts (not only once the drag passes the touch slop); the gap widens to 12 dp meanwhile (4 dp each side of the pill; was the 4 dp island gap, which the pill filled), each pane giving half so the border's centre stays put, and narrows again on release (animated) |

The pane weights (`ui/PaneWeights.kt`, IntelliJ's `WindowInfoImpl` weight 0.33 and side weight 0.5) are saved
per project next to the open editors (`OpenEditors`, `"panes": {"left": 0.28, …}`), 500 ms after the last change,
so a drag writes once.

Checked on the desktop with a throwaway two-split window: the side-by-side border dragged 204 px went from 0.33
to 0.445 (204 px of 1768 px room), the stacked one from 0.50 to 0.28; each border followed the pointer. Not
checked: the pill (needs a finger on Android, once `ClientScreen` uses the split) and the pointer shapes
(screenshots don't show the pointer).

## 18. Android system bars (2026-11-18)

The theme was `Theme.Material.NoActionBar` and the app never drew behind the system bars. On Android 14 and older
both bars were black; Android 15+ forces drawing behind them, so they showed the IDE window's grey there.

| Part | Before (API 34 emulator) | Now |
|---|---|---|
| Status and navigation bars | black `#000000` | transparent on every version (`enableEdgeToEdge` with `SystemBarStyle.dark(TRANSPARENT)`), light icons; no grey contrast overlay under 3-button navigation (`isNavigationBarContrastEnforced = false`) |
| Behind them, connect screen | `#26282C` on Android 15+ (the IDE window's colour) | the connect screen's own `#191A1C` (`ConnectApp` paints `Gateway.background` before the insets padding) |
| Behind them, IDE screen | `#26282C` | `#26282C`: the bottom tool window bar runs into the navigation bar |
| Camera cutout, phone sideways | black strip | the background fills it (`enableEdgeToEdge` lays out into short-edge cutouts); content keeps clear (`safeDrawing`) |
| Before the first frame | `Theme.Material`'s black | `Theme.Ideate`: window background and launch splash `#191A1C`, the connect screen's colour |

Measured on the API 34 emulator (1080 × 2220): status bar rows 0–65 and navigation bar rows 2154–2219 were
`#000000`; now `#191A1C` on the connect screen and `#26282C` on the IDE screen. Not checked: a cutout (the
emulator has none).

The project tint behind the status bar (ideate-editor, same day, after the layout rewrite): `ClientScreen` takes
the insets (`insets = Modifier.windowInsetsPadding(appInsets())` through `EditorApp`) and applies them inside its
background, so the window colour and `drawProjectGradient` start at the screen's top. The gradient's 300 dp fade
then runs from behind the status bar, as on macOS it runs from behind the merged title bar. The connect screen
keeps the insets on its own column; the reconnect popup centres on the whole screen.

## 19. Two windows: connections and editor (2026-11-18)

IntelliJ has a welcome frame and a project frame; Gateway has its own window and JetBrains Client's. IDEate had one
window that showed the connect screen and then the IDE screen.

| | Connections window | Editor window |
|---|---|---|
| Content | `ConnectApp` (connect/ConnectController.kt) | `EditorApp` |
| Desktop size | 800 × 650 dp, `FlatWelcomeFrame`'s default (`MAX_DEFAULT_WIDTH` 800, `DEFAULT_HEIGHT` 650) | 1200 × 800 dp, as before |
| Title bar | an empty strip, then a 1 dp line in `WelcomeScreen.separatorColor` (`Gateway.divider`) | the main toolbar |
| Closing it | quits (desktop); ends the session and the app's task (Android) | leaves the session; the backend keeps running |

- **Desktop** (`Main.kt`): one window at a time. The connections window shows while there is no session, the editor
  window while there is one. One controller holds one session, so the connections window has nothing to do
  meanwhile.
- **Android** (`MainActivity`, `EditorActivity`): `MainActivity` starts `EditorActivity` when a session opens, and
  `EditorActivity` finishes when it ends.
  - On a phone both are in one task: the editor covers the connect screen, and Back leaves the session.
  - Under desktop windowing (`isInMultiWindowMode`) the editor starts with `FLAG_ACTIVITY_NEW_TASK |
    FLAG_ACTIVITY_MULTIPLE_TASK`, so it is a second window. Both stay open; closing the editor's window leaves the
    session (`onDestroy` while finishing).
  - The controller, fonts and icons are shared through `IdeateProcess`. A dropped connection is retried while
    either activity is started.
- **Checked:**
  - Desktop: connections window 800 × 650 with the divider; a join link opens only the 1200 × 800 editor window;
    its close button brings the connections window back.
  - Phone emulator (Android 14): editor on top in the same task; Back returns; a History tap opens it again.
  - Tablet emulator (Android 16, desktop windowing): two tasks, two windows; the editor's X leaves the session.
- **Not checked:** split-screen (also `isInMultiWindowMode`, so the editor also starts as its own task there), and
  the system recreating `EditorActivity` after the process ended (it goes back to `MainActivity`).

## 20. Text effects in the editor (2026-11-18)

Underlines, waves, strikeouts and boxes are ported from IntelliJ's painter, not approximated: `ui/EditorEffects.kt`
follows `EffectPainter2D` (IC `platform/platform-api/src/com/intellij/ui/paint`) as `EditorPainter.paintTextEffect`
calls it, with the default registry values `ide.text.effect.new`, `ide.text.effect.new.metrics` and
`ide.text.effect.new.scale`, all `true`.

- **Inputs.** The baseline `y`, the room under it (`EditorView.getDescent()`, 5 px for JetBrains Mono 12 at line
  spacing 1.2), the font's line metrics and the screen scale. JetBrains Mono's tables: `underlinePosition` -155,
  `underlineThickness` 50, `yStrikeoutPosition` 320, `yStrikeoutSize` 50 of 1000 units per em.
- **Thickness.** `factor × max(font's underline thickness, fontSize / 13)`, moved onto a whole device pixel
  (`PaintUtil.alignToInt`): down, except the wave on a HiDPI screen, which goes up. Factors: line 1, bold line 2,
  dotted 2, wave 3.
- **Offset under the baseline.** The font's underline offset (1.86 at size 12), on a device pixel the same way, and
  never so low that the effect leaves the line.

| Effect | JetBrains Mono 12, 2x screen | 1x screen | Before (IDEate) |
|---|---|---|---|
| `LINE_UNDERSCORE` | 0.5 thick, 1.5 under the baseline | 1 thick at 1 | 1 thick, centred 1.5 under |
| `BOLD_LINE_UNDERSCORE` | 1.5 thick at 1.5 | 2 thick at 1 | 2 thick, centred 1.5 under |
| `BOLD_DOTTED_LINE` | round dots 1.5 wide, every 3, at 1.5 | square dots 2 wide, every 4, at 1 | dashes 2 long, every 4 |
| `WAVE_UNDERSCORE` | zig-zag from 2 to 4 under the baseline, a step every 2, stroke 0.7 | from 1 to 3, a step every 2 | from 3 to 5, a step every 1 |
| `STRIKEOUT` | 0.5 thick, 3.5 above the baseline | 1 thick, 3 above | 1 thick, 3.5 above |
| `BOXED` | 1-unit frame around the whole line height (`drawSimpleBorder`) | the same | frame around the caret's height |

- The wave and the dots start at multiples of their period from the editor's left, so two ranges next to each
  other join up (IntelliJ tiles one cached image the same way).
- A wave taller than 4 units (large fonts) is drawn with curves, as in IntelliJ; at the editor's sizes it is the
  zig-zag.
- The protocol sends only these six (`IdeaEffectType`); IntelliJ's `ROUNDED_BOX`, `SEARCH_MATCH`,
  `SLIGHTLY_WIDER_BOX` and `FADED` never arrive.
- **Checked:** unit tests for the geometry at 1x and 2x (`EditorEffectsTest`); on the desktop (2x) the error,
  warning and typo waves against IntelliJ's Color Scheme preview. Not seen on screen: strikeout, dotted line and
  box (the test file showed none).
- **Not done:** IntelliJ joins a box that spans several lines into one outline; IDEate frames each line's part.

## 21. Search Everywhere popup (2026-11-19)

IntelliJ's sources (`platform/searchEverywhere/frontend/src/ui`, `JBUI.CurrentTheme.BigPopup`, `Popup.Selection`)
and ManyIslandsDark.theme.json; not measured on the real client (its popup is a window of its own that the
automation used here cannot capture).

| Part | Value | Source |
|---|---|---|
| Width | 700, less 8 on each side when the window is narrower | registry `search.everywhere.new.minimum.width` |
| Place | centred; top at a quarter of the window's height less half the popup's, not above the main toolbar; fixed once shown, so it grows downward | `SeFrontendService.calcPopupPositionAndShow` |
| Frame | `Popup.background` `#26282C`, 1 px `#33353B`, radius 8 | `PopupContainer` |
| Header | insets 2, 4, 0, 9; tabs 24 high, 12 each side; the selected one filled `#233558` with white text, radius 4 | `SearchEverywhere.Header.insets`, `BigPopup.tabInsets`, `SearchEverywhere.Tab.*` |
| Field | IDEate's `SearchTextField` undecorated, 14 each side (12 + the input insets' 2); the search-with-history icon (`AllIcons.Actions.SearchWithHistory`, magnifier with ▾), 6 to the text; a click lists the tab's earlier searches | `ComplexPopup.TextField.borderInsets`, `textFieldInputInsets`, `SePopupContentPane.addHistoryExtensionToTextField` |
| Separators | 1 px `#33353B` under the tabs and under the field | `customLineBottom` |
| Rows | 24 high (22 compact); selection band 8 in from the sides, radius 4, `#2A4371`; then 8 inside | `List.rowHeight`, `Popup.Selection.leftRightInset`, `innerInsets` |
| Row | icon 16, gap 6, name (never cut), gap 6, folder in `#73767C` shortened to row − name − 16 − location − 20 by `SETextShortener.getShortenContainerText` (`of org.junit.jupiter.api.function` → `of org....api.function`; kept whole when that room is negative), the location and its icon at the right end, cut at the row's end when nothing is left | `SeTargetItemPresentationRenderer` |
| Match | only on the selected row: the matched ranges on `#BA9752` in black | `SearchMatch.startBackground` = `search-match-bg` |
| List | at most 600 high, and at most 60% of the window | `BigPopup.maxListHeight` |
| Nothing found | "Nothing found." in `#73767C` | `SeEmptyResultInfo` |

## 22. Editor tab row: one scrolling row and the hidden-tabs list (2026-11-19)

IntelliJ's default editor tab placement is one row (`ScrollableSingleRowLayout`): tabs keep their full width and the
row scrolls sideways; JBTabsImpl scrolls the selected tab into view. When some tabs don't fully show, a ▾
(`TabListAction`, `AllIcons.Toolbar.Expand` = `expui/general/chevronDownLarge.svg`) appears at the row's right end;
in the new UI it opens a list popup (`JBTabsImpl.showListPopup`, `HiddenInfosListPopupStep`) of the hidden tabs with
their icons, a separator between those cut off on the left and those on the right. Choosing one selects it.

IDEate (`ClientScreen.EditorTabs`):
- the row scrolls with a finger, a trackpad or the wheel; names stay on one line (they wrapped before, and the last
  tab lost its ×);
- a tab counts as hidden when any part of it is outside the visible row, measured on a wrapper around each tab (the
  context menu area would otherwise be the parent and every tab would read as at 0);
- on a phone held upright, a sideways drag that starts on the tab row scrolls the tabs until they reach that end;
  only then does it switch pages (`TabRowScroll`, given to the page swiper with the drag's start point);
- the tab's icon comes from the backend, as JetBrains Client does it: `VfsModel.getFileIcon(RdFileIconParams(projectId,
  fileId, flags = 2))`, IntelliJ's `IconUtil.computeFileIcon(file, Iconable.ICON_FLAG_READ_STATUS, project)` for
  tabs (`EditorsSplitters`). Before 2026-11-19 a tab took its icon from the Project tree and fell back to the plain
  text icon for a file the tree hadn't loaded (Big.java opened from Search Everywhere). The read-only lock layer
  is dropped, like other overlays (`iconPaths`);
- not yet: the × on hover in the list, and the ⋮ after the ▾ (IntelliJ's editor tab actions). Desktop: checked
  only on the emulator; the mouse wheel on the row is untested.
Row widths (IDEate, from the renderer's rules): the name in full; the location's icon only while the name and the
folder leave room; then the folder; the location's text gets what is left and is cut at the row's end. Checked
with a copy of the xtool repo (876 files): Classes "Execu", Files "build.gradle".
History: `SearchHistoryList`, per tab, newest first, at most 50, kept while the app runs; the query is saved when the
popup closes (`SeFrontendService`). Before 2026-11-19 a long folder took the row and left the name no room (the name
had the row's weight, the folder didn't, so the folder was measured first).


## 23. Mouse pointer over the editor (2026-11-20)

| Where | IntelliJ | IDEate |
|---|---|---|
| Text area | `TEXT_CURSOR`, the I-beam (`EditorImpl`, `getDefaultCursor`) | `PointerIcon.Text` |
| Gutter (line numbers, icons) | `DEFAULT_CURSOR`, the arrow (`EditorGutterComponentImpl`) | `PointerIcon.Default` left of `gutterWidth` |
| ⌘ (Ctrl) held over a symbol | `HAND_CURSOR` | `PointerIcon.Hand` |
| Over the selection, no button down | the arrow, because the selection can be dragged | not done: IDEate has no drag and drop of the selection yet |
| Over a folded placeholder | `HAND_CURSOR` | not done: IDEate doesn't fold |

Only a mouse or trackpad shows a pointer; touch is unchanged.
