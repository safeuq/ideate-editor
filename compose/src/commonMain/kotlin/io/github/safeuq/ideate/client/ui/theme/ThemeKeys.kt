package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * IntelliJ UI keys (`UIManager` names such as `Panel.background`) resolved in the current theme
 * ([LafManager.currentTheme]), as JetBrains Client resolves the colors the backend sends by name
 * (Lux, docs/lux-protocol.md): `ColorHost.getColor(NamedColor)` is `JBColor.namedColor(name,
 * fallback)`. Null: the caller takes the backend's fallback, as JBColor does.
 */
object ThemeKeys {
  fun color(key: String): Color? = LafManager.currentTheme.color(key)
}
