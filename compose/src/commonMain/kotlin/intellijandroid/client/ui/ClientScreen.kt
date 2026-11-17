package intellijandroid.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import intellijandroid.client.ui.theme.medium
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Only common Compose APIs here (no AWT, no desktop-only modules), so this screen can move to
// Android as is.

/** Phone or tablet, upright or sideways (docs/mobile-layout.md). */
enum class ScreenLayout {
  PHONE_PORTRAIT,
  TABLET_PORTRAIT,
  LANDSCAPE,
}

/**
 * Android's tablet line: smallest width 600 dp (`sw600dp`). Measured on the window, so a desktop
 * window or a split-screen app picks its layout from its own size.
 */
fun screenLayout(widthDp: Float, heightDp: Float) =
  when {
    widthDp >= heightDp -> ScreenLayout.LANDSCAPE
    widthDp >= 600f -> ScreenLayout.TABLET_PORTRAIT
    else -> ScreenLayout.PHONE_PORTRAIT
  }

/** Phone portrait shows one of these at a time; swipes move between them. */
private enum class Page {
  LEFT,
  EDITOR,
  RIGHT,
}

/**
 * The main window: main toolbar, tool window bar, the editor island and open tool windows, status
 * line below.
 */
@Composable
fun ClientScreen(state: SessionState, session: ClientSession) {
  val fonts = LocalClientFonts.current
  var notice by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(notice) {
    if (notice != null) {
      delay(3000)
      notice = null
    }
  }
  val unsupported = { what: String -> notice = "$what isn't supported in this client yet" }

  BoxWithConstraints(Modifier.fillMaxSize().background(IslandsDark.windowBackground)) {
    // The backend's dialogs (Rename, New, Delete, …): windows of their own (docs/lux-protocol.md).
    intellijandroid.client.ui.lux.LuxDialogs(state.luxDialogs, session)
    val layout = screenLayout(maxWidth.value, maxHeight.value)
    val windows = remember { ToolWindowState(left = ToolWindow.PROJECT, right = null) }
    var page by remember { mutableStateOf(Page.EDITOR) }
    // One per screen, shared by the layouts: the pager asks it whether a sideways swipe scrolls the
    // editor or switches to a tool window.
    val editorScroll = remember { SidewaysScroll() }
    // One per tool window, so each keeps its position; the pager asks the one on screen.
    val windowScrolls = remember { ToolWindow.entries.associateWith { SidewaysScroll() } }
    // Phone portrait starts on the editor; the other layouts start with Project open, like
    // IntelliJ.
    LaunchedEffect(layout) { if (layout == ScreenLayout.PHONE_PORTRAIT) page = Page.EDITOR }
    // A tool window covering the editor takes the keyboard away with it.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(page) { if (page != Page.EDITOR) focusManager.clearFocus() }
    // Back from a tool window page (phone held upright) returns to the editor first.
    LocalBackHandler.current(page != Page.EDITOR) { page = Page.EDITOR }

    fun barClick(window: ToolWindow) {
      if (!window.supported) return unsupported(window.title)
      if (layout != ScreenLayout.PHONE_PORTRAIT) return windows.toggle(window)
      val target = if (window.side == Side.LEFT) Page.LEFT else Page.RIGHT
      val current = if (window.side == Side.LEFT) windows.lastLeft else windows.lastRight
      page = if (page == target && current == window) Page.EDITOR else target
      windows.open(window)
    }
    val open =
      when (layout) {
        ScreenLayout.PHONE_PORTRAIT ->
          setOfNotNull(
            windows.lastLeft.takeIf { page == Page.LEFT },
            windows.lastRight.takeIf { page == Page.RIGHT },
          )
        else -> setOfNotNull(windows.left, windows.right)
      }

    // The backend builds the Structure tree only while the tool window shows.
    val structureShown = ToolWindow.STRUCTURE in open
    LaunchedEffect(structureShown) { session.setStructureVisible(structureShown) }

    @Composable
    fun Window(window: ToolWindow, onBack: (() -> Unit)?, modifier: Modifier) =
      ToolWindowPanel(window, onBack, modifier.island()) {
        ToolWindowContent(
          window,
          state,
          session,
          windowScrolls.getValue(window),
          onNavigated = { onBack?.invoke() },
        )
      }

    Column(Modifier.fillMaxSize()) {
      MainToolbar(state.projectName, unsupported)
      when (layout) {
        ScreenLayout.PHONE_PORTRAIT -> {
          SwipePager(
            page = page,
            onPage = { page = it },
            canScroll = { onPage, dragX ->
              when (onPage) {
                Page.LEFT -> windowScrolls.getValue(windows.lastLeft).canScroll(dragX)
                Page.EDITOR -> editorScroll.canScroll(dragX)
                Page.RIGHT -> windowScrolls.getValue(windows.lastRight).canScroll(dragX)
              }
            },
            left = {
              Window(windows.lastLeft, onBack = { page = Page.EDITOR }, Modifier.fillMaxSize())
            },
            editor = { EditorIsland(state, session, editorScroll, Modifier.fillMaxSize()) },
            right = {
              Window(windows.lastRight, onBack = { page = Page.EDITOR }, Modifier.fillMaxSize())
            },
            modifier =
              Modifier.weight(1f).fillMaxWidth().padding(horizontal = IslandsDark.islandGap),
          )
          ToolWindowBar(open, ::barClick, vertical = false, Modifier.fillMaxWidth())
        }
        ScreenLayout.TABLET_PORTRAIT -> {
          Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = IslandsDark.islandGap)) {
            EditorIsland(state, session, editorScroll, Modifier.weight(1f).fillMaxWidth())
            // Open tool windows share one area under the editor, side by side.
            if (open.isNotEmpty()) {
              Spacer(Modifier.height(IslandsDark.islandGap))
              Row(Modifier.weight(0.8f).fillMaxWidth()) {
                listOfNotNull(windows.left, windows.right).forEachIndexed { index, window ->
                  if (index > 0) Spacer(Modifier.width(IslandsDark.islandGap))
                  Window(window, onBack = null, Modifier.weight(1f).fillMaxHeight())
                }
              }
            }
          }
          ToolWindowBar(open, ::barClick, vertical = false, Modifier.fillMaxWidth())
        }
        ScreenLayout.LANDSCAPE ->
          Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolWindowBar(open, ::barClick, vertical = true, Modifier.fillMaxHeight())
            // Open tool windows share one column left of the editor, one above the other.
            if (open.isNotEmpty()) {
              Column(Modifier.width(300.dp).fillMaxHeight()) {
                listOfNotNull(windows.left, windows.right).forEachIndexed { index, window ->
                  if (index > 0) Spacer(Modifier.height(IslandsDark.islandGap))
                  Window(window, onBack = null, Modifier.weight(1f).fillMaxWidth())
                }
              }
              Spacer(Modifier.width(IslandsDark.islandGap))
            }
            EditorIsland(
              state,
              session,
              editorScroll,
              Modifier.weight(1f).fillMaxHeight().padding(end = IslandsDark.islandGap),
            )
          }
      }
      val status = notice ?: state.failure ?: state.log.lastOrNull().orEmpty()
      BasicText(
        status,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style =
          fonts.medium(
            if (notice == null && state.failure != null) IslandsDark.textError
            else IslandsDark.textSecondary,
          ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
      )
    }
    // Over everything: the backend's BeControl popups (New ▸ File's name).
    intellijandroid.client.ui.popup.BackendPopups(state.backendPopups, session)
  }
}

private fun Modifier.island() =
  clip(RoundedCornerShape(IslandsDark.islandRadius)).background(IslandsDark.islandBackground)

@Composable
private fun ToolWindowContent(
  window: ToolWindow,
  state: SessionState,
  session: ClientSession,
  scroll: SidewaysScroll,
  onNavigated: () -> Unit,
) =
  when (window) {
    ToolWindow.PROJECT ->
      ProjectTreeView(
        tree = state.tree,
        onToggle = { session.toggle(it.id) },
        onSelect = { session.select(it.id) },
        // Opening a file also slides back to the editor on a phone held upright.
        onOpen = { node ->
          node.path
            ?.takeIf { !node.isDirectory }
            ?.let { path ->
              session.open(path)
              onNavigated()
            }
        },
        modifier = Modifier.fillMaxSize(),
        scroll = scroll,
        onMenu = { session.projectMenu(it.id) },
        menuEntries = { state.contextMenu?.entries },
      )
    ToolWindow.STRUCTURE ->
      StructureView(state, session, onNavigated, Modifier.fillMaxSize(), scroll)
    else -> Unit
  }

/**
 * Phone portrait: left tool window, editor and right tool window side by side, one screen wide
 * each; the content follows the finger (dragging right reveals the left window). From the editor a
 * swipe opens either side; from a tool window only the swipe back to the editor works. Horizontal
 * drags are taken before the editor sees them, so selecting text by dragging sideways doesn't work
 * in this layout.
 */
@Composable
private fun SwipePager(
  page: Page,
  onPage: (Page) -> Unit,
  /**
   * Whether a sideways drag of this many px would scroll the content of this page
   * (SidewaysScroll.canScroll): the editor's text or a tool window's tree.
   */
  canScroll: (Page, Float) -> Boolean,
  left: @Composable () -> Unit,
  editor: @Composable () -> Unit,
  right: @Composable () -> Unit,
  modifier: Modifier,
) {
  BoxWithConstraints(modifier.clipToBounds()) {
    // One page is a screen wide plus the gap between islands, so neighbouring islands keep their
    // gap while sliding.
    val width =
      constraints.maxWidth.toFloat() + with(LocalDensity.current) { IslandsDark.islandGap.toPx() }
    fun offsetOf(target: Page) =
      when (target) {
        Page.LEFT -> width
        Page.EDITOR -> 0f
        Page.RIGHT -> -width
      }
    val offset = remember { Animatable(offsetOf(page)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page, width) { offset.animateTo(offsetOf(page)) }

    Box(
      Modifier.fillMaxSize().pointerInput(page, width) {
        val range =
          when (page) {
            Page.LEFT -> 0f..width
            Page.EDITOR -> -width..width
            Page.RIGHT -> -width..0f
          }
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          val velocity = VelocityTracker()
          var travelled = Offset.Zero
          var swiping = false
          while (true) {
            val change =
              awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            if (!swiping) {
              travelled += delta
              if (travelled.getDistance() < viewConfiguration.touchSlop) continue
              // Mostly sideways: a swipe. Anything else belongs to the content (scrolling, taps).
              if (abs(travelled.x) < 2 * abs(travelled.y)) return@awaitEachGesture
              // A sideways swipe scrolls the page's content (editor text, tool window tree) unless
              // it is already at that edge. Decided once here, so one swipe never turns from
              // scrolling into switching.
              if (canScroll(page, travelled.x)) return@awaitEachGesture
              swiping = true
            }
            change.consume()
            velocity.addPosition(change.uptimeMillis, change.position)
            scope.launch { offset.snapTo((offset.value + delta.x).coerceIn(range)) }
          }
          if (!swiping) return@awaitEachGesture
          // Past a third of the screen, or a quick flick, moves one page; otherwise it springs
          // back.
          val flick = velocity.calculateVelocity().x
          val moved = offset.value - offsetOf(page)
          val target =
            when {
              moved > width / 3 || flick > 1000f ->
                if (page == Page.RIGHT) Page.EDITOR else Page.LEFT
              moved < -width / 3 || flick < -1000f ->
                if (page == Page.LEFT) Page.EDITOR else Page.RIGHT
              else -> page
            }.takeIf { offsetOf(it) in range } ?: page
          if (target == page) scope.launch { offset.animateTo(offsetOf(page)) } else onPage(target)
        }
      },
    ) {
      listOf(Page.LEFT to left, Page.EDITOR to editor, Page.RIGHT to right).forEach {
        (target, content) ->
        val base = -offsetOf(target)
        Box(Modifier.fillMaxSize().offset { IntOffset((base + offset.value).roundToInt(), 0) }) {
          content()
        }
      }
    }
  }
}

@Composable
private fun EditorIsland(
  state: SessionState,
  session: ClientSession,
  editorScroll: SidewaysScroll,
  modifier: Modifier,
) {
  val fonts = LocalClientFonts.current
  // What the backend copies (ClipboardModel) goes to the system clipboard, as in JetBrains Client.
  val systemClipboard = LocalClipboardManager.current
  LaunchedEffect(state.backendClipboard) {
    state.backendClipboard?.let { systemClipboard.setText(AnnotatedString(it.text)) }
  }
  Column(modifier.island()) {
    val selected = state.editors.firstOrNull { it.path == state.selectedEditor }
    EditorTabs(state, session)
    val scheme = state.scheme
    if (selected != null && scheme != null) {
      val actions =
        remember(session) {
          EditorActions(
            session::type,
            session::key,
            { offset, anchor -> session.moveCaret(offset, anchor) },
            session::undo,
            session::redo,
            session::complete,
            session::lookupKey,
            session::chooseLookupItem,
            session::paste,
            session::pasteSimple,
            session::copied,
            session::editorMenu,
          )
        }
      EditorView(
        selected,
        scheme,
        actions,
        Modifier.fillMaxSize(),
        state.lookup?.takeIf { it.path == selected.path },
        editorScroll,
        backendMenu = { state.contextMenu?.entries },
      )
    } else {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
          if (state.tree.nodes.isEmpty()) "Connecting…"
          else "Double-tap a file in the Project tool window to open it",
          style = fonts.label(IslandsDark.textSecondary),
          modifier = Modifier.padding(16.dp),
        )
      }
    }
  }
}

/**
 * Editor tabs: file icon, name and a close button; the selected one is a rounded "pill" like
 * Islands Dark (measured on the real client). IntelliJ shows the close button on every tab, on the
 * right, by default (`UISettings.showCloseButton`, `closeTabButtonOnTheRight`).
 */
@Composable
private fun EditorTabs(state: SessionState, session: ClientSession) {
  if (state.editors.isEmpty()) return
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val clipboard = LocalClipboardManager.current

  // IntelliJ's tab context menu (EditorTabPopupMenu); what IDEate can't do yet is greyed out.
  fun tabMenu(path: String, name: String): List<MenuEntry> {
    val paths = state.editors.map { it.path }
    val index = paths.indexOf(path)
    fun close(targets: List<String>) = targets.forEach(session::closeEditor)
    return listOf(
      MenuItem("Close", shortcut("⌘W", "Ctrl+F4")) { session.closeEditor(path) },
      MenuItem("Close Other Tabs", enabled = paths.size > 1) {
        session.selectEditor(path)
        close(paths - path)
      },
      MenuItem("Close All Tabs") { close(paths) },
      MenuItem("Close Tabs to the Left", enabled = index > 0) { close(paths.take(index)) },
      MenuItem("Close Tabs to the Right", enabled = index < paths.size - 1) {
        close(paths.drop(index + 1))
      },
      MenuSeparator,
      MenuItem("Pin Tab", enabled = false, iconPath = "expui/general/pin.svg"),
      MenuSeparator,
      MenuItem("Split Right", enabled = false, iconPath = "expui/general/splitVertically.svg"),
      MenuItem("Split Down", enabled = false, iconPath = "expui/general/splitHorizontally.svg"),
      MenuItem("Split and Move Right", enabled = false),
      MenuItem("Split and Move Down", enabled = false),
      MenuSeparator,
      MenuItem("Copy Absolute Path", shortcut("⇧⌘C", "Ctrl+Shift+C")) {
        clipboard.setText(AnnotatedString(path))
      },
      MenuItem("Copy File Name") { clipboard.setText(AnnotatedString(name)) },
      MenuItem("Select In…", shortcut("⌥F1", "Alt+F1"), enabled = false),
      SubMenu("Open In", emptyList(), enabled = false),
      MenuSeparator,
      MenuItem("Configure Editor Tabs…", enabled = false),
    )
  }

  Row(
    Modifier.fillMaxWidth().height(IslandsDark.tabRowHeight).padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (editor in state.editors) {
      val selected = editor.path == state.selectedEditor
      val iconPath =
        state.tree.nodes.values.firstOrNull { it.path == editor.path }?.iconPaths?.firstOrNull()
          ?: "expui/fileTypes/text.svg"
      val pill = RoundedCornerShape(IslandsDark.tabRadius)
      ContextMenuArea(entries = { tabMenu(editor.path, editor.name) }) {
        Box(
          Modifier.height(IslandsDark.tabHeight)
            .clip(pill)
            .then(
              if (selected)
                Modifier.background(IslandsDark.tabSelectedBackground)
                  .border(1.dp, IslandsDark.tabSelectedBorder, pill)
              else Modifier,
            )
            .clickable { session.selectEditor(editor.path) }
            .padding(start = 8.dp, end = 4.dp),
        ) {
          Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            icons.painter(iconPath)?.let { Image(it, null, Modifier.size(16.dp)) }
            Spacer(Modifier.width(6.dp))
            BasicText(
              editor.name,
              style = fonts.label(),
            )
            Spacer(Modifier.width(2.dp))
            TabCloseButton(editor.name) { session.closeEditor(editor.path) }
          }
        }
      }
    }
  }
}

/** IntelliJ's tab close button: `closeSmall`, `closeSmallHovered` under the pointer. */
@Composable
private fun TabCloseButton(fileName: String, onClose: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val icon =
    LocalIcons.current.painter(
      if (hovered) "expui/general/closeSmallHovered.svg" else "expui/general/closeSmall.svg",
    ) ?: return
  Box(
    Modifier.size(20.dp)
      .hoverable(interaction)
      .clickable(interactionSource = interaction, indication = null, onClick = onClose),
    contentAlignment = Alignment.Center,
  ) {
    Image(icon, contentDescription = "Close $fileName", modifier = Modifier.size(16.dp))
  }
}
