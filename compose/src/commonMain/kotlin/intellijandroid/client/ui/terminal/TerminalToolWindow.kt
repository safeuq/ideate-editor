package intellijandroid.client.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState
import intellijandroid.client.session.terminal.TerminalKeyInput
import intellijandroid.client.session.terminal.TerminalTabState
import intellijandroid.client.ui.TabCloseButton
import intellijandroid.client.ui.component.IconButton
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * Which terminal tab shows. It lives above the window so the tabs in the header and the terminal in
 * the content agree.
 */
class TerminalTabsState {
  var selectedId by mutableStateOf<String?>(null)
  internal var knownIds = emptySet<String>()

  /** The selected tab; the newest one when none is (or the selected one closed). */
  fun selected(tabs: List<TerminalTabState>) =
    tabs.firstOrNull { it.id == selectedId } ?: tabs.lastOrNull()
}

/**
 * The tabs of the Terminal window as IntelliJ's new UI puts them: in the tool window's header,
 * after the title: a chip per shell with its close button (shown on the selected tab and under the
 * pointer), then New Tab (+). Closing the last tab hides the window, as IntelliJ does ([onHide]).
 * ponytail: no ⌄ (the list of predefined shells) after +; the backend sends no shell list yet.
 */
@Composable
fun TerminalHeaderTabs(
  state: SessionState,
  session: ClientSession,
  tabsState: TerminalTabsState,
  onHide: () -> Unit,
) {
  val tabs = state.terminals
  val selected = tabsState.selected(tabs)
  val fonts = LocalClientFonts.current
  Row(verticalAlignment = Alignment.CenterVertically) {
    Row(
      Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (tab in tabs) {
        val isSelected = tab.id == selected?.id
        val pill = RoundedCornerShape(IslandsDark.tabRadius)
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
          Modifier.height(IslandsDark.tabHeight)
            .clip(pill)
            .then(
              if (isSelected)
                Modifier.background(IslandsDark.tabSelectedBackground)
                  .border(1.dp, IslandsDark.tabSelectedBorder, pill)
              else Modifier,
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null) { tabsState.selectedId = tab.id }
            .padding(start = 8.dp, end = 4.dp),
        ) {
          Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            BasicText(tab.title, style = fonts.label(), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(2.dp))
            if (isSelected || hovered)
              TabCloseButton(tab.title) {
                session.closeTerminal(tab.id)
                if (tabs.size == 1) onHide()
              }
            else Spacer(Modifier.width(20.dp))
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
}

/**
 * The Terminal tool window's content (spec 2.2): the selected terminal. The tabs are in the header
 * ([TerminalHeaderTabs]). A window with no tab opens one. [focus] tells the keyboard bar which
 * terminal has the keyboard.
 */
@Composable
fun TerminalToolWindow(
  state: SessionState,
  session: ClientSession,
  focus: TerminalFocus,
  tabsState: TerminalTabsState,
  modifier: Modifier = Modifier,
) {
  val tabs = state.terminals
  // A tab that opens is shown.
  LaunchedEffect(tabs.map { it.id }) {
    val ids = tabs.map { it.id }
    ids.lastOrNull { it !in tabsState.knownIds }?.let { tabsState.selectedId = it }
    tabsState.knownIds = ids.toSet()
  }
  // The bar button is there before the project is bound; the first tab opens once it is.
  LaunchedEffect(state.terminalsReady) {
    if (state.terminalsReady && state.terminals.isEmpty()) session.openTerminal()
  }

  val selected = tabsState.selected(tabs)
  // Clipped to its own area: nothing of the terminal may draw over the header above it.
  Box(modifier.fillMaxSize().clipToBounds()) {
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
