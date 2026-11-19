package intellijandroid.client.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.component.IndeterminateHorizontalProgressBar
import intellijandroid.client.ui.component.OutlinedButton
import intellijandroid.client.ui.component.popupContainer
import intellijandroid.client.ui.theme.ClientFonts
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.label

/**
 * Gateway's "Starting the remote IDE Backend" popup (from a 2x screenshot of Gateway 2026.3, in dp
 * = pt). Gateway shows each step and a real fraction; `intellij-backend start` answers only once,
 * at the end, so the bar is indeterminate like IntelliJ's and the detail line names the project.
 */
@Composable
fun StartProgress(title: String, detail: String, fonts: ClientFonts, onCancel: (() -> Unit)?) {
  // Not dismissable by tapping outside or Back: Cancel is the only way out, as in Gateway. With no
  // [onCancel] (stopping a backend) it closes when the work is done.
  Dialog(
    onDismissRequest = {},
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
      ),
  ) {
    // Gateway's 459 dp when it fits with a 16 dp margin each side, Cancel beside the text.
    // Narrower screens get the space left (a 411 dp phone: 379 dp) and, instead of the button,
    // IntelliJ's compact cancel: the round × after the bar, as in its status bar and Background
    // Tasks popup (InlineProgressIndicator.createCancelButton).
    BoxWithConstraints {
      val narrow = maxWidth < Popup.width + Popup.screenMargin * 2
      val width = if (narrow) maxWidth - Popup.screenMargin * 2 else Popup.width
      // Gateway shows Cancel focused (its focus ring), so Enter cancels.
      val cancelFocus = remember { FocusRequester() }
      val cancel: @Composable () -> Unit = {
        if (onCancel != null) {
          OutlinedButton("Cancel", Modifier.focusRequester(cancelFocus), onClick = onCancel)
        }
      }
      val popup =
        Modifier.width(width)
          .popupContainer(0.dp) // Gateway's popup is square (measured)
          .padding(start = Popup.inset, end = Popup.inset, top = 10.dp, bottom = 12.dp)
      if (narrow) {
        Box(popup) {
          ProgressText(
            title,
            detail,
            fonts,
            afterBar =
              onCancel?.let { { StopButton(Modifier.focusRequester(cancelFocus), onClick = it) } },
          )
        }
      } else {
        Row(
          popup,
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          ProgressText(title, detail, fonts, Modifier.weight(1f))
          cancel()
        }
      }
      if (onCancel != null) LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
  }
}

/** The title, the bar (with [afterBar] at its right end) and the detail line. */
@Composable
private fun ProgressText(
  title: String,
  detail: String,
  fonts: ClientFonts,
  modifier: Modifier = Modifier,
  afterBar: (@Composable () -> Unit)? = null,
) {
  val text = fonts.label()
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(title, style = text)
    if (afterBar == null) IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth())
    else
      Row(verticalAlignment = Alignment.CenterVertically) {
        IndeterminateHorizontalProgressBar(Modifier.weight(1f))
        afterBar()
      }
    BasicText(
      detail,
      style = text.copy(color = IslandsDark.textSecondary),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * IntelliJ's `InplaceButton` with `AllIcons.Process.Stop`: the 14 px round ×, no frame, the
 * brighter `stopHovered` under the pointer. A 20 × 24 dp tap area with the icon at its right end: 6
 * dp after the bar, and flush with the text's right edge.
 */
@Composable
private fun StopButton(modifier: Modifier, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val icon =
    LocalIcons.current.painter(if (hovered) "process/stopHovered.svg" else "process/stop.svg")
      ?: return
  Box(
    modifier
      .size(width = 20.dp, height = 24.dp)
      .hoverable(interaction)
      .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Image(icon, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
  }
}

/** Measured on Gateway's popup: 459 × 70 pt, bar 350 × 4, Cancel 74 × 30 with its focus ring. */
private object Popup {
  val width = 459.dp
  val inset = 11.dp
  /** Space kept between the popup and the screen's sides when the screen is narrow. */
  val screenMargin = 16.dp
}
