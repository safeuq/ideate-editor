package intellijandroid.client.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.theme.IslandsDark

/**
 * The resize pointers a mouse shows over a split's border, as IntelliJ's `Splitter` sets
 * `W_RESIZE_CURSOR` (panes side by side) and `N_RESIZE_CURSOR` (stacked). Common Compose has no
 * resize pointer, so each platform provides them: AWT cursors on the desktop, `PointerIcon` double
 * arrows on Android.
 */
data class ResizePointers(val sideBySide: PointerIcon, val stacked: PointerIcon)

val LocalResizePointers = staticCompositionLocalOf {
  ResizePointers(PointerIcon.Default, PointerIcon.Default)
}

/**
 * Two panes with a border between them that can be dragged, as IntelliJ's `Splitter`: [first] gets
 * [proportion] of the room (0..1, room = the size less the island gap), [second] the rest.
 * [stacked] puts [first] above [second]; otherwise side by side. A drag reports the new proportion
 * through [onProportion].
 * - The border takes presses over the island gap and [SplitLook.grabMargin] on each side of it; a
 *   mouse sees the resize pointer there.
 * - A finger pressing the border shows a pill on it and widens the gap to [SplitLook.touchGap]
 *   around it; a mouse's drag does neither.
 * - Each pane keeps at least [SplitLook.leastPane]; a split too small for that doesn't move.
 */
@Composable
fun SplitPane(
  stacked: Boolean,
  proportion: Float,
  onProportion: (Float) -> Unit,
  modifier: Modifier = Modifier,
  first: @Composable () -> Unit,
  second: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier) {
    val gap = IslandsDark.islandGap
    val room = (if (stacked) maxHeight else maxWidth) - gap
    val firstSize = room * shownProportion(proportion, room.value, SplitLook.leastPane.value)
    // While a finger drags, the gap widens to SplitLook.touchGap so the pill has room around it;
    // each pane gives half, so the border's centre (and the proportion) stays where it was.
    var touchDrag by remember { mutableStateOf(false) }
    val extra by
      animateDpAsState(
        if (touchDrag) (SplitLook.touchGap - gap).coerceAtLeast(0.dp) else 0.dp,
        label = "split gap",
      )
    if (stacked) {
      Column {
        Box(Modifier.fillMaxWidth().height(firstSize - extra / 2)) { first() }
        Spacer(Modifier.height(gap + extra))
        Box(Modifier.fillMaxWidth().weight(1f)) { second() }
      }
    } else {
      Row {
        Box(Modifier.fillMaxHeight().width(firstSize - extra / 2)) { first() }
        Spacer(Modifier.width(gap + extra))
        Box(Modifier.fillMaxHeight().weight(1f)) { second() }
      }
    }
    Border(
      stacked,
      firstSize,
      room,
      onProportion,
      pill = touchDrag,
      onTouchDrag = { touchDrag = it },
    )
  }
}

/** Sizes of a split's border; the colours follow the theme. */
object SplitLook {
  /** Each pane's least size. */
  val leastPane = 60.dp

  /**
   * How far past the island gap the border takes presses, on each side: 4 dp, so 12 dp in all in
   * compact. IntelliJ's mouse zone is 6 px around a 1 px divider (`ide.splitter.mouseZone`); a
   * finger needs more.
   */
  val grabMargin = 4.dp

  /** The pill a finger's drag shows: Android's split-screen handle, across the border. */
  val pillLength = 32.dp
  val pillThickness = 4.dp

  /** The gap while a finger drags: the pill with 4 dp on each side. A mouse leaves it as it is. */
  val touchGap = 12.dp
  val pill = Color(0xFF9DA0A8) // Slider.buttonColor in Islands Dark
}

/**
 * [proportion] as the split shows it: IntelliJ's `ToolWindowPane.normalizeWeight` for values out of
 * range (0 or less → 0.33, 1 or more → 0.67), then kept so both panes get [least] of [room] (when
 * the room allows). Sizes in any one unit, e.g. dp.
 */
internal fun shownProportion(proportion: Float, room: Float, least: Float): Float {
  val normal =
    when {
      proportion <= 0f -> DEFAULT_WEIGHT
      proportion >= 1f -> 1f - DEFAULT_WEIGHT
      else -> proportion
    }
  if (room < least * 2) return normal
  return normal.coerceIn(least / room, 1f - least / room)
}

/**
 * The proportion after dragging the border by [delta] from where the first pane was [startFirst]
 * long, as `Splitter.DividerImpl` sets it from the pointer, clamped by [shownProportion].
 */
internal fun draggedProportion(startFirst: Float, delta: Float, room: Float, least: Float): Float =
  shownProportion(((startFirst + delta) / room).coerceIn(0.001f, 0.999f), room, least)

private const val DEFAULT_WEIGHT = 0.33f // WindowInfoImpl.DEFAULT_WEIGHT

/**
 * The draggable border over the gap after [firstSize] (the first pane's size with the plain gap).
 * [pill] shows while a finger drags; [onTouchDrag] reports when one starts and ends.
 */
@Composable
private fun Border(
  stacked: Boolean,
  firstSize: Dp,
  room: Dp,
  onProportion: (Float) -> Unit,
  pill: Boolean,
  onTouchDrag: (Boolean) -> Unit,
) {
  val density = LocalDensity.current
  val pointers = LocalResizePointers.current
  val margin = SplitLook.grabMargin
  val thickness = IslandsDark.islandGap + margin * 2
  val currentFirst by rememberUpdatedState(firstSize)
  val currentRoom by rememberUpdatedState(room)
  val report by rememberUpdatedState(onProportion)
  val touchDrag by rememberUpdatedState(onTouchDrag)
  // The border moves with the drag, so pointer positions (relative to it) are added to where it
  // was laid out when the event came: positions along the split.
  var placedAt by remember { mutableFloatStateOf(0f) }
  Box(
    (if (stacked) Modifier.offset(y = firstSize - margin).fillMaxWidth().height(thickness)
      else Modifier.offset(x = firstSize - margin).fillMaxHeight().width(thickness))
      .onPlaced { coordinates ->
        val position = coordinates.positionInParent()
        placedAt = if (stacked) position.y else position.x
      }
      .pointerHoverIcon(if (stacked) pointers.stacked else pointers.sideBySide)
      .pointerInput(stacked) {
        awaitEachGesture {
          fun along(change: PointerInputChange) =
            placedAt + if (stacked) change.position.y else change.position.x

          val down = awaitFirstDown()
          val start = with(density) { currentFirst.toPx() }
          val roomPx = with(density) { currentRoom.toPx() }
          val least = with(density) { SplitLook.leastPane.toPx() }
          val downAt = along(down)
          // A finger sees the pill (and the wider gap) as soon as it presses the border; it goes
          // again if the finger lifts without dragging or another gesture (the pager) takes over.
          val touch = down.type == PointerType.Touch
          if (touch) touchDrag(true)
          val slopped = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
          if (slopped == null) {
            if (touch) touchDrag(false)
            return@awaitEachGesture
          }
          report(draggedProportion(start, along(slopped) - downAt, roomPx, least))
          drag(slopped.id) { change ->
            change.consume()
            report(draggedProportion(start, along(change) - downAt, roomPx, least))
          }
          if (touch) touchDrag(false)
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    if (pill) {
      Box(
        Modifier.size(
            if (stacked) SplitLook.pillLength else SplitLook.pillThickness,
            if (stacked) SplitLook.pillThickness else SplitLook.pillLength,
          )
          .background(SplitLook.pill, RoundedCornerShape(SplitLook.pillThickness / 2)),
      )
    }
  }
}
