package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.jetbrains.JBR
import io.github.safeuq.ideate.client.ui.TitleBarArea
import io.github.safeuq.ideate.client.ui.theme.Islands
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * IntelliJ's merged title bar on macOS (`MacToolbarFrameHeader`): JetBrains Runtime's
 * `CustomTitleBar` makes the window's title bar transparent and as tall as the main toolbar, and
 * puts the traffic lights inside the toolbar row. JBR drags the window from that row and zooms it
 * on a double click; [TitleBarArea.control] marks the toolbar's buttons, where a press must reach
 * the app instead (`forceHitTest(true)`, as Jewel's `WindowMouseEventEffect` does).
 *
 * Null off macOS, or on a Java runtime without JBR's window decorations: the window keeps its
 * native title bar.
 */
@Composable
fun rememberMacTitleBar(window: ComposeWindow): TitleBarArea? {
  if (!System.getProperty("os.name").startsWith("Mac")) return null
  if (!JBR.isWindowDecorationsSupported()) return null
  val decorations = JBR.getWindowDecorations()
  val titleBar = remember(window) { decorations.createCustomTitleBar() }
  val controls = remember(window) { ControlAreas() }
  val height = Islands.toolbarHeight
  // Set once per window and height, as MacToolbarFrameHeader does, never again on entering full
  // screen: JBR's updateCustomTitleBar then calls setWindowControlsHidden:NO, which hides its
  // full-screen buttons (MacFullScreenControlsManager) and brings back macOS's own, shown only on
  // hover, without minimize, and off the toolbar's centre line.
  remember(titleBar, height) {
    titleBar.height = height.value
    decorations.setCustomTitleBar(window, titleBar)
  }
  DisposableEffect(window, titleBar) {
    val listener =
      object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = hitTest(event)

        override fun mouseReleased(event: MouseEvent) = hitTest(event)

        override fun mouseDragged(event: MouseEvent) = hitTest(event)

        override fun mouseMoved(event: MouseEvent) = hitTest(event)

        // Window points to the layout's px, which positionInWindow gives.
        private fun hitTest(event: MouseEvent) {
          val transform = window.graphicsConfiguration?.defaultTransform
          val point =
            Offset(
              (event.x * (transform?.scaleX ?: 1.0)).toFloat(),
              (event.y * (transform?.scaleY ?: 1.0)).toFloat(),
            )
          titleBar.forceHitTest(controls.areas.values.any { it.contains(point) })
        }
      }
    window.addMouseListener(listener)
    window.addMouseMotionListener(listener)
    onDispose {
      window.removeMouseListener(listener)
      window.removeMouseMotionListener(listener)
    }
  }
  // The room for the traffic lights, in a window and in full screen alike: IntelliJ's fixed gap
  // (MacToolbarFrameHeader.updateBorders) rather than JBR's leftInset, which is 0 in full screen.
  return remember(controls) {
    TitleBarArea(
      start = GAP_FOR_BUTTONS.dp,
      end = titleBar.rightInset.dp,
      height = 0.dp,
      control = Modifier.then(ControlAreaElement(controls)),
    )
  }
}

/**
 * IntelliJ's room for the traffic lights, in points: 26 + 52
 * (`MacToolbarFrameHeaderKt.GAP_FOR_BUTTONS`, used by `MacToolbarFrameHeader.updateBorders`).
 */
private const val GAP_FOR_BUTTONS = 26f + 52f

/** Where the title bar's controls are, in window px; one entry per [ControlAreaNode]. */
private class ControlAreas {
  val areas = mutableMapOf<ControlAreaNode, Rect>()
}

private data class ControlAreaElement(val controls: ControlAreas) :
  ModifierNodeElement<ControlAreaNode>() {
  override fun create() = ControlAreaNode(controls)

  override fun update(node: ControlAreaNode) {
    node.controls.areas.remove(node)
    node.controls = controls
  }
}

private class ControlAreaNode(var controls: ControlAreas) :
  Modifier.Node(), GlobalPositionAwareModifierNode {
  override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
    controls.areas[this] = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
  }

  override fun onDetach() {
    controls.areas.remove(this)
  }
}
