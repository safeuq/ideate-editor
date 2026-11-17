package intellijandroid.client.connect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
  val segment by
    rememberInfiniteTransition(label = "start-progress")
      .animateFloat(
        initialValue = -Popup.segment,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "segment",
      )
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
        .background(Popup.background)
        .border(1.dp, Popup.border)
        .padding(start = Popup.inset, end = Popup.inset, top = 10.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(title, style = text)
        Canvas(Modifier.fillMaxWidth().height(Popup.barHeight)) {
          val radius = CornerRadius(size.height / 2)
          drawRoundRect(Popup.track, cornerRadius = radius)
          val start = (segment * size.width).coerceAtLeast(0f)
          val end = ((segment + Popup.segment) * size.width).coerceAtMost(size.width)
          if (end > start)
            drawRoundRect(Popup.fill, Offset(start, 0f), Size(end - start, size.height), radius)
        }
        BasicText(
          detail,
          style = text.copy(color = IslandsDark.textSecondary),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box(
        Modifier.size(Popup.buttonWidth, Popup.buttonHeight)
          .border(2.dp, Popup.fill, Popup.buttonShape)
          .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
      ) {
        BasicText("Cancel", style = text)
      }
    }
  }
}

/** Measured on Gateway's popup: 459 × 70 pt, bar 350 × 4, Cancel 74 × 30 with its focus ring. */
private object Popup {
  val width = 459.dp
  val inset = 11.dp
  val background = IslandsDark.popupBackground
  val border = IslandsDark.popupBorder
  val barHeight = 4.dp
  val track = Color(0xFF40434A) // ProgressBar.trackColor = control-bg-small = gray-50
  val fill = IslandsDark.controlBrandBackground // ProgressBar.progressColor = control-brand-bg
  /** Part of the bar the moving segment covers. */
  const val segment = 0.3f
  val buttonWidth = 74.dp
  val buttonHeight = 30.dp
  val buttonShape = RoundedCornerShape(5.dp)
}
