package intellijandroid.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import intellijandroid.client.session.ClientSession
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * How much room each tool window area gets, as IntelliJ's `WindowInfoImpl` keeps it: a side or the
 * bottom area takes a `weight` of the window (default 0.33, `DEFAULT_WEIGHT`), and a split side
 * gives its first window `sideWeight` of the side (default 0.5). Example: on a 1200 dp wide window
 * the left pane is 396 dp; split, Project gets the top half and Structure the bottom half.
 */
data class PaneWeights(
  /** Left pane's width, of the window's (desktop, tablet sideways). */
  val left: Float = DEFAULT_WEIGHT,
  /** Right pane's width, of the window's. */
  val right: Float = DEFAULT_WEIGHT,
  /** Bottom area's height under the editor, of the editor column's (desktop, tablet sideways). */
  val bottom: Float = DEFAULT_WEIGHT,
  /** Left top's share of the left side when it is split (left bottom gets the rest). */
  val leftSplit: Float = DEFAULT_SIDE_WEIGHT,
  /** Right top's share of the right side when it is split. */
  val rightSplit: Float = DEFAULT_SIDE_WEIGHT,
  /** Bottom left's share of the bottom area when both halves show. */
  val bottomSplit: Float = DEFAULT_SIDE_WEIGHT,
  /** Phone sideways: the one tool window's width, of the window's. */
  val compact: Float = DEFAULT_WEIGHT,
) {
  /** By name, as saved per project, e.g. `{"left": 0.28, "leftSplit": 0.5, …}`. */
  fun toMap(): Map<String, Float> =
    mapOf(
      "left" to left,
      "right" to right,
      "bottom" to bottom,
      "leftSplit" to leftSplit,
      "rightSplit" to rightSplit,
      "bottomSplit" to bottomSplit,
      "compact" to compact,
    )

  companion object {
    const val DEFAULT_WEIGHT = 0.33f
    const val DEFAULT_SIDE_WEIGHT = 0.5f

    /**
     * From [saved] (see [toMap]); a missing name keeps its default, and a value out of 0..1 is
     * replaced as IntelliJ's `ToolWindowPane.normalizeWeight` does (0 or less → 0.33, 1 or more →
     * 0.67).
     */
    fun from(saved: Map<String, Float>): PaneWeights {
      fun weight(name: String, default: Float) =
        saved[name]?.let { value ->
          when {
            value <= 0f -> DEFAULT_WEIGHT
            value >= 1f -> 1f - DEFAULT_WEIGHT
            else -> value
          }
        } ?: default
      return PaneWeights(
        left = weight("left", DEFAULT_WEIGHT),
        right = weight("right", DEFAULT_WEIGHT),
        bottom = weight("bottom", DEFAULT_WEIGHT),
        leftSplit = weight("leftSplit", DEFAULT_SIDE_WEIGHT),
        rightSplit = weight("rightSplit", DEFAULT_SIDE_WEIGHT),
        bottomSplit = weight("bottomSplit", DEFAULT_SIDE_WEIGHT),
        compact = weight("compact", DEFAULT_WEIGHT),
      )
    }

    /** After a drag stops, how long until the weights are saved: one write per drag. */
    const val SAVE_DELAY_MS = 500L
  }
}

/**
 * The pane weights for [session]'s project, saved per project next to its open editors
 * (`OpenEditors`, "panes") and read back on the next connection, as IntelliJ keeps each tool
 * window's weight in the project's layout. A change is saved [PaneWeights.SAVE_DELAY_MS] after the
 * last one, so a drag writes once.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPaneWeights(session: ClientSession): MutableState<PaneWeights> {
  val weights = remember(session) { mutableStateOf(PaneWeights.from(session.savedPanes())) }
  LaunchedEffect(session) {
    snapshotFlow { weights.value }
      .drop(1)
      .debounce(PaneWeights.SAVE_DELAY_MS)
      .collect { session.savePanes(it.toMap()) }
  }
  return weights
}
