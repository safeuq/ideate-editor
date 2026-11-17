package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.session.BackendToolWindow
import intellijandroid.client.ui.component.Menu
import intellijandroid.client.ui.component.Menus
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

enum class Side {
  LEFT,
  RIGHT,
}

/**
 * A tool window button: one of the backend's tool windows ([BackendToolWindow]) or Project, which
 * JetBrains Client registers itself. Only Project and Structure work; the others say "not supported
 * yet" when tapped. Structure sits on the right here (IntelliJ puts it on the left), so one window
 * can be open on each side. Two are the same window when their [id]s are.
 */
class ToolWindow(
  val id: String,
  val title: String,
  val icon: String?,
  val side: Side,
  /** On the left bar's lower part, where the new UI puts the bottom tool windows. */
  val bottomGroup: Boolean = false,
) {
  val supported
    get() = id == PROJECT.id || id == STRUCTURE.id

  override fun equals(other: Any?) = other is ToolWindow && other.id == id

  override fun hashCode() = id.hashCode()

  companion object {
    val PROJECT = ToolWindow("Project", "Project", "expui/toolwindows/project.svg", Side.LEFT)
    val STRUCTURE =
      ToolWindow("Structure", "Structure", "expui/toolwindows/structure.svg", Side.RIGHT)
  }
}

/**
 * The bar's buttons: [groups] separated by lines (left stripe, its lower part, right stripe), and
 * the rest of the available windows behind the ⋯ button ([more]).
 */
data class ToolWindowButtons(val groups: List<List<ToolWindow>>, val more: List<ToolWindow>)

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
    // Bottom, which the new UI shows on the left bar's lower part
    "Version Control",
    "Problems",
    "Problems View",
    "Terminal",
    "Services",
    // Right
    "Notifications",
    "AIAssistant",
    "Database",
    "Gradle",
    "Maven",
  )

/**
 * The buttons for the backend's [windows] plus Project. Example: on a Mac backend with no version
 * control, left: Project; lower left: Version Control, Problems; right: Structure, AI Chat,
 * Database, Gradle; under ⋯: Coverage, Database Output, Endpoints, Learn, Profiler, …; Commit is
 * unavailable, so nowhere.
 */
fun toolWindowButtons(windows: List<BackendToolWindow>): ToolWindowButtons {
  val available =
    listOf(ToolWindow.PROJECT) +
      windows
        .filter { it.available && it.id != ToolWindow.PROJECT.id }
        .map { window ->
          ToolWindow(
            window.id,
            window.title,
            window.iconPath,
            side =
              if (window.id == ToolWindow.STRUCTURE.id || window.anchor == "RIGHT") Side.RIGHT
              else Side.LEFT,
            bottomGroup = window.anchor == "BOTTOM",
          )
        }
  val onStripe = windows.filter { it.onStripe == true }.map { it.id }.toSet()
  val (stripe, more) = available.partition { it.id in DEFAULT_LAYOUT || it.id in onStripe }
  val ordered = stripe.sortedBy {
    DEFAULT_LAYOUT.indexOf(it.id).let { at -> if (at < 0) Int.MAX_VALUE else at }
  }
  return ToolWindowButtons(
    groups =
      listOf(
          ordered.filter { it.side == Side.LEFT && !it.bottomGroup },
          ordered.filter { it.side == Side.LEFT && it.bottomGroup },
          ordered.filter { it.side == Side.RIGHT },
        )
        .filter { it.isNotEmpty() },
    more = more.sortedBy { it.title.lowercase() },
  )
}

/** Which tool window is open on each side; at most one per side, as the portrait spec asks. */
class ToolWindowState(left: ToolWindow?, right: ToolWindow?) {
  var left by mutableStateOf(left)
  var right by mutableStateOf(right)
  /** Phone portrait: the window a swipe opens on each side (the last one used there). */
  var lastLeft by mutableStateOf(left ?: ToolWindow.PROJECT)
  var lastRight by mutableStateOf(right ?: ToolWindow.STRUCTURE)

  fun open(window: ToolWindow) =
    when (window.side) {
      Side.LEFT -> {
        left = window
        lastLeft = window
      }
      Side.RIGHT -> {
        right = window
        lastRight = window
      }
    }

  /** Tapping an open window's button closes it, like IntelliJ's stripes. */
  fun toggle(window: ToolWindow) =
    if (window == left || window == right) close(window.side) else open(window)

  fun close(side: Side) =
    when (side) {
      Side.LEFT -> left = null
      Side.RIGHT -> right = null
    }
}

/**
 * IntelliJ's stripe buttons in one bar: the left stripe's icons and ⋯ (the windows without a
 * button), its lower part, then the right stripe's, with separators between.
 */
@Composable
fun ToolWindowBar(
  buttons: ToolWindowButtons,
  open: Set<ToolWindow>,
  onClick: (ToolWindow) -> Unit,
  vertical: Boolean,
  modifier: Modifier = Modifier,
) {
  @Composable
  fun Buttons() =
    buttons.groups.forEachIndexed { index, group ->
      if (index > 0) {
        // Measured against Gateway (2026-11-17): a 1 dp line as long as the highlight and lined up
        // with it, 5 dp from the buttons on either side.
        val length =
          IslandsDark.stripeButtonWidth -
            IslandsDark.stripeHighlightStart -
            IslandsDark.stripeHighlightEnd
        Box(
          (if (vertical)
              Modifier.padding(start = IslandsDark.stripeHighlightStart, top = 5.dp, bottom = 5.dp)
                .size(length, 1.dp)
            else
              Modifier.padding(start = 5.dp, end = 5.dp, bottom = IslandsDark.stripeHighlightStart)
                .size(1.dp, length))
            .background(IslandsDark.toolbarSeparator),
        )
      }
      group.forEach { window ->
        StripeButton(window.icon, window.title, window in open, vertical) { onClick(window) }
      }
      // The new UI's "More tool windows" button closes the left stripe's upper part.
      if (index == 0 && buttons.more.isNotEmpty()) MoreButton(buttons.more, onClick, vertical)
    }
  if (vertical) {
    Column(
      modifier.width(IslandsDark.stripeThickness).verticalScroll(rememberScrollState()),
      // Buttons against the window edge; the bar's extra 3 dp face the islands.
      horizontalAlignment = Alignment.Start,
    ) {
      Buttons()
    }
  } else {
    Row(
      modifier.height(IslandsDark.stripeThickness).horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.Bottom,
    ) {
      Buttons()
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
  Popup(
    popupPositionProvider = DropDownPosition(beside),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Menu({ entries }, rowHeight = Menus.touchRowHeight, close = onDismiss)
  }
}

private class DropDownPosition(private val beside: Boolean) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    var x = if (beside) anchorBounds.right else anchorBounds.left
    var y = if (beside) anchorBounds.top else anchorBounds.bottom
    if (y + popupContentSize.height > windowSize.height)
      y = (if (beside) anchorBounds.bottom else anchorBounds.top) - popupContentSize.height
    if (x + popupContentSize.width > windowSize.width) x = windowSize.width - popupContentSize.width
    return IntOffset(x.coerceAtLeast(0), y.coerceAtLeast(0))
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
  onClick: () -> Unit,
) {
  val icon = iconPath?.let { LocalIcons.current.painter(it) }
  val across = IslandsDark.stripeButtonWidth
  val along = IslandsDark.stripeButtonLength
  val top = IslandsDark.stripeHighlightTop
  val start = IslandsDark.stripeHighlightStart
  val end = IslandsDark.stripeHighlightEnd
  Box(
    (if (vertical) Modifier.size(across, along) else Modifier.size(along, across))
      .clickable(onClick = onClick)
      .padding(
        if (vertical) PaddingValues(start = start, top = top, end = end, bottom = top)
        else PaddingValues(start = top, top = end, end = top, bottom = start),
      )
      .background(
        if (selected) IslandsDark.stripeHighlight else Color.Transparent,
        RoundedCornerShape(IslandsDark.stripeHighlightRadius),
      ),
    contentAlignment = Alignment.Center,
  ) {
    icon?.let {
      Image(it, contentDescription = description, modifier = Modifier.size(IslandsDark.stripeIcon))
    }
  }
}

/**
 * A tool window island: header with the title (and a back chevron on a phone held upright), then
 * [content]. With [views] the title is a view selector, e.g. "Project ▾" with Project, Packages,
 * Project Files, …: the [title] and a chevron that open the views as a menu (the new UI's
 * `ComboContentLayout`).
 */
@Composable
fun ToolWindowPanel(
  window: ToolWindow,
  onBack: (() -> Unit)?,
  modifier: Modifier = Modifier,
  title: String = window.title,
  views: List<MenuEntry>? = null,
  content: @Composable () -> Unit,
) {
  val fonts = LocalClientFonts.current
  Column(modifier) {
    Row(
      Modifier.fillMaxWidth()
        .height(IslandsDark.headerHeight)
        .padding(start = if (onBack != null) 4.dp else IslandsDark.headerLeftInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      onBack?.let { back ->
        LocalIcons.current.painter("expui/general/chevronLeft.svg")?.let { icon ->
          Box(
            Modifier.fillMaxHeight().width(32.dp).clickable(onClick = back),
            contentAlignment = Alignment.Center,
          ) {
            Image(icon, contentDescription = "Back to the editor", modifier = Modifier.size(16.dp))
          }
        }
      }
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
    }
    // On a phone held upright (back chevron shown) the tool window covers the editor; a line under
    // the header, in ToolWindow.Header.borderColor, sets the title apart from the content.
    if (onBack != null) Box(Modifier.fillMaxWidth().height(1.dp).background(Bars.headerBorder))
    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
  }
}

/**
 * Colors and fixed sizes of the bars; the sizes that follow the UI density are in [IslandsDark].
 */
private object Bars {
  val headerBorder = IslandsDark.toolWindowBorder // ToolWindow.Header.borderColor
}
