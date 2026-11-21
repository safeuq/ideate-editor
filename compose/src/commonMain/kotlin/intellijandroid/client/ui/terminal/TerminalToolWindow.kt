package intellijandroid.client.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState
import intellijandroid.client.session.terminal.TerminalKeyInput
import intellijandroid.client.ui.TabCloseButton
import intellijandroid.client.ui.component.IconButton
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * The Terminal tool window's content (spec 2.2): a row of tabs, one per shell, each with a close
 * button, a New Tab button (+) after them, and the selected terminal under it. A window with no tab
 * opens one; closing the last tab hides the window, as IntelliJ does. [onHide] hides it. [focus]
 * tells the keyboard bar which terminal has the keyboard.
 */
@Composable
fun TerminalToolWindow(
  state: SessionState,
  session: ClientSession,
  focus: TerminalFocus,
  onHide: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tabs = state.terminals
  var selectedId by remember { mutableStateOf<String?>(null) }
  var knownIds by remember { mutableStateOf(emptySet<String>()) }
  // A tab that opens is shown; the window opened with none opens the first.
  LaunchedEffect(tabs.map { it.id }) {
    val ids = tabs.map { it.id }
    ids.lastOrNull { it !in knownIds }?.let { selectedId = it }
    knownIds = ids.toSet()
  }
  // The bar button is there before the project is bound; the first tab opens once it is.
  LaunchedEffect(state.terminalsReady) {
    if (state.terminalsReady && state.terminals.isEmpty()) session.openTerminal()
  }

  val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.lastOrNull()
  val fonts = LocalClientFonts.current
  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(IslandsDark.tabRowHeight),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        Modifier.weight(1f, fill = false)
          .horizontalScroll(rememberScrollState())
          .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (tab in tabs) {
          val isSelected = tab.id == selected?.id
          val pill = RoundedCornerShape(IslandsDark.tabRadius)
          Box(
            Modifier.height(IslandsDark.tabHeight)
              .clip(pill)
              .then(
                if (isSelected)
                  Modifier.background(IslandsDark.tabSelectedBackground)
                    .border(1.dp, IslandsDark.tabSelectedBorder, pill)
                else Modifier,
              )
              .clickable { selectedId = tab.id }
              .padding(start = 8.dp, end = 4.dp),
          ) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
              BasicText(tab.title, style = fonts.label(), maxLines = 1, softWrap = false)
              Spacer(Modifier.width(2.dp))
              TabCloseButton(tab.title) {
                session.closeTerminal(tab.id)
                if (tabs.size == 1) onHide()
              }
            }
          }
        }
      }
      IconButton(
        "expui/general/add.svg",
        "New Tab",
        onClick = { if (state.terminalsReady) session.openTerminal() },
        modifier = Modifier.padding(horizontal = 4.dp),
        size = IslandsDark.tabHeight,
      )
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
      val scheme = state.scheme
      if (selected != null && scheme != null)
        // One view per tab, so a tab keeps its own scroll, selection and focus.
        key(selected.id) {
          val input =
            remember(selected.id) {
              TerminalKeyInput(
                sendText = { text -> session.terminalText(selected.id, text) },
                sendKey = { keyCode, modifiers ->
                  session.terminalKey(selected.id, keyCode, modifiers)
                },
              )
            }
          val actions =
            remember(selected.id) {
              TerminalActions(
                resize = { columns, rows -> session.resizeTerminal(selected.id, columns, rows) },
                paste = { text -> session.pasteToTerminal(selected.id, text) },
                clearBuffer = { session.clearTerminalBuffer(selected.id) },
              )
            }
          TerminalView(selected, scheme, input, actions, focus, Modifier.fillMaxSize())
        }
    }
  }
}
