package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import io.github.safeuq.ideate.client.session.BackendToolWindow
import io.github.safeuq.ideate.client.ui.component.AppPopup
import io.github.safeuq.ideate.client.ui.component.HelpTooltip
import io.github.safeuq.ideate.client.ui.component.IconButton
import io.github.safeuq.ideate.client.ui.component.Menu
import io.github.safeuq.ideate.client.ui.component.TooltipAlignment
import io.github.safeuq.ideate.client.ui.component.keep
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/** A side of the window, as IntelliJ's `ToolWindowAnchor` (top isn't used by the new UI). */
enum class Side {
  LEFT,
  RIGHT,
  BOTTOM,
}

/**
 * Where a tool window goes: its side plus IntelliJ's split flag (`WindowInfo.isSplit`), which puts
 * it in the second half of that side. Left and right split top/bottom, the bottom splits left/right
 * (`ToolWindowAnchor.isSplitVertically`). Declared in the order of the bars' groups.
 */
enum class Place(val side: Side, val split: Boolean) {
  LEFT_TOP(Side.LEFT, false),
  LEFT_BOTTOM(Side.LEFT, true),
  BOTTOM_LEFT(Side.BOTTOM, false),
  BOTTOM_RIGHT(Side.BOTTOM, true),
  RIGHT_TOP(Side.RIGHT, false),
  RIGHT_BOTTOM(Side.RIGHT, true);

  companion object {
    /** `BeToolWindow.position` (LEFT, RIGHT, BOTTOM; TOP goes left) and `sideTool`. */
    fun of(anchor: String, split: Boolean): Place = entries.first { place ->
      place.split == split &&
        place.side ==
          when (anchor) {
            "RIGHT" -> Side.RIGHT
            "BOTTOM" -> Side.BOTTOM
            else -> Side.LEFT
          }
    }
  }
}

/**
 * A tool window button: one of the backend's tool windows ([BackendToolWindow]) or Project and
 * Terminal, which JetBrains Client registers itself. Every one opens: Project and Structure are
 * Ideate's own; a backend window shows the panel, or the toolbar and tree, the backend sends
 * (Build, Gradle), or nothing when it sends neither. Two are the same window when their [id]s are.
 */
class ToolWindow(val id: String, val title: String, val icon: String?, val place: Place) {

  override fun equals(other: Any?) = other is ToolWindow && other.id == id

  override fun hashCode() = id.hashCode()

  companion object {
    val PROJECT = ToolWindow("Project", "Project", "expui/toolwindows/project.svg", Place.LEFT_TOP)
    val STRUCTURE =
      ToolWindow("Structure", "Structure", "expui/toolwindows/structure.svg", Place.LEFT_BOTTOM)
    /** The shell on the backend machine; the client draws it (spec 1), no backend window. */
    val TERMINAL =
      ToolWindow("Terminal", "Terminal", "icons/expui/toolwindow/terminal.svg", Place.BOTTOM_LEFT)
  }
}

/**
 * The bar buttons of each [Place], and the rest of the available windows behind the ⋯ button
 * ([more]).
 */
data class ToolWindowButtons(val byPlace: Map<Place, List<ToolWindow>>, val more: List<ToolWindow>)

/**
 * IntelliJ's new UI default layout (`DefaultToolWindowLayoutBuilderImpl.addPlatformDefaultsV2`):
 * these get a stripe button, in this order; any other window only when the backend asks for one
 * ([BackendToolWindow.onStripe]), else it is under ⋯. JetBrains Client keeps the user's own layout
 * on its side; the backend doesn't send it (docs/tool-windows.md).
 */
private val DEFAULT_LAYOUT =
  listOf(
    // Left
    "Project",
    "Commit",
    "Structure",
    // Bottom
    "Version Control",
    "Problems",
    "Problems View",
    "Terminal",
    "Services",
    // Run, Debug and Build: IntelliJ shows them once used; here whenever the backend lists them
    // (it does after the first run or build), as the other windows. ponytail: the order after
    // Services is a guess; check against IntelliJ's stripe.
    "Run",
    "Debug",
    "Build",
    // Right
    "Notifications",
    "AIAssistant",
    "Database",
    "Gradle",
    "Maven",
  )

/**
 * The buttons for the backend's [windows] plus Project and Terminal. Example: on a Mac backend with
 * no version control, left top: Project; left bottom: Structure; bottom left: Version Control,
 * Problems; right top: AI Chat, Database, Gradle; under ⋯: Coverage, Database Output, Endpoints,
 * Learn, Profiler, …; Commit is unavailable, so nowhere.
 */
fun toolWindowButtons(windows: List<BackendToolWindow>): ToolWindowButtons {
  val own = listOf(ToolWindow.PROJECT, ToolWindow.TERMINAL)
  val available =
    own +
      windows
        .filter { window -> window.available && own.none { it.id == window.id } }
        .map { window ->
          ToolWindow(
            window.id,
            window.title,
            window.iconPath,
            Place.of(window.anchor, window.split),
          )
        }
  val onStripe = windows.filter { it.onStripe == true }.map { it.id }.toSet()
  val (stripe, more) = available.partition { it.id in DEFAULT_LAYOUT || it.id in onStripe }
  val ordered = stripe.sortedBy {
    DEFAULT_LAYOUT.indexOf(it.id).let { at -> if (at < 0) Int.MAX_VALUE else at }
  }
  return ToolWindowButtons(
    byPlace = ordered.groupBy { it.place },
    more = more.sortedBy { it.title.lowercase() },
  )
}

/** The window's shape, which sets the rules of [ToolWindowState] (docs/mobile-layout.md). */
enum class ScreenLayout {
  /** Phone or tablet held upright: side pages, the editor or the bottom windows in the middle. */
  PORTRAIT,
  /**
   * Desktop, tablet held sideways: bars on both sides; the left pane, the editor and the right pane
   * side by side, with the bottom area under all three, as IntelliJ's default layout.
   */
  WIDE,
  /** Phone held sideways: one bar on the left, the editor and at most one tool window. */
  COMPACT,
}

/**
 * Upright when taller than wide; sideways, a window at least 600 dp tall (Android's tablet line,
 * `sw600dp`) gets the desktop rules, a shorter one the phone's. Measured on the window, so a
 * desktop window or a split-screen app picks its layout from its own size. Examples: a phone 411 ×
 * 891 → portrait; the same phone sideways 891 × 411 → compact; a desktop window 1200 × 800 → wide.
 */
fun screenLayout(widthDp: Float, heightDp: Float) =
  when {
    widthDp < heightDp -> ScreenLayout.PORTRAIT
    heightDp >= 600f -> ScreenLayout.WIDE
    else -> ScreenLayout.COMPACT
  }

/**
 * The open tool window of each [Place], at most one, as on IntelliJ's stripes. Opening another
 * window of the same place replaces it. The layout adds its own rule:
 * - [ScreenLayout.WIDE]: no more; both side panes and the bottom area can be open together, each
 *   opened or closed on its own.
 * - [ScreenLayout.COMPACT]: one tool window at a time.
 * - [ScreenLayout.PORTRAIT]: no more; the screen shows one page at a time anyway.
 */
class ToolWindowState {
  val open = mutableStateMapOf(Place.LEFT_TOP to ToolWindow.PROJECT)
  /** The window each place had last, for a swipe to an empty side page (phone upright). */
  private val last = mutableMapOf(Place.LEFT_TOP to ToolWindow.PROJECT)

  fun isOpen(window: ToolWindow) = open[window.place] == window

  /** The open windows as saved: place name to window id, e.g. `{"LEFT_TOP": "Project"}`. */
  fun saved(): Map<String, String> =
    open.entries.associate { (place, window) -> place.name to window.id }

  /** Opens the windows [saved] says, as far as [buttons] has them. */
  fun restore(saved: Map<String, String>, buttons: ToolWindowButtons) {
    val windows = buttons.byPlace.values.flatten() + buttons.more
    open.clear()
    for ((place, id) in saved) windows
      .firstOrNull { it.id == id }
      ?.let { open[Place.valueOf(place)] = it }
  }

  fun open(window: ToolWindow, layout: ScreenLayout) {
    open[window.place] = window
    last[window.place] = window
    when (layout) {
      ScreenLayout.COMPACT -> open.keys.filter { it != window.place }.forEach(open::remove)
      ScreenLayout.WIDE,
      ScreenLayout.PORTRAIT -> Unit
    }
  }

  fun close(window: ToolWindow) {
    if (isOpen(window)) open.remove(window.place)
  }

  /** Tapping a window's button: closes it when open, opens it otherwise. */
  fun toggle(window: ToolWindow, layout: ScreenLayout) =
    if (isOpen(window)) close(window) else open(window, layout)

  fun closeSide(side: Side) = Place.entries.filter { it.side == side }.forEach(open::remove)

  fun openOn(side: Side): List<ToolWindow> =
    Place.entries.filter { it.side == side }.mapNotNull { open[it] }

  /** Opens [side]'s last windows again, if it has none open; whether there is any now. */
  fun reopen(side: Side): Boolean {
    if (openOn(side).isEmpty())
      Place.entries
        .filter { it.side == side }
        .forEach { place -> last[place]?.let { open[place] = it } }
    return openOn(side).isNotEmpty()
  }

  /** Whether a swipe can show [side]: it has a window open or one to open again. */
  fun canShow(side: Side) =
    Place.entries.any { it.side == side && (open[it] != null || last[it] != null) }

  /** After a rotation: drops what the new [layout] can't show (all but one window in compact). */
  fun fit(layout: ScreenLayout) {
    when (layout) {
      ScreenLayout.COMPACT -> Place.entries.filter { it in open }.drop(1).forEach(open::remove)
      ScreenLayout.WIDE,
      ScreenLayout.PORTRAIT -> Unit
    }
  }
}

/**
 * A tool window bar: the buttons of [top]'s places from the start, separated by lines where a group
 * ends, and [foot]'s places at the far end (IntelliJ's new UI keeps the bottom windows at the foot
 * of each side's bar). ⋯ (windows without a button) closes the left top group. Scrolls when the
 * buttons don't fit.
 */
@Composable
fun ToolWindowBar(
  buttons: ToolWindowButtons,
  open: Set<ToolWindow>,
  onClick: (ToolWindow) -> Unit,
  vertical: Boolean,
  modifier: Modifier = Modifier,
  top: List<Place> = Place.entries,
  foot: List<Place> = emptyList(),
  /** The right side's bar: the buttons against the right edge, the highlight mirrored. */
  mirrored: Boolean = false,
) {
  @Composable
  fun Separator() {
    // Measured against Gateway (2026-11-17): a 1 dp line as long as the highlight and lined up
    // with it, 5 dp from the buttons on either side.
    val length =
      IslandsDark.stripeButtonWidth -
        IslandsDark.stripeHighlightStart -
        IslandsDark.stripeHighlightEnd
    val edge = if (mirrored) IslandsDark.stripeHighlightEnd else IslandsDark.stripeHighlightStart
    Box(
      (if (vertical)
          Modifier.padding(start = edge, end = edge, top = 5.dp, bottom = 5.dp).size(length, 1.dp)
        else
          Modifier.padding(start = 5.dp, end = 5.dp, bottom = IslandsDark.stripeHighlightStart)
            .size(1.dp, length))
        .background(IslandsDark.toolbarSeparator),
    )
  }

  @Composable
  fun Groups(places: List<Place>) {
    var first = true
    for (place in places) {
      val group = buttons.byPlace[place].orEmpty()
      val withMore = place == Place.LEFT_TOP && buttons.more.isNotEmpty()
      if (group.isEmpty() && !withMore) continue
      if (!first) Separator()
      first = false
      group.forEach { window ->
        StripeButton(window.icon, window.title, window in open, vertical, mirrored) {
          onClick(window)
        }
      }
      if (withMore) MoreButton(buttons.more, onClick, vertical)
    }
  }

  if (vertical) {
    BoxWithConstraints(modifier.width(IslandsDark.stripeThickness)) {
      Column(
        Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
        // Buttons against the window edge; the bar's extra 3 dp face the islands.
        horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start,
      ) {
        Column(horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
          Groups(top)
        }
        Column(horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
          Groups(foot)
        }
      }
    }
  } else {
    Row(
      modifier.height(IslandsDark.stripeThickness).horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.Bottom,
    ) {
      Groups(top + foot)
    }
  }
}

/**
 * ⋯: a menu of the tool windows that have no button, by name (IntelliJ's `MoreSquareStripeButton`).
 */
@Composable
private fun MoreButton(
  windows: List<ToolWindow>,
  onClick: (ToolWindow) -> Unit,
  vertical: Boolean,
) {
  var shown by remember { mutableStateOf(false) }
  Box {
    StripeButton("expui/general/moreHorizontal.svg", "More tool windows", shown, vertical) {
      shown = true
    }
    if (shown)
      DropDownMenu(
        entries =
          windows.map { window ->
            MenuItem(window.title, iconPath = window.icon, onClick = { onClick(window) })
          },
        beside = vertical,
        onDismiss = { shown = false },
      )
  }
}

/**
 * A menu under the composable it is placed in (a Box around the button), or [beside] it on the
 * right; moved up or left when there is no room.
 */
@Composable
internal fun DropDownMenu(entries: List<MenuEntry>, beside: Boolean, onDismiss: () -> Unit) {
  AppPopup(DropDownPosition(beside), onDismissRequest = onDismiss) {
    Menu({ entries }, close = onDismiss)
  }
}

private class DropDownPosition(private val beside: Boolean) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = if (beside) anchorBounds.right else anchorBounds.left
    var y = if (beside) anchorBounds.top else anchorBounds.bottom
    if (y + popupContentSize.height > windowSize.height)
      y = (if (beside) anchorBounds.bottom else anchorBounds.top) - popupContentSize.height
    return windowSize.keep(x, y, popupContentSize)
  }
}

/**
 * IntelliJ's `SquareStripeButton`: the whole button takes taps; a shown window's highlight and the
 * icon sit in the button minus the stripe's icon padding. A horizontal bar (under the islands)
 * turns the left stripe's button on its side: the window edge is at the bottom, the islands at the
 * top.
 */
@Composable
private fun StripeButton(
  iconPath: String?,
  description: String,
  selected: Boolean,
  vertical: Boolean,
  mirrored: Boolean = false,
  onClick: () -> Unit,
) {
  val icon = iconPath?.let { LocalIcons.current.painter(it) }
  val across = IslandsDark.stripeButtonWidth
  val along = IslandsDark.stripeButtonLength
  val top = IslandsDark.stripeHighlightTop
  val start = IslandsDark.stripeHighlightStart
  val end = IslandsDark.stripeHighlightEnd
  // IntelliJ's SquareStripeButtonLook: the hover and pressed fills take the selected highlight's
  // place and shape (ActionButton.hoverBackground / pressedBackground), not the whole button.
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val pressed by interaction.collectIsPressedAsState()
  // IntelliJ's SquareStripeButton.getAlignment: the tooltip goes on the side facing the islands; a
  // horizontal bar (IntelliJ's ToolWindowStripeExtension case) puts it above.
  HelpTooltip(
    description,
    when {
      !vertical -> TooltipAlignment.TOP
      mirrored -> TooltipAlignment.LEFT
      else -> TooltipAlignment.RIGHT
    },
  ) {
    Box(
      (if (vertical) Modifier.size(across, along) else Modifier.size(along, across))
        .clickable(interaction, indication = null, onClick = onClick)
        .padding(
          if (vertical && mirrored) PaddingValues(start = end, top = top, end = start, bottom = top)
          else if (vertical) PaddingValues(start = start, top = top, end = end, bottom = top)
          else PaddingValues(start = top, top = end, end = top, bottom = start),
        )
        .background(
          when {
            selected || pressed -> IslandsDark.stripeHighlight
            hovered -> IslandsDark.toolbarHover
            else -> Color.Transparent
          },
          RoundedCornerShape(IslandsDark.stripeHighlightRadius),
        ),
      contentAlignment = Alignment.Center,
    ) {
      icon?.let {
        Image(
          it,
          contentDescription = description,
          modifier = Modifier.size(IslandsDark.stripeIcon),
        )
      }
    }
  }
}

/**
 * A tool window island: header with the title, then [content]. With [views] the title is a view
 * selector, e.g. "Project ▾" with Project, Packages, Project Files, …: the [title] and a chevron
 * that open the views as a menu (the new UI's `ComboContentLayout`).
 */
@Composable
fun ToolWindowPanel(
  window: ToolWindow,
  /**
   * Whether a line under the header, in ToolWindow.Header.borderColor, sets the title apart from
   * the content; see [headerLineShown] for IntelliJ's rule. The scrolled case (d) is added here:
   * the content reports it through [LocalHeaderScrolled].
   */
  headerLine: Boolean,
  modifier: Modifier = Modifier,
  title: String = window.title,
  views: List<MenuEntry>? = null,
  /**
   * Content in the header after the title, taking the room up to the buttons: the Terminal's tabs.
   */
  headerContent: (@Composable () -> Unit)? = null,
  /** The header's buttons, left to right, before Hide. */
  actions: List<HeaderAction> = emptyList(),
  /** Hide (IntelliJ's "—" at the header's end); null: no Hide button. */
  onHide: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val fonts = LocalClientFonts.current
  val scrolled = remember { mutableStateOf(false) }
  Column(modifier) {
    Row(
      Modifier.fillMaxWidth()
        .height(IslandsDark.headerHeight)
        .padding(start = IslandsDark.headerLeftInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Bold in the new UI (ComboContentLayout: BaseLabel(ui, ExperimentalUI.isNewUI())). Inter
      // has no Bold file; IntelliJ draws bold UI text with Inter SemiBold.
      var viewsShown by remember { mutableStateOf(false) }
      Box {
        Row(
          if (views == null) Modifier
          else Modifier.clickable { viewsShown = true }.padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BasicText(
            title,
            style =
              fonts
                .label()
                .copy(
                  fontSize = IslandsDark.headerFontSize.sp,
                  fontWeight = FontWeight.SemiBold,
                ),
          )
          if (views != null)
            LocalIcons.current.painter("expui/general/chevronDown.svg")?.let {
              Image(it, null, Modifier.padding(start = 2.dp).size(16.dp))
            }
        }
        if (viewsShown && views != null)
          DropDownMenu(views, beside = false, onDismiss = { viewsShown = false })
      }
      if (headerContent != null) {
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { headerContent() }
      } else Spacer(Modifier.weight(1f))
      val buttons =
        actions + listOfNotNull(onHide?.let { HeaderAction("expui/general/hide.svg", "Hide", it) })
      buttons.forEach { action ->
        IconButton(action.iconPath, action.name, action.onClick, Modifier.padding(end = 2.dp))
      }
      Spacer(Modifier.width(6.dp))
    }
    if (headerLine || scrolled.value)
      Box(Modifier.fillMaxWidth().height(1.dp).background(Bars.headerBorder))
    Box(Modifier.fillMaxWidth().weight(1f)) {
      CompositionLocalProvider(LocalHeaderScrolled provides scrolled) { content() }
    }
  }
}

/**
 * IntelliJ's rule for the line under a tool window header (new UI,
 * `ToolWindowHeader.paintComponent`), without the scrolled case ([LocalHeaderScrolled]). It is
 * drawn when any of these holds:
 * - [docked at the bottom][bottom] (`anchor == BOTTOM`): Run, Build, Terminal, Problems, Git;
 * - the header shows tabs, more than one [tabCount] (TABBED content, `Run: Main, Slow`);
 * - a [toolbar] sits right under the header (`hasTopToolbar()`, e.g. Gradle);
 * - [portrait]: Ideate's phone held upright, where the window covers the editor.
 *
 * `HIDE_HEADER_BOTTOM_LINE` would switch it off, but nothing in Community sets it.
 */
fun headerLineShown(bottom: Boolean, tabCount: Int, toolbar: Boolean, portrait: Boolean) =
  bottom || tabCount > 1 || toolbar || portrait

/**
 * Set by a tool window's scrolling content to true while it is scrolled away from its top, which
 * IntelliJ shows as a line under the header (`ToolWindowHeader.SCROLLED_STATE`, from
 * `ScrollPaneTracker`). Null outside a [ToolWindowPanel].
 */
val LocalHeaderScrolled = compositionLocalOf<MutableState<Boolean>?> { null }

/** Reports whether [listState] is scrolled away from its first row to [LocalHeaderScrolled]. */
@Composable
fun ReportHeaderScrolled(listState: LazyListState) {
  val scrolled = LocalHeaderScrolled.current ?: return
  val away by
    remember(listState) {
      derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
      }
    }
  // After the composition, not during it: writing state while composing recomposes again.
  SideEffect { scrolled.value = away }
  DisposableEffect(Unit) { onDispose { scrolled.value = false } }
}

/**
 * A tool window header button: an icon from the icons jar and its name (IntelliJ's tooltip), e.g.
 * `expui/general/locate.svg`, "Select Opened File".
 */
class HeaderAction(val iconPath: String, val name: String, val onClick: () -> Unit)

/**
 * A tool window's action toolbar from the backend, e.g. Gradle's: IntelliJ's horizontal
 * `ActionToolbarImpl` inside the content, insets 5 top and bottom and 7 at the sides
 * (`ToolBar.horizontalToolbarInsets` in the new UI), 22 dp buttons with 16 dp icons, separators 7
 * dp wide with a 1 dp line (`MySeparator`). A disabled action shows its icon faded and does
 * nothing; a group with an icon (Gradle's settings) opens its items below it. The text goes in the
 * description, as IntelliJ shows it in the tooltip.
 */
@Composable
fun ToolWindowToolbar(entries: List<MenuEntry>, modifier: Modifier = Modifier) {
  if (entries.isEmpty()) return
  Row(
    modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 7.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (entry in entries) {
      when (entry) {
        is MenuItem -> {
          val icon = entry.iconPath ?: continue
          IconButton(
            icon,
            entry.label,
            onClick = if (entry.enabled) entry.onClick else ({}),
            modifier = if (entry.enabled) Modifier else Modifier.alpha(DISABLED_ALPHA),
          )
        }
        is SubMenu -> {
          val icon = entry.iconPath ?: continue
          var open by remember { mutableStateOf(false) }
          Box {
            IconButton(
              icon,
              entry.label,
              onClick = { if (entry.enabled) open = true },
              modifier = if (entry.enabled) Modifier else Modifier.alpha(DISABLED_ALPHA),
            )
            if (open) DropDownMenu(entry.entries, beside = false, onDismiss = { open = false })
          }
        }
        MenuSeparator ->
          Box(
            Modifier.padding(horizontal = 3.dp)
              .size(1.dp, 18.dp)
              .background(IslandsDark.toolbarSeparator),
          )
      }
    }
  }
}

/** IntelliJ draws a disabled action's icon with `IconLoader.getDisabledIcon`, about this faint. */
private const val DISABLED_ALPHA = 0.4f

/**
 * Colors and fixed sizes of the bars; the sizes that follow the UI density are in [IslandsDark].
 */
private object Bars {
  val headerBorder = IslandsDark.toolWindowBorder // ToolWindow.Header.borderColor
}
