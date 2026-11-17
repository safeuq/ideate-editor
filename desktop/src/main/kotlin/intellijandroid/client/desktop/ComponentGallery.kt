package intellijandroid.client.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.component.DefaultButton
import intellijandroid.client.ui.component.OutlinedButton
import intellijandroid.client.ui.component.TextField
import intellijandroid.client.ui.theme.ClientFonts
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

/**
 * Debug aid: `CLIENT_GALLERY=<file.png>` renders the shared components (`ui/component`) in their
 * states into one PNG and exits. A visual check needs no clicking through screens, and can't reach
 * a running backend by a misplaced click.
 */
object ComponentGallery {
  private const val WIDTH = 900
  private const val HEIGHT = 700
  private const val SCALE = 2f // Retina, as the screenshots of IntelliJ it is compared with

  fun render(target: File, clientLib: File, fonts: ClientFonts) {
    val density = Density(SCALE)
    val icons = DesktopResources.JarIcons(clientLib, density)
    val scene =
      ImageComposeScene((WIDTH * SCALE).toInt(), (HEIGHT * SCALE).toInt(), density) {
        CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
          Gallery()
        }
      }
    try {
      scene.render(0)
      // A second frame, after the focus requests ran.
      val image = scene.render(500_000_000L)
      target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    } finally {
      scene.close()
    }
  }
}

@Composable
private fun Gallery() {
  Column(
    Modifier.fillMaxSize().background(IslandsDark.islandBackground).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Section("Button.kt: DefaultButton, OutlinedButton; enabled, disabled") {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DefaultButton("OK") {}
        OutlinedButton("Cancel") {}
        DefaultButton("OK", enabled = false) {}
        OutlinedButton("Cancel", enabled = false) {}
        OutlinedButton("Check Connection and Continue") {}
      }
    }
    Section("TextField.kt: empty, text, focused, disabled, secret, multi-line") {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TextField("", {}, Modifier.width(200.dp))
          TextField("safeuq@127.0.0.1", {}, Modifier.width(200.dp))
          Focused { modifier -> TextField("focused", {}, modifier.width(200.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TextField("disabled", {}, Modifier.width(200.dp), enabled = false)
          TextField("secret", {}, Modifier.width(200.dp), secret = true)
          TextField("line 1\nline 2\nline 3", {}, Modifier.width(200.dp), singleLine = false)
        }
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  val fonts = LocalClientFonts.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText(title, style = fonts.label(IslandsDark.textSecondary))
    content()
  }
}

/** [content] with focus requested, to show the focused state. */
@Composable
private fun Focused(content: @Composable (Modifier) -> Unit) {
  val focus = remember { FocusRequester() }
  content(Modifier.focusRequester(focus))
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
