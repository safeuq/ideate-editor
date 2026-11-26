package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.safeuq.ideate.client.session.ExecutorStatus
import io.github.safeuq.ideate.client.session.ProjectWidgetLook
import io.github.safeuq.ideate.client.session.RunSelector
import io.github.safeuq.ideate.client.session.RunToolbarButton
import io.github.safeuq.ideate.client.ui.component.IconButton
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label
import kotlin.math.abs
import kotlin.math.roundToInt

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
  val shape = RoundedCornerShape(Islands.toolbarButtonRadius)
  Row(
    modifier
      .then(control)
      .height(Islands.toolbarButton)
      .clip(shape)
      .background(if (hovered) Islands.toolbarHover else Color.Transparent)
      .hoverable(interaction)
      .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
      .padding(start = Islands.toolbarDropdownStart, end = Islands.toolbarDropdownEnd),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

private const val INACTIVE_STOP = "InactiveStopActionPlaceholder"

/**
 * IntelliJ's main toolbar, reduced: the project widget on the left; the run widget (selector, Run,
 * Debug, Stop, More), then Search Everywhere, on the right. The project name and the run widget are
 * live.
 */
@Composable
fun MainToolbar(
  projectName: String?,
  /** The backend's widget button once loaded: its text and icon replace [projectName]'s. */
  projectWidget: ProjectWidgetLook?,
  modifier: Modifier = Modifier,
  /** Opens Search Everywhere: the magnifier at the right end, as in IntelliJ's new UI. */
  onSearch: (() -> Unit)? = null,
  /** The project widget's icon centre in the window, in px: where the project gradient peaks. */
  onProjectIconCenter: (Float) -> Unit = {},
  /**
   * Where the islands start, so the project widget's box lines up with them when the window has its
   * own title bar: after the tool window bar in landscape, as Gateway's second row does.
   */
  islandsStart: Dp = Islands.islandGap,
  /** Phone upright, away from the editor: the button back to it, at the row's right end. */
  back: ToolbarBack? = null,
  /** The run widget: the selected configuration, then the backend's buttons (RunToolbar). */
  runSelector: RunSelector = RunSelector(),
  runButtons: List<RunToolbarButton> = emptyList(),
  /**
   * A run button was pressed, e.g. `Run`, `Debug`, `Stop`; [bounds]: its place in the window, dp.
   */
  onRunButton: (actionId: String, bounds: IntRect) -> Unit = { _, _ -> },
  /** The selector was pressed: asks for its popup, which [runConfigurationEntries] then holds. */
  onRunConfigurations: () -> Unit = {},
  /** The selector popup's rows; null until the backend has answered. */
  runConfigurationEntries: List<MenuEntry>? = null,
  /** The project widget was pressed; [bounds]: its place in the window, dp. */
  onProject: (bounds: IntRect) -> Unit = {},
  /** The main menu for the three-line button first in the row; null: no button (macOS). */
  mainMenu: List<MenuEntry>? = null,
  /** Whether the main menu's popup is open; held by the caller for the Alt+\\ shortcut. */
  mainMenuOpen: Boolean = false,
  /** Opens or closes the main menu's popup. */
  onMainMenuOpen: (Boolean) -> Unit = {},
) {
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val text = fonts.label().copy(fontSize = Islands.toolbarFontSize.sp)
  // In the window's title bar (TitleBar.kt): leave the window's buttons their room, be at least as
  // tall as the system's caption bar, and keep the controls from dragging the window.
  val titleBar = LocalTitleBarArea.current
  val control = titleBar?.control ?: Modifier
  Row(
    modifier
      .fillMaxWidth()
      .height(maxOf(Islands.toolbarHeight, titleBar?.height ?: 0.dp))
      // After the window's buttons when they are in this row (macOS); else lined up with the
      // islands.
      .padding(
        start = titleBar?.start?.takeIf { it > 0.dp } ?: islandsStart,
        end = (titleBar?.end ?: 0.dp) + 6.dp,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    mainMenu?.let { menus ->
      MainMenuButton(menus, mainMenuOpen, onMainMenuOpen, control)
      Spacer(Modifier.width(4.dp))
    }
    val name = projectWidget?.text?.takeIf { it.isNotEmpty() } ?: projectName ?: "…"
    // The project widget keeps its width (up to 200 dp); the run configurations give way first.
    var projectBounds by remember { mutableStateOf(IntRect.Zero) }
    val density = LocalDensity.current
    ToolbarDropdown(
      control,
      Modifier.widthIn(max = 200.dp).padding(end = 8.dp).onGloballyPositioned { coordinates ->
        // The popup's place is in dp.
        val box = coordinates.boundsInWindow()
        projectBounds =
          with(density) {
            IntRect(
              box.left.toDp().value.roundToInt(),
              box.top.toDp().value.roundToInt(),
              box.right.toDp().value.roundToInt(),
              box.bottom.toDp().value.roundToInt(),
            )
          }
      },
      onClick = { onProject(projectBounds) },
    ) {
      // Sized like the toolbar icons (RecentProject.Avatar.iconSize falls back to
      // MainToolbar.Button.iconSize).
      val iconPlace = Modifier.onGloballyPositioned { coordinates ->
        onProjectIconCenter(coordinates.positionInWindow().x + coordinates.size.width / 2f)
      }
      // An icon of the icon jar (the welcome project's home icon, a project's own icon file) is
      // 16 dp in the avatar's box; otherwise the avatar the backend names, or the name's.
      val widgetIcon = projectWidget?.iconPath?.let(icons::painter)
      if (widgetIcon != null)
        Box(iconPlace.size(Islands.toolbarIcon), contentAlignment = Alignment.Center) {
          Image(widgetIcon, null, Modifier.size(16.dp))
        }
      else ProjectAvatar(projectWidget?.avatar ?: name, Islands.toolbarIcon, iconPlace)
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
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      var selectorOpen by remember { mutableStateOf(false) }
      Box(Modifier.weight(1f, fill = false)) {
        ToolbarDropdown(
          control,
          onClick = {
            selectorOpen = true
            onRunConfigurations()
          },
        ) {
          runSelector.iconPath?.let { path ->
            icons.painter(path)?.let {
              Image(it, null, Modifier.padding(end = 6.dp).size(Islands.toolbarIcon))
            }
          }
          BasicText(runSelector.name, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
          icons.painter("expui/general/chevronDown.svg")?.let {
            Image(it, null, Modifier.padding(start = 2.dp).size(16.dp))
          }
        }
        if (selectorOpen)
          DropDownMenu(
            runConfigurationEntries ?: listOf(MenuItem("Loading…", enabled = false)),
            beside = false,
          ) {
            selectorOpen = false
          }
      }
      Row(control) {
        runButtons.forEach { button ->
          val iconPath = button.iconPath
          // The placeholder keeps Stop's place while nothing runs: an empty slot of a button's
          // size.
          if (button.actionId == INACTIVE_STOP || iconPath == null)
            Spacer(
              Modifier.size(
                Islands.runButtonWidth + Islands.runButtonGap * 2,
                Islands.toolbarButton,
              ),
            )
          else {
            val label = button.description ?: button.text ?: button.actionId
            // RunWidgetButtonLook: a running Run (and Debug) and Stop are filled; the strokes are
            // white on a fill, green on the other execution buttons, grey on the rest.
            val execution = button.executorStatus != null
            val filled =
              button.enabled &&
                (button.actionId == "Stop" || button.executorStatus == ExecutorStatus.RUNNING)
            val fill =
              when {
                !filled -> null
                button.actionId == "Stop" -> Islands.color("RunWidget.stopBackground")
                else -> Islands.color("RunWidget.runningBackground")
              }
            val tint =
              Islands.color(
                when {
                  filled -> "RunWidget.runningIconColor"
                  execution -> "RunWidget.runIconColor"
                  else -> "RunWidget.iconColor"
                },
              )
            var bounds by remember { mutableStateOf(IntRect.Zero) }
            val density = LocalDensity.current
            IconButton(
              iconPath,
              label,
              onClick = { onRunButton(button.actionId, bounds) },
              modifier =
                Modifier.onGloballyPositioned { coordinates ->
                    // The popup's place is in dp; the bounds include the gaps, as the button
                    // component's do in IntelliJ.
                    val box = coordinates.boundsInWindow()
                    bounds =
                      with(density) {
                        IntRect(
                          box.left.toDp().value.roundToInt(),
                          box.top.toDp().value.roundToInt(),
                          box.right.toDp().value.roundToInt(),
                          box.bottom.toDp().value.roundToInt(),
                        )
                      }
                  }
                  // RunWidgetButtonLook paints the highlight inside the button's border gaps.
                  .padding(horizontal = Islands.runButtonGap),
              size = Islands.toolbarButton,
              iconSize = Islands.toolbarIcon,
              enabled = button.enabled,
              width = Islands.runButtonWidth,
              fill = fill,
              tint = tint,
            )
          }
        }
      }
      onSearch?.let { search ->
        IconButton(
          "expui/general/search.svg",
          "Search Everywhere",
          onClick = search,
          modifier = control.padding(start = 6.dp),
          size = Islands.toolbarButton,
          iconSize = Islands.toolbarIcon,
        )
      }
      // A divider, then the way back to the editor; the run controls move left to make room.
      back?.let { target ->
        Box(
          Modifier.padding(horizontal = 4.dp)
            .size(1.dp, Islands.toolbarIcon)
            .background(Islands.toolbarSeparator),
        )
        IconButton(
          if (target.pointsRight) "expui/general/chevronRight.svg"
          else "expui/general/chevronLeft.svg",
          "Back to the editor",
          onClick = target.onClick,
          modifier = control,
          size = Islands.toolbarButton,
          iconSize = Islands.toolbarIcon,
        )
      }
    }
  }
}

/**
 * The main toolbar's way back to the editor on a phone held upright: a chevron pointing to where
 * the editor is ([pointsRight] on the left page), IntelliJ's `expui/general/chevronRight.svg` or
 * `chevronLeft.svg`.
 */
class ToolbarBack(val pointsRight: Boolean, val onClick: () -> Unit)

/**
 * A project's avatar as `AvatarUtils.generateColoredAvatar` draws it: corners of arcRatio 0.4 (an
 * arc diameter, so a radius of 0.2 × size), the initials in JetBrains Mono SemiBold at 13/20 of the
 * size (16 → 10, 20 → 13). The main toolbar's is 16 dp, a row of the project widget's popup 20 dp.
 */
@Composable
internal fun ProjectAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
  Box(
    modifier.size(size).background(projectAvatarBrush(name), RoundedCornerShape(size * 0.2f)),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(
      initials(name),
      style =
        TextStyle(
          color = Color.White,
          fontFamily = LocalClientFonts.current.editor,
          fontWeight = FontWeight.SemiBold,
          fontSize = (size.value.toInt() * 13 / 20).sp,
        ),
    )
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
  val color = "RecentProject.Color${projectColorIndex(name) + 1}"
  val start = Islands.color("$color.Avatar.Start")
  val end = Islands.color("$color.Avatar.End")
  return Brush.linearGradient(
    listOf(end, start),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f),
  )
}

/** The project's colour, 0 to 8, picked as [projectAvatarBrush] describes. */
private fun projectColorIndex(name: String) = abs(name.hashCode() % 9) // Color1..9

/**
 * The colour of the project gradient behind the main toolbar ([drawProjectGradient]): the same
 * colour index as the avatar, `RecentProject.ColorN.MainToolbarGradientStart` in
 * ManyIslandsDark.theme.json. `smoke` → Color4 `#335661`.
 */
fun projectGradientColor(name: String): Color =
  Islands.color("RecentProject.Color${projectColorIndex(name) + 1}.MainToolbarGradientStart")
