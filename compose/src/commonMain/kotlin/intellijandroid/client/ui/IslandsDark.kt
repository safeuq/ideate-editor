package intellijandroid.client.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
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
 */
object IslandsDark {
  /**
   * Set once by the app before its first composition (desktop: IntelliJ's own setting; Android:
   * compact). Changing it later doesn't redraw what is already on screen.
   */
  @Volatile var density = UiDensity.COMPACT

  private fun byDensity(normal: Dp, compact: Dp) =
    if (density == UiDensity.COMPACT) compact else normal

  private fun byDensity(normal: Float, compact: Float) =
    if (density == UiDensity.COMPACT) compact else normal

  val windowBackground = Color(0xFF26282C) // main-window-bg, behind the islands
  val islandBackground = Color(0xFF191A1C) // layer-0-bg: tool windows and editor
  val islandRadius
    get() = byDensity(10.dp, 8.dp) // Island.arc 20 (compact 16), halved

  val islandGap
    get() = byDensity(6.dp, 4.dp) // Island.borderWidth 6 (compact 4)

  val text = Color(0xFFD1D3D9) // text-default
  val textSecondary = Color(0xFF73767C) // grayed (e.g. path after the module name)
  val link = Color(0xFF538AF9) // measured: completion match highlight, the theme's blue for links

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
  val treeSelectionFocused = Color(0xFF2A4371) // selection-bg-active
  val treeSelectionUnfocused = Color(0xFF33353B) // selection-bg-inactive
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

/**
 * Fonts the screens use; the platform supplies them (desktop: files in the JetBrains Client's JBR).
 */
data class ClientFonts(val editor: FontFamily, val ui: FontFamily)

/**
 * Loads IntelliJ icons by their path in the icons jar, e.g. `expui/nodes/folder.svg`; the platform
 * supplies it.
 */
fun interface IconProvider {
  fun painter(path: String): Painter?
}

val LocalClientFonts = staticCompositionLocalOf {
  ClientFonts(FontFamily.Monospace, FontFamily.SansSerif)
}
val LocalIcons = staticCompositionLocalOf { IconProvider { null } }

/**
 * Added to the editor so the platform's text input can reach it (Android: opens the soft keyboard).
 * Desktop: nothing.
 */
val LocalEditorTextInput = staticCompositionLocalOf<Modifier> { Modifier }
