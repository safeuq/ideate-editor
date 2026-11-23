package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.uiautomation.BeLabel
import com.jetbrains.ide.model.uiautomation.BeTab
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Run window's tabs: a `BeTab` per run, named after its configuration. */
class BackendTabsTest {
  private fun tab(name: String, closeable: Boolean) =
    BeTab(BeLabel(false).also { it.text.value = name }, BeLabel(false), closeable)

  @Test
  fun tabNamesFollowTheBackendTabs() {
    assertEquals(listOf("Main", "Slow"), tabNames(listOf(tab("Main", true), tab("Slow", false))))
    assertEquals(emptyList(), tabNames(emptyList()))
  }
}
