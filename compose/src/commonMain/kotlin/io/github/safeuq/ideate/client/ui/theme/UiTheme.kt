package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One IntelliJ UI theme, as `UIThemeLookAndFeelInfo`: its [id] and [name], whether it [isDark], the
 * editor color scheme it brings along ([editorSchemeId], the theme JSON's `editorScheme`, e.g.
 * Islands Light → "Light"), and its `UIManager` colors.
 *
 * Generated from JetBrains Client itself (tools/ui-colors/): [ISLANDS_DARK], [ISLANDS_LIGHT].
 */
class UiTheme
internal constructor(
  val id: String,
  val name: String,
  val isDark: Boolean,
  val editorSchemeId: String,
  colorTable: List<String>,
) {
  private val colors by lazy { UiColorTable.parse(colorTable) }

  /**
   * [key] as `JBColor.namedColor` resolves it (`JBColor.calculateColorOrNull`): the `UIManager`
   * color, else the theme's `"*"` map's first suffix, in its order, that the key ends with; null
   * when neither has it, and the caller takes its fallback.
   */
  fun color(key: String): Color? =
    colors.named[key] ?: colors.wildcard.firstOrNull { (suffix) -> key.endsWith(suffix) }?.second

  override fun toString() = name
}

/**
 * IntelliJ's `LafManager`: the current theme. Snapshot state, so whatever reads it while composing
 * or drawing is redone when it changes; code outside Compose registers a listener, as with
 * `LafManagerListener`.
 */
object LafManager {
  var currentTheme: UiTheme by mutableStateOf(ISLANDS_DARK)
    private set

  private val listeners = CopyOnWriteArrayList<(UiTheme) -> Unit>()

  /** The themes Ideate has, as IntelliJ's default pair for "Sync with OS". */
  val defaultDarkTheme: UiTheme
    get() = ISLANDS_DARK

  val defaultLightTheme: UiTheme
    get() = ISLANDS_LIGHT

  fun setCurrentLookAndFeel(theme: UiTheme) {
    if (theme == currentTheme) return
    currentTheme = theme
    listeners.forEach { listener -> listener(theme) }
  }

  /** Calls [listener] after each theme change until the returned function is called. */
  fun addListener(listener: (UiTheme) -> Unit): () -> Unit {
    listeners += listener
    return { listeners -= listener }
  }
}

/**
 * IntelliJ's "Sync with OS" (Settings | Appearance | Theme): the OS's dark or light mode picks
 * [LafManager.defaultDarkTheme] or [LafManager.defaultLightTheme], and a switch while the app runs
 * switches the theme too. Called at the top of every window's content, so what it composes already
 * reads the new theme.
 *
 * Only when this window's reading changes: a window behind another (Android's connect screen behind
 * the editor) isn't told of a switch until it shows again, and setting its stale mode on every
 * composition would flip the theme back and forth with the window in front, every frame.
 */
@Composable
fun SyncThemeWithOs() {
  val dark = isSystemInDarkTheme()
  remember(dark) {
    LafManager.setCurrentLookAndFeel(
      if (dark) LafManager.defaultDarkTheme else LafManager.defaultLightTheme,
    )
  }
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
