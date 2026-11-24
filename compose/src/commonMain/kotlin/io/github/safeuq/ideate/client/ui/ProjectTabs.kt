package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.safeuq.ideate.client.session.OpenProject
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/** Each tab's width for [count] tabs in [total]: equal, the last takes the remainder. */
internal fun projectTabWidths(total: Int, count: Int): List<Int> {
  if (count <= 0) return emptyList()
  val each = total / count
  return List(count) { index -> if (index == count - 1) total - each * (count - 1) else each }
}

/**
 * IntelliJ's macOS project tabs (WindowTabsComponent, IslandsUICustomization.paintProjectTab): one
 * tab per open project under the main toolbar; [shown] (a host path) is the selected one.
 */
@Composable
fun ProjectTabs(
  projects: List<OpenProject>,
  shown: String?,
  onShow: (hostPath: String) -> Unit,
  onClose: (hostPath: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var hoveredPath by remember { mutableStateOf<String?>(null) }
  BoxWithConstraints(modifier.fillMaxWidth().height(30.dp).background(Islands.windowBackground)) {
    val widths = projectTabWidths(constraints.maxWidth, projects.size)
    val density = LocalDensity.current
    Row(Modifier.fillMaxHeight()) {
      projects.forEachIndexed { index, project ->
        val next = projects.getOrNull(index + 1)
        // Only with three or more tabs, and not next to a selected or hovered tab.
        val separator =
          projects.size >= 3 &&
            next != null &&
            listOf(project.hostPath, next.hostPath).none { it == shown || it == hoveredPath }
        ProjectTab(
          project,
          selected = project.hostPath == shown,
          separator = separator,
          onHover = { hovered ->
            if (hovered) hoveredPath = project.hostPath
            else if (hoveredPath == project.hostPath) hoveredPath = null
          },
          onShow = { onShow(project.hostPath) },
          onClose = { onClose(project.hostPath) },
          modifier = Modifier.width(with(density) { widths[index].toDp() }),
        )
      }
    }
  }
}

@Composable
private fun ProjectTab(
  project: OpenProject,
  selected: Boolean,
  separator: Boolean,
  onHover: (Boolean) -> Unit,
  onShow: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  LaunchedEffect(hovered) { onHover(hovered) }
  Box(
    modifier
      .fillMaxHeight()
      .hoverable(interaction)
      .clickable(interactionSource = interaction, indication = null, onClick = onShow),
  ) {
    val fill =
      when {
        selected -> Islands.projectTabSelectedBackground
        hovered -> Islands.projectTabHoverBackground
        else -> null
      }
    // fillRoundRect 1 px in from each side, full height, arc Island.arc 10.
    if (fill != null) {
      Box(
        Modifier.matchParentSize()
          .padding(horizontal = 1.dp)
          .background(fill, RoundedCornerShape(5.dp)),
      )
    }
    BasicText(
      project.name,
      style =
        LocalClientFonts.current
          .label()
          .copy(
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color =
              when {
                selected -> Islands.projectTabSelectedForeground
                hovered -> Islands.projectTabHoverForeground
                else -> Islands.projectTabForeground
              },
          ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(start = 4.dp, end = 15.dp),
    )
    if (hovered) {
      Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
        ProjectTabCloseButton(project.name, onClose)
      }
    }
    if (separator) {
      Box(
        Modifier.align(Alignment.CenterEnd)
          .padding(vertical = 5.dp)
          .width(1.dp)
          .fillMaxHeight()
          .background(Islands.projectTabSeparator),
      )
    }
  }
}

/** `closeSmall`, `closeSmallHovered` under the pointer, 16 dp. */
@Composable
private fun ProjectTabCloseButton(name: String, onClose: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val icon =
    LocalIcons.current.painter(
      if (hovered) "expui/general/closeSmallHovered.svg" else "expui/general/closeSmall.svg",
    ) ?: return
  Image(
    icon,
    contentDescription = "Close $name",
    modifier =
      Modifier.size(16.dp)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = onClose),
  )
}
