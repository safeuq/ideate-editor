package intellijandroid.client.android

import android.app.Activity
import android.os.Build
import android.view.WindowInsetsController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import intellijandroid.client.ui.TitleBarArea

/**
 * Android desktop windowing (API 35+): the main toolbar goes into the window's caption bar, as
 * IntelliJ's toolbar takes the title bar on macOS. From "Customizable header insets"
 * (developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing):
 * - [transparentCaptionBar] lets the app draw under the caption bar, with light caption buttons for
 *   the dark toolbar;
 * - `WindowInsets.getBoundingRects(captionBar())` gives the system's own parts of the caption bar
 *   (the window buttons, the app chip), which the toolbar keeps clear of;
 * - the system drags the window from the rest of the caption bar; the toolbar's controls take their
 *   taps back with `systemGestureExclusion`.
 *
 * Null outside desktop windowing (no caption bar) and before API 35.
 */
@Composable
fun rememberCaptionBar(): TitleBarArea? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
  val density = LocalDensity.current
  // Read through Compose, so a change of the caption bar recomposes.
  val captionHeight = WindowInsets.captionBar.getTop(density)
  if (captionHeight == 0) return null
  val view = LocalView.current
  val rects =
    view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()
  val width = view.rootView.width
  // Each part sits against the window's left or right edge.
  var start = 0
  var end = 0
  for (rect in rects) {
    if (rect.left < width - rect.right) start = maxOf(start, rect.right)
    else end = maxOf(end, width - rect.left)
  }
  return with(density) {
    TitleBarArea(
      start = start.toDp(),
      end = end.toDp(),
      height = captionHeight.toDp(),
      control = Modifier.systemGestureExclusion(),
    )
  }
}

/**
 * The insets the app keeps clear of: all of [safeDrawing] but the caption bar from API 35, where
 * the toolbar draws into it ([rememberCaptionBar]).
 */
@Composable
fun appInsets(): WindowInsets =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
    WindowInsets.safeDrawing.exclude(WindowInsets.captionBar)
  else WindowInsets.safeDrawing

/** A see-through caption bar with light buttons (the toolbar under it is dark). */
fun Activity.transparentCaptionBar() {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
  window.insetsController?.setSystemBarsAppearance(
    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
      WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
  )
}
