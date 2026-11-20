package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.session.BackgroundTask
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.Crumb
import intellijandroid.client.session.SessionState
import intellijandroid.client.session.StatusWidget
import intellijandroid.client.ui.component.DeterminateHorizontalProgressBar
import intellijandroid.client.ui.component.IndeterminateHorizontalProgressBar
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * IntelliJ's status bar in the new UI (`IdeStatusBarImpl`, docs/status-bar.md): the breadcrumbs on
 * the left (`NavBar`), the info text or the newest background task in the middle
 * (`InfoAndProgressPanel`), and the backend's widgets on the right (Position, LF, UTF-8, indent,
 * read-only). On a phone held upright ([narrow]) the breadcrumbs are the editor's footer instead
 * ([EditorBreadcrumbs]), and the bar scrolls sideways: the task's short bar, then Position and the
 * rest. Sizes are IntelliJ's for mouse and touch alike.
 */
@Composable
fun StatusBar(
  state: SessionState,
  session: ClientSession,
  /** A notice, the session's failure or its last log line; shown while no task runs. */
  info: String,
  infoIsError: Boolean,
  narrow: Boolean,
  modifier: Modifier = Modifier,
) {
  var processesShown by remember { mutableStateOf(false) }
  val tasks = state.tasks
  if (tasks.isEmpty()) processesShown = false
  val progress = inlineProgress(tasks, processesShown)
  val bar = Modifier.fillMaxWidth().height(Look.height).padding(horizontal = Look.barInset)
  // The Box makes the bar the Processes popup's anchor, so the popup sits just above it.
  Box(modifier.fillMaxWidth()) {
    if (narrow) {
      Row(
        bar.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (progress != null)
          InlineProgressPanel(
            progress,
            withText = false,
            session,
            onClick = { processesShown = !processesShown },
          )
        else if (info.isNotEmpty())
          Info(info, infoIsError, Modifier.widthIn(max = Look.narrowInfoWidth))
        // Position first, as the one widget a phone needs most; the rest after it.
        val widgets = state.statusWidgets.sortedBy { if (it.id == "Position") 0 else 1 }
        for (widget in widgets) Widget(widget, session)
      }
    } else {
      Row(bar, verticalAlignment = Alignment.CenterVertically) {
        // The breadcrumbs take the free space; the task or the info text sits before the widgets,
        // at its own width (at most Look.infoMaxWidth), as InfoAndProgressPanel does.
        Box(Modifier.weight(1f)) { Breadcrumbs(state, session, Modifier) }
        if (progress != null)
          InlineProgressPanel(
            progress,
            withText = true,
            session,
            onClick = { processesShown = !processesShown },
            // InfoAndProgressPanelImpl's wrapper: JBUI.Borders.empty(0, 40, 0, 4).
            modifier = Modifier.padding(start = 40.dp, end = 4.dp),
          )
        else if (info.isNotEmpty())
          Info(
            info,
            infoIsError,
            Modifier.padding(horizontal = Look.gap).widthIn(max = Look.infoMaxWidth),
          )
        for (widget in state.statusWidgets) Widget(widget, session)
      }
    }
    if (processesShown && tasks.isNotEmpty())
      ProcessesPopup(tasks, session, onDismiss = { processesShown = false })
  }
}

/**
 * The breadcrumbs as the editor island's footer on a phone held upright, where the status bar has
 * no room for them, as IntelliJ can put its editor breadcrumbs below the editor (Settings › Editor
 * › General › Breadcrumbs › Bottom). The status bar's height and crumbs; the end stays in view.
 * Above them a 1 dp line in [borderColor], as `BreadcrumbsXmlWrapper` draws in the new UI
 * (`insetsTop(1)` in the scheme's `BREADCRUMBS_BORDER_COLOR`).
 */
@Composable
fun EditorBreadcrumbs(state: SessionState, session: ClientSession, borderColor: Color) {
  if (state.breadcrumbs.isEmpty()) return
  Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
    Box(
      Modifier.fillMaxWidth().height(Look.height).padding(horizontal = Look.footerInset),
      contentAlignment = Alignment.CenterStart,
    ) {
      Breadcrumbs(state, session, Modifier)
    }
  }
}

/**
 * IntelliJ's status bar breadcrumbs (`NavBarItemComponent`): icon and text, 4 dp apart, inside a
 * 2/4 dp padding that takes the hover fill, a chevron before every crumb but the first. A tap lists
 * the crumb's children; a file or member there opens in the editor, a folder lists its own. When
 * they don't fit, the start is cut and the end (the file and member) stays.
 */
@Composable
private fun Breadcrumbs(state: SessionState, session: ClientSession, modifier: Modifier) {
  val crumbs = state.breadcrumbs
  if (crumbs.isEmpty()) return
  val scroll = rememberScrollState()
  LaunchedEffect(crumbs, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
  Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
    crumbs.forEachIndexed { index, crumb ->
      if (index > 0) Icon("expui/general/chevronRight.svg", null)
      CrumbItem(crumb, state, session)
    }
  }
}

@Composable
private fun CrumbItem(crumb: Crumb, state: SessionState, session: ClientSession) {
  val fonts = LocalClientFonts.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val open = state.crumbMenu?.anchorNodeId == crumb.nodeId
  Box {
    Row(
      Modifier.background(if (hovered || open) Look.hover else Color.Transparent, Look.shape)
        .hoverable(interaction)
        .clickable(interaction, indication = null) { session.openCrumb(crumb.nodeId) }
        .padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      crumb.iconPath?.let { Icon(it, null) }
      if (crumb.iconPath != null) Spacer(Modifier.width(4.dp))
      BasicText(
        crumb.text,
        maxLines = 1,
        style = fonts.label(if (hovered || open) Look.hoverText else Look.text),
      )
    }
    val menu = state.crumbMenu
    if (open && menu != null)
      DropDownMenu(
        menu.items.map { item ->
          MenuItem(item.text, iconPath = item.iconPath, onClick = { session.chooseCrumbItem(item) })
        },
        beside = false,
        onDismiss = session::closeCrumbMenu,
      )
  }
}

/** The status text IntelliJ shows in the middle when no task runs, e.g. a notice. */
@Composable
private fun Info(text: String, isError: Boolean, modifier: Modifier) {
  BasicText(
    text,
    modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = LocalClientFonts.current.label(if (isError) IslandsDark.textError else Look.text),
  )
}

/**
 * What IntelliJ's `InlineProgressPanel` shows, in the counter mode IntelliJ uses by default
 * (registry `progresses.show.counter.icon.instead.of.show.link` = true).
 */
internal data class InlineProgress(
  /** `setInlineProgressByWeight`'s choice. */
  val indicator: BackgroundTask,
  /** False while the Processes popup is open, or when the task isn't for the status bar. */
  val indicatorVisible: Boolean,
  /** `CounterLabel.getNumberToShow`: sizes the counter even when it shows nothing. */
  val counterNumber: Int,
  /** `CounterLabel.refreshPanelText`: "+2" after the indicator, "3" without it, often "". */
  val counterText: String,
  /** `multiProcessLink` while the popup is open: "Hide processes (3)". */
  val hideLink: String?,
)

/**
 * IntelliJ's `InfoAndProgressPanel.setInlineProgressByWeight` and
 * `InlineProgressPanel.updateState`: the tasks for the status bar first, in the order they started,
 * then the first one not paused. Null without tasks. The backend doesn't send
 * `statusBarIndicatorWeight`, so the start order decides.
 */
internal fun inlineProgress(tasks: List<BackgroundTask>, popupShown: Boolean): InlineProgress? {
  if (tasks.isEmpty()) return null
  val sorted = tasks.sortedBy { if (it.inStatusBar) 0 else 1 }
  val indicator = sorted.firstOrNull { !it.paused } ?: sorted.first()
  val visible = !popupShown && indicator.inStatusBar
  val number = if (visible) tasks.size - 1 else tasks.size
  val text =
    when {
      popupShown -> ""
      number <= 0 -> ""
      !visible && number == 1 -> ""
      visible -> "+$number"
      else -> "$number"
    }
  return InlineProgress(
    indicator,
    visible,
    number,
    text,
    if (popupShown) "Hide processes (${tasks.size})" else null,
  )
}

/**
 * IntelliJ's `InlineProgressPanel`: the widget insets (`StatusBar.Widget.border()`, 8 at the sides
 * in the new UI), then from the left the "Hide processes" link while the popup is open, the
 * indicator, and the counter, which keeps its width with no text. That width and the insets are the
 * room IntelliJ leaves after the bar's buttons. A tap opens or closes the Processes popup
 * (`triggerPopupShowing`). [withText] false (a phone held upright) leaves the task's text out.
 */
// ponytail: no spinner (`AsyncProcessIcon`) for a task that isn't for the status bar, and no hover
// fill; add them with IntelliJ's SpinningProgressIcon frames and WidgetEffectRenderer if missed.
@Composable
private fun InlineProgressPanel(
  progress: InlineProgress,
  withText: Boolean,
  session: ClientSession,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fonts = LocalClientFonts.current
  Row(
    modifier
      .clickable(onClick = onClick)
      .semantics { contentDescription = "Show all processes" }
      .padding(horizontal = Look.widgetInset),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    progress.hideLink?.let { BasicText(it, maxLines = 1, style = fonts.label(Look.text)) }
    if (progress.indicatorVisible) ProgressIndicator(progress.indicator, withText, session)
    Counter(progress.counterNumber, progress.counterText)
  }
}

/**
 * IntelliJ's compact `ProgressComponent` (`InfoAndProgressPanel.MyProgressComponent`): a
 * `BorderLayout(2, 0)` with the text and the bar in the centre and the buttons east. The text is
 * the task's text, or its title without one; the bar is 104 wide in a wrapper with 8 before and 4
 * after.
 */
@Composable
private fun ProgressIndicator(task: BackgroundTask, withText: Boolean, session: ClientSession) {
  if (withText)
    BasicText(
      task.text?.takeIf { it.isNotEmpty() } ?: task.title,
      Modifier.widthIn(max = Look.titleMaxWidth),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = LocalClientFonts.current.label(Look.text),
    )
  Box(Modifier.padding(start = 8.dp, end = 4.dp)) {
    Bar(task, Modifier.width(Look.compactBarWidth))
  }
  Spacer(Modifier.width(2.dp))
  TaskButtons(task, session)
}

/**
 * IntelliJ's `CounterLabel`: its text 4 after the indicator (`emptyLeft(4)`), as wide as the widest
 * of "+0" to "+9" (or "+10" to "+99", …, for as many digits as [number] has), so the indicator
 * doesn't move as the count changes and keeps that room when there is no count.
 */
@Composable
private fun Counter(number: Int, text: String) {
  val style = LocalClientFonts.current.label(Look.text)
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val digits = number.toString().length
  val width =
    remember(style, digits, text) {
      val range =
        when (digits) {
          1 -> 0..9
          2 -> 10..99
          3 -> 100..999
          else -> null
        }
      val widest = range?.maxOf { measurer.measure("+$it", style).size.width }
      with(density) { (widest ?: measurer.measure(text, style).size.width).toDp() }
    }
  BasicText(text, Modifier.padding(start = 4.dp).width(width), maxLines = 1, style = style)
}

@Composable
private fun Bar(task: BackgroundTask, modifier: Modifier) {
  val fraction = task.fraction
  if (fraction == null || task.paused && fraction <= 0.0)
    IndeterminateHorizontalProgressBar(modifier)
  else DeterminateHorizontalProgressBar(fraction.toFloat(), modifier)
}

/**
 * A task's buttons, as `MyProgressComponent.createEastButtons`: pause or resume when it can pause,
 * then × when it can be cancelled, each with 2 after (`createButtonPanel`). The new UI's icons
 * (PlatformIconMappings.json: `process/progressPauseSmall` → `expui/progress/pause`, …), brighter
 * under the pointer.
 */
@Composable
private fun TaskButtons(task: BackgroundTask, session: ClientSession) {
  if (task.suspendable)
    if (task.paused) TaskButton("expui/progress/resume", "Resume") { session.resumeTask(task.id) }
    else TaskButton("expui/progress/pause", "Pause") { session.pauseTask(task.id) }
  if (task.cancellable) TaskButton("expui/progress/stop", "Cancel") { session.cancelTask(task.id) }
}

@Composable
private fun TaskButton(icon: String, description: String, action: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Box(
    Modifier.padding(end = 2.dp)
      .hoverable(interaction)
      .clickable(interaction, indication = null, onClick = action)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Icon(if (hovered) "${icon}Hovered.svg" else "$icon.svg", null)
  }
}

/**
 * A backend widget, as `StatusBarWidget`'s text or icon component: 6 dp top and bottom, 8 dp at the
 * sides (`StatusBar.Widget.widgetInsets`), the muted text turning default with a hover fill inset
 * 3, 4, 1, 4 (`StatusBar.Widget.hoverInsets`). A tap goes to the backend with where the widget is.
 */
@Composable
private fun Widget(widget: StatusWidget, session: ClientSession) {
  val fonts = LocalClientFonts.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var bounds by remember { mutableStateOf(IntRect.Zero) }
  Box(
    Modifier.onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInWindow()
        bounds =
          IntRect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
      }
      .hoverable(interaction)
      .clickable(interaction, indication = null) {
        session.clickStatusWidget(widget.id, bounds.left, bounds.top, bounds.width, bounds.height)
      }
      .semantics { contentDescription = widget.tooltip ?: widget.text ?: widget.id },
  ) {
    Row(
      Modifier.padding(start = 4.dp, top = 3.dp, end = 4.dp, bottom = 1.dp)
        .background(if (hovered) Look.hover else Color.Transparent, Look.shape)
        .padding(start = 4.dp, top = 3.dp, end = 4.dp, bottom = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      widget.iconPath?.let { Icon(it, null) }
      if (widget.iconPath != null && widget.text != null) Spacer(Modifier.width(4.dp))
      widget.text?.let {
        BasicText(it, maxLines = 1, style = fonts.label(if (hovered) Look.hoverText else Look.text))
      }
    }
  }
}

/**
 * IntelliJ's Processes popup (`ProcessPopup`): "Processes" and a hide button on top, then each task
 * as `ProgressComponent` shows it: its title, a full-width bar with its button, and its text and
 * details below, a line between tasks. 300 to 500 dp wide, at the window's right above the status
 * bar.
 */
@Composable
private fun ProcessesPopup(
  tasks: List<BackgroundTask>,
  session: ClientSession,
  onDismiss: () -> Unit,
) {
  val fonts = LocalClientFonts.current
  Popup(
    popupPositionProvider = ProcessesPosition,
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Column(
      Modifier.widthIn(min = 300.dp, max = 500.dp)
        .background(IslandsDark.popupBackground, Look.popupShape)
        .border(1.dp, IslandsDark.popupBorder, Look.popupShape),
    ) {
      Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        BasicText(
          "Processes",
          Modifier.fillMaxWidth().align(Alignment.Center),
          style =
            fonts.label().copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        Box(
          Modifier.align(Alignment.CenterEnd).clickable(onClick = onDismiss).semantics {
            contentDescription = "Hide"
          },
        ) {
          Icon("expui/general/hide.svg", null)
        }
      }
      Column(Modifier.verticalScroll(rememberScrollState())) {
        tasks.asReversed().forEachIndexed { index, task ->
          if (index > 0)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IslandsDark.popupBorder))
          Column(
            Modifier.fillMaxWidth()
              .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BasicText(task.title, maxLines = 2, style = fonts.label())
            Row(verticalAlignment = Alignment.CenterVertically) {
              Bar(task, Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              TaskButtons(task, session)
            }
            val lines = listOfNotNull(task.text?.takeIf { it != task.title }, task.details)
            if (lines.isNotEmpty())
              BasicText(
                lines.joinToString("\n"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = fonts.label(IslandsDark.textSecondary),
              )
          }
        }
      }
    }
  }
}

/**
 * Over the status bar at the window's right, as `ProcessPopup` places itself 20 dp from the edge.
 */
private object ProcessesPosition : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = (windowSize.width - popupContentSize.width - anchorBounds.height).coerceAtLeast(0)
    val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(x, y)
  }
}

@Composable
private fun Icon(path: String, description: String?) {
  LocalIcons.current.painter(path)?.let {
    Image(it, contentDescription = description, modifier = Modifier.size(16.dp))
  }
}

/** IntelliJ's values for the new UI status bar in Islands Dark (docs/status-bar.md). */
private object Look {
  /** A widget's 16 dp line plus its 6 dp insets above and below. */
  val height = 28.dp
  /** `IdeStatusBarImpl`: `JBUI.Borders.empty(0, 10)` in the new UI. */
  val barInset = 10.dp
  /** The info text's gap to its neighbours (`InlineProgressPanel`'s gap). */
  val gap = 10.dp
  /** `StatusBar.Widget.widgetInsets` at the sides in the new UI. */
  val widgetInset = 8.dp
  /** `InlineProgressIndicator`'s compact bar: 104 wide. */
  val compactBarWidth = 104.dp
  val titleMaxWidth = 320.dp
  /** The editor footer's crumbs: 4 in from the island's edge, as the editor gutter's first gap. */
  val footerInset = 4.dp
  val narrowInfoWidth = 220.dp
  val infoMaxWidth = 400.dp
  val text = Color(0xFF9FA2A8) // StatusBar.Widget.foreground = text-muted = gray-100
  val hoverText = Color(0xFFD1D3D9) // hoverForeground = text-default = gray-130
  val hover = Color(0x17FFFFFF) // hoverBackground = toolbar-bg-hovered = transparent-white-20
  val shape = RoundedCornerShape(4.dp)
  val popupShape = RoundedCornerShape(8.dp)
}
