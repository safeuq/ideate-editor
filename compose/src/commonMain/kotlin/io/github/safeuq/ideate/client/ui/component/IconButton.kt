package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.theme.Islands

/**
 * IntelliJ's `ActionButton` (platform/platform-impl/src/com/intellij/openapi/actionSystem/impl/
 * ActionButton.java, painted by `IdeaActionButtonLook`), Jewel's `IconButton`: an icon in a square
 * that lights up on hover and press. [selected] keeps it filled, as a `ToggleAction` that is on.
 *
 * [iconPath] is a path in the icons jar, e.g. `expui/general/closeSmall.svg`; [hoveredIconPath]
 * swaps it while the pointer is over the button (the tab close button's `closeSmallHovered.svg`).
 * Draws nothing when the icon isn't in the jar.
 */
@Composable
fun IconButton(
  iconPath: String,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = IconButtonLook.size,
  iconSize: Dp = IconButtonLook.iconSize,
  selected: Boolean = false,
  hoveredIconPath: String? = null,
  /** False greys the icon out and ignores the pointer, as a disabled action's button is. */
  enabled: Boolean = true,
  /** For a button wider than high, e.g. the run widget's 34 × 32. */
  width: Dp = size,
  /**
   * A filled button, e.g. the run widget's running Run (green) and Stop (red): the hover and
   * pressed backgrounds are blended over it, as `RunWidgetButtonLook` does. Not when disabled.
   */
  fill: Color? = null,
  /** Paints every stroke of the icon in this colour (IntelliJ's `toStrokeIcon`). */
  tint: Color? = null,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val pressed by interaction.collectIsPressedAsState()
  val icon =
    LocalIcons.current.painter(
      if (hovered && hoveredIconPath != null) hoveredIconPath else iconPath,
    ) ?: return
  val background =
    when {
      !enabled -> Color.Transparent
      fill != null && pressed -> Islands.color("RunWidget.pressedBackground").compositeOver(fill)
      fill != null && hovered -> Islands.color("RunWidget.hoverBackground").compositeOver(fill)
      fill != null -> fill
      pressed || selected -> IconButtonLook.pressedBackground
      hovered -> IconButtonLook.hoverBackground
      else -> Color.Transparent
    }
  Box(
    modifier
      .size(width, size)
      .background(background, IconButtonLook.shape)
      .hoverable(interaction)
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      icon,
      contentDescription = contentDescription,
      modifier = Modifier.size(iconSize).alpha(if (enabled) 1f else DISABLED_ALPHA),
      colorFilter = tint?.let { ColorFilter.tint(it) },
    )
  }
}

private const val DISABLED_ALPHA = 0.4f

internal object IconButtonLook {
  val size = 22.dp // ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE 22,22
  val iconSize = 16.dp
  // ActionButton.hoverBackground = toolbar-bg-hovered = core-bg-transparent-hovered =
  // transparent-white-20
  val hoverBackground
    get() = Islands.color("ActionButton.hoverBackground")

  // ActionButton.pressedBackground = toolbar-bg-pressed = core-bg-transparent-pressed =
  // transparent-white-40; also a selected toggle (ActionButtonLook paints PUSHED that way)
  val pressedBackground
    get() = Islands.color("ActionButton.pressedBackground")

  val shape =
    RoundedCornerShape(4.dp) // IdeaActionButtonLook: DarculaUIUtil.BUTTON_ARC = Button.arc 8
}
