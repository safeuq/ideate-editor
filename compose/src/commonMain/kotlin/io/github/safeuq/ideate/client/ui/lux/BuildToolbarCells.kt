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
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import kotlin.math.roundToInt

/**
 * One toolbar button of the Build panel's backend-drawn strips, in the panel's own dp: the square
 * named [label] at ([x], [y]) that is [BUILD_CELL_SIZE] wide and tall. [maskX] is a column of the
 * strip that holds the line the backend's tree draws over the console strip, not the button: it is
 * drawn as a copy of the column before it.
 */
internal data class BuildCell(val label: String, val x: Int, val y: Int, val maskX: Int? = null) {
  /** The panel point under [offsetX], [offsetY] dp inside this cell; where a click goes. */
  fun panelPoint(offsetX: Int, offsetY: Int) = IntOffset(x + offsetX, y + offsetY)

  /**
   * Where the panel's top left must be on the screen so that this cell, drawn at ([screenX],
   * [screenY]), is where the backend thinks it is: its menu and tooltip then open under it.
   */
  fun panelOrigin(screenX: Int, screenY: Int) = IntOffset(screenX - x, screenY - y)
}

/**
 * The buttons in the 65 dp panel, measured on the Mac backend with the new UI dark theme (spec
 * 2026-11-23, "Cell geometry"): every button is a 22 dp square, one every 26 dp from 4 dp under the
 * panel's top. The left strip's are 5 dp from the left (Rerun, Stop, Pin, Filter); the console
 * strip's are 38 dp from the left (Soft-Wrap, Scroll to the End, Clear All), and the tree's border
 * at x = 41 runs over them top to bottom. ponytail: fixed numbers from one backend and theme; read
 * the buttons' places from the backend, or find them in the bitmap, if a theme or scale moves them.
 */
internal val BUILD_TOOLBAR_CELLS =
  listOf("Rerun", "Stop", "Pin", "Filter").mapIndexed { index, label ->
    BuildCell(label, 5, 4 + 26 * index)
  }
internal val CONSOLE_TOOLBAR_CELLS =
  listOf("Soft-Wrap", "Scroll to the End", "Clear All").mapIndexed { index, label ->
    BuildCell(label, 46, 4 + 26 * index)
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
private fun BuildCellView(
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
    Modifier.size(BUILD_CELL_SIZE.dp)
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
    val size = IntSize(pixels(BUILD_CELL_SIZE), pixels(BUILD_CELL_SIZE))
    drawImage(
      frame.bitmap,
      source,
      size,
      dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
    )
    cell.maskX?.let { column ->
      val width = pixels(column + 1) - pixels(column)
      drawImage(
        frame.bitmap,
        IntOffset(pixels(column) - width, pixels(cell.y)),
        IntSize(width, size.height),
        dstOffset = IntOffset(pixels(column - cell.x), 0),
        dstSize = IntSize(width, this.size.height.roundToInt()),
      )
    }
  }
}
