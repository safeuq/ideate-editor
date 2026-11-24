package io.github.safeuq.ideate.client.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UiDensityTest {
  /** The user's IntelliJIdea2026.3/options/ui.lnf.xml on 2026-11-17. */
  private val compactSettings =
    """
    <application>
      <component name="UISettings">
        <option name="SHOW_PREVIEW_IN_SEARCH_EVERYWHERE" value="true" />
        <option name="UI_DENSITY" value="COMPACT" />
      </component>
    </application>
    """
      .trimIndent()

  @Test
  fun readsCompact() {
    assertEquals(UiDensity.COMPACT, UiDensity.fromIntelliJSettings(compactSettings))
  }

  @Test
  fun missingOptionIsIntelliJsDefault() {
    val settings = compactSettings.replace("""<option name="UI_DENSITY" value="COMPACT" />""", "")
    assertEquals(UiDensity.NORMAL, UiDensity.fromIntelliJSettings(settings))
    assertEquals(
      UiDensity.NORMAL,
      UiDensity.fromIntelliJSettings("""<option name="UI_DENSITY" value="DEFAULT" />"""),
    )
  }

  @Test
  fun unknownValueFails() {
    assertFailsWith<IllegalStateException> {
      UiDensity.fromIntelliJSettings("""<option name="UI_DENSITY" value="SPACIOUS" />""")
    }
  }

  @Test
  fun sizesFollowTheDensity() {
    val before = Islands.density
    try {
      Islands.density = UiDensity.NORMAL
      assertEquals(listOf(40f, 30f, 20f, 41f, 24f, 6f), sizes())
      Islands.density = UiDensity.COMPACT
      assertEquals(listOf(32f, 26f, 16f, 31f, 22f, 4f), sizes())
    } finally {
      Islands.density = before
    }
  }

  private fun sizes() =
    with(Islands) {
      listOf(toolbarHeight, toolbarButton, stripeIcon, headerHeight, treeRowHeight, islandGap).map {
        it.value
      }
    }
}
