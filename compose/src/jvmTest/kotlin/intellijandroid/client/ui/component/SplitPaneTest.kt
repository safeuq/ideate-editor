package intellijandroid.client.ui.component

import intellijandroid.client.ui.PaneWeights
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitPaneTest {
  @Test
  fun keepsBothPanesAtLeastTheLeastSize() {
    // 600 dp of room, 60 dp least: 0.1 to 0.9.
    assertEquals(0.5f, shownProportion(0.5f, room = 600f, least = 60f))
    assertEquals(0.1f, shownProportion(0.02f, room = 600f, least = 60f))
    assertEquals(0.9f, shownProportion(0.97f, room = 600f, least = 60f))
    // Too small for two least sizes: shown as given.
    assertEquals(0.2f, shownProportion(0.2f, room = 100f, least = 60f))
  }

  @Test
  fun normalizesOutOfRangeLikeIntellij() {
    assertEquals(0.33f, shownProportion(0f, room = 600f, least = 60f))
    assertEquals(0.67f, shownProportion(1.5f, room = 600f, least = 60f), 0.0001f)
  }

  @Test
  fun dragMovesTheBorderByTheDelta() {
    // First pane 300 of 600; dragged 60 right: 0.6; dragged far left: the least size.
    assertEquals(0.6f, draggedProportion(300f, 60f, room = 600f, least = 60f), 0.0001f)
    assertEquals(0.1f, draggedProportion(300f, -500f, room = 600f, least = 60f), 0.0001f)
  }

  @Test
  fun readsSavedWeights() {
    val saved = PaneWeights(left = 0.28f, leftSplit = 0.6f).toMap()
    assertEquals(PaneWeights(left = 0.28f, leftSplit = 0.6f), PaneWeights.from(saved))
    // Missing names keep their defaults; out-of-range values are normalized.
    assertEquals(
      PaneWeights(right = 0.33f, bottom = 1f - PaneWeights.DEFAULT_WEIGHT),
      PaneWeights.from(mapOf("right" to 0f, "bottom" to 2f)),
    )
  }
}
