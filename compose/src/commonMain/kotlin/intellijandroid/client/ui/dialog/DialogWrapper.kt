package intellijandroid.client.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import intellijandroid.client.ui.IS_MAC
import intellijandroid.client.ui.component.DefaultButton
import intellijandroid.client.ui.component.OutlinedButton
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/** One button of a dialog's south panel, as an `Action` from `DialogWrapper.createActions()`. */
class DialogAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * IntelliJ's `DialogWrapper` (platform-api/…/openapi/ui/DialogWrapper.java): the [title] row, the
 * center panel ([content]), the error text and the south panel with [actions]. [actions] are in
 * `createActions()` order, the first being the default (OK: filled, and Enter); macOS puts it
 * rightmost (`sortActionsOnMac`). Escape and a click outside call [onCancel].
 *
 * [contentPadding] is the center panel's inset: 14 at the sides for a form (Kotlin UI DSL panels
 * bring their own; 14 is what IntelliJ's dialogs measure); a dialog that places its parts itself,
 * like the folder chooser, passes 0. A [scrollable] center scrolls when taller than [maxHeight];
 * one that fills the height with a weighted child (a tree) sets it false.
 *
 * Example: Gateway's "Connect to SSH", actions Check Connection and Continue (default) and Cancel;
 * `[Cancel] [Check Connection and Continue]` on a Mac.
 */
@Composable
fun DialogWrapper(
  title: String,
  actions: List<DialogAction>,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  width: Dp = DialogLook.width,
  maxHeight: Dp = DialogLook.maxHeight,
  error: String? = null,
  contentPadding: PaddingValues = PaddingValues(horizontal = DialogLook.sideInset),
  scrollable: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val fonts = LocalClientFonts.current
  DialogWindow(actions, onCancel, focusFrame = false) {
    Column(modifier.width(width).heightIn(max = maxHeight).dialogFrame()) {
      Box(
        Modifier.fillMaxWidth()
          .height(DialogLook.titleHeight)
          .padding(start = DialogLook.sideInset),
        contentAlignment = Alignment.CenterStart,
      ) {
        // The window title: macOS draws it in the title bar, semibold label size.
        BasicText(
          title,
          style =
            fonts.label().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        )
      }
      Column(
        Modifier.weight(1f, fill = false)
          .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
          .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
      )
      error?.let {
        BasicText(
          it,
          style = fonts.label(IslandsDark.textError),
          modifier = Modifier.padding(horizontal = DialogLook.sideInset).padding(top = 8.dp),
        )
      }
      DialogButtons(
        actions,
        Modifier.padding(
          start = DialogLook.sideInset,
          end = DialogLook.sideInset,
          top = DialogLook.southTop,
          bottom = DialogLook.bottomInset,
        ),
      )
    }
  }
}

/**
 * The dialog window both [DialogWrapper] and [MessageDialog] use: Escape (and a click outside)
 * cancels, Enter runs the first, default action when it is enabled. A multi-line field keeps its
 * Enter, as it handles the key before the window sees it. [focusFrame]: a dialog without fields
 * takes the focus itself, so the keys reach it.
 */
@Composable
internal fun DialogWindow(
  actions: List<DialogAction>,
  onCancel: () -> Unit,
  focusFrame: Boolean,
  content: @Composable () -> Unit,
) {
  val focus = remember { FocusRequester() }
  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      Modifier.then(if (focusFrame) Modifier.focusRequester(focus).focusable() else Modifier)
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
          when (event.key) {
            Key.Enter,
            Key.NumPadEnter -> actions.firstOrNull()?.takeIf { it.enabled }?.onClick?.invoke()
            Key.Escape -> onCancel()
            else -> return@onKeyEvent false
          }
          true
        },
    ) {
      content()
    }
  }
  if (focusFrame) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The south panel's buttons, right-aligned 12 apart (`DialogWrapper.BASE_BUTTON_GAP`), the default
 * one last on macOS and first elsewhere. [focused] starts with the focus ring, as IntelliJ's
 * `focusedOptionIndex`; Enter then presses that button, Escape still cancels.
 */
@Composable
internal fun DialogButtons(
  actions: List<DialogAction>,
  modifier: Modifier = Modifier,
  focused: DialogAction? = null,
) {
  val default = actions.firstOrNull() ?: return
  require(focused == null || focused in actions) { "The focused action must be one of the actions" }
  val shown = if (IS_MAC) actions.drop(1) + default else actions
  val focusRequester = remember { FocusRequester() }
  Row(
    modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
  ) {
    for (action in shown) {
      val buttonModifier =
        if (action === focused) Modifier.focusRequester(focusRequester) else Modifier
      if (action === default)
        DefaultButton(action.label, buttonModifier, action.enabled, action.onClick)
      else OutlinedButton(action.label, buttonModifier, action.enabled, action.onClick)
    }
  }
  if (focused != null) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** The dialog's own frame: background, rounded corners and outline. */
internal fun Modifier.dialogFrame(
  background: Color = IslandsDark.islandBackground,
  outline: Color = DialogLook.outline,
) = background(background, DialogLook.shape).border(1.dp, outline, DialogLook.shape)

/**
 * Measured on IntelliJ's "Open File or Project" dialog (Islands Dark, macOS, 2x screenshot), in dp
 * = pt. No theme keys: macOS draws the window's corners and border.
 */
internal object DialogLook {
  val width = 475.dp
  val maxHeight = 600.dp
  val shape = RoundedCornerShape(10.dp)
  val outline = Color(0xFF393B40)
  val titleHeight = 32.dp
  val sideInset = 14.dp
  val southTop = 12.dp
  val bottomInset = 11.dp
}
