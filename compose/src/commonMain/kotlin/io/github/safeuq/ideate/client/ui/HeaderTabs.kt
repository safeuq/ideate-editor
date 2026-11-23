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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.session.BackendToolWindowView
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/**
 * One chip of the tool window header's tabs, as the new UI draws them (Terminal's shells, Run's
 * "Main"): the selected one has a background and a border; its close button shows on the selected
 * chip and under the pointer. [onClose] null: no close button.
 */
@Composable
fun HeaderTab(title: String, isSelected: Boolean, onSelect: () -> Unit, onClose: (() -> Unit)?) {
  val pill = RoundedCornerShape(IslandsDark.tabRadius)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Box(
    Modifier.height(IslandsDark.tabHeight)
      .clip(pill)
      .then(
        if (isSelected)
          Modifier.background(IslandsDark.tabSelectedBackground)
            .border(1.dp, IslandsDark.tabSelectedBorder, pill)
        else Modifier,
      )
      .hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onSelect)
      .padding(start = 8.dp, end = if (onClose == null) 8.dp else 4.dp),
  ) {
    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
      BasicText(title, style = LocalClientFonts.current.label(), maxLines = 1, softWrap = false)
      if (onClose != null) {
        Spacer(Modifier.width(2.dp))
        if (isSelected || hovered) TabCloseButton(title, onClose) else Spacer(Modifier.width(20.dp))
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
        onSelect = { onSelect(index) },
        onClose = if (view.closeable.getOrNull(index) == true) ({ onClose(index) }) else null,
      )
    }
  }
}
