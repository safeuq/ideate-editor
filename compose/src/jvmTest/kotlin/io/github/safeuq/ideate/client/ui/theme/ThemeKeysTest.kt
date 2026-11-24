package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color
import com.jetbrains.rd.ide.model.NamedColor
import com.jetbrains.rd.ide.model.RgbaColor
import io.github.safeuq.ideate.client.ui.lux.resolveLuxColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ThemeKeysTest {
  private val grayXcf = RgbaColor(207, 207, 207, 255) // JBUI's fallback for pressedBackground

  @Test
  fun resolvesKeysFromTheClientsUiManager() {
    assertEquals(Color(0x29FFFFFF), ThemeKeys.color("ActionButton.pressedBackground"))
    assertEquals(Color(0xFF191A1C), ThemeKeys.color("Panel.background"))
  }

  @Test
  fun takesTheFirstWildcardSuffixForAKeyTheUiManagerLacks() {
    assertEquals(Color(0x17FFFFFF), ThemeKeys.color("Made.Up.hoverBackground"))
    assertNull(ThemeKeys.color("Made.Up.nothingLikeThis"))
  }

  @Test
  fun aNamedColorUsesTheFallbackOnlyWhenTheKeyIsUnknownAndIsTransparentWithoutOne() {
    assertEquals(
      Color(0x29FFFFFF),
      resolveLuxColor(NamedColor("ActionButton.pressedBackground", grayXcf)),
    )
    assertEquals(Color(207, 207, 207), resolveLuxColor(NamedColor("Made.Up.nothing", grayXcf)))
    assertEquals(Color.Transparent, resolveLuxColor(NamedColor("Made.Up.nothing", null)))
  }

  @Test
  fun refusesALineThatIsNotAColor() {
    assertFailsWith<IllegalArgumentException> { UiColorTable.parse(listOf("Panel.background\n")) }
  }
}
