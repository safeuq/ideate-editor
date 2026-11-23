package intellijandroid.client.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * IntelliJ's UI density (Settings | Appearance | UI density). In [COMPACT] IntelliJ replaces every
 * theme key that has a `.compact` twin by that twin, e.g. `Tree.rowHeight` 24 by
 * `Tree.rowHeight.compact` 22.
 */
enum class UiDensity {
  NORMAL,
  COMPACT;

  companion object {
    private val savedOption = Regex("""<option\s+name="UI_DENSITY"\s+value="(\w+)"""")

    /**
     * The density IntelliJ saved in `options/ui.lnf.xml`, e.g. `<option name="UI_DENSITY"
     * value="COMPACT" />`. IntelliJ (`UIDensity`: DEFAULT, COMPACT) leaves the option out while it
     * is DEFAULT.
     */
    fun fromIntelliJSettings(uiLnfXml: String): UiDensity =
      when (val saved = savedOption.find(uiLnfXml)?.groupValues?.get(1)) {
        null,
        "DEFAULT" -> NORMAL
        "COMPACT" -> COMPACT
        else -> error("Unknown UI_DENSITY \"$saved\" in IntelliJ's ui.lnf.xml")
      }
  }
}

/**
 * IntelliJ IDEA 2026.3 "Islands Dark" UI chrome, from the theme files and Swing painting code
 * (values and sources: docs/intellij-look.md, section 11 for the density pairs). Editor text colors
 * don't live here: they come from the backend's color scheme. Sizes that differ between the
 * densities are getters reading [density].
 *
 * Each color names the theme key it comes from and how the name resolves in
 * `ManyIslandsDark.theme.json`'s `colors` map, e.g. `Component.borderColor = control-border =
 * gray-50`. A theme value wins over one measured on a screenshot (docs/ui-components.md).
 */
object IslandsDark {
  /**
   * Set once by the app before its first composition (desktop: IntelliJ's own setting; Android:
   * compact). Changing it later doesn't redraw what is already on screen.
   */
  @Volatile var density = UiDensity.COMPACT

  fun byDensity(normal: Dp, compact: Dp) = if (density == UiDensity.COMPACT) compact else normal

  private fun byDensity(normal: Float, compact: Float) =
    if (density == UiDensity.COMPACT) compact else normal

  val windowBackground = Color(0xFF26282C) // main-window-bg, behind the islands
  val islandBackground = Color(0xFF191A1C) // layer-0-bg: tool windows and editor
  val islandRadius
    get() = byDensity(10.dp, 8.dp) // Island.arc 20 (compact 16), halved

  val islandGap
    get() = byDensity(6.dp, 4.dp) // Island.borderWidth 6 (compact 4)

  val text = Color(0xFFD1D3D9) // *.foreground = text-default = gray-130
  val textSecondary = Color(0xFF73767C) // *.infoForeground = text-secondary = gray-80
  val textDisabled = Color(0xFF4C4F56) // *.disabledForeground = text-disabled = gray-60
  val textError = Color(0xFFF57E84) // Label.errorForeground = text-error
  val textSuccess = Color(0xFF6DB083) // text-success = green-100 (a running backend)
  val textOverAccent = Color(0xFFFFFFFF) // Button.default.foreground = text-over-accent = white
  val link = Color(0xFF71A1FE) // Link.activeForeground = text-link = blue-100

  // Component.borderColor, Button.startBorderColor = control-border = gray-50
  val controlBorder = Color(0xFF40434A)
  // Button.default.startBackground = control-brand-bg = blue-80
  val controlBrandBackground = Color(0xFF3871E1)
  val focusBorder = Color(0xFF3871E1) // Component.focusColor = control-brand-border = blue-80

  // EditorTabs.underTabsBorderColor = editor-border = layer-0-border = gray-30
  val underTabsBorder = Color(0xFF26282C)

  // Popup.background, PopupMenu.background = popup-bg = gray-30
  val popupBackground = Color(0xFF26282C)
  // Popup.borderColor, Separator.separatorColor = popup-border = gray-40
  val popupBorder = Color(0xFF33353B)

  // *.selectionBackground = selection-bg-active = blue-50
  val selectionBackground = Color(0xFF2A4371)
  // *.selectionInactiveBackground = selection-bg-inactive = gray-40
  val selectionInactiveBackground = Color(0xFF33353B)

  // *.borderColor = tool-window-border = layer-0-border = gray-30: tool window header line,
  // scroll pane borders
  val toolWindowBorder = Color(0xFF26282C)
  // ToolTip.background and ToolTip.borderColor = feedback-bg = layer-2-bg = gray-40
  val tooltipBackground = Color(0xFF33353B)
  // ToolTip.shortcutForeground = text-muted = gray-100
  val tooltipShortcut = Color(0xFF9FA2A8)
  val toolbarSeparator = Color(0x21FFFFFF) // ToolBar.separatorColor = toolbar-border

  val headerHeight
    get() = byDensity(41.dp, 31.dp) // ToolWindow.Header.height (code default 41, compact 31)

  val headerFontSize
    get() = UI_FONT_SIZE + byDensity(0f, -1f) // ToolWindow.Header.fontSizeOffset.compact -1

  val headerLeftInset = 12.dp

  val treeRowHeight
    get() = byDensity(24.dp, 22.dp) // Tree.rowHeight 24 (compact 22; measured 22)

  val treeTopInset
    get() = byDensity(4.dp, 2.dp) // Tree.border top 4 (compact 2)

  // The selection's right inset: measured 12 on the real client in compact mode, so not
  // Tree.border's 12 (compact 8). Its left inset is treeChevronInset (Gateway, 2026-11-17).
  val treeSideInset = 12.dp

  // Where a top-level row's chevron box starts, and the selection's left edge: measured 9 in
  // compact against Gateway (2026-11-17), Tree.border's 8 plus 1; normal is inferred the same way
  // (12 + 1).
  val treeChevronInset
    get() = byDensity(13.dp, 9.dp)

  val treeIndent = 19.dp // leftChildIndent 7 → 8 + rightChildIndent 11
  val treeIconTextGap = 2.dp // chevron box end to icon: measured
  val treeTextGap = 3.dp // icon to text: measured against Gateway (2026-11-17)
  val treeHover = Color(0x10FFFFFF) // selection-bg-hovered
  val toolbarHover = Color(0x17FFFFFF) // toolbar-bg-hovered = transparent-white-20

  val tabRowHeight = 32.dp // measured: editor tabs row
  val tabHeight = 24.dp
  val tabSelectedBackground = Color(0xFF233657) // measured: selected editor tab pill
  val tabSelectedBorder = Color(0xFF2C4A82)
  val tabRadius = 6.dp
  val treeSelectionRadius = 4.dp // Tree.Selection.arc 8 passed as arc diameter

  // Main toolbar: MainToolbar.Button.size 30 (code default; compact 26), iconSize 20 (compact
  // 16), Icon.insets 6,5,4,5 (compact 3,4,3,4): height = button + top + bottom inset.
  // MainToolbar.Button.arc.compact 8 (an arc diameter); normal uses the toolbar buttons' 12.
  val toolbarButtonRadius
    get() = byDensity(6.dp, 4.dp)

  // A toolbar dropdown's (project widget, run configurations) padding: MainToolbar.Dropdown
  // .borderInsets, new UI 5,10,5,6 (top, left, bottom, right); its height is the toolbar button's.
  val toolbarDropdownStart = 10.dp
  val toolbarDropdownEnd = 6.dp

  val toolbarButton
    get() = byDensity(30.dp, 26.dp)

  val toolbarIcon
    get() = byDensity(20.dp, 16.dp)

  val toolbarHeight
    get() = byDensity(40.dp, 32.dp) // 30 + 6 + 4; compact 26 + 3 + 3

  val toolbarFontSize
    get() = UI_FONT_SIZE + byDensity(0f, -1f) // MainToolbar.fontSizeOffset.compact -1

  // Tool window bar: StripeToolbar.Button.size 37,40 (compact 32,32), iconSize 20 (compact 16).
  // Width across the bar, length along it.
  val stripeButtonWidth
    get() = byDensity(37.dp, 32.dp)

  val stripeButtonLength
    get() = byDensity(40.dp, 32.dp)

  val stripeIcon
    get() = byDensity(20.dp, 16.dp)

  // The open window's highlight sits inside the button, inset by
  // StripeToolbar.Button.leftStripeIcon.padding (top, left, bottom, right) 5,5,5,2 (compact
  // 4,5,4,3): 30×30 (compact 24×24).
  val stripeHighlightTop
    get() = byDensity(5.dp, 4.dp)

  val stripeHighlightStart = 5.dp

  val stripeHighlightEnd
    get() = byDensity(2.dp, 3.dp)

  // Button.ToolWindow.arc 12 (compact 8), an arc diameter.
  val stripeHighlightRadius
    get() = byDensity(6.dp, 4.dp)

  // ActionButton.pressedBackground = toolbar-bg-pressed = transparent-white-40: a shown window.
  val stripeHighlight = Color(0x29FFFFFF)

  // Measured 35 in compact against Gateway (2026-11-17): the button and 3 to the islands.
  val stripeThickness
    get() = stripeButtonWidth + 3.dp

  /** Editor.Gutter.emptyAnnotationAreaWidth: 4 (code default), compact 0. */
  val gutterEmptyAnnotationArea
    get() = byDensity(4.dp, 0.dp)

  /**
   * Editor.Gutter.gapAfterVcsMarkersWidth, gapAfterLineNumbersWidth and gapAfterIconsWidth: 4 each
   * (code default), compact 2 (darcula.theme.json).
   */
  val gutterGap
    get() = byDensity(4.dp, 2.dp)

  const val UI_FONT_SIZE = 13f // Inter 13 (user setting)
  const val EDITOR_FONT_SIZE = 12f // JetBrains Mono 12 (user setting)
  const val LINE_NUMBER_FONT_SIZE = 11f // editor size - 1
  const val LINE_SPACING = 1.2f // default editor line spacing
}
