package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/** Where [HelpTooltip] sits next to its content (`HelpTooltip.Alignment`). */
enum class TooltipAlignment {
  RIGHT,
  LEFT,
  TOP,
  BOTTOM,
}

/**
 * IntelliJ's `HelpTooltip` with a title and an optional [shortcut] (`HelpTooltip.kt` in
 * platform-api): shows [title] at once when the mouse is over [content] and hides it when the mouse
 * leaves or presses it (the stripe buttons set both delays to 0). A touch never shows it. No
 * description, link or long press yet.
 */
@Composable
fun HelpTooltip(
  title: String,
  alignment: TooltipAlignment,
  modifier: Modifier = Modifier,
  shortcut: String? = null,
  content: @Composable () -> Unit,
) {
  var hovered by remember { mutableStateOf(false) }
  var pressed by remember { mutableStateOf(false) }
  Box(
    modifier.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val mouse = event.changes.all { it.type == PointerType.Mouse }
          when {
            !mouse -> Unit
            event.type == PointerEventType.Enter -> hovered = true
            event.type == PointerEventType.Exit -> {
              hovered = false
              pressed = false
            }
            event.type == PointerEventType.Press -> pressed = true
          }
        }
      }
    },
  ) {
    content()
    if (hovered && !pressed)
      AppPopup(TooltipPosition(alignment, LocalDensity.current), focusable = false) {
        TooltipPanel(title, shortcut)
      }
  }
}

// IntelliJ's single-line tooltip: HelpTooltip.smallTextBorderInsets (new UI 8, 12, 9, 12),
// ToolTip.borderCornerRadius 4, ToolTip.background and ToolTip.borderColor = feedback-bg =
// layer-2-bg = gray-40, ToolTip.foreground = text-default, ToolTip.shortcutForeground = text-muted.
// No shadow yet (IntelliJ draws one).
@Composable
private fun TooltipPanel(title: String, shortcut: String?) {
  val shape = RoundedCornerShape(4.dp)
  val text: AnnotatedString = buildAnnotatedString {
    append(title)
    // HelpTooltip.getShortcutAsHtml: two spaces, then the shortcut in the muted colour.
    if (shortcut != null)
      withStyle(SpanStyle(color = Islands.tooltipShortcut)) { append("  $shortcut") }
  }
  Box(
    Modifier.clip(shape)
      .background(Islands.tooltipBackground)
      .border(1.dp, Islands.tooltipBackground, shape)
      .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 9.dp),
  ) {
    BasicText(text, style = LocalClientFonts.current.label())
  }
}

/** `HelpTooltip.Alignment.getPointFor`: 5 dp off the component, 1 dp along it. */
private class TooltipPosition(private val alignment: TooltipAlignment, density: Density) :
  PopupPositionProvider {
  private val gap = with(density) { 5.dp.roundToPx() }
  private val along = with(density) { 1.dp.roundToPx() }

  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val point =
      when (alignment) {
        TooltipAlignment.RIGHT -> IntOffset(anchorBounds.right + gap, anchorBounds.top + along)
        TooltipAlignment.LEFT ->
          IntOffset(anchorBounds.left - popupContentSize.width - gap, anchorBounds.top + along)
        TooltipAlignment.TOP ->
          IntOffset(anchorBounds.left + along, anchorBounds.top - gap - popupContentSize.height)
        TooltipAlignment.BOTTOM -> IntOffset(anchorBounds.left + along, anchorBounds.bottom + gap)
      }
    return windowSize.keep(point.x, point.y, popupContentSize)
  }
}
