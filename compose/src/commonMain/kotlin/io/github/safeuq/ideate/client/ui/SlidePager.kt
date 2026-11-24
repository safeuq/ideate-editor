package io.github.safeuq.ideate.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.Islands
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Where each page stands, in pages: the page in slot 3 is one screen to the right of the page in
 * slot 2. Only the pages on screen have a slot: the current one, and the one sliding in or out.
 */
internal class PagerSlots<K>(first: K) {
  var current = first
    private set

  private val slots = hashMapOf(first to 0)

  val keys: Set<K>
    get() = slots.keys

  fun slotOf(key: K): Int? = slots[key]

  val currentSlot: Int
    get() = slots.getValue(current)

  /**
   * [next] becomes the current page. A page already beside the current one (the finger brought it
   * in) keeps its slot; any other takes the slot on the side [toTheRight] says. The page left keeps
   * its slot, so it is still there to slide out.
   */
  fun show(next: K, toTheRight: Boolean) {
    if (next == current) return
    val slot = slots[next] ?: (currentSlot + if (toTheRight) 1 else -1)
    slots.keys.retainAll(setOf(current))
    slots[next] = slot
    current = next
  }

  /** [key] stands beside the current page: [step] -1 on its left, 1 on its right. */
  fun placeBeside(key: K, step: Int) {
    val slot = currentSlot + step
    slots.entries.removeAll { it.value == slot }
    slots[key] = slot
  }

  /** Only the current page is left on screen. */
  fun settle() {
    slots.keys.retainAll(setOf(current))
  }
}

/**
 * Pages side by side, one on screen at a time: the pages follow a sideways drag, with a gap and
 * rounded corners while they move (the island pages on a phone held upright, the projects on a
 * phone). Past a third of the width, or with a quick flick, the drag moves one page; otherwise the
 * page springs back. A change of [current] made elsewhere (a button, a popup) slides as well.
 */
@Composable
fun <K : Any> SlidePager(
  current: K,
  /** The page beside [current]: [step] -1 the one on its left, 1 the one on its right. */
  neighbour: (step: Int) -> K?,
  /**
   * Where a page stands in the row, lower is further left. Decides which side a page that became
   * [current] without a drag comes in from.
   */
  order: (K) -> Int,
  /** A drag ended on that page: the caller makes it [current]. */
  onShow: (K) -> Unit,
  /**
   * Whether a sideways drag of [dragX] px that went [down] at this point (in the window) may slide
   * the pages. E.g. false while the content under the finger can still scroll that way.
   */
  canSlide: (down: Offset, dragX: Float) -> Boolean,
  modifier: Modifier = Modifier,
  /** Pages that stay composed while off screen, so they keep what they remember. */
  keep: List<K> = emptyList(),
  /** Whether the pages keep the gap and the corners at rest too. */
  alwaysRounded: Boolean = false,
  /** One page; [radius] is its corner radius right now: 0 at rest, the islands' while sliding. */
  page: @Composable (key: K, radius: Dp) -> Unit,
) {
  val book = remember { PagerSlots(current) }
  // Before anything is placed: the slots are plain values the placement below reads.
  if (book.current != current) book.show(current, toTheRight = order(current) > order(book.current))
  // Where the screen is, in slots. Kept in pages, not px, so the gap growing or shrinking doesn't
  // move the pages.
  val position = remember { Animatable(book.currentSlot.toFloat()) }
  var dragging by remember { mutableStateOf(false) }
  // Counts changes of the slots made outside composition, so the pages are composed again.
  var slotChanges by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(current) {
    position.animateTo(book.currentSlot.toFloat())
    book.settle()
    slotChanges++
  }

  val rounded = dragging || position.isRunning || alwaysRounded
  val gap by animateDpAsState(if (rounded) Islands.islandGap else 0.dp)
  val radius by animateDpAsState(if (rounded) Islands.islandRadius else 0.dp)

  val pageBeside by rememberUpdatedState(neighbour)
  val maySlide by rememberUpdatedState(canSlide)
  val show by rememberUpdatedState(onShow)
  // The pager's top left in the window: [canSlide] takes window coordinates.
  var origin by remember { mutableStateOf(Offset.Zero) }
  BoxWithConstraints(modifier.clipToBounds()) {
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    // One page is a screen wide plus the gap, so neighbouring pages keep their gap while sliding;
    // the gap also stands between the pages and the screen's sides.
    val stride = constraints.maxWidth.toFloat() - gapPx
    val hidden = constraints.maxWidth * 2

    Box(
      Modifier.fillMaxSize()
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(current) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Asked for only when the drag goes that way: making a page can cost something.
            val left by lazy { pageBeside(-1) }
            val right by lazy { pageBeside(1) }
            val rest = book.currentSlot
            val velocity = VelocityTracker()
            var travelled = Offset.Zero
            var sliding = false
            try {
              while (true) {
                val change =
                  awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                    it.id == down.id
                  } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!sliding) {
                  travelled += delta
                  if (travelled.getDistance() < viewConfiguration.touchSlop) continue
                  // Mostly sideways: a slide. Anything else belongs to the content (scrolling,
                  // taps). Decided once, so one drag never turns from scrolling into sliding.
                  if (abs(travelled.x) < ONE_AXIS_RATIO * abs(travelled.y)) return@awaitEachGesture
                  if (!maySlide(origin + down.position, travelled.x)) return@awaitEachGesture
                  sliding = true
                  dragging = true
                }
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                // The finger going right brings in the page on the left.
                var target = position.value - delta.x / size.width
                if (target < rest && left == null || target > rest && right == null)
                  target = rest.toFloat()
                target = target.coerceIn(rest - 1f, rest + 1f)
                val beside = if (target < rest) left else if (target > rest) right else null
                if (beside != null && book.slotOf(beside) == null) {
                  book.placeBeside(beside, if (target < rest) -1 else 1)
                  slotChanges++
                }
                scope.launch { position.snapTo(target) }
              }
              if (!sliding) return@awaitEachGesture
              val flick = velocity.calculateVelocity().x
              val moved = position.value - rest
              val target =
                when {
                  moved < -1f / 3 || (flick > FLICK && moved < 0f) -> left
                  moved > 1f / 3 || (flick < -FLICK && moved > 0f) -> right
                  else -> null
                }
              if (target != null) show(target)
              else
                scope.launch {
                  position.animateTo(rest.toFloat())
                  book.settle()
                  slotChanges++
                }
            } finally {
              dragging = false
            }
          }
        },
    ) {
      // Read so that a change of the slots composes the pages again.
      slotChanges
      (keep + book.keys).distinct().forEach { pageKey ->
        key(pageKey) {
          Box(
            Modifier.fillMaxSize().padding(horizontal = gap).offset {
              val slot = book.slotOf(pageKey)
              IntOffset(
                if (slot == null) hidden else ((slot - position.value) * stride).roundToInt(),
                0,
              )
            },
          ) {
            page(pageKey, radius)
          }
        }
      }
    }
  }
}

/** A finger faster than this, in px per second, moves one page however short the drag. */
private const val FLICK = 1000f
