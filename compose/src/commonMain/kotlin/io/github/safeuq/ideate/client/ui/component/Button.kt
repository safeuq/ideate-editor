package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/**
 * IntelliJ's default button: the dialog's OK, filled with the accent (`DarculaButtonUI` with
 * `JButton.isDefaultButton`; Jewel's `DefaultButton` in `ui/component/Button.kt`).
 */
@Composable
fun DefaultButton(
  text: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) = Button(text, onClick, modifier, enabled, default = true)

/** IntelliJ's ordinary button: outlined, transparent (Jewel's `OutlinedButton`). */
@Composable
fun OutlinedButton(
  text: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) = Button(text, onClick, modifier, enabled, default = false)

@Composable
private fun Button(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier,
  enabled: Boolean,
  default: Boolean,
) {
  val fonts = LocalClientFonts.current
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val shape = RoundedCornerShape(ButtonLook.radius)
  val background = if (enabled && default) Islands.controlBrandBackground else Color.Transparent
  val border =
    when {
      !enabled -> ButtonLook.disabledBorder
      // Focused: the border turns the dialog color and the focus ring goes around it.
      focused -> ButtonLook.focusedBorder
      default -> Islands.controlBrandBackground
      else -> Islands.controlBorder
    }
  val content =
    when {
      !enabled -> Islands.textDisabled
      default -> Islands.textOverAccent
      else -> Islands.text
    }
  Box(
    modifier
      .defaultMinSize(ButtonLook.minWidth, ButtonLook.height)
      .drawBehind {
        if (!focused) return@drawBehind
        // DarculaButtonPainter: a 2 px ring in the focus color just outside the button.
        val ring = ButtonLook.focusWidth.toPx()
        drawRoundRect(
          Islands.focusBorder,
          topLeft = Offset(-ring / 2, -ring / 2),
          size = Size(size.width + ring, size.height + ring),
          cornerRadius = CornerRadius(ButtonLook.radius.toPx() + ring / 2),
          style = Stroke(ring),
        )
      }
      .background(background, shape)
      .border(1.dp, border, shape)
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .padding(horizontal = ButtonLook.horizontalPadding),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(text, style = fonts.label(content), maxLines = 1)
  }
}

/** DarculaButtonUI and the Islands Dark theme (ManyIslandsDark.theme.json). */
private object ButtonLook {
  val radius = 4.dp // Button.arc 8, an arc diameter
  val minWidth = 72.dp // Button.minimumSize 72,28
  val height // Button.minimumSize 72,28; compact 72,24
    get() = Islands.byDensity(28.dp, 24.dp)

  val horizontalPadding = 14.dp // DarculaButtonUI.HORIZONTAL_PADDING
  val focusWidth = 2.dp // DarculaUIUtil.BW (focus ring)
  val focusedBorder
    get() = Islands.color("Button.focusedBorderColor") // control-bg / dialog-bg

  val disabledBorder
    get() = Islands.color("Button.disabledBorderColor") // control-border-disabled
}
