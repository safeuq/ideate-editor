package io.github.safeuq.ideate.client.ui.theme

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
 * IntelliJ IDEA 2026.3 "Islands" UI chrome, from the theme files and Swing painting code (values
 * and sources: docs/intellij-look.md, section 11 for the density pairs). Editor text colors don't
 * live here: they come from the backend's color scheme. Sizes that differ between the densities are
 * getters reading [density].
 *
 * Each color is a getter reading its `UIManager` key in [LafManager.currentTheme], so it follows a
 * theme change; the comments say how the key resolves in `ManyIslandsDark.theme.json`'s `colors`
 * map, e.g. `Component.borderColor = control-border = gray-50`.
 */
object Islands {
  /** [key] in the current theme; both themes have every key this file names. */
  fun color(key: String): Color =
    checkNotNull(LafManager.currentTheme.color(key)) {
      "$key is missing from ${LafManager.currentTheme.name}"
    }

  /** [key] in the current theme, or [fallback] when the theme doesn't set it. */
  private fun color(key: String, fallback: () -> Color): Color =
    LafManager.currentTheme.color(key) ?: fallback()

  /** [key], or IntelliJ's default for it: [light] or [dark] by the theme, e.g. `0xFFD3D5DB`. */
  private fun color(key: String, light: Long, dark: Long): Color =
    color(key) { Color(if (LafManager.currentTheme.isDark) dark else light) }

  // Project tabs (WindowTabsComponent, IslandsUICustomization.paintProjectTab): the Islands themes
  // set most MainWindow.Tab keys (Islands Dark: foreground text-muted, selectedForeground
  // text-default, selectedBackground tool-window-bg, hoverBackground tab-bg-hovered, separatorColor
  // toolbar-border); the fallbacks are IntelliJ's for the keys a theme leaves out.
  val projectTabSelectedBackground
    get() = color("MainWindow.Tab.selectedBackground") { islandBackground }

  val projectTabHoverBackground
    get() =
      color("MainWindow.Tab.hoverBackground") {
        color("MainToolbar.Dropdown.transparentHoverBackground")
      }

  val projectTabSeparator
    get() = color("MainWindow.Tab.separatorColor", 0xFFD3D5DB, 0xFF43454A)

  val projectTabForeground
    get() = color("MainWindow.Tab.foreground", 0xFFA8ADBD, 0xFFB4B8BF)

  val projectTabSelectedForeground
    get() = color("MainWindow.Tab.selectedForeground", 0xFFC9CCD6, 0xFFCED0D6)

  val projectTabHoverForeground
    get() = color("MainWindow.Tab.hoverForeground", 0xFFC9CCD6, 0xFFCED0D6)

  /**
   * Set once by the app before its first composition (desktop: IntelliJ's own setting; Android:
   * compact). Changing it later doesn't redraw what is already on screen.
   */
  @Volatile var density = UiDensity.COMPACT

  fun byDensity(normal: Dp, compact: Dp) = if (density == UiDensity.COMPACT) compact else normal

  private fun byDensity(normal: Float, compact: Float) =
    if (density == UiDensity.COMPACT) compact else normal

  val windowBackground
    get() = color("MainWindow.background") // main-window-bg, behind the islands

  // The full-screen traffic lights' circles at rest; the theme doesn't set it, so it is IntelliJ's
  // default (MacFullScreenControlsManager.configureColors).
  val fullScreenControlBackground
    get() = color("MainWindow.FullScreeControl.Background")

  val islandBackground
    get() = color("ToolWindow.background") // layer-0-bg: tool windows and editor

  val islandRadius
    get() = byDensity(10.dp, 8.dp) // Island.arc 20 (compact 16), halved

  val islandGap
    get() = byDensity(6.dp, 4.dp) // Island.borderWidth 6 (compact 4)

  val text
    get() = color("Label.foreground") // *.foreground = text-default = gray-130

  val textSecondary
    get() = color("Component.infoForeground") // *.infoForeground = text-secondary = gray-80

  val textDisabled
    get() = color("Label.disabledForeground") // *.disabledForeground = text-disabled = gray-60

  val textError
    get() = color("Label.errorForeground") // Label.errorForeground = text-error

  val textSuccess
    get() = color("Label.successForeground") // text-success = green-100 (a running backend)

  val textOverAccent
    get() =
      color("Button.default.foreground") // Button.default.foreground = text-over-accent = white

  val link
    get() = color("Link.activeForeground") // Link.activeForeground = text-link = blue-100

  // Component.borderColor, Button.startBorderColor = control-border = gray-50
  val controlBorder
    get() = color("Component.borderColor")

  // Button.default.startBackground = control-brand-bg = blue-80
  val controlBrandBackground
    get() = color("Button.default.startBackground")

  val focusBorder
    get() = color("Component.focusColor") // Component.focusColor = control-brand-border = blue-80

  // EditorTabs.underTabsBorderColor = editor-border = layer-0-border = gray-30
  val underTabsBorder
    get() = color("EditorTabs.underTabsBorderColor")

  // Popup.background, PopupMenu.background = popup-bg = gray-30
  val popupBackground
    get() = color("Popup.background")

  // Popup.borderColor, Separator.separatorColor = popup-border = gray-40
  val popupBorder
    get() = color("Popup.borderColor")

  // *.selectionBackground = selection-bg-active = blue-50
  val selectionBackground
    get() = color("Tree.selectionBackground")

  // *.selectionInactiveBackground = selection-bg-inactive = gray-40
  val selectionInactiveBackground
    get() = color("Tree.selectionInactiveBackground")

  // *.borderColor = tool-window-border = layer-0-border = gray-30: tool window header line,
  // scroll pane borders
  val toolWindowBorder
    get() = color("ToolWindow.Header.borderColor")

  // ToolTip.background and ToolTip.borderColor = feedback-bg = layer-2-bg = gray-40
  val tooltipBackground
    get() = color("ToolTip.background")

  // ToolTip.shortcutForeground = text-muted = gray-100
  val tooltipShortcut
    get() = color("ToolTip.shortcutForeground")

  val toolbarSeparator
    get() = color("ToolBar.separatorColor") // ToolBar.separatorColor = toolbar-border

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
  val treeHover
    get() = color("Tree.hoverBackground") // selection-bg-hovered

  val toolbarHover
    get() = color("ActionButton.hoverBackground") // toolbar-bg-hovered = transparent-white-20

  val tabRowHeight = 32.dp // measured: editor tabs row
  val tabHeight = 24.dp
  val tabSelectedBackground
    get() = color("EditorTabs.underlinedTabBackground")

  val tabSelectedBorder
    get() = color("EditorTabs.underlinedBorderColor")

  val tabRadius = 6.dp

  // Tool window header tabs (Terminal's shells, Run's "Main", Problems' "File"): IntelliJ paints
  // them with IslandsTabPainter.paintTab (IslandsUICustomization.toolWindowTabPainter), called by
  // TabContentLayout.paintComponent for each ContentTabLabel.
  /** IslandsTabPainter.getHOffsetUnscaled: the pill sits this far inside the label on each side. */
  val headerTabHOffset
    get() = byDensity(4.dp, 2.dp)

  /** IslandsTabPainter.paintTab: the pill is the label's height minus vOffset = max(h - 28, 8). */
  val headerTabFullHeight
    get() = byDensity(28.dp, 24.dp)

  val headerTabMinVOffset
    get() = byDensity(8.dp, 4.dp)

  /** MainToolbar.Button.arc 12 (compact 8), an arc diameter: radius 6 (compact 4). */
  val headerTabRadius
    get() = byDensity(6.dp, 4.dp)

  // ContentLabel.getPreferredSize, new UI: the label's border is ToolWindow.HeaderTab
  // .leftRightInsets (0, 12) on both sides; the close icon starts 8 after the text (size.width - 4)
  // and ends 7 before the label's end (ICONS_GAP 3 + 4). Less the 4 hOffset, inside the pill:
  val headerTabTextInset = 8.dp // pill edge to text
  val headerTabCloseGap = 8.dp // text to the 16 dp close icon
  val headerTabCloseEndInset = 3.dp // close icon to pill edge

  /** BaseLabel's ToolWindow.Header.labelLeftRightInsets (0, 12, 0, 16): title to the first tab. */
  val headerTitleEndInset = 16.dp

  // IslandsTabPainter.getColors; a key the theme leaves out (EditorTabs.regularBackground,
  // regularBorderColor, hoverBorderColor in Islands) paints nothing.
  private fun colorOrTransparent(key: String) = color(key) { Color.Transparent }

  /** Fill and 1 dp border of a header tab, by IslandsTabPainter.getColors. */
  fun headerTabColors(selected: Boolean, active: Boolean, hovered: Boolean): Pair<Color, Color> =
    when {
      selected && active ->
        color("EditorTabs.underlinedTabBackground") to color("EditorTabs.underlinedBorderColor")
      selected && hovered ->
        color("EditorTabs.hoverBackground") to color("EditorTabs.inactiveUnderlinedTabBorderColor")
      selected ->
        color("EditorTabs.inactiveUnderlinedTabBackground") to
          color("EditorTabs.inactiveUnderlinedTabBorderColor")
      hovered ->
        color("EditorTabs.hoverBackground") to colorOrTransparent("EditorTabs.hoverBorderColor")
      else ->
        colorOrTransparent("EditorTabs.regularBackground") to
          colorOrTransparent("EditorTabs.regularBorderColor")
    }

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

  // A run widget button and its highlight: RunWidget.actionButtonWidth 30 (compact 26), as high
  // as the toolbar's buttons (RunWidget.toolbarHeight 30, compact 26).
  val runButtonWidth
    get() = byDensity(30.dp, 26.dp)

  // Each side of a run widget button, outside its highlight: RunWidget.toolbarBorderDirectionalGap
  // 2 (compact 4). Compact: 4 + 26 + 4 = the 34 measured in JetBrains Client's accessibility tree.
  val runButtonGap
    get() = byDensity(2.dp, 4.dp)

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
  val stripeHighlight
    get() = color("ActionButton.pressedBackground")

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
