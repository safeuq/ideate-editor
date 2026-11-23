package io.github.safeuq.ideate.client.ui.terminal

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.core.input.KeyEvent
import io.github.safeuq.ideate.client.session.terminal.TerminalKeyInput
import io.github.safeuq.ideate.client.ui.IS_MAC
import io.github.safeuq.ideate.client.ui.component.IconButtonLook
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts

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
 * IntelliJ shows for these keys in macOS shortcuts (`MacKeymapUtil`), arrows included.
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
    // IntelliJ's arrow key symbols in shortcut text (MacKeymapUtil.LEFT, UP, RIGHT, DOWN), the same
    // family as the keys before them.
    GlyphKey("←", "Left") { keys.key(KeyEvent.VK_LEFT) }
    GlyphKey("↑", "Up") { keys.key(KeyEvent.VK_UP) }
    GlyphKey("→", "Right") { keys.key(KeyEvent.VK_RIGHT) }
    GlyphKey("↓", "Down") { keys.key(KeyEvent.VK_DOWN) }
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
    // Centred by the glyph's outline, as an SVG icon is: centring the line of text leaves ⎋ ⌥ ⌘
    // low,
    // since each sits on the font's baseline. ⌃ still sits high (its outline doesn't come through
    // from the fallback font on Android), which keeps it apart from the ↑ key.
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = LocalClientFonts.current.ui, fontSize = 16.sp)
    Canvas(Modifier.size(16.dp)) {
      val layout = measurer.measure(glyph, style)
      val outline = layout.getPathForRange(0, glyph.length).getBounds()
      drawText(
        layout,
        color = IslandsDark.text,
        topLeft = Offset(size.width / 2 - outline.center.x, size.height / 2 - outline.center.y),
      )
    }
  }
}
