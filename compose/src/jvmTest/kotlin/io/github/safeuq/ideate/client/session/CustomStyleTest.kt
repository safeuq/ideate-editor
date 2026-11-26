package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ANSI console colours: `FrontendCustomStyleHighlighterModelHandler` and `ColorUtil.isDark`. */
class CustomStyleTest {
  private fun scheme(background: Long) =
    EditorScheme(
      "Test",
      listOf(
        mapOf(
          "TEXT" to TextStyle(foreground = 0xFF000000, background = background),
          "CONSOLE_NORMAL_OUTPUT" to TextStyle(foreground = 0xFF111111),
        ),
      ),
      listOf(emptyMap()),
      emptyMap(),
    )

  @Test
  fun isDarkFollowsColorUtil() {
    assertTrue(scheme(0xFF1E1F22).isDark)
    assertFalse(scheme(0xFFFFFFFF).isDark)
  }

  @Test
  fun customStyleReplacesTheKeyForTheSchemeBrightness() {
    val red = TextStyle(foreground = 0xFFF0524F)
    val darkRed = TextStyle(foreground = 0xFFC7222D)
    val raw =
      ClientSession.RawHighlight(
        1,
        0,
        3,
        0,
        listOf(listOf("CONSOLE_NORMAL_OUTPUT")),
        emptyList(),
        null,
        false,
        "CustomStyleHighlighterModel",
        lightStyle = darkRed,
        darkStyle = red,
      )
    assertEquals(red, raw.toHighlight(scheme(0xFF1E1F22)).style)
    assertEquals(darkRed, raw.toHighlight(scheme(0xFFFFFFFF)).style)
    assertEquals(
      TextStyle(foreground = 0xFF111111),
      raw.copy(lightStyle = null, darkStyle = null).toHighlight(scheme(0xFF1E1F22)).style,
    )
  }
}
