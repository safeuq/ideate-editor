package intellijandroid.client.ui.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.h4
import intellijandroid.client.ui.theme.label

/** The icon at the left of a [MessageDialog], as IntelliJ's `Messages.get…Icon()`. */
enum class MessageKind(val iconPath: String) {
  INFORMATION("expui/general/informationDialog.svg"),
  QUESTION("expui/general/questionDialog.svg"),
  WARNING("expui/general/warningDialog.svg"),
  ERROR("expui/general/errorDialog.svg"),
}

/**
 * IntelliJ's standard message dialog (`Messages.showYesNoDialog`, `showOkCancelDialog`, …), as the
 * New UI draws it (`AlertDialog` in platform/platform-impl/…/ui/messages/AlertMessagesManager.kt):
 * no title bar, the 32 px [kind] icon at the left, the bold [title] (`JBFont.h4`) above the
 * [message], the buttons at the bottom right. [actions] go in IntelliJ's order, the first being the
 * default; [DialogWrapper]'s south panel orders them for the platform. Escape and a click outside
 * call [onCancel], like the dialog's close. [focused] is `Messages`' `focusedOptionIndex`: that
 * button starts with the focus ring.
 *
 * Example: the SSH host key question, `title = "Connecting To Remote Host"`, `kind = QUESTION`,
 * actions OK and Cancel, Cancel focused; shown as `[Cancel] [OK]` on a Mac, `[OK] [Cancel]`
 * elsewhere, as Gateway shows it.
 */
@Composable
fun MessageDialog(
  title: String,
  message: String,
  kind: MessageKind,
  actions: List<DialogAction>,
  onCancel: () -> Unit,
  focused: DialogAction? = null,
) {
  require(actions.isNotEmpty()) { "A message dialog needs at least one button" }
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  // AlertDialog: 370 wide, 440 for a message over 130 characters.
  val width = if (message.length <= 130) 370.dp else 440.dp
  DialogWindow(actions, onCancel, focusFrame = focused == null) {
    Row(
      Modifier.width(width)
        // AlertDialog.background = popup-bg; its PopupBorder in popup-border.
        .dialogFrame(IslandsDark.popupBackground, IslandsDark.popupBorder)
        .padding(20.dp), // createContentPaneBorder: 20 all round
      horizontalArrangement = Arrangement.spacedBy(12.dp), // the icon panel's hgap
    ) {
      icons.painter(kind.iconPath)?.let { Image(it, null, Modifier.size(32.dp)) }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, style = fonts.h4())
        BasicText(message, style = fonts.label())
        // 22 between the message and the buttons: 8 from the column plus 14.
        DialogButtons(actions, Modifier.padding(top = 14.dp), focused)
      }
    }
  }
}
