package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import intellijandroid.client.ui.component.IconButton
import intellijandroid.client.ui.component.Menu
import intellijandroid.client.ui.component.Menus
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import kotlin.math.abs

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
 * A main toolbar dropdown (`ToolbarComboButton`): the project widget, the run configurations. A box
 * of the toolbar button's height with MainToolbar.Dropdown's padding, filled with
 * `toolbar-bg-hovered` under the pointer (Gateway's widget measured 25 dp high, 10 dp before the
 * avatar).
 */
@Composable
private fun ToolbarDropdown(
  control: Modifier,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(IslandsDark.toolbarButtonRadius)
  Row(
    modifier
      .then(control)
      .height(IslandsDark.toolbarButton)
      .clip(shape)
      .background(if (hovered) IslandsDark.toolbarHover else Color.Transparent)
      .hoverable(interaction)
      .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
      .padding(start = IslandsDark.toolbarDropdownStart, end = IslandsDark.toolbarDropdownEnd),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

/**
 * IntelliJ's main toolbar, reduced: the project widget on the left; the run widget, Build, Run,
 * Debug and More on the right. Only the project name is live; the rest reports "not supported yet"
 * through [onUnsupported].
 */
@Composable
fun MainToolbar(
  projectName: String?,
  onUnsupported: (String) -> Unit,
  modifier: Modifier = Modifier,
  /** The project widget's icon centre in the window, in px: where the project gradient peaks. */
  onProjectIconCenter: (Float) -> Unit = {},
  /**
   * Where the islands start, so the project widget's box lines up with them when the window has its
   * own title bar: after the tool window bar in landscape, as Gateway's second row does.
   */
  islandsStart: Dp = IslandsDark.islandGap,
) {
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val text = fonts.label().copy(fontSize = IslandsDark.toolbarFontSize.sp)
  // In the window's title bar (TitleBar.kt): leave the window's buttons their room, be at least as
  // tall as the system's caption bar, and keep the controls from dragging the window.
  val titleBar = LocalTitleBarArea.current
  val control = titleBar?.control ?: Modifier
  Row(
    modifier
      .fillMaxWidth()
      .height(maxOf(IslandsDark.toolbarHeight, titleBar?.height ?: 0.dp))
      // After the window's buttons when they are in this row (macOS); else lined up with the
      // islands.
      .padding(
        start = titleBar?.start?.takeIf { it > 0.dp } ?: islandsStart,
        end = (titleBar?.end ?: 0.dp) + 6.dp,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val name = projectName ?: "…"
    ToolbarDropdown(control, Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
      // The project widget's avatar as AvatarUtils.generateColoredAvatar draws it: sized like the
      // toolbar icons (RecentProject.Avatar.iconSize falls back to MainToolbar.Button.iconSize),
      // corners of arcRatio 0.4 (an arc diameter, so a radius of 0.2 × size), the initials in
      // JetBrains Mono SemiBold at 13/20 of the size (16 → 10, 20 → 13).
      val avatarSize = IslandsDark.toolbarIcon
      Box(
        Modifier.size(avatarSize)
          .onGloballyPositioned { coordinates ->
            onProjectIconCenter(coordinates.positionInWindow().x + coordinates.size.width / 2f)
          }
          .background(projectAvatarBrush(name), RoundedCornerShape(avatarSize * 0.2f)),
        contentAlignment = Alignment.Center,
      ) {
        BasicText(
          initials(name),
          style =
            TextStyle(
              color = Color.White,
              fontFamily = fonts.editor,
              fontWeight = FontWeight.SemiBold,
              fontSize = (avatarSize.value.toInt() * 13 / 20).sp,
            ),
        )
      }
      BasicText(
        name,
        style = text.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp),
      )
      icons.painter("expui/general/chevronDown.svg")?.let {
        Image(it, null, Modifier.padding(start = 2.dp).size(16.dp))
      }
    }
    Spacer(Modifier.weight(1f))
    ToolbarDropdown(control, onClick = { onUnsupported("Run configurations") }) {
      BasicText("Current File", style = text, maxLines = 1)
      icons.painter("expui/general/chevronDown.svg")?.let {
        Image(it, null, Modifier.padding(start = 2.dp).size(16.dp))
      }
    }
    Row(control, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      listOf(
          "expui/build/build.svg" to "Build",
          "expui/run/run.svg" to "Run",
          "expui/run/debug.svg" to "Debug",
          "expui/general/moreVertical.svg" to "More actions",
        )
        .forEach { (path, label) ->
          IconButton(
            path,
            label,
            onClick = { onUnsupported(label) },
            size = IslandsDark.toolbarButton,
            iconSize = IslandsDark.toolbarIcon,
          )
        }
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
 * IntelliJ's `AvatarUtils.initials`: `xtool` → `X`, `MyProject` → `MP`, `xtool-web-app` → `XA`. Two
 * capitals from camel case win; otherwise the first and last word, split at the first of space,
 * comma, `-`, `_`, `.` or quotes that gives two words; otherwise the camel case letters (just one
 * for a single lower-case word).
 */
internal fun initials(name: String): String {
  val camelCase =
    name
      .dropWhile { !it.isLetter() }
      .takeWhile { it.isLetterOrDigit() }
      .filterIndexed { index, character -> index == 0 || character.isUpperCase() }
      .take(2)
      .uppercase()
  if (camelCase.length == 2) return camelCase
  val trimmed = name.filter { !it.isSurrogate() }.trim()
  val words =
    listOf(" ", ",", "-", "_", ".", "`'\"").firstNotNullOfOrNull { delimiters ->
      trimmed
        .split(*delimiters.toCharArray())
        .map { word -> word.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
        .takeIf { it.size >= 2 }
    } ?: return camelCase
  return "${words.first().first()}${words.last().first()}".uppercase()
}

/**
 * The project avatar's gradient (`AvatarUtils.generateColoredAvatar`):
 * `RecentProject.ColorN.Avatar` End at the bottom left to Start at the top right. IntelliJ keeps
 * each project's color index in the project's workspace, which the client can't read, so this picks
 * it as IntelliJ does for a non-local project
 * (`RecentProjectIconHelper.getGeneratedNonLocalProjectIcon`): `abs(id.hashCode() % 9)`, with the
 * project name as the id. `smoke` → Color4, blue.
 */
fun projectAvatarBrush(name: String): Brush {
  val (start, end) = AVATAR_GRADIENTS[projectColorIndex(name)]
  return Brush.linearGradient(
    listOf(end, start),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f),
  )
}

/** The project's colour, 0 to 8, picked as [projectAvatarBrush] describes. */
private fun projectColorIndex(name: String) = abs(name.hashCode() % AVATAR_GRADIENTS.size)

/**
 * The colour of the project gradient behind the main toolbar ([drawProjectGradient]): the same
 * colour index as the avatar, `RecentProject.ColorN.MainToolbarGradientStart` in
 * ManyIslandsDark.theme.json. `smoke` → Color4 `#335661`.
 */
fun projectGradientColor(name: String): Color = MAIN_TOOLBAR_GRADIENT[projectColorIndex(name)]

private val MAIN_TOOLBAR_GRADIENT =
  listOf(
    Color(0xFF664832),
    Color(0xFF5E4724),
    Color(0xFF45522F),
    Color(0xFF335661),
    Color(0xFF394773),
    Color(0xFF613861),
    Color(0xFF503D70),
    Color(0xFF325959),
    Color(0xFF365439),
  )

/** `RecentProject.Color1..9.Avatar.Start/End` in ManyIslandsDark.theme.json. */
private val AVATAR_GRADIENTS =
  listOf(
    Color(0xFFE08855) to Color(0xFFE9806F),
    Color(0xFFB08B14) to Color(0xFFBB7F19),
    Color(0xFFA1A359) to Color(0xFF87AA59),
    Color(0xFF3B92B8) to Color(0xFF6183EC),
    Color(0xFF3574F0) to Color(0xFF7A64F0),
    Color(0xFFC84D8F) to Color(0xFFA956CF),
    Color(0xFF955AE0) to Color(0xFFA84DE0),
    Color(0xFF24A394) to Color(0xFF279CCD),
    Color(0xFF5FAD65) to Color(0xFF3D968B),
  )

/**
 * Colors and fixed sizes of the bars; the sizes that follow the UI density are in [IslandsDark].
 */
private object Bars {
  val headerBorder = IslandsDark.toolWindowBorder // ToolWindow.Header.borderColor
}
