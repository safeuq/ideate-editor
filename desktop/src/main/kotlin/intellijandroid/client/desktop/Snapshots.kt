package intellijandroid.client.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.LocalClientFonts
import intellijandroid.client.ui.LocalIcons
import java.io.File
import kotlin.concurrent.thread
import org.jetbrains.skia.EncodedImageFormat

/**
 * Debug aid: renders [content] (the connect or IDE screen) off-screen into [target] (PNG) every few
 * seconds, so its look can be checked without a visible or unlocked display. Enabled with the
 * environment variable `CLIENT_SNAPSHOT=<file.png>`.
 */
object Snapshots {
  private const val WIDTH = 1200
  private const val HEIGHT = 800
  private const val SCALE = 2f // Retina

  fun start(target: File, clientLib: File, fonts: ClientFonts, content: @Composable () -> Unit) {
    val density = Density(SCALE)
    val icons = DesktopResources.JarIcons(clientLib, density)
    thread(name = "client-snapshots", isDaemon = true) {
      while (true) {
        Thread.sleep(3_000)
        // Render on the UI thread: Compose state must not be read from two threads at once (the
        // window renders there).
        var png: org.jetbrains.skia.Data? = null
        javax.swing.SwingUtilities.invokeAndWait {
          val scene =
            ImageComposeScene((WIDTH * SCALE).toInt(), (HEIGHT * SCALE).toInt(), density) {
              CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
                content()
              }
            }
          png =
            try {
              scene.render().encodeToData(EncodedImageFormat.PNG)
            } finally {
              scene.close()
            }
        }
        val encoded = png ?: continue
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(encoded.bytes)
        temporary.renameTo(target)
      }
    }
  }
}
