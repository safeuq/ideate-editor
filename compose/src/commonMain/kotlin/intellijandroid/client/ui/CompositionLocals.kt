package intellijandroid.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/**
 * Loads IntelliJ icons by their path in the icons jar, e.g. `expui/nodes/folder.svg`; the platform
 * supplies it.
 */
fun interface IconProvider {
  fun painter(path: String): Painter?
}

val LocalIcons = staticCompositionLocalOf { IconProvider { null } }

/**
 * Added to the editor so the platform's text input can reach it: given the editor's
 * [EditorTextTarget], a modifier (Android: opens the soft keyboard and connects it to the target).
 * Desktop: nothing; its keys arrive as key events.
 */
/**
 * The platform's Back (Android: the system Back gesture or button):
 * `LocalBackHandler.current(enabled) { … }` handles it while composed and enabled; the one composed
 * last wins, as with Android's `BackHandler`. Desktop: nothing.
 */
val LocalBackHandler =
  staticCompositionLocalOf<@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit> {
    { _, _ -> }
  }

val LocalEditorTextInput = staticCompositionLocalOf<(EditorTextTarget) -> Modifier> { { Modifier } }
