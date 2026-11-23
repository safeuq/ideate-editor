package intellijandroid.client.ui.terminal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.core.input.KeyEvent
import intellijandroid.client.session.terminal.TerminalKeyInput
import intellijandroid.client.ui.IS_MAC
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.component.IconButtonLook
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts

/**
 * Which terminal has the keyboard, for the keyboard bar: the terminal view puts its input here
 * while it has focus (and takes it away when it loses it); null when no terminal has focus.
 */
class TerminalFocus {
  internal var focused by mutableStateOf<TerminalKeyInput?>(null)
}

/**
 * The keyboard bar (docs/feature-parity-plan.md section 1): an extra bar below the tool window bar,
 * shown while the soft keyboard is open. With a terminal focused it holds the keys a soft keyboard
 * doesn't have, each one an icon: Esc, Ctrl, Alt and Meta (each stays on until the next key), Tab
 * and the four arrows. The glyphs (⌥ and ⌘ are `MacKeymapUtil.OPTION` and `COMMAND`) are the ones
 * IntelliJ shows for these keys in macOS shortcuts (`MacKeymapUtil`); the arrows are IntelliJ's
 * chevron icons.
 *
 * The buttons take taps without taking focus, so the terminal keeps it (and the soft keyboard stays
 * open).
 */
@Composable
fun TerminalKeyBar(keys: TerminalKeyInput, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .height(KEY_BAR_HEIGHT)
      .background(IslandsDark.windowBackground)
      .padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GlyphKey("⎋", "Esc") { keys.key(KeyEvent.VK_ESCAPE) }
    GlyphKey("⌃", "Ctrl", selected = keys.ctrl) { keys.toggleCtrl() }
    GlyphKey("⌥", "Alt", selected = keys.alt) { keys.toggleAlt() }
    GlyphKey("⌘", if (IS_MAC) "Command" else "Meta", selected = keys.meta) { keys.toggleMeta() }
    GlyphKey("⇥", "Tab") { keys.key(KeyEvent.VK_TAB) }
    ArrowKey("expui/general/chevronLeft.svg", "Left", KeyEvent.VK_LEFT, keys)
    ArrowKey("expui/general/chevronUp.svg", "Up", KeyEvent.VK_UP, keys)
    ArrowKey("expui/general/chevronRight.svg", "Right", KeyEvent.VK_RIGHT, keys)
    ArrowKey("expui/general/chevronDown.svg", "Down", KeyEvent.VK_DOWN, keys)
  }
}

private val KEY_BAR_HEIGHT = 32.dp

/**
 * A toolbar icon button (24 dp, 16 dp icon) that reacts to taps but can't take focus, with a key
 * cap's border: `Component.borderColor` (control-border) on the toolbar button's own 4 dp corners,
 * the colours IntelliJ gives an outlined control (`Button.startBorderColor`).
 */
@Composable
private fun KeyButton(
  description: String,
  selected: Boolean,
  onTap: () -> Unit,
  icon: @Composable () -> Unit,
) {
  Box(
    Modifier.size(24.dp)
      .background(
        if (selected) IconButtonLook.pressedBackground else Color.Transparent,
        IconButtonLook.shape,
      )
      .border(1.dp, IslandsDark.controlBorder, IconButtonLook.shape)
      .semantics {
        contentDescription = description
        role = Role.Button
        this.selected = selected
      }
      .pointerInput(onTap) { detectTapGestures { onTap() } },
    contentAlignment = Alignment.Center,
  ) {
    icon()
  }
}

@Composable
private fun GlyphKey(
  glyph: String,
  description: String,
  selected: Boolean = false,
  onTap: () -> Unit,
) {
  KeyButton(description, selected, onTap) {
    // The glyph fills the 16 dp icon box, as an SVG icon does.
    BasicText(
      glyph,
      Modifier.size(16.dp),
      style =
        TextStyle(
          color = IslandsDark.text,
          fontFamily = LocalClientFonts.current.ui,
          fontSize = 16.sp,
          textAlign = TextAlign.Center,
        ),
      maxLines = 1,
      softWrap = false,
    )
  }
}

@Composable
private fun ArrowKey(iconPath: String, description: String, keyCode: Int, keys: TerminalKeyInput) {
  val icon = LocalIcons.current.painter(iconPath)
  KeyButton(description, selected = false, onTap = { keys.key(keyCode) }) {
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
  }
}
