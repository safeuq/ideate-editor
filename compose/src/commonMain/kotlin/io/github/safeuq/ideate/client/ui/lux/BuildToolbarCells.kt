package io.github.safeuq.ideate.client.ui.lux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jetbrains.rd.ide.model.LxEvent
import io.github.safeuq.ideate.client.session.LuxDialog
import io.github.safeuq.ideate.client.session.PanelBuildTree
import io.github.safeuq.ideate.client.session.PanelConsole
import io.github.safeuq.ideate.client.session.PanelLocal
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import kotlin.math.roundToInt

/**
 * One piece of the BuildView panel's backend-drawn toolbars, in the panel's own dp: the rectangle
 * named [label] at ([x], [y]), [width] by [height]; a toolbar button is [BUILD_CELL_SIZE] square.
 */
internal data class BuildCell(
  val label: String,
  val x: Int,
  val y: Int,
  val width: Int = BUILD_CELL_SIZE,
  val height: Int = BUILD_CELL_SIZE,
) {
  /** The panel point under [offsetX], [offsetY] dp inside this cell; where a click goes. */
  fun panelPoint(offsetX: Int, offsetY: Int) = IntOffset(x + offsetX, y + offsetY)

  /**
   * Where the panel's top left must be on the screen so that this cell, drawn at ([screenX],
   * [screenY]), is where the backend thinks it is: its menu and tooltip then open under it.
   */
  fun panelOrigin(screenX: Int, screenY: Int) = IntOffset(screenX - x, screenY - y)
}

/**
 * Where the backend put the BuildView's toolbars, read from its tree's and console's bounds in the
 * panel (dp). [top] is the height of a toolbar row above both (the Run window's: tree `y=32`,
 * console `y=32` gives 32; 0 in the Build window), [left] the width of a strip left of the tree
 * (the Build window's `BuildView` toolbar: tree `x=33` gives 33; 0 in the Run window), and
 * [consoleLeft] where the strip right of the console starts (its right edge: Run at 411 dp wide:
 * console `x=136, width=243` gives 379; Build at 73 dp: `x=44, width=-3` gives 41).
 */
internal data class BuildStrips(val top: Int, val left: Int, val consoleLeft: Int)

/** The toolbars' places for [locals] that hold a [PanelBuildTree] and a [PanelConsole]. */
internal fun buildStrips(locals: List<PanelLocal>): BuildStrips {
  val tree = locals.filterIsInstance<PanelBuildTree>().first().bounds
  val console = locals.filterIsInstance<PanelConsole>().first().bounds
  return BuildStrips(
    top = minOf(tree.y, console.y).coerceAtLeast(0),
    left = tree.x.coerceAtLeast(0),
    consoleLeft = console.x + console.width,
  )
}

/** The labels of the "BuildView" toolbar's buttons, as IntelliJ names the place. */
internal val BUILD_VIEW_LABELS = listOf("Rerun", "Stop", "Pin", "Filter")

/** The labels of the "BuildConsole" toolbar's buttons. */
internal val BUILD_CONSOLE_LABELS = listOf("Soft-Wrap", "Scroll to the End", "Clear All")

/**
 * The buttons of a vertical strip whose top left is ([stripLeft], [stripTop]), measured on the Mac
 * backend with the new UI dark theme (spec 2026-11-23, "Cell geometry"): every button is a 22 dp
 * square, 5 dp from the strip's left, the first 4 dp under its top, one every 26 dp. Build: the
 * left strip at (0, 0) gives x 5; the console's at (41, 0) gives x 46. Run: the console's at (379,
 * 32) gives (384, 36 + 26 * i). ponytail: fixed numbers from one backend and theme; read the
 *     buttons' places from the backend, or find them in the bitmap, if a theme or scale moves them.
 */
internal fun stripCells(labels: List<String>, stripLeft: Int, stripTop: Int) =
  labels.mapIndexed { index, label ->
    BuildCell(label, stripLeft + 5, stripTop + 4 + 26 * index)
  }

internal const val BUILD_CELL_SIZE = 22

/**
 * A toolbar row: IntelliJ's horizontal toolbar insets as `ToolWindowToolbar` (Gradle) has them, 7
 * dp at the sides and 5 above and below a 22 dp button. A 16 dp icon sits 3 dp inside its button,
 * so a side of 7 and a gap of 4 leave the same 10 dp from the row's edge to the first icon as
 * between two icons.
 */
private val ROW_SIDE = 7.dp
private val ROW_GAP = 4.dp // the backend's own spacing: a button every 26 dp
private val ROW_VERTICAL = 5.dp

/**
 * A horizontal toolbar of [cells] cut out of [frame], with the 1 px line in the tool window's
 * border colour under it. Each cell sends its pointer events to [dialog] at the cell's own place in
 * the panel, so the backend hovers and presses the button where it drew it.
 */
@Composable
internal fun BuildToolbarRow(
  dialog: LuxDialog,
  frame: LuxFrame,
  cells: List<BuildCell>,
  send: (LxEvent) -> Unit,
  onAnchor: (panelX: Int, panelY: Int) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = ROW_SIDE, vertical = ROW_VERTICAL),
      horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (cell in cells) BuildCellView(dialog, frame, cell, send, onAnchor)
    }
    PixelLine()
  }
}

/** 1 px in the tool window's border colour, as IntelliJ's one pixel splitter draws it. */
@Composable
internal fun PixelLine() {
  Box(
    Modifier.fillMaxWidth()
      .height(with(LocalDensity.current) { 1.toDp() })
      .background(IslandsDark.toolWindowBorder),
  )
}

@Composable
internal fun BuildCellView(
  dialog: LuxDialog,
  frame: LuxFrame,
  cell: BuildCell,
  send: (LxEvent) -> Unit,
  onAnchor: (panelX: Int, panelY: Int) -> Unit,
) {
  val density = LocalDensity.current.density
  var windowPosition by remember { mutableStateOf(Offset.Zero) }
  fun pixels(dp: Int) = (dp * density).roundToInt()
  Canvas(
    Modifier.size(cell.width.dp, cell.height.dp)
      .semantics { contentDescription = cell.label }
      .onGloballyPositioned { windowPosition = it.positionInWindow() }
      // Before the pointer's events go to the backend (the Initial pass comes first): the panel is
      // moved so that this cell's place in it is where the cell is on the screen, and the
      // backend's tooltip and menu open under the button as drawn here, not under the strip.
      .pointerInput(cell, density) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Press)
              cell
                .panelOrigin(
                  (windowPosition.x / density).roundToInt(),
                  (windowPosition.y / density).roundToInt(),
                )
                .let { onAnchor(it.x, it.y) }
          }
        }
      }
      .luxPointer(
        dialog,
        1f,
        send,
        focus = null,
        dragScrolls = false,
        origin = IntOffset(cell.x, cell.y),
      ),
  ) {
    frame.painted // read, so a painted batch redraws
    val source = IntOffset(pixels(cell.x), pixels(cell.y))
    val size = IntSize(pixels(cell.width), pixels(cell.height))
    drawImage(
      frame.bitmap,
      source,
      size,
      dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
    )
  }
}
