package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.session.BackendToolWindowView
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/**
 * One tab of a tool window's header (Terminal's shells, Run's "Main", Problems' "File"), as
 * IntelliJ's Islands UI draws a ContentTabLabel: the label is the header row's height, and
 * IslandsTabPainter.paintTab fills a pill inside it, [Islands.headerTabHOffset] in from each side
 * and max(height - 28, 8) shorter, then outlines it in 1 dp. Its colours follow
 * [Islands.headerTabColors]: [isActive] is whether the tool window has the focus
 * (`window.isActive()` in TabContentLayout). [onClose] null: no close button; otherwise the close
 * button is on the tab all the time, as ContentTabLabel's CloseContentTabAction is available
 * whenever the content can be closed (canBeClosed).
 */
@Composable
fun HeaderTab(
  title: String,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
  onClose: (() -> Unit)?,
) {
  val pill = RoundedCornerShape(Islands.headerTabRadius)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val (fill, border) = Islands.headerTabColors(isSelected, isActive, hovered)
  val labelHeight = Islands.headerHeight
  val verticalOffset = maxOf(labelHeight - Islands.headerTabFullHeight, Islands.headerTabMinVOffset)
  Box(
    Modifier.height(labelHeight)
      .hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onSelect)
      .padding(horizontal = Islands.headerTabHOffset),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      Modifier.height(labelHeight - verticalOffset)
        .background(fill, pill)
        .border(1.dp, border, pill)
        // The close button's box is 20 dp around its 16 dp icon, so 2 dp less on each side of it.
        .padding(
          start = Islands.headerTabTextInset,
          end =
            if (onClose == null) Islands.headerTabTextInset
            else Islands.headerTabCloseEndInset - 2.dp,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicText(title, style = LocalClientFonts.current.label(), maxLines = 1, softWrap = false)
      if (onClose != null) {
        Spacer(Modifier.width(Islands.headerTabCloseGap - 2.dp))
        TabCloseButton(title, onClose)
      }
    }
  }
}

/**
 * The tabs of a backend tool window with a painted panel (Run, Build) in its header: a chip per
 * `BeTab`, e.g. "Main" and "Slow". [onClose] gets the index of a tab closed by its button (only
 * tabs that are closeable have one).
 */
@Composable
fun BackendHeaderTabs(
  view: BackendToolWindowView,
  isActive: Boolean,
  onSelect: (Int) -> Unit,
  onClose: (Int) -> Unit,
) {
  Row(
    Modifier.horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    view.tabs.forEachIndexed { index, name ->
      HeaderTab(
        name,
        isSelected = index == view.selectedTab,
        isActive = isActive,
        onSelect = { onSelect(index) },
        onClose = if (view.closeable.getOrNull(index) == true) ({ onClose(index) }) else null,
      )
    }
  }
}
