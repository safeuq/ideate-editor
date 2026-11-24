package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * IntelliJ UI keys (`UIManager` names such as `Panel.background`) resolved as JetBrains Client
 * resolves the colors the backend sends by name (Lux, docs/lux-protocol.md). There
 * `ColorHost.getColor(NamedColor)` is `JBColor.namedColor(name, fallback)`, which looks the key up
 * (`JBColor.calculateColorOrNull`):
 * 1. `UIManager.getColor(key)`, e.g. `ActionButton.pressedBackground` = #29FFFFFF;
 * 2. else the theme's `"*"` map: the first suffix, in the map's order, that the key ends with, e.g.
 *    `Foo.hoverBackground` → `hoverBackground` = #17FFFFFF;
 * 3. else null: the caller takes the backend's fallback, as JBColor does.
 *
 * Both come from JetBrains Client's own UIManager with Islands Dark ([ISLANDS_DARK_UI_COLORS]).
 */
object ThemeKeys {
  fun color(key: String): Color? =
    table.named[key] ?: table.wildcard.firstOrNull { (suffix) -> key.endsWith(suffix) }?.second

  private val table by lazy { UiColorTable.parse(ISLANDS_DARK_UI_COLORS) }
}

/** [named]: `UIManager` key to color; [wildcard]: the `"*"` map's suffixes, in its order. */
internal class UiColorTable(
  val named: Map<String, Color>,
  val wildcard: List<Pair<String, Color>>,
) {
  companion object {
    /** Lines `key<TAB>AARRGGBB`, and `*<TAB>suffix<TAB>AARRGGBB` for the wildcard map. */
    fun parse(parts: List<String>): UiColorTable {
      val named = HashMap<String, Color>()
      val wildcard = ArrayList<Pair<String, Color>>()
      for (line in parts.joinToString("").lineSequence().filter { it.isNotEmpty() }) {
        val fields = line.split('\t')
        when {
          fields.size == 3 && fields[0] == "*" -> wildcard += fields[1] to argb(fields[2])
          fields.size == 2 -> named[fields[0]] = argb(fields[1])
          else -> throw IllegalArgumentException("Not a UI color line: $line")
        }
      }
      return UiColorTable(named, wildcard)
    }

    private fun argb(hex: String) = Color(hex.toLong(16).toInt())
  }
}
