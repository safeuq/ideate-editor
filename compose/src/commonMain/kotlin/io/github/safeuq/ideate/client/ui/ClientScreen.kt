package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.SearchTab
import io.github.safeuq.ideate.client.session.SessionState
import io.github.safeuq.ideate.client.ui.component.IconButton
import io.github.safeuq.ideate.client.ui.component.SearchHistory
import io.github.safeuq.ideate.client.ui.component.SplitPane
import io.github.safeuq.ideate.client.ui.lux.LuxPaneView
import io.github.safeuq.ideate.client.ui.terminal.TerminalFocus
import io.github.safeuq.ideate.client.ui.terminal.TerminalHeaderTabs
import io.github.safeuq.ideate.client.ui.terminal.TerminalKeyBar
import io.github.safeuq.ideate.client.ui.terminal.TerminalTabsState
import io.github.safeuq.ideate.client.ui.terminal.TerminalToolWindow
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label
import kotlinx.coroutines.delay

// Only common Compose APIs here (no AWT, no desktop-only modules), so this screen can move to
// Android as is.

/** Phone upright shows one of these at a time; swipes move between them. */
private enum class Page {
  LEFT,
  /** The editor, or the bottom tool windows in its place. */
  MIDDLE,
  RIGHT,
}

private fun Side.page() =
  when (this) {
    Side.LEFT -> Page.LEFT
    Side.RIGHT -> Page.RIGHT
    Side.BOTTOM -> Page.MIDDLE
  }

/**
 * The main window: main toolbar, tool window bars, the editor island and open tool windows, status
 * line below. How they are arranged depends on the window's shape ([screenLayout],
 * docs/mobile-layout.md).
 */
@Composable
fun ClientScreen(
  state: SessionState,
  session: ClientSession,
  /**
   * Keeps the content clear of the system bars (Android), applied inside the background: the window
   * colour and the project gradient run up behind the status bar from the screen's top, as the
   * gradient runs behind the merged title bar on macOS.
   */
  insets: Modifier = Modifier,
  /** The open tool windows; the caller keeps it to save the layout (`ConnectController`). */
  windows: ToolWindowState = remember { ToolWindowState() },
) {
  val fonts = LocalClientFonts.current
  var notice by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(notice) {
    if (notice != null) {
      delay(3000)
      notice = null
    }
  }
  val unsupported = { what: String -> notice = "$what isn't supported in this client yet" }

  // Where the project gradient peaks: the project widget's icon, once laid out (IntelliJ's default
  // before that is 150).
  var gradientCenter by remember { mutableFloatStateOf(Float.NaN) }
  val projectColor = state.projectName?.let(::projectGradientColor)
  val doubleShift = remember { DoubleShift() }
  // Called by both key modifiers below; true when the event opens Search Everywhere.
  val onDoubleShiftKey =
    onDoubleShiftKey@{ event: androidx.compose.ui.input.key.KeyEvent, beforeSoftKeyboard: Boolean ->
      val search = session.searchEverywhere ?: return@onDoubleShiftKey false
      if (state.search != null) return@onDoubleShiftKey false
      val shift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
      val down = event.type == KeyEventType.KeyDown
      if (!doubleShift.onKey(shift, down, System.currentTimeMillis(), beforeSoftKeyboard))
        return@onDoubleShiftKey false
      search.open(SearchTab.FILES)
      true
    }
  // Search Everywhere's earlier searches, per tab, for as long as this screen lives.
  val searchHistories = remember { mutableMapOf<SearchTab, SearchHistory>() }
  BoxWithConstraints(
    Modifier.fillMaxSize()
      // On Android, while the editor or the terminal takes text, a key goes to the keyboard app
      // before onPreviewKeyEvent: the keyboard app keeps `4` of a typed `$` (and commits "$") but
      // passes its Shift on, so two `$` looked like a double Shift. This sees every key first.
      .onPreInterceptKeyBeforeSoftKeyboard { event ->
        onDoubleShiftKey(event, true)
      }
      .onPreviewKeyEvent { event ->
        if (onDoubleShiftKey(event, false)) return@onPreviewKeyEvent true
        val search = session.searchEverywhere ?: return@onPreviewKeyEvent false
        if (state.search != null) return@onPreviewKeyEvent false
        val down = event.type == KeyEventType.KeyDown
        // IntelliJ's Go to Class / File / Symbol: ⌘O, ⇧⌘O, ⌥⌘O (Ctrl+N, Ctrl+Shift+N,
        // Ctrl+Alt+Shift+N elsewhere).
        val command = if (IS_MAC) event.isMetaPressed else event.isCtrlPressed
        val key = if (IS_MAC) Key.O else Key.N
        if (!down || !command || event.key != key) return@onPreviewKeyEvent false
        search.open(
          when {
            event.isAltPressed -> SearchTab.SYMBOLS
            event.isShiftPressed -> SearchTab.FILES
            else -> SearchTab.CLASSES
          },
        )
        true
      }
      .background(Islands.windowBackground)
      .drawBehind {
        if (projectColor != null) {
          val center = if (gradientCenter.isNaN()) 150.dp.toPx() else gradientCenter
          drawProjectGradient(Islands.windowBackground, projectColor, center)
        }
      }
      .then(insets),
  ) {
    // The backend's dialogs (Rename, New, Delete, …): windows of their own (docs/lux-protocol.md).
    io.github.safeuq.ideate.client.ui.lux.LuxDialogs(state.luxDialogs, session)
    val layout = screenLayout(maxWidth.value, maxHeight.value)
    // A phone, not a tablet: narrower than Android's tablet line (sw600dp), as in screenLayout.
    val phoneWidth = maxWidth.value < 600f
    var page by remember { mutableStateOf(Page.MIDDLE) }
    var weights by rememberPaneWeights(session)
    // One per screen, shared by the layouts: the pager asks it whether a sideways swipe scrolls the
    // editor or switches to a tool window.
    val editorScroll = remember { SidewaysScroll() }
    val tabRow = remember { TabRowScroll() }
    // One per tool window, so each keeps its position; the pager asks the ones on screen.
    val windowScrolls = remember { mutableMapOf<ToolWindow, SidewaysScroll>() }
    fun scrollOf(window: ToolWindow) = windowScrolls.getOrPut(window) { SidewaysScroll() }
    val buttons = remember(state.toolWindows) { toolWindowButtons(state.toolWindows) }
    // After a rotation: keep what the new layout can show; a phone held upright starts on the
    // editor.
    LaunchedEffect(layout) {
      windows.fit(layout)
      if (layout == ScreenLayout.PORTRAIT) page = Page.MIDDLE
    }
    // Phone upright: the middle page shows the bottom windows instead of the editor while any is
    // open.
    val bottomShown = layout == ScreenLayout.PORTRAIT && windows.openOn(Side.BOTTOM).isNotEmpty()
    val editorCovered = layout == ScreenLayout.PORTRAIT && (page != Page.MIDDLE || bottomShown)
    // A tool window covering the editor takes the keyboard away with it.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(editorCovered) { if (editorCovered) focusManager.clearFocus() }

    // Phone upright, back to the editor: from a side page to the middle; from the bottom windows,
    // closes them.
    fun backToEditor() {
      if (page != Page.MIDDLE) page = Page.MIDDLE else windows.closeSide(Side.BOTTOM)
    }
    LocalBackHandler.current(editorCovered) { backToEditor() }

    // Closes [window]; on a phone upright the last one of a side page takes you back to the middle.
    fun closeWindow(window: ToolWindow) {
      windows.close(window)
      if (
        layout == ScreenLayout.PORTRAIT &&
          window.place.side.page() != Page.MIDDLE &&
          windows.openOn(window.place.side).isEmpty()
      )
        page = Page.MIDDLE
    }

    fun barClick(window: ToolWindow) {
      if (layout != ScreenLayout.PORTRAIT) return windows.toggle(window, layout)
      val target = window.place.side.page()
      when {
        // Open on another page: show that page.
        windows.isOpen(window) && page != target -> page = target
        // On screen: close it.
        windows.isOpen(window) -> closeWindow(window)
        else -> {
          windows.open(window, layout)
          page = target
        }
      }
    }
    // The windows on screen, whose buttons are highlighted.
    val shown =
      windows.open.values
        .filter { layout != ScreenLayout.PORTRAIT || it.place.side.page() == page }
        .toSet()

    // The backend builds the Structure tree only while the tool window shows. It hears of a hide
    // 30 s late: on a phone held upright a swipe to the editor and back would otherwise make the
    // phone's backend build the whole tree again.
    val structureShown = ToolWindow.STRUCTURE in shown
    LaunchedEffect(structureShown) {
      if (!structureShown) delay(STRUCTURE_HIDE_DELAY_MS)
      session.setStructureVisible(structureShown)
    }
    // And builds a backend tool window's content (Gradle's tree and toolbar) only while it shows.
    val backendShown =
      shown
        .map { it.id }
        .filter {
          it != ToolWindow.PROJECT.id &&
            it != ToolWindow.STRUCTURE.id &&
            it != ToolWindow.TERMINAL.id
        }
        .toSet()
    LaunchedEffect(backendShown) { session.setBackendToolWindowsShown(backendShown) }
    // The backend asks for a window to show, e.g. Run when a run starts (IntelliJ's "Activate tool
    // window"), or to hide, e.g. Build when its last tab closed. The window's own button does the
    // opening.
    // The request can come before the window has its button, so it waits for the button.
    var handledRequest by remember { mutableStateOf(0) }
    LaunchedEffect(state.showRequest, buttons) {
      val request = state.showRequest ?: return@LaunchedEffect
      if (request.serial == handledRequest) return@LaunchedEffect
      val window =
        buttons.byPlace.values.flatten().firstOrNull { it.id == request.windowId }
          ?: return@LaunchedEffect
      handledRequest = request.serial
      if (request.show && window !in shown) barClick(window)
      if (!request.show && windows.isOpen(window)) closeWindow(window)
    }

    // Opening a file from a tool window shows the editor on a phone held upright.
    fun onNavigated() {
      if (layout != ScreenLayout.PORTRAIT) return
      page = Page.MIDDLE
      windows.closeSide(Side.BOTTOM)
    }

    // Which terminal has the keyboard, for the keyboard bar under the tool window bar.
    val terminalFocus = remember { TerminalFocus() }
    val terminalTabs = remember { TerminalTabsState() }

    // Structure's folded rows, here for its header's Expand All and Collapse All.
    var structureCollapsed by remember { mutableStateOf(emptySet<String>()) }

    // The header buttons IntelliJ shows; the backend sends none for these two windows
    // (BeIdeaToolWindow.headerToolbar is empty), JetBrains Client draws them itself.
    fun headerActions(window: ToolWindow): List<HeaderAction> =
      when {
        window == ToolWindow.PROJECT &&
          state.projectViews.current.let { it == null || it.paneId == PROJECT_PANE } ->
          listOf(
            HeaderAction("expui/general/locate.svg", "Select Opened File") {
              session.selectOpenedFile()
            },
            HeaderAction("expui/general/expandAll.svg", "Expand Selected") {
              session.expandSelected()
            },
            HeaderAction("expui/general/collapseAll.svg", "Collapse All") {
              session.collapseProjectTree()
            },
          )
        window == ToolWindow.STRUCTURE ->
          listOf(
            HeaderAction("expui/general/expandAll.svg", "Expand All") {
              structureCollapsed = emptySet()
            },
            HeaderAction("expui/general/collapseAll.svg", "Collapse All") {
              structureCollapsed = allStructureParents(state.structure)
            },
          )
        else -> emptyList()
      }

    @Composable
    fun Window(window: ToolWindow, modifier: Modifier) {
      // Project's header picks the view: Project, Packages, Project Files, ….
      val views = state.projectViews.takeIf { window == ToolWindow.PROJECT && it.options.size > 1 }
      ToolWindowPanel(
        window,
        headerLine =
          headerLineShown(
            bottom = window.place.side == Side.BOTTOM,
            tabCount =
              if (window == ToolWindow.TERMINAL) state.terminals.size
              else state.backendViews[window.id]?.takeIf { it.panel != null }?.tabs?.size ?: 0,
            toolbar =
              state.backendViews[window.id]?.let { it.panel == null && it.toolbar.isNotEmpty() } ==
                true,
            portrait = layout == ScreenLayout.PORTRAIT,
          ),
        modifier.island(LocalIslandRadius.current),
        title = views?.current?.name ?: window.title,
        views =
          views?.options?.map { option ->
            MenuItem(
              option.name,
              iconPath = option.iconPath,
              onClick = { session.chooseProjectView(option) },
            )
          },
        headerContent =
          if (window == ToolWindow.TERMINAL) {
            { TerminalHeaderTabs(state, session, terminalTabs) { barClick(window) } }
          } else
            state.backendViews[window.id]
              ?.takeIf { it.panel != null }
              ?.let { view ->
                {
                  BackendHeaderTabs(
                    view,
                    onSelect = { session.selectBackendTab(window.id, it) },
                    onClose = { session.closeBackendTab(window.id, it) },
                  )
                }
              },
        actions = headerActions(window),
        // The window is on screen, so its stripe button's tap hides it (and on a phone held
        // upright, the last one of a side page goes back to the editor).
        onHide = { barClick(window) },
      ) {
        val scroll = scrollOf(window)
        Box(Modifier.fillMaxSize().onGloballyPositioned { scroll.bounds = it.boundsInRoot() }) {
          ToolWindowContent(
            window,
            state,
            session,
            scroll,
            onNavigated = ::onNavigated,
            structureCollapsed = structureCollapsed,
            onStructureCollapsedChange = { structureCollapsed = it },
            terminalFocus = terminalFocus,
            terminalTabs = terminalTabs,
            portrait = layout == ScreenLayout.PORTRAIT && phoneWidth,
          )
        }
      }
    }

    /**
     * The open windows of [side]: one fills the area; both halves share it, [stacked] one above the
     * other or side by side, split as the pane weights say.
     */
    @Composable
    fun Area(side: Side, stacked: Boolean, modifier: Modifier) {
      val (firstPlace, secondPlace) = Place.entries.filter { it.side == side }
      val first = windows.open[firstPlace]
      val second = windows.open[secondPlace]
      if (first != null && second != null) {
        SplitPane(
          stacked,
          proportion =
            when (side) {
              Side.LEFT -> weights.leftSplit
              Side.RIGHT -> weights.rightSplit
              Side.BOTTOM -> weights.bottomSplit
            },
          onProportion = {
            weights =
              when (side) {
                Side.LEFT -> weights.copy(leftSplit = it)
                Side.RIGHT -> weights.copy(rightSplit = it)
                Side.BOTTOM -> weights.copy(bottomSplit = it)
              }
          },
          modifier,
          first = { Window(first, Modifier.fillMaxSize()) },
          second = { Window(second, Modifier.fillMaxSize()) },
        )
      } else {
        (first ?: second)?.let { Window(it, modifier) }
      }
    }

    @Composable
    fun Editor(modifier: Modifier) =
      EditorIsland(
        state,
        session,
        editorScroll,
        tabRow,
        breadcrumbsFooter = layout == ScreenLayout.PORTRAIT,
        modifier,
      )

    Column(Modifier.fillMaxSize()) {
      MainToolbar(
        state.projectName,
        state.projectWidget,
        // Phone layouts have no project tab bar; a sideways drag that starts on this row slides
        // between the projects (EditorApp's SlidePager), so the row says where it is.
        modifier =
          LocalProjectSwipeArea.current?.let { area ->
            Modifier.onGloballyPositioned {
              val bounds = it.boundsInRoot()
              area.top = bounds.top
              area.bottom = bounds.bottom
            }
          } ?: Modifier,
        onSearch =
          if (state.searchAvailable) {
            { session.searchEverywhere?.open(SearchTab.FILES) }
          } else null,
        onProjectIconCenter = { gradientCenter = it },
        islandsStart =
          if (layout == ScreenLayout.PORTRAIT) Islands.islandGap else Islands.stripeThickness,
        back =
          if (editorCovered) ToolbarBack(pointsRight = page == Page.LEFT, onClick = ::backToEditor)
          else null,
        runSelector = state.runSelector,
        runButtons = state.runButtons,
        onRunButton = { actionId, bounds -> session.runToolbarAction(actionId, bounds) },
        onRunConfigurations = session::runConfigurationsMenu,
        runConfigurationEntries = state.contextMenu?.entries,
        onProject = session::projectWidgetClicked,
      )
      if (layout == ScreenLayout.WIDE && state.openProjects.size > 1)
        ProjectTabs(
          state.openProjects,
          state.shownProject,
          session::showProject,
          session::closeProject,
        )
      // Any open soft keyboard has a bottom inset. A floating one keeps only a strip for its back
      // and
      // switch buttons (about 50 dp on Gboard, Android 14); a docked one is 250 dp or more. Android
      // can't tell the two apart, so: docked when the keyboard reaches 100 dp above the navigation
      // bar. ponytail: a size rule; a docked keyboard under 100 dp would count as floating.
      val density = LocalDensity.current
      val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
      val keyboardDocked =
        WindowInsets.ime.exclude(WindowInsets.navigationBars).getBottom(density) >=
          with(density) { 100.dp.roundToPx() }
      when (layout) {
        ScreenLayout.PORTRAIT -> {
          // Only the window the drag started on: Project over Structure each scroll on their own.
          fun scrolls(side: Side, dragX: Float, down: Offset) =
            windows.openOn(side).any {
              val scroll = scrollOf(it)
              scroll.bounds.contains(down) && scroll.canScroll(dragX)
            }
          fun twoIslands(target: Page) =
            when (target) {
              Page.LEFT -> windows.openOn(Side.LEFT).size > 1
              Page.MIDDLE -> bottomShown && windows.openOn(Side.BOTTOM).size > 1
              Page.RIGHT -> windows.openOn(Side.RIGHT).size > 1
            }
          SlidePager(
            current = page,
            // A side page shows when it has windows open or to open again.
            neighbour = { step ->
              when (page) {
                Page.LEFT -> Page.MIDDLE.takeIf { step > 0 }
                Page.MIDDLE ->
                  if (step < 0) Page.LEFT.takeIf { windows.canShow(Side.LEFT) }
                  else Page.RIGHT.takeIf { windows.canShow(Side.RIGHT) }
                Page.RIGHT -> Page.MIDDLE.takeIf { step < 0 }
              }
            },
            order = { it.ordinal },
            onShow = { target ->
              // A swipe to an empty side page opens its last windows again.
              if (target != Page.MIDDLE)
                windows.reopen(if (target == Page.LEFT) Side.LEFT else Side.RIGHT)
              page = target
            },
            // A sideways swipe scrolls the page's content (the editor's text, its tab row or a
            // tool window's tree, SidewaysScroll.canScroll) unless it is already at that edge.
            canSlide = { down, dragX ->
              !when (page) {
                Page.LEFT -> scrolls(Side.LEFT, dragX, down)
                Page.MIDDLE ->
                  when {
                    bottomShown -> scrolls(Side.BOTTOM, dragX, down)
                    tabRow.contains(down) -> tabRow.canScroll(dragX)
                    else -> editorScroll.canScroll(dragX)
                  }
                Page.RIGHT -> scrolls(Side.RIGHT, dragX, down)
              }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            keep = Page.entries,
            // Two islands on a page (the side's halves, or both bottom windows) keep their gap and
            // corners.
            alwaysRounded = twoIslands(page),
          ) { target, radius ->
            // A page with two islands is always inset by the gap, at rest and while it slides, so
            // no page changes size during a swipe.
            val area =
              Modifier.fillMaxSize()
                .padding(horizontal = if (twoIslands(target)) Islands.islandGap else 0.dp)
            CompositionLocalProvider(LocalIslandRadius provides radius) {
              when (target) {
                Page.LEFT -> Area(Side.LEFT, stacked = true, area)
                Page.MIDDLE ->
                  if (bottomShown) Area(Side.BOTTOM, stacked = true, area)
                  else Editor(Modifier.fillMaxSize())
                Page.RIGHT -> Area(Side.RIGHT, stacked = true, area)
              }
            }
          }
          // A docked keyboard leaves little height, so the bar and the status bar give way to the
          // keyboard bar; a floating keyboard changes nothing.
          if (!keyboardDocked)
            ToolWindowBar(buttons, shown, ::barClick, vertical = false, Modifier.fillMaxWidth())
        }
        ScreenLayout.WIDE ->
          Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolWindowBar(
              buttons,
              shown,
              ::barClick,
              vertical = true,
              Modifier.fillMaxHeight(),
              top = listOf(Place.LEFT_TOP, Place.LEFT_BOTTOM),
              foot = listOf(Place.BOTTOM_LEFT),
            )
            // Left pane | editor | right pane, with the bottom area under all three, as IntelliJ's
            // default layout. Both sides open: left | (editor | right), so a drag on one border
            // leaves the other pane's width as it is (weights are of the whole width).
            @Composable
            fun Panes(modifier: Modifier) {
              val left = windows.openOn(Side.LEFT).isNotEmpty()
              val right = windows.openOn(Side.RIGHT).isNotEmpty()
              @Composable
              fun EditorAndRight(modifier: Modifier) {
                // Of the room right of the left pane (the whole width when there is no left pane).
                val rest = if (left) 1f - weights.left else 1f
                if (!right) Editor(modifier)
                else
                  SplitPane(
                    stacked = false,
                    proportion = ((rest - weights.right) / rest),
                    onProportion = { weights = weights.copy(right = rest * (1f - it)) },
                    modifier,
                    first = { Editor(Modifier.fillMaxSize()) },
                    second = { Area(Side.RIGHT, stacked = true, Modifier.fillMaxSize()) },
                  )
              }
              if (!left) EditorAndRight(modifier)
              else
                SplitPane(
                  stacked = false,
                  proportion = weights.left,
                  onProportion = { weights = weights.copy(left = it) },
                  modifier,
                  first = { Area(Side.LEFT, stacked = true, Modifier.fillMaxSize()) },
                  second = { EditorAndRight(Modifier.fillMaxSize()) },
                )
            }
            val main = Modifier.weight(1f).fillMaxHeight()
            if (windows.openOn(Side.BOTTOM).isEmpty()) Panes(main)
            else
              SplitPane(
                stacked = true,
                proportion = 1f - weights.bottom,
                onProportion = { weights = weights.copy(bottom = 1f - it) },
                main,
                first = { Panes(Modifier.fillMaxSize()) },
                second = { Area(Side.BOTTOM, stacked = false, Modifier.fillMaxSize()) },
              )
            ToolWindowBar(
              buttons,
              shown,
              ::barClick,
              vertical = true,
              Modifier.fillMaxHeight(),
              top = listOf(Place.RIGHT_TOP, Place.RIGHT_BOTTOM),
              foot = listOf(Place.BOTTOM_RIGHT),
              mirrored = true,
            )
          }
        ScreenLayout.COMPACT ->
          Row(Modifier.weight(1f).fillMaxWidth()) {
            // One bar with every group; left windows open left of the editor, right and bottom
            // ones right of it, at full height.
            ToolWindowBar(buttons, shown, ::barClick, vertical = true, Modifier.fillMaxHeight())
            val main = Modifier.weight(1f).fillMaxHeight().padding(end = Islands.islandGap)
            val window = Place.entries.firstNotNullOfOrNull { windows.open[it] }
            when {
              window == null -> Editor(main)
              window.place.side == Side.LEFT ->
                SplitPane(
                  stacked = false,
                  proportion = weights.compact,
                  onProportion = { weights = weights.copy(compact = it) },
                  main,
                  first = { Window(window, Modifier.fillMaxSize()) },
                  second = { Editor(Modifier.fillMaxSize()) },
                )
              else ->
                SplitPane(
                  stacked = false,
                  proportion = 1f - weights.compact,
                  onProportion = { weights = weights.copy(compact = 1f - it) },
                  main,
                  first = { Editor(Modifier.fillMaxSize()) },
                  second = { Window(window, Modifier.fillMaxSize()) },
                )
            }
          }
      }
      // The keyboard bar (docs/feature-parity-plan.md section 1): extra keys under the tool window
      // bar while the soft keyboard is open. For now only a focused terminal has keys to show.
      if (keyboardOpen) terminalFocus.focused?.let { TerminalKeyBar(it) }
      // IntelliJ's status bar: breadcrumbs, the info text or the newest task, the backend's
      // widgets.
      if (!(keyboardDocked && layout == ScreenLayout.PORTRAIT))
        StatusBar(
          state,
          session,
          // The last log line is a desktop debugging aid; a phone shows only notices and failures,
          // so Position stays in view.
          info =
            notice
              ?: state.failure
              ?: if (layout == ScreenLayout.PORTRAIT) "" else state.log.lastOrNull().orEmpty(),
          infoIsError = notice == null && state.failure != null,
          narrow = layout == ScreenLayout.PORTRAIT,
        )
    }
    // Over everything: the backend's BeControl popups (New ▸ File's name).
    io.github.safeuq.ideate.client.ui.popup.BackendPopups(state.backendPopups, session)
    val search = session.searchEverywhere
    if (state.search != null && search != null) {
      SearchEverywherePopup(
        state.search,
        search,
        // Under the main toolbar, which the popup must not cover on a phone.
        top = Islands.toolbarHeight + Islands.islandGap,
        maxHeight = maxHeight * 0.6f,
        onChosen = ::onNavigated,
        onUnsupported = unsupported,
        history = searchHistories.getOrPut(state.search.tab) { SearchHistory(size = 50) },
      )
    }
  }
}

private fun Modifier.island(radius: Dp) =
  clip(RoundedCornerShape(radius)).background(Islands.islandBackground)

/**
 * The islands' corner radius: IntelliJ's everywhere, except on a phone held upright, where a page
 * with one island at rest draws it square and flush with the screen's sides ([SwipePager]).
 */
private val LocalIslandRadius = compositionLocalOf { Islands.islandRadius }

/** `RdProjectViewAsyncPane` "ProjectPane": the Project view, the one with [SessionState.tree]. */
private const val PROJECT_PANE = "ProjectPane"

@Composable
private fun ToolWindowContent(
  window: ToolWindow,
  state: SessionState,
  session: ClientSession,
  scroll: SidewaysScroll,
  onNavigated: () -> Unit,
  structureCollapsed: Set<String>,
  onStructureCollapsedChange: (Set<String>) -> Unit,
  terminalFocus: TerminalFocus,
  terminalTabs: TerminalTabsState,
  portrait: Boolean,
) =
  when {
    // A pane the backend paints, e.g. the welcome project's "Projects and Files".
    window == ToolWindow.PROJECT && state.projectViews.panel != null ->
      LuxPaneView(
        state.projectViews.panel,
        state,
        onResize = session::resizeProjectPanel,
        onRepaint = session::repaintProjectPanel,
        send = session::projectPanelEvent,
        session = session,
        modifier = Modifier.fillMaxSize(),
      )
    // Packages, Scopes: rows the backend draws (a BeTreeGrid); it opens what is double-tapped.
    window == ToolWindow.PROJECT &&
      state.projectViews.current.let { it != null && it.paneId != PROJECT_PANE } ->
      ProjectTreeView(
        tree = state.projectViews.tree,
        onToggle = { session.paneToggle(it.id) },
        onSelect = { session.paneSelect(it.id) },
        onOpen = { node ->
          session.paneOpen(node.id)
          if (!node.hasChildren) onNavigated()
        },
        modifier = Modifier.fillMaxSize(),
        scroll = scroll,
      )
    window == ToolWindow.PROJECT ->
      ProjectTreeView(
        tree = state.tree,
        onToggle = { session.toggle(it.id) },
        onSelect = { session.select(it.id) },
        // Opening a file also slides back to the editor on a phone held upright.
        onOpen = { node ->
          node.path
            ?.takeIf { !node.isDirectory }
            ?.let { path ->
              session.openNode(node.id, path)
              onNavigated()
            }
        },
        modifier = Modifier.fillMaxSize(),
        scroll = scroll,
        onMenu = { session.projectMenu(it.id) },
        menuEntries = { state.contextMenu?.entries },
        // The scheme's FILESTATUS_<id> colour, e.g. FILESTATUS_IDEA_FILESTATUS_IGNORED D69A6B.
        fileStatusColor = { status -> state.scheme?.color("FILESTATUS_$status")?.let(::Color) },
      )
    window == ToolWindow.STRUCTURE ->
      StructureView(
        state,
        session,
        onNavigated,
        Modifier.fillMaxSize(),
        scroll,
        structureCollapsed,
        onStructureCollapsedChange,
      )
    window == ToolWindow.TERMINAL -> TerminalToolWindow(state, session, terminalFocus, terminalTabs)
    else -> BackendToolWindowContent(window, state, session, scroll, portrait)
  }

/**
 * A tool window the backend fills, e.g. Gradle: its action toolbar above its tree, as JetBrains
 * Client shows them. While it loads, the text of its label ("Initializing Gradle projects data…");
 * a window whose content Ideate can't draw yet says so, with its header as any other.
 */
@Composable
private fun BackendToolWindowContent(
  window: ToolWindow,
  state: SessionState,
  session: ClientSession,
  scroll: SidewaysScroll,
  portrait: Boolean,
) {
  val view = state.backendViews[window.id]
  // Run, Build: the backend paints the panel; its console is drawn by the client inside it.
  if (view?.panel != null) {
    LuxPaneView(
      view.panel,
      state,
      onResize = { x, y, width, height ->
        session.resizeBackendPanel(window.id, x, y, width, height)
      },
      onRepaint = { session.repaintBackendPanel(window.id) },
      send = { event -> session.backendPanelEvent(window.id, event) },
      session = session,
      modifier = Modifier.fillMaxSize(),
      portrait = portrait,
      scroll = scroll,
    )
    return
  }
  if (view == null || (view.tree.nodes.isEmpty() && view.toolbar.isEmpty())) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      BasicText(
        // Empty until the backend's content arrives, a moment after the window opens.
        view?.label ?: "",
        style = LocalClientFonts.current.label().copy(color = Islands.textSecondary),
      )
    }
    return
  }
  Column(Modifier.fillMaxSize()) {
    ToolWindowToolbar(view.toolbar)
    ProjectTreeView(
      tree = view.tree,
      onToggle = { session.backendToggle(window.id, it.id) },
      onSelect = { session.backendSelect(window.id, it.id) },
      // E.g. Gradle runs the task; the window stays, as in IntelliJ.
      onOpen = { session.backendOpen(window.id, it.id) },
      modifier = Modifier.fillMaxWidth().weight(1f),
      scroll = scroll,
    )
  }
}

@Composable
private fun EditorIsland(
  state: SessionState,
  session: ClientSession,
  editorScroll: SidewaysScroll,
  tabRow: TabRowScroll,
  /** A phone held upright: the breadcrumbs under the editor, not in the status bar. */
  breadcrumbsFooter: Boolean,
  modifier: Modifier,
) {
  val fonts = LocalClientFonts.current
  // What the backend copies (ClipboardModel) goes to the system clipboard, as in JetBrains Client.
  val systemClipboard = LocalClipboardManager.current
  LaunchedEffect(state.backendClipboard) {
    state.backendClipboard?.let { systemClipboard.setText(AnnotatedString(it.text)) }
  }
  Column(modifier.island(LocalIslandRadius.current)) {
    val selected = state.editors.firstOrNull { it.path == state.selectedEditor }
    EditorTabs(state, session, tabRow)
    // JBEditorTabsBorder in the new UI: a line under the tab row in
    // EditorTabs.underTabsBorderColor. Not while sticky lines show at the editor's top (user
    // request): their panel starts right there.
    var stickyShown by remember { mutableStateOf(false) }
    if (!stickyShown) EditorBorder(Islands.underTabsBorder)
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
            session::runEditorAction,
            session::linkAt,
            session::usagesKey,
            session::chooseUsage,
            session::closeUsages,
            session::caretFromTop,
          )
        }
      EditorView(
        selected,
        scheme,
        actions,
        Modifier.fillMaxWidth().weight(1f),
        state.lookup?.takeIf { it.path == selected.path },
        state.usages?.takeIf { it.path == selected.path },
        editorScroll,
        backendMenu = { state.contextMenu?.entries },
        onStickyShown = { stickyShown = it },
      )
      if (breadcrumbsFooter)
        EditorBreadcrumbs(
          state,
          session,
          // EditorColors.BREADCRUMBS_BORDER_COLOR falls back to INDENT_GUIDE_COLOR.
          (scheme.color("BREADCRUMBS_BORDER_COLOR") ?: scheme.color("INDENT_GUIDE"))?.let(::Color)
            ?: Islands.popupBorder,
        )
    } else {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
          if (state.tree.nodes.isEmpty()) "Connecting…"
          else "Double-tap a file in the Project tool window to open it",
          style = fonts.label(Islands.textSecondary),
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
private fun EditorTabs(state: SessionState, session: ClientSession, tabRow: TabRowScroll) {
  if (state.editors.isEmpty()) return
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val clipboard = LocalClipboardManager.current

  // IntelliJ's tab context menu (EditorTabPopupMenu); what Ideate can't do yet is greyed out.
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

  // IntelliJ's single-row tabs (ScrollableSingleRowLayout): one row that scrolls sideways, names on
  // one line, and the ▾ (TabListAction) listing the tabs that don't fully show.
  val scroll = rememberScrollState()
  tabRow.scroll = scroll
  // Once the row is gone (the last tab closed), the page swiper must not ask the old row.
  DisposableEffect(tabRow) {
    onDispose {
      tabRow.scroll = null
      tabRow.bounds = Rect.Zero
    }
  }
  // Each tab's left edge and width in the scrolling row, in px.
  val placed = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
  var viewport by remember { mutableIntStateOf(0) }
  fun shown(path: String): Boolean {
    val (left, width) = placed[path] ?: return true
    return left >= scroll.value && left + width <= scroll.value + viewport
  }
  // The selected tab scrolls into view, as JBTabsImpl does on selection.
  LaunchedEffect(state.selectedEditor, placed[state.selectedEditor], viewport) {
    val (left, width) = placed[state.selectedEditor] ?: return@LaunchedEffect
    when {
      left < scroll.value -> scroll.animateScrollTo(left)
      left + width > scroll.value + viewport -> scroll.animateScrollTo(left + width - viewport)
    }
  }
  Row(
    Modifier.fillMaxWidth().height(Islands.tabRowHeight),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier.weight(1f)
        .onGloballyPositioned { tabRow.bounds = it.boundsInRoot() }
        .onSizeChanged { viewport = it.width }
        .horizontalScroll(scroll)
        .padding(start = 4.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (editor in state.editors) {
        val selected = editor.path == state.selectedEditor
        val iconPath = fileIconPath(state, editor.path)
        val pill = RoundedCornerShape(Islands.tabRadius)
        // Measured on this wrapper: the row is its parent.
        Box(
          Modifier.onPlaced { coordinates ->
            placed[editor.path] = coordinates.positionInParent().x.toInt() to coordinates.size.width
          },
        ) {
          ContextMenuArea(entries = { tabMenu(editor.path, editor.name) }) {
            Box(
              Modifier.height(Islands.tabHeight)
                .clip(pill)
                .then(
                  if (selected)
                    Modifier.background(Islands.tabSelectedBackground)
                      .border(1.dp, Islands.tabSelectedBorder, pill)
                  else Modifier,
                )
                .clickable { session.selectEditor(editor.path) }
                .padding(start = 8.dp, end = 4.dp),
            ) {
              Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                icons.painter(iconPath)?.let { Image(it, null, Modifier.size(16.dp)) }
                Spacer(Modifier.width(6.dp))
                BasicText(editor.name, style = fonts.label(), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(2.dp))
                TabCloseButton(editor.name) { session.closeEditor(editor.path) }
              }
            }
          }
        }
      }
    }
    val hidden = state.editors.filterNot { shown(it.path) }
    if (scroll.maxValue > 0 && hidden.isNotEmpty()) {
      var open by remember { mutableStateOf(false) }
      Box {
        IconButton(
          "expui/general/chevronDownLarge.svg",
          "Show Hidden Tabs",
          onClick = { open = true },
          modifier = Modifier.padding(horizontal = 4.dp),
          size = Islands.tabHeight,
          iconSize = 16.dp,
        )
        if (open) {
          // HiddenInfosListPopupStep: tabs cut off on the left, a separator, then those on the
          // right.
          val firstOnRight = hidden.indexOfFirst { (placed[it.path]?.first ?: 0) >= scroll.value }
          DropDownMenu(
            hidden.flatMapIndexed { index, editor ->
              listOfNotNull(
                MenuSeparator.takeIf { index == firstOnRight && index > 0 },
                MenuItem(editor.name, iconPath = fileIconPath(state, editor.path)) {
                  session.selectEditor(editor.path)
                },
              )
            },
            beside = false,
          ) {
            open = false
          }
        }
      }
    }
  }
}

/**
 * The editor's tab row for the page swiper: a sideways drag that starts on it scrolls the tabs
 * until they reach that end, as [SidewaysScroll] does for the editor's text.
 */
class TabRowScroll {
  internal var scroll: ScrollState? = null
  internal var bounds = Rect.Zero

  fun contains(point: Offset) = bounds.contains(point)

  fun canScroll(dragX: Float): Boolean {
    val current = scroll ?: return false
    return if (dragX > 0) current.value > 0 else current.value < current.maxValue
  }
}

/** The file's icon from the Project tree, or the plain text icon. */
private fun fileIconPath(state: SessionState, path: String) =
  state.tabIcons[path]
    ?: state.tree.nodes.values.firstOrNull { it.path == path }?.iconPaths?.firstOrNull()
    ?: "expui/fileTypes/text.svg"

/** IntelliJ's tab close button: `closeSmall`, `closeSmallHovered` under the pointer. */
@Composable
internal fun TabCloseButton(fileName: String, onClose: () -> Unit) {
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

/**
 * Backend windows whose backend-painted panels come in tabs, one per run or build; they get the tab
 * chips in the header. ponytail: a fixed list; the backend has no flag for it.
 */
private val RUN_WINDOWS = setOf("Run", "Debug", "Build")

private const val STRUCTURE_HIDE_DELAY_MS = 30_000L
