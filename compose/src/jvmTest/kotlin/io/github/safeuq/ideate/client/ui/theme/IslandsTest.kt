package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IslandsTest {
  @AfterTest fun backToDark() = LafManager.setCurrentLookAndFeel(LafManager.defaultDarkTheme)

  /** Each chrome color in Islands Dark (the values it had as constants) and in Islands Light. */
  @Test
  fun chromeColorsFollowTheTheme() {
    val colors = {
      with(Islands) {
        listOf(
          windowBackground,
          fullScreenControlBackground,
          islandBackground,
          text,
          textSecondary,
          textDisabled,
          textError,
          textSuccess,
          textOverAccent,
          link,
          controlBorder,
          controlBrandBackground,
          focusBorder,
          underTabsBorder,
          popupBackground,
          popupBorder,
          selectionBackground,
          selectionInactiveBackground,
          toolWindowBorder,
          tooltipBackground,
          tooltipShortcut,
          toolbarSeparator,
          treeHover,
          toolbarHover,
          tabSelectedBackground,
          tabSelectedBorder,
          stripeHighlight,
        )
      }
    }
    val dark =
      listOf(
        0xFF26282C,
        0xFF575A5C,
        0xFF191A1C,
        0xFFD1D3D9,
        0xFF73767C,
        0xFF4C4F56,
        0xFFF57E84,
        0xFF6DB083,
        0xFFFFFFFF,
        0xFF71A1FE,
        0xFF40434A,
        0xFF3871E1,
        0xFF3871E1,
        0xFF26282C,
        0xFF26282C,
        0xFF33353B,
        0xFF2A4371,
        0xFF33353B,
        0xFF26282C,
        0xFF33353B,
        0xFF9FA2A8,
        0x21FFFFFF,
        0x10FFFFFF,
        0x17FFFFFF,
        0xFF233558,
        0xFF2E4D89,
        0x29FFFFFF,
      )
    val light =
      listOf(
        0xFFE9EAEE,
        0xFF7A7B80,
        0xFFFFFFFF,
        0xFF000000,
        0xFF73767C,
        0xFF9FA2A8,
        0xFFC54E58,
        0xFF338555,
        0xFFFFFFFF,
        0xFF2F5EB9,
        0xFFD1D3D9,
        0xFF3871E1,
        0xFF3871E1,
        0xFFE9EAEE,
        0xFFFFFFFF,
        0xFFD1D3D9,
        0xFFD0DFFE,
        0xFFE9EAEE,
        0xFFE9EAEE,
        0xFFFFFFFF,
        0xFF73767C,
        0x20000000,
        0x08000000,
        0x12000000,
        0xFFE3EBFE,
        0xFFA7C5FF,
        0x20000000,
      )
    assertEquals(dark.map(::Color), colors())
    LafManager.setCurrentLookAndFeel(LafManager.defaultLightTheme)
    assertEquals(light.map(::Color), colors())
  }

  /**
   * GroupHeaderSeparator's keys through the theme's "*" map: separatorColor is popup-border in dark
   * (#33353B) but dialog-border in light (#E9EAEE); separatorForeground is text-secondary.
   */
  @Test
  fun groupHeaderKeysResolve() {
    val lines =
      listOf(LafManager.defaultDarkTheme to 0xFF33353B, LafManager.defaultLightTheme to 0xFFE9EAEE)
    for ((theme, line) in lines) {
      LafManager.setCurrentLookAndFeel(theme)
      assertEquals(Color(line), Islands.color("Popup.separatorColor"))
      assertEquals(Islands.textSecondary, Islands.color("Popup.separatorForeground"))
    }
  }

  /** IslandsTabPainter.getColors in Islands Dark: fill and border of a header tab per state. */
  @Test
  fun headerTabColorsFollowIslandsTabPainter() {
    fun colors(selected: Boolean, active: Boolean, hovered: Boolean) =
      Islands.headerTabColors(selected, active, hovered).let { (fill, border) ->
        listOf(fill, border)
      }
    // EditorTabs.underlinedTabBackground / underlinedBorderColor
    assertEquals(listOf(Color(0xFF233558), Color(0xFF2E4D89)), colors(true, true, false))
    // EditorTabs.hoverBackground / inactiveUnderlinedTabBorderColor
    assertEquals(listOf(Color(0x17FFFFFF), Color(0xFF40434A)), colors(true, false, true))
    // EditorTabs.inactiveUnderlinedTabBackground / inactiveUnderlinedTabBorderColor
    assertEquals(listOf(Color(0xFF26282C), Color(0xFF40434A)), colors(true, false, false))
    // EditorTabs.hoverBackground; Islands has no hoverBorderColor
    assertEquals(listOf(Color(0x17FFFFFF), Color.Transparent), colors(false, true, true))
    // Islands has no regularBackground or regularBorderColor
    assertEquals(listOf(Color.Transparent, Color.Transparent), colors(false, true, false))
  }
}
