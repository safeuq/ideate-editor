package intellijandroid.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import intellijandroid.client.session.ClientSession

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
  companion object {
    const val DEFAULT_WEIGHT = 0.33f
    const val DEFAULT_SIDE_WEIGHT = 0.5f
  }
}

/**
 * The pane weights for [session]'s project.
 *
 * ponytail: kept for this screen only; saving them per project comes from the UI session.
 */
@Composable
fun rememberPaneWeights(session: ClientSession): MutableState<PaneWeights> =
  remember(session) { mutableStateOf(PaneWeights()) }
