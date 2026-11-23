package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.IslandsDark

/**
 * The window's title bar, when the app draws into it as IntelliJ's new UI does: the main toolbar
 * takes its place and the window's own buttons sit inside the toolbar row.
 * - macOS on JetBrains Runtime: the traffic lights at the left ([start]), the row drags the window
 *   and a double click zooms it (desktop `MacTitleBar`).
 * - Android desktop windowing (API 35+): the system's caption buttons at the right ([end]), the
 *   caption bar [height] tall (android `CaptionBar`).
 *
 * Null when the platform draws its own title bar.
 */
class TitleBarArea(
  /** Room to leave at the left for the window's buttons. */
  val start: Dp,
  /** Room to leave at the right for the window's buttons. */
  val end: Dp,
  /** The least height of the row: the system's caption bar, or 0 when the row sets the height. */
  val height: Dp,
  /**
   * For every control in the row (buttons, the project widget): taps reach the app there instead of
   * dragging the window. Elsewhere the row moves the window.
   */
  val control: Modifier,
)

val LocalTitleBarArea = staticCompositionLocalOf<TitleBarArea?> { null }

/** An empty, draggable strip where the title bar is, for screens without the main toolbar. */
@Composable
fun TitleBarSpacer() {
  val area = LocalTitleBarArea.current ?: return
  Spacer(Modifier.fillMaxWidth().height(maxOf(area.height, IslandsDark.toolbarHeight)))
}

/**
 * IntelliJ's project-colour gradient behind the main toolbar and the top of the window, as Islands
 * paints it (`IslandsGradientPainter.renderFrameGradientImage`): from the left edge [background]
 * turns into the project's colour, strongest under the project widget's icon ([centerX], px), and
 * back to [background] over the next 600 dp; downwards it fades into [background] over 300 dp
 * (`RecentProject.MainToolbarGradient.width` 600, `.height` 300 in ManyIslandsDark). The colour at
 * the centre is [projectColor] blended in at 85% (`blendColorsInRgb(main, center, 0.85)`).
 *
 * ponytail: drawn at full strength in an inactive window too; IntelliJ fades it there
 * (`islandsInactiveAlpha`).
 */
fun DrawScope.drawProjectGradient(background: Color, projectColor: Color, centerX: Float) {
  val length = 600.dp.toPx()
  val height = 300.dp.toPx()
  val blended = lerp(background, projectColor, 0.85f)
  val right = centerX + length
  drawRect(
    Brush.horizontalGradient(
      0f to background,
      centerX / right to blended,
      1f to background,
      startX = 0f,
      endX = right,
    ),
    topLeft = Offset.Zero,
    size = Size(minOf(right, size.width), height),
  )
  drawRect(
    Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), 0f, height),
    topLeft = Offset.Zero,
    size = Size(minOf(right, size.width), height),
  )
}
