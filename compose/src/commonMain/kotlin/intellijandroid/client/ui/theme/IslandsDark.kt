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
  val textOverAccent = Color(0xFFFFFFFF) // Button.default.foreground = text-over-accent = white
  val link = Color(0xFF538AF9) // measured: completion match highlight, the theme's blue for links

  // Component.borderColor, Button.startBorderColor = control-border = gray-50
  val controlBorder = Color(0xFF40434A)
  // Button.default.startBackground = control-brand-bg = blue-80
  val controlBrandBackground = Color(0xFF3871E1)
  val focusBorder = Color(0xFF3871E1) // Component.focusColor = control-brand-border = blue-80

  // Popup.background, PopupMenu.background = popup-bg = gray-30
  val popupBackground = Color(0xFF26282C)
  // Popup.borderColor, Separator.separatorColor = popup-border = gray-40
  val popupBorder = Color(0xFF33353B)

  // *.selectionBackground = selection-bg-active = blue-50
  val selectionBackground = Color(0xFF2A4371)
  // *.selectionInactiveBackground = selection-bg-inactive = gray-40
  val selectionInactiveBackground = Color(0xFF33353B)

  val headerHeight
    get() = byDensity(41.dp, 31.dp) // ToolWindow.Header.height (code default 41, compact 31)

  val headerFontSize
    get() = UI_FONT_SIZE + byDensity(0f, -1f) // ToolWindow.Header.fontSizeOffset.compact -1

  val headerLeftInset = 12.dp

  val treeRowHeight
    get() = byDensity(24.dp, 22.dp) // Tree.rowHeight 24 (compact 22; measured 22)

  val treeTopInset
    get() = byDensity(4.dp, 2.dp) // Tree.border top 4 (compact 2)

  // Measured 12 on the real client in compact mode, so not Tree.border's 12 (compact 8).
  val treeSideInset = 12.dp // also the selection's left/right inset
  val treeIndent = 19.dp // leftChildIndent 7 → 8 + rightChildIndent 11
  val treeIconTextGap = 2.dp
  val treeHover = Color(0x10FFFFFF) // selection-bg-hovered

  val tabRowHeight = 32.dp // measured: editor tabs row
  val tabHeight = 24.dp
  val tabSelectedBackground = Color(0xFF233657) // measured: selected editor tab pill
  val tabSelectedBorder = Color(0xFF2C4A82)
  val tabRadius = 6.dp
  val treeSelectionRadius = 4.dp // Tree.Selection.arc 8 passed as arc diameter

  // Main toolbar: MainToolbar.Button.size 30 (code default; compact 26), iconSize 20 (compact
  // 16), Icon.insets 6,5,4,5 (compact 3,4,3,4): height = button + top + bottom inset.
  val toolbarButton
    get() = byDensity(30.dp, 26.dp)

  val toolbarIcon
    get() = byDensity(20.dp, 16.dp)

  val toolbarHeight
    get() = byDensity(40.dp, 32.dp) // 30 + 6 + 4; compact 26 + 3 + 3

  val toolbarFontSize
    get() = UI_FONT_SIZE + byDensity(0f, -1f) // MainToolbar.fontSizeOffset.compact -1

  // Tool window bar: StripeToolbar.Button.size 37,40 (compact 32,32), iconSize 20 (compact 16).
  // Our buttons are square, so normal takes the 40; the bar adds 4 on each side.
  val stripeButton
    get() = byDensity(40.dp, 32.dp)

  val stripeIcon
    get() = byDensity(20.dp, 16.dp)

  val stripeThickness
    get() = stripeButton + 8.dp

  /** Editor.Gutter.emptyAnnotationAreaWidth: 4 (code default), compact 0. */
  val gutterEmptyAnnotationArea
    get() = byDensity(4.dp, 0.dp)

  const val UI_FONT_SIZE = 13f // Inter 13 (user setting)
  const val EDITOR_FONT_SIZE = 12f // JetBrains Mono 12 (user setting)
  const val LINE_NUMBER_FONT_SIZE = 11f // editor size - 1
  const val LINE_SPACING = 1.2f // default editor line spacing
}
