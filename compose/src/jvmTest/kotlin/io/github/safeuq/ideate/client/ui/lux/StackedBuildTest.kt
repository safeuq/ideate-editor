package io.github.safeuq.ideate.client.ui.lux

import com.jetbrains.rd.ide.model.LxRect
import io.github.safeuq.ideate.client.session.PanelBuildTree
import io.github.safeuq.ideate.client.session.PanelConsole
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the Build window is laid out by Ideate (console on top, tree below). */
class StackedBuildTest {
  private val bounds = LxRect(33, 0, 238, 211)
  private val tree = PanelBuildTree(modelUid = 6, bounds = bounds)
  private val console = PanelConsole(kernelEditorId = "pecp68j6afdfimptnf60", bounds = bounds)

  @Test
  fun anyPanelWithATreeAndAConsoleOnAPhoneUpright() {
    // (portrait, locals) to whether Ideate stacks them
    val expected =
      mapOf(
        (true to listOf(tree, console)) to true, // phone upright, Build or Run
        (false to listOf(tree, console)) to false, // desktop, tablet sideways, phone sideways
        (true to listOf(console)) to false, // no tree yet (a Sync tab, a plain Run console)
        (true to emptyList<PanelConsole>()) to false, // nothing drawn yet
      )
    assertEquals(
      expected,
      expected.mapValues { (input, _) -> stackedBuildView(input.first, input.second) },
    )
  }

  @Test
  fun stripsComeFromTheBounds() {
    // Run at 411 dp: the toolbar row is 32 tall, no left strip, the console's strip starts at 379.
    val run =
      listOf(
        PanelBuildTree(6, LxRect(0, 32, 135, 681)),
        PanelConsole("a", LxRect(136, 32, 243, 681)),
      )
    // Build at 73 dp: left strip 33, no top row, the console's strip starts at 41.
    val build =
      listOf(PanelBuildTree(6, LxRect(33, 0, 10, 681)), PanelConsole("a", LxRect(44, 0, -3, 681)))
    assertEquals(BuildStrips(top = 32, left = 0, consoleLeft = 379), buildStrips(run))
    assertEquals(BuildStrips(top = 0, left = 33, consoleLeft = 41), buildStrips(build))
  }

  @Test
  fun stripsAreBothToolbarColumns() {
    // Left strip 33 (32 and its divider), the tree's 8 (its least width), the console's strip 32:
    // the
    // console's strip then sits clear of the tree's border (spec 2026-11-23, "Cell geometry").
    assertEquals(33 + 8 + 32, BUILD_STRIPS_WIDTH)
  }
}
