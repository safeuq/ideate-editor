package intellijandroid.client.ui

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
 * Added to the editor so the platform's text input can reach it (Android: opens the soft keyboard).
 * Desktop: nothing.
 */
val LocalEditorTextInput = staticCompositionLocalOf<Modifier> { Modifier }
