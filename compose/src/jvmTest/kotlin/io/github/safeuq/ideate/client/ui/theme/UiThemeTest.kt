package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiThemeTest {
  @AfterTest fun backToDark() = LafManager.setCurrentLookAndFeel(LafManager.defaultDarkTheme)

  @Test
  fun eachThemeNamesItsEditorScheme() {
    assertTrue(LafManager.defaultDarkTheme.isDark)
    assertEquals("Islands Dark", LafManager.defaultDarkTheme.editorSchemeId)
    // ManyIslandsLight.theme.json: "editorScheme": "Light", not "Islands Light".
    assertFalse(LafManager.defaultLightTheme.isDark)
    assertEquals("Light", LafManager.defaultLightTheme.editorSchemeId)
  }

  @Test
  fun themeKeysFollowTheCurrentTheme() {
    assertEquals(Color(0xFF191A1C), ThemeKeys.color("Panel.background"))
    LafManager.setCurrentLookAndFeel(LafManager.defaultLightTheme)
    assertEquals(Color(0xFFF7F8F9), ThemeKeys.color("Panel.background"))
    assertEquals(Color(0x20000000), ThemeKeys.color("ActionButton.pressedBackground"))
    assertEquals(Color(0x12000000), ThemeKeys.color("Made.Up.hoverBackground"))
  }

  @Test
  fun listenersHearEachChangeUntilRemoved() {
    val heard = mutableListOf<String>()
    val remove = LafManager.addListener { theme -> heard += theme.name }
    LafManager.setCurrentLookAndFeel(LafManager.defaultLightTheme)
    LafManager.setCurrentLookAndFeel(LafManager.defaultLightTheme) // no change, no call
    remove()
    LafManager.setCurrentLookAndFeel(LafManager.defaultDarkTheme)
    assertEquals(listOf("Islands Light"), heard)
  }
}
