package intellijandroid.client.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import intellijandroid.client.ui.theme.IslandsDark

/**
 * Two panes with a border between them that can be dragged, as IntelliJ's `Splitter`: [first] gets
 * [proportion] of the room (0..1), [second] the rest, with the island gap between them. [stacked]
 * puts [first] above [second]; otherwise side by side. A drag reports the new proportion through
 * [onProportion].
 *
 * ponytail: fixed proportion for now; the drag (a pill on the border for a finger, the resize
 * cursor for a mouse, a 60 dp least size) comes from the UI session.
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
  val firstWeight = proportion.coerceIn(0.05f, 0.95f)
  if (stacked) {
    Column(modifier) {
      Box(Modifier.fillMaxWidth().weight(firstWeight)) { first() }
      Spacer(Modifier.height(IslandsDark.islandGap))
      Box(Modifier.fillMaxWidth().weight(1f - firstWeight)) { second() }
    }
  } else {
    Row(modifier) {
      Box(Modifier.fillMaxHeight().weight(firstWeight)) { first() }
      Spacer(Modifier.width(IslandsDark.islandGap))
      Box(Modifier.fillMaxHeight().weight(1f - firstWeight)) { second() }
    }
  }
}
