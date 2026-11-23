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
  fun onlyBuildWindowWithATreeOnAPhoneUpright() {
    // (portrait, window id, locals) to whether Ideate stacks them
    val expected =
      mapOf(
        Triple(true, "Build", listOf(tree, console)) to true, // phone upright
        Triple(false, "Build", listOf(tree, console)) to
          false, // desktop, tablet sideways, phone sideways
        Triple(true, "Run", listOf(tree, console)) to
          false, // the Run window keeps the backend's layout
        Triple(true, "Build", listOf(console)) to false, // no tree yet (a Sync tab)
        Triple(true, "Build", emptyList<PanelConsole>()) to false, // nothing drawn yet
      )
    assertEquals(
      expected,
      expected.mapValues { (input, _) -> stackedBuild(input.first, input.second, input.third) },
    )
  }

  @Test
  fun stripsAreBothToolbarColumns() {
    // Left strip 33 (32 and its divider) plus the console's 32, as probed on the Mac backend.
    assertEquals(33 + 32, BUILD_STRIPS_WIDTH)
  }
}
