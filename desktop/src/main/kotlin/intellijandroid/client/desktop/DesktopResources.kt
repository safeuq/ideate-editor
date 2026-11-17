package intellijandroid.client.desktop

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import intellijandroid.client.ui.IconJar
import intellijandroid.client.ui.IconProvider
import intellijandroid.client.ui.theme.ClientFonts
import intellijandroid.client.ui.theme.UiDensity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop sources for fonts and icons: both ship with JetBrains Client, next to the protocol jars.
 * Example: `…/IntelliJ IDEA.app/Contents/lib` (icons jar) and
 * `…/Contents/jbr/Contents/Home/lib/fonts` (fonts).
 */
object DesktopResources {
  fun fonts(clientLib: File): ClientFonts {
    val fontDirectory = clientLib.resolveSibling("jbr/Contents/Home/lib/fonts")
    fun font(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
      Font(
        fontDirectory.resolve(name).also { check(it.isFile) { "Missing font $it" } },
        weight,
        style,
      )
    return ClientFonts(
      editor =
        FontFamily(
          font("JetBrainsMono-Regular.ttf", FontWeight.Normal),
          font("JetBrainsMono-Bold.ttf", FontWeight.Bold),
          font("JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
          font("JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
        ),
      ui =
        FontFamily(
          font("Inter-Regular.otf", FontWeight.Normal),
          font("Inter-SemiBold.otf", FontWeight.SemiBold),
        ),
    )
  }

  /**
   * IntelliJ IDEA's UI density on this computer, read (never written) from the newest
   * `IntelliJIdea<version>/options/ui.lnf.xml`, e.g. `~/Library/Application
   * Support/JetBrains/IntelliJIdea2026.3/options/ui.lnf.xml`. A settings folder without that file
   * is IntelliJ's default, normal; no IntelliJ settings at all gives compact.
   * `IDEATE_UI_DENSITY=normal` or `compact` overrides it.
   */
  fun uiDensity(): UiDensity {
    System.getenv("IDEATE_UI_DENSITY")?.let { chosen ->
      return UiDensity.entries.firstOrNull { it.name.equals(chosen, ignoreCase = true) }
        ?: error("IDEATE_UI_DENSITY must be normal or compact, not \"$chosen\"")
    }
    val version = Regex("""IntelliJIdea(\d+)\.(\d+)""")
    val newest =
      jetbrainsSettingsFolder()
        .listFiles()
        ?.mapNotNull { folder ->
          version.matchEntire(folder.name)?.let { match ->
            folder to match.groupValues[1].toInt() * 100 + match.groupValues[2].toInt()
          }
        }
        ?.maxByOrNull { (_, number) -> number }
        ?.first ?: return UiDensity.COMPACT
    val settings = newest.resolve("options/ui.lnf.xml")
    return if (settings.isFile) UiDensity.fromIntelliJSettings(settings.readText())
    else UiDensity.NORMAL
  }

  /** Where JetBrains IDEs keep their settings folders on this operating system. */
  private fun jetbrainsSettingsFolder(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("mac") -> home.resolve("Library/Application Support/JetBrains")
      os.contains("windows") ->
        File(requireNotNull(System.getenv("APPDATA")) { "APPDATA is not set" }, "JetBrains")
      else -> File(System.getenv("XDG_CONFIG_HOME") ?: home.resolve(".config").path, "JetBrains")
    }
  }

  /** Icons from [IconJar], drawn with Skia. */
  class JarIcons(clientLib: File, private val density: Density) : IconProvider {
    private val jar = IconJar(clientLib.resolve("intellij.platform.icons.jar"))
    private val cache = ConcurrentHashMap<String, Painter?>()

    override fun painter(path: String): Painter? =
      cache.computeIfAbsent(path) {
        (jar.svg(it) ?: IconJar.EXTRA_ICONS[it])?.let { svg ->
          loadSvgPainter(svg.byteInputStream(), density)
        }
      }
  }
}
