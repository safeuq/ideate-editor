package io.github.safeuq.ideate.client.ui

import io.github.safeuq.ideate.client.ui.theme.ISLANDS_DARK
import io.github.safeuq.ideate.client.ui.theme.ISLANDS_LIGHT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WelcomeFeatureGridTest {
  @Test
  fun `the three buttons carry JetBrains Client's feature keys`() {
    assertEquals(
      listOf("New.Markdown.File", "Open.File", "terminal.toolwindow"),
      IDEA_FEATURE_BUTTONS.map { it.featureKey },
    )
  }

  @Test
  fun `every button icon is in the client's icons jar for both themes`() {
    val clientLib =
      checkNotNull(System.getProperty("jetbrainsClientLib")) { "No jetbrainsClientLib property" }
    val jar = IconJar(File(clientLib, "intellij.platform.icons.jar"))
    for (model in IDEA_FEATURE_BUTTONS) {
      assertNotNull(jar.svg(model.iconPath, ISLANDS_LIGHT), "${model.iconPath} (light)")
      assertNotNull(jar.svg(model.iconPath, ISLANDS_DARK), "${model.iconPath} (dark)")
    }
  }
}
