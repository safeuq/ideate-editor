package io.github.safeuq.ideate.client.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.connect.StartProgress
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.MenuSeparator
import io.github.safeuq.ideate.client.ui.SubMenu
import io.github.safeuq.ideate.client.ui.component.CheckboxRow
import io.github.safeuq.ideate.client.ui.component.DefaultButton
import io.github.safeuq.ideate.client.ui.component.IconButton
import io.github.safeuq.ideate.client.ui.component.IndeterminateHorizontalProgressBar
import io.github.safeuq.ideate.client.ui.component.Link
import io.github.safeuq.ideate.client.ui.component.Menu
import io.github.safeuq.ideate.client.ui.component.OutlinedButton
import io.github.safeuq.ideate.client.ui.component.RadioButtonRow
import io.github.safeuq.ideate.client.ui.component.SearchHistory
import io.github.safeuq.ideate.client.ui.component.SearchTextField
import io.github.safeuq.ideate.client.ui.component.TextField
import io.github.safeuq.ideate.client.ui.component.popupContainer
import io.github.safeuq.ideate.client.ui.dialog.DialogAction
import io.github.safeuq.ideate.client.ui.dialog.DialogWrapper
import io.github.safeuq.ideate.client.ui.dialog.MessageDialog
import io.github.safeuq.ideate.client.ui.dialog.MessageKind
import io.github.safeuq.ideate.client.ui.projectAvatarBrush
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

/**
 * Debug aid: `CLIENT_GALLERY=<file.png>` renders the shared components (`ui/component`) in their
 * states into one PNG and exits. A visual check needs no clicking through screens, and can't reach
 * a running backend by a misplaced click.
 */
object ComponentGallery {
  private const val WIDTH = 900
  private const val HEIGHT = 760
  private const val SCALE = 2f // Retina, as the screenshots of IntelliJ it is compared with

  fun render(target: File, clientLib: File, fonts: ClientFonts) {
    val density = Density(SCALE)
    val icons = DesktopResources.JarIcons(clientLib, density)
    fun page(
      file: File,
      widthDp: Int = WIDTH,
      heightDp: Int = HEIGHT,
      content: @Composable () -> Unit,
    ) {
      val scene =
        ImageComposeScene((widthDp * SCALE).toInt(), (heightDp * SCALE).toInt(), density) {
          CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
            content()
          }
        }
      try {
        scene.render(0)
        // A second frame, after the focus requests ran.
        val image = scene.render(500_000_000L)
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
      } finally {
        scene.close()
      }
    }
    // A scene has one focus, so each focused sample renders alone first and the gallery shows it
    // as an image; requesting focus in three places on one page left only the last one focused.
    fun shot(
      widthDp: Int,
      heightDp: Int,
      inset: Int,
      content: @Composable () -> Unit,
    ): ImageBitmap {
      val scene =
        ImageComposeScene((widthDp * SCALE).toInt(), (heightDp * SCALE).toInt(), density) {
          CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
            Box(Modifier.fillMaxSize().background(Islands.islandBackground).padding(inset.dp)) {
              content()
            }
          }
        }
      return try {
        scene.render(0)
        scene.render(500_000_000L).toComposeImageBitmap()
      } finally {
        scene.close()
      }
    }
    val focused =
      FocusedShots(
        textField =
          shot(202, 30, inset = 1) {
            Focused { modifier -> TextField("focused", {}, modifier.width(200.dp)) }
          },
        radio =
          shot(110, 24, inset = 0) {
            Focused { modifier -> RadioButtonRow("Focused", selected = true, modifier) {} }
          },
        checkbox =
          shot(110, 24, inset = 0) {
            Focused { modifier -> CheckboxRow("Focused", checked = false, modifier) {} }
          },
      )
    page(target) { Gallery(focused) }
    // Dialogs cover the window, so each gets its own page next to the target: out-dialog.png, …
    val base = target.path.removeSuffix(".png")
    page(File("$base-dialog.png")) { Backdrop { SampleDialog() } }
    page(File("$base-message.png")) { Backdrop { SampleMessage() } }
    page(File("$base-lux.png")) { Backdrop { SampleLuxDialog() } }
    page(File("$base-progress.png")) { Backdrop { SampleProgress() } }
    // A phone held upright (411 dp): the round × after the bar instead of the Cancel button.
    page(File("$base-progress-narrow.png"), widthDp = 411, heightDp = 300) {
      Backdrop { SampleProgress() }
    }
  }
}

@Composable
private fun Backdrop(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(Islands.windowBackground)) { content() }
}

/** DialogWrapper.kt with fields, radio buttons, an error and the south panel. */
@Composable
private fun SampleDialog() {
  DialogWrapper(
    "Connect to SSH",
    actions = listOf(DialogAction("Check Connection and Continue") {}, DialogAction("Cancel") {}),
    onCancel = {},
    error = "SSH Connection Check Failed: Auth fail for methods 'publickey'",
  ) {
    BasicText("Host:", style = LocalClientFonts.current.label())
    TextField("127.0.0.1", {})
    RadioButtonRow("Ideate key", selected = true) {}
    RadioButtonRow("Password", selected = false) {}
  }
}

/** StartProgress.kt: Gateway's "Starting the remote IDE Backend" popup. */
@Composable
private fun SampleProgress() {
  StartProgress(
    "Starting the remote IDE Backend",
    "Running intellij-backend start for xtool; this takes up to 3 minutes",
    LocalClientFonts.current,
    onCancel = {},
  )
}

/** MessageDialog.kt: the SSH host key question. */
@Composable
private fun SampleMessage() {
  val cancel = DialogAction("Cancel") {}
  MessageDialog(
    "Connecting To Remote Host",
    "The authenticity of host '[100.66.92.44]:8022' cannot be established. ssh-ed25519 key " +
      "fingerprint is c0:ec:02:38:0b:b6:50:91:f8:91:69:eb:54:91:1b:5a.\n\nAre you sure you want " +
      "to continue connecting?",
    MessageKind.QUESTION,
    listOf(DialogAction("OK") {}, cancel),
    onCancel = {},
    focused = cancel,
  )
}

@Composable
private fun Gallery(focused: FocusedShots) {
  Column(
    Modifier.fillMaxSize().background(Islands.islandBackground).padding(20.dp),
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
          Image(focused.textField, "Focused text field")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TextField("disabled", {}, Modifier.width(200.dp), enabled = false)
          TextField("secret", {}, Modifier.width(200.dp), secret = true)
          TextField("line 1\nline 2\nline 3", {}, Modifier.width(200.dp), singleLine = false)
        }
      }
    }
    Section(
      "SearchTextField.kt: empty, with text (clear icon), welcome screen's (history, undecorated)",
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchTextField("", {}, Modifier.width(200.dp))
        SearchTextField("smoke", {}, Modifier.width(200.dp))
        SearchTextField(
          "",
          {},
          Modifier.width(200.dp),
          history = SearchHistory(),
          undecorated = true,
        )
      }
    }
    Section(
      "Link.kt: link, disabled; main toolbar project avatars for smoke, ideate-editor, xtool",
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Link("~/intellij/smoke", {})
        Link("Authenticate…", {})
        Link("unreachable path", {}, enabled = false)
        listOf("smoke", "ideate-editor", "xtool").forEach { name ->
          Box(
            Modifier.size(20.dp).background(projectAvatarBrush(name), RoundedCornerShape(4.dp)),
          )
        }
      }
    }
    Section("IconButton.kt: 22 dp, selected (a toggle that is on), main toolbar size") {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton("expui/general/settings.svg", "Settings", {})
        IconButton("expui/general/show.svg", "Show hidden", {}, selected = true)
        IconButton(
          "expui/build/build.svg",
          "Build",
          {},
          size = Islands.toolbarButton,
          iconSize = Islands.toolbarIcon,
        )
      }
    }
    Section("PopupContainer.kt, Menu.kt: pointer rows; LinearProgressBar.kt: indeterminate") {
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Menu(
          {
            listOf(
              MenuItem("Copy", shortcut = "⌘C", iconPath = "expui/general/copy.svg"),
              MenuItem("Paste", shortcut = "⌘V", iconPath = "expui/general/paste.svg"),
              MenuSeparator,
              MenuItem("Find Usages", shortcut = "⌥F7", enabled = false),
              SubMenu("Refactor", emptyList()),
            )
          },
          rowHeight = 24.dp,
        ) {}
        Box(Modifier.width(300.dp).popupContainer(8.dp).padding(12.dp)) {
          IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth())
        }
      }
    }
    Toggles(focused)
  }
}

@Composable
private fun Toggles(focused: FocusedShots) {
  Section("RadioButton.kt, Checkbox.kt: off, on, focused, disabled") {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Column {
        RadioButtonRow("Password", selected = false) {}
        RadioButtonRow("Ideate key", selected = true) {}
        Image(focused.radio, "Focused radio button")
        RadioButtonRow("Disabled", selected = false, enabled = false) {}
        RadioButtonRow("Disabled on", selected = true, enabled = false) {}
      }
      Column {
        CheckboxRow("Save password", checked = false) {}
        CheckboxRow("Checked", checked = true) {}
        Image(focused.checkbox, "Focused check box")
        CheckboxRow("Disabled", checked = false, enabled = false) {}
        CheckboxRow("Disabled on", checked = true, enabled = false) {}
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  val fonts = LocalClientFonts.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText(title, style = fonts.label(Islands.textSecondary))
    content()
  }
}

/** The focused samples, each rendered in its own scene (see `render`). */
private class FocusedShots(
  val textField: ImageBitmap,
  val radio: ImageBitmap,
  val checkbox: ImageBitmap,
)

/** [content] with focus requested, to show the focused state. */
@Composable
private fun Focused(content: @Composable (Modifier) -> Unit) {
  val focus = remember { FocusRequester() }
  content(Modifier.focusRequester(focus))
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
