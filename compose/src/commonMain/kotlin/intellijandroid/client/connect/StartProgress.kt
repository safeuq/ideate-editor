package intellijandroid.client.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
fun StartProgress(title: String, detail: String, fonts: ClientFonts, onCancel: () -> Unit) {
  val text = fonts.label()
  // Not dismissable by tapping outside or Back: Cancel is the only way out, as in Gateway.
  Dialog(
    onDismissRequest = {},
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
      ),
  ) {
    Row(
      Modifier.width(Popup.width)
        .popupContainer(0.dp) // Gateway's popup is square (measured)
        .padding(start = Popup.inset, end = Popup.inset, top = 10.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(title, style = text)
        IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth())
        BasicText(
          detail,
          style = text.copy(color = IslandsDark.textSecondary),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // Gateway shows Cancel focused (its focus ring), so Enter cancels.
      val cancelFocus = remember { FocusRequester() }
      OutlinedButton("Cancel", Modifier.focusRequester(cancelFocus), onClick = onCancel)
      LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
  }
}

/** Measured on Gateway's popup: 459 × 70 pt, bar 350 × 4, Cancel 74 × 30 with its focus ring. */
private object Popup {
  val width = 459.dp
  val inset = 11.dp
}
