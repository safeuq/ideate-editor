package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The icon at the left of a [MessageDialog], as IntelliJ's `Messages.get…Icon()`. */
enum class MessageKind(val iconPath: String) {
  INFORMATION("expui/general/informationDialog.svg"),
  QUESTION("expui/general/questionDialog.svg"),
  WARNING("expui/general/warningDialog.svg"),
  ERROR("expui/general/errorDialog.svg"),
}

/** One button of a [MessageDialog]. */
class MessageOption(val label: String, val onClick: () -> Unit)

/**
 * IntelliJ's standard message dialog (`Messages.showYesNoDialog`, `showOkCancelDialog`, …), as the
 * New UI draws it (`AlertDialog` in platform/platform-impl/…/ui/messages/AlertMessagesManager.kt):
 * no title bar, the 32 px [kind] icon at the left, the bold [title] (label font + 1) above the
 * [message], the buttons at the bottom right. [options] go in IntelliJ's order; the first is the
 * default (filled, and Enter), and on macOS it is the rightmost one, as `sortActionsOnMac` reverses
 * them. Escape and a click outside call [onCancel], like the dialog's close.
 *
 * Example: the SSH host key question, `title = "SSH"`, `kind = QUESTION`, options Continue and
 * Cancel; shown as `[Cancel] [Continue]` on a Mac, `[Continue] [Cancel]` elsewhere.
 */
@Composable
fun MessageDialog(
  title: String,
  message: String,
  kind: MessageKind,
  options: List<MessageOption>,
  onCancel: () -> Unit,
) {
  require(options.isNotEmpty()) { "A message dialog needs at least one button" }
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val label =
    TextStyle(
      color = IslandsDark.text,
      fontFamily = fonts.ui,
      fontSize = IslandsDark.UI_FONT_SIZE.sp,
    )
  val focus = remember { FocusRequester() }
  // AlertDialog: 370 wide, 440 for a message over 130 characters.
  val width = if (message.length <= 130) 370.dp else 440.dp
  val shown = if (IS_MAC) options.reversed() else options
  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Row(
      Modifier.width(width)
        .background(Alert.background, Alert.shape)
        .border(1.dp, Alert.border, Alert.shape)
        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp)
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (event.key) {
            Key.Enter,
            Key.NumPadEnter -> options.first().onClick()
            Key.Escape -> onCancel()
            else -> return@onPreviewKeyEvent false
          }
          true
        },
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      icons.painter(kind.iconPath)?.let { Image(it, null, Modifier.size(32.dp)) }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
          title,
          style =
            label.copy(fontSize = (IslandsDark.UI_FONT_SIZE + 1).sp, fontWeight = FontWeight.Bold),
        )
        BasicText(message, style = label)
        // 22 between the message and the buttons: 8 from the column plus 14.
        Row(
          Modifier.fillMaxWidth().padding(top = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
          shown.forEach { option ->
            DialogButton(
              option.label,
              label,
              primary = option === options.first(),
              onClick = option.onClick,
            )
          }
        }
      }
    }
  }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** IntelliJ's dialog button: the default one filled with the accent, the others outlined. */
@Composable
fun DialogButton(label: String, style: TextStyle, primary: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.height(24.dp)
      .then(
        if (primary) Modifier.background(Alert.accent, Alert.buttonShape)
        else Modifier.border(1.dp, Alert.buttonBorder, Alert.buttonShape),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = style)
  }
}

private object Alert {
  val background = Color(0xFF26282C) // AlertDialog.background = popup-bg (Islands Dark)
  val border = Color(0xFF33353B) // popup-border
  val shape = RoundedCornerShape(10.dp)
  val accent = Color(0xFF4670DA) // Button.default.startBackground, as the folder chooser measured
  val buttonBorder = Color(0xFF414349)
  val buttonShape = RoundedCornerShape(4.dp)
}
