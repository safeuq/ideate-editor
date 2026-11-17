package intellijandroid.client.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.ui.component.IconButton
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import kotlin.math.abs

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
  /** Phone upright, away from the editor: the button back to it, at the row's right end. */
  back: ToolbarBack? = null,
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
    // A divider, then the way back to the editor; the run controls move left to make room.
    back?.let { target ->
      Box(
        Modifier.padding(horizontal = 4.dp)
          .size(1.dp, IslandsDark.toolbarIcon)
          .background(IslandsDark.toolbarSeparator),
      )
      IconButton(
        if (target.pointsRight) "expui/general/chevronRight.svg"
        else "expui/general/chevronLeft.svg",
        "Back to the editor",
        onClick = target.onClick,
        modifier = control,
        size = IslandsDark.toolbarButton,
        iconSize = IslandsDark.toolbarIcon,
      )
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
