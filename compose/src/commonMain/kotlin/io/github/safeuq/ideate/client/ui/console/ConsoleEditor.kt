package io.github.safeuq.ideate.client.ui.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.safeuq.ideate.client.session.ConsoleText
import io.github.safeuq.ideate.client.session.EditorFile
import io.github.safeuq.ideate.client.session.EditorKind
import io.github.safeuq.ideate.client.session.EditorScheme
import io.github.safeuq.ideate.client.ui.EditorActions
import io.github.safeuq.ideate.client.ui.EditorView
import io.github.safeuq.ideate.client.ui.SidewaysScroll

/**
 * A backend console (Run, Build Output) in IntelliJ's console editor, `ConsoleViewUtil.
 * setupConsoleEditor` (docs/terminal-and-consoles-spec.md 5.1). [kernelEditorId] is the console's
 * id from its text control, e.g. `g9ojv29tmj8vgr2tv7gf`.
 *
 * ponytail: the editor's font, not the scheme's console font (IntelliJ's `CONSOLE_FONT`); lift when
 * `EditorScheme` reads the console font.
 */
@Composable
fun ConsoleEditor(
  kernelEditorId: String,
  console: ConsoleText,
  scheme: EditorScheme,
  modifier: Modifier = Modifier,
  /** Text Ideate copied, e.g. for the backend's clipboard; nothing needs it for a console yet. */
  copied: (String) -> Unit = {},
  /**
   * The sideways scroll of the tool window the console is in, so the phone's page swiper lets a
   * sideways swipe scroll a long line instead of turning the page (ClientScreen asks it).
   */
  scroll: SidewaysScroll = remember { SidewaysScroll() },
) {
  // The console is read-only, but its text can be selected and copied: the selection lives here
  // (an editor of a file keeps it in its edit session).
  var selection by remember(kernelEditorId) { mutableStateOf(0 to 0) }
  val file =
    EditorFile(
      path = "console:$kernelEditorId",
      text = console.text,
      highlights = console.highlights,
      indentGuides = emptyList(),
      caret = selection.first,
      selectionStart = selection.second,
      selectionEnd = selection.first,
    )
  EditorView(
    file,
    scheme,
    EditorActions(
      type = {},
      key = {},
      moveCaret = { offset, anchor ->
        selection =
          offset.coerceIn(0, console.text.length) to anchor.coerceIn(0, console.text.length)
      },
      undo = {},
      redo = {},
      copied = copied,
    ),
    modifier,
    scroll = scroll,
    kind = EditorKind.CONSOLE,
  )
}
