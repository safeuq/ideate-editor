package intellijandroid.client.ui

import intellijandroid.client.session.TextEffect
import kotlin.test.Test
import kotlin.test.assertEquals

/** IntelliJ's effect geometry for JetBrains Mono 12 with 5 units under the baseline. */
class EditorEffectsTest {
  @Test
  fun onAHiDpiScreen() {
    assertEquals(EffectLine(1.5f, 0.5f), underlineOf(TextEffect.LINE_UNDERSCORE, 12f, 5f, 2f))
    assertEquals(EffectLine(1.5f, 1.5f), underlineOf(TextEffect.BOLD_LINE_UNDERSCORE, 12f, 5f, 2f))
    assertEquals(EffectLine(1.5f, 1.5f), underlineOf(TextEffect.BOLD_DOTTED_LINE, 12f, 5f, 2f))
    // The wave rounds up: 3 × 0.923 = 2.77 → 3; offset 1.86 → 2.
    assertEquals(EffectLine(2f, 3f), underlineOf(TextEffect.WAVE_UNDERSCORE, 12f, 5f, 2f))
    assertEquals(EffectLine(-3.5f, 0.5f), strikeoutOf(12f, 2f))
  }

  @Test
  fun onAPlainScreen() {
    assertEquals(EffectLine(1f, 1f), underlineOf(TextEffect.LINE_UNDERSCORE, 12f, 5f, 1f))
    assertEquals(EffectLine(1f, 3f), underlineOf(TextEffect.WAVE_UNDERSCORE, 12f, 5f, 1f))
    assertEquals(EffectLine(-3f, 1f), strikeoutOf(12f, 1f))
  }

  @Test
  fun staysInsideTheLineWhenThereIsLittleRoom() {
    // 2 units under the baseline: the wave moves up to fit.
    assertEquals(EffectLine(0.5f, 1.5f), underlineOf(TextEffect.WAVE_UNDERSCORE, 12f, 2f, 2f))
  }
}
