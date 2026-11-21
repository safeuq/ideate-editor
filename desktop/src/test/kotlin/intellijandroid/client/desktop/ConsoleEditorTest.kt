package intellijandroid.client.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeColorEntry
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeEntry
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.IdeaEffectType
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributes
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributesKey
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.IdeaColorKey
import com.jetbrains.rd.ide.model.RgbColor
import intellijandroid.client.session.ConsoleText
import intellijandroid.client.session.EditorScheme
import intellijandroid.client.session.Highlight
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.console.ConsoleEditor
import intellijandroid.client.ui.theme.LocalClientFonts
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The console editor with fixed text, no backend: the stderr line is red (`CONSOLE_ERROR_OUTPUT`),
 * the background is `CONSOLE_BACKGROUND_KEY`, and the text starts at the left edge, with no gutter
 * for line numbers. The PNG goes to `DRIVER_OUT` (default `build/driver`).
 */
@OptIn(ExperimentalTestApi::class)
class ConsoleEditorTest {
  private fun rgb(value: Int): AbstractColor =
    RgbColor((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

  private fun attributes(foreground: Int?, background: Int? = null) =
    IdeaTextAttributes(
      foreground?.let(::rgb),
      background?.let(::rgb),
      null,
      null,
      IdeaEffectType.LINE_UNDERSCORE,
      0,
    )

  private fun entry(name: String, attributes: IdeaTextAttributes) =
    ColorsSchemeEntry(IdeaTextAttributesKey(null, name), false, attributes)

  private val consoleBackground = 0x1E1F22
  private val errorRed = 0xFF6B68
  private val scheme =
    EditorScheme(
      "Test",
      listOf(
        ColorsSchemeModel(
          "Test",
          listOf(
            entry("TEXT", attributes(0xBCBEC4, 0x2B2D30)),
            entry("CONSOLE_NORMAL_OUTPUT", attributes(0xBCBEC4)),
            entry("CONSOLE_ERROR_OUTPUT", attributes(errorRed)),
          ),
          listOf(
            ColorsSchemeColorEntry(
              IdeaColorKey(null, "CONSOLE_BACKGROUND_KEY", rgb(consoleBackground)),
              false,
              rgb(consoleBackground),
            ),
          ),
        ),
      ),
    )

  @Test
  fun consoleEditorHasNoGutterAndColorsStderr() {
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")))
    val fonts = DesktopResources.fonts(clientLib)
    val text = "just a simple greeting\n22\nan error line\nProcess finished with exit code 0\n"
    val errorStart = text.indexOf("an error line")
    val console =
      ConsoleText(
        text,
        listOf(
          Highlight(
            1,
            errorStart,
            errorStart + "an error line".length,
            1001,
            scheme.style(listOf(listOf("CONSOLE_ERROR_OUTPUT"))),
          ),
        ),
      )
    runDesktopComposeUiTest(width = 600, height = 200) {
      val icons = DesktopResources.JarIcons(clientLib, Density(1f))
      setContent {
        CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
          ConsoleEditor("g9ojv29tmj8vgr2tv7gf", console, scheme)
        }
      }
      waitForIdle()
      val image = onRoot().captureToImage().toAwtImage()
      ImageIO.write(image, "png", out.resolve("console-editor.png"))
      fun rgbAt(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
      // Background: CONSOLE_BACKGROUND_KEY, not the editor's TEXT background.
      assertEquals(consoleBackground, rgbAt(590, 190))
      // The first text pixel is within the first 20 px: no gutter for line numbers (64 px in an
      // editor).
      val firstLineHasText =
        (0 until 20).any { x -> (0 until 20).any { y -> rgbAt(x, y) != consoleBackground } }
      assertTrue(firstLineHasText, "text does not start at the left edge")
      // Some pixel of the third line is nearly the stderr red.
      val redPixels =
        (0 until 200).sumOf { y ->
          (0 until 300).count { x ->
            val pixel = rgbAt(x, y)
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            red > 0xC0 && green < 0x90
          }
        }
      assertTrue(redPixels > 20, "no CONSOLE_ERROR_OUTPUT colored text ($redPixels red pixels)")
    }
  }
}
