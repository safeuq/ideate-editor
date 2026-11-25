package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fonts the screens use; the platform supplies them (desktop: files in the JetBrains Client's JBR).
 */
data class ClientFonts(val editor: FontFamily, val ui: FontFamily)

val LocalClientFonts = staticCompositionLocalOf {
  ClientFonts(FontFamily.Monospace, FontFamily.SansSerif)
}

// IntelliJ's JBFont (platform/util/ui/src/com/intellij/util/ui/JBFont.java): every UI size is
// label() plus or minus a few points, e.g. small() = label().lessOn(2) = 11 next to label()'s 13.

/** `JBFont.label()`: the UI font at the user's size (Inter 13). */
fun ClientFonts.label(color: Color = Islands.text) =
  TextStyle(color = color, fontFamily = ui, fontSize = Islands.UI_FONT_SIZE.sp)

/** A tool window header's title, e.g. "Project": label() at ToolWindow.Header's size, semibold. */
fun ClientFonts.toolWindowTitle(color: Color = Islands.text) =
  label(color).copy(fontSize = Islands.headerFontSize.sp, fontWeight = FontWeight.SemiBold)

/** `JBFont.medium()` = `label().lessOn(1)`. */
fun ClientFonts.medium(color: Color = Islands.text) =
  label(color).copy(fontSize = (Islands.UI_FONT_SIZE - 1).sp)

/** `JBFont.small()` = `label().lessOn(2)`: comment text under a field. */
fun ClientFonts.small(color: Color = Islands.text) =
  label(color).copy(fontSize = (Islands.UI_FONT_SIZE - 2).sp)

/** `JBFont.h4()` = `label().biggerOn(1).asBold()`. */
fun ClientFonts.h4(color: Color = Islands.text) =
  label(color).copy(fontSize = (Islands.UI_FONT_SIZE + 1).sp, fontWeight = FontWeight.Bold)

/** `JBFont.h3()` = `label().biggerOn(3)`. */
fun ClientFonts.h3(color: Color = Islands.text) =
  label(color).copy(fontSize = (Islands.UI_FONT_SIZE + 3).sp)

/** The editor font at the user's size (JetBrains Mono 12), for code and paths. */
fun ClientFonts.editor(color: Color = Islands.text) =
  TextStyle(color = color, fontFamily = editor, fontSize = Islands.EDITOR_FONT_SIZE.sp)
