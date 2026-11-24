package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.Islands

/**
 * The frame every IntelliJ popup has (`AbstractPopup` with its `PopupBorder`), Jewel's
 * `PopupContainer`: `Popup.background` filled, a 1 px `Popup.borderColor` line, rounded by
 * [radius]. Menus use 8 (`PopupMenu.borderCornerRadius`), the completion popup 6 (arc 12), e.g.
 * `Modifier.width(240.dp).popupContainer(8.dp)`.
 */
fun Modifier.popupContainer(radius: Dp): Modifier {
  val shape = RoundedCornerShape(radius)
  return background(Islands.popupBackground, shape).border(1.dp, Islands.popupBorder, shape)
}
