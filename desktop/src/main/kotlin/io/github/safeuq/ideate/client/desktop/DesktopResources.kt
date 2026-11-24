package io.github.safeuq.ideate.client.desktop

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import io.github.safeuq.ideate.client.ui.IconJar
import io.github.safeuq.ideate.client.ui.IconProvider
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.LafManager
import io.github.safeuq.ideate.client.ui.theme.UiDensity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

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

  /**
   * Icons from [IconJar], drawn with Skia: the platform icons jar first, then the client's other
   * jars, as the Android app packs them. The backend names some icons it expects the client to have
   * outside the icons jar, e.g. Gradle's task icon `icons/newui/task.svg` in
   * `intellij.platform.externalSystem.impl.jar`.
   */
  class JarIcons(private val clientLib: File, private val density: Density) : IconProvider {
    private val jar = IconJar(clientLib.resolve("intellij.platform.icons.jar"))
    private val cache = ConcurrentHashMap<String, Painter?>()

    /**
     * Which jar of the client has each SVG, e.g. `icons/newui/task.svg` →
     * `lib/intellij.platform.externalSystem.impl.jar`; the first one wins.
     */
    // ponytail: built on the first icon the platform jar lacks, about a second for the client's
    // jars; an index file at build time if that pause matters.
    private val otherJars: Map<String, IconJar> by lazy {
      val jars = mutableMapOf<File, IconJar>()
      val byPath = mutableMapOf<String, IconJar>()
      (clientLib.walk() + clientLib.resolveSibling("plugins").walk())
        .filter { it.isFile && it.extension == "jar" }
        .forEach { file ->
          runCatching {
            ZipFile(file).use { zip ->
              zip
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".svg") }
                .forEach { entry ->
                  byPath.putIfAbsent(entry.name, jars.getOrPut(file) { IconJar(file) })
                }
            }
          }
        }
      byPath
    }

    /** Reads [LafManager.currentTheme], so a composable asking for an icon redraws on a switch. */
    override fun painter(path: String): Painter? {
      val theme = LafManager.currentTheme
      return cache.computeIfAbsent("${theme.id}:$path") {
        (jar.svg(path, theme)
            ?: otherJars[path.removePrefix("/")]?.svg(path, theme)
            ?: IconJar.extra(path, theme))
          ?.let { svg -> loadSvgPainter(svg.byteInputStream(), density) }
      }
    }
  }
}
