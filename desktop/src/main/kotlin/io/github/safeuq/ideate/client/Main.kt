package io.github.safeuq.ideate.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.safeuq.ideate.client.connect.ConnectApp
import io.github.safeuq.ideate.client.connect.ConnectController
import io.github.safeuq.ideate.client.connect.ConnectScreen
import io.github.safeuq.ideate.client.connect.ConnectionHistory
import io.github.safeuq.ideate.client.connect.EditorApp
import io.github.safeuq.ideate.client.connect.SshSetup
import io.github.safeuq.ideate.client.connect.StoredText
import io.github.safeuq.ideate.client.desktop.AgentMcp
import io.github.safeuq.ideate.client.desktop.ComponentGallery
import io.github.safeuq.ideate.client.desktop.DesktopOpenSshFiles
import io.github.safeuq.ideate.client.desktop.DesktopResources
import io.github.safeuq.ideate.client.desktop.FileSecretStore
import io.github.safeuq.ideate.client.desktop.IdeateLocals
import io.github.safeuq.ideate.client.desktop.Snapshots
import io.github.safeuq.ideate.client.session.OpenEditors
import io.github.safeuq.ideate.client.ssh.RemoteHosts
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.lux.PngDecoder
import io.github.safeuq.ideate.client.ui.lux.luxScreenScale
import io.github.safeuq.ideate.client.ui.lux.pngDecoder
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess
import org.jetbrains.skia.Image

/**
 * Desktop entry point: the same connect screen as the Android app (Link, backends on this
 * computer's ports 5990 to 5999, history), without Termux. Usage: `[join link]
 * [absolute file path on the backend to open]`; a link connects at once, as the app's `joinLink`
 * extra does. History is kept in `~/.ideate/`.
 */
fun main(arguments: Array<String>) {
  if (arguments.size > 2) {
    System.err.println(
      "Usage: [join link, e.g. tcp://127.0.0.1:5990#jt=…&fp=…] [file path on the backend to open]",
    )
    exitProcess(2)
  }
  // The protocol jars from JetBrains Client 263.7307.200 are class version 69.
  check(Runtime.version().feature() >= 25) {
    "Needs Java 25 or newer (the protocol jars are Java 25 class files); running ${Runtime.version()}"
  }
  val clientLib =
    File(
      requireNotNull(System.getProperty("jetbrains.client.lib")) {
        "System property jetbrains.client.lib is not set (the Gradle run task sets it)"
      },
    )
  val fonts = DesktopResources.fonts(clientLib)
  IslandsDark.density = DesktopResources.uiDensity()
  // Lux dialogs' images (ui/lux/LuxImages.kt).
  pngDecoder = PngDecoder { bytes ->
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
  }
  luxScreenScale =
    GraphicsEnvironment.getLocalGraphicsEnvironment()
      .defaultScreenDevice
      .defaultConfiguration
      .defaultTransform
      .scaleX
      .toFloat()
  // Debug aid: CLIENT_GALLERY=<file.png> renders the shared components and exits.
  System.getenv("CLIENT_GALLERY")?.let { target ->
    ComponentGallery.render(File(target), clientLib, fonts)
    exitProcess(0)
  }

  val dataFolder = File(System.getProperty("user.home"), ".ideate")

  if (dataFolder.mkdirs()) {
    // The history holds client private keys. Windows has no POSIX permissions; the user's home
    // folder protects it there.
    runCatching {
      Files.setPosixFilePermissions(
        dataFolder.toPath(),
        PosixFilePermissions.fromString("rwx------"),
      )
    }
  }
  val controller =
    ConnectController(
      history = ConnectionHistory(FileText(dataFolder.resolve("connection-history.json"))),
      lastLink = FileText(dataFolder.resolve("join-link.txt")),
      lastFile = FileText(dataFolder.resolve("open-file.txt")),
      openEditors = OpenEditors(FileText(dataFolder.resolve("open-editors.json"))),
      termux = null,
      device = "This computer",
      ssh =
        SshSetup(
          hosts = FileText(dataFolder.resolve("ssh-hosts.json")),
          knownHosts = FileText(dataFolder.resolve("known_hosts")),
          secrets = FileSecretStore(dataFolder.resolve("secrets")),
          openSsh = DesktopOpenSshFiles,
          alsoKnownHosts = DesktopOpenSshFiles::knownHosts,
          remoteHostFor = RemoteHosts::forSession,
          deviceName =
            runCatching { java.net.InetAddress.getLocalHost().hostName.substringBefore('.') }
              .getOrDefault("desktop"),
        ),
    )
  arguments.firstOrNull()?.let { link -> controller.connectLink(link, arguments.getOrNull(1)) }

  System.getenv("CLIENT_SNAPSHOT")?.let { target ->
    // Without ConnectApp's and EditorApp's effects: the snapshot composes afresh every few seconds
    // and must not
    // refresh or reconnect.
    Snapshots.start(File(target), clientLib, fonts) {
      val session = controller.session
      if (session == null) ConnectScreen(controller.screenState(), controller.actions, fonts)
      else ClientScreen(session.state.value, session)
    }
  }
  application {
    // Two windows, one at a time, as IntelliJ's welcome frame and project frame: the connections
    // window (FlatWelcomeFrame's default 800 × 650), and the editor window while a session is
    // open. Closing the editor window leaves the session; closing the connections window quits.
    val session = controller.session
    if (session == null) {
      IdeateWindow(
        rememberWindowState(width = 800.dp, height = 650.dp),
        clientLib,
        fonts,
        onClose = {
          controller.close()
          exitApplication()
        },
      ) {
        ConnectApp(controller, fonts)
      }
    } else {
      IdeateWindow(
        rememberWindowState(width = 1200.dp, height = 800.dp),
        clientLib,
        fonts,
        onClose = controller::leave,
      ) {
        EditorApp(controller, session, fonts)
      }
    }
  }
}

@Composable
private fun IdeateWindow(
  windowState: WindowState,
  clientLib: File,
  fonts: ClientFonts,
  onClose: () -> Unit,
  content: @Composable () -> Unit,
) {
  Window(onCloseRequest = onClose, title = "Ideate", state = windowState) {
    DisposableEffect(window) {
      AgentMcp.register(window)
      onDispose { AgentMcp.unregister(window) }
    }
    IdeateLocals(
      window,
      windowState.placement == WindowPlacement.Fullscreen,
      clientLib,
      fonts,
      content,
    )
  }
}

/** One stored string in a file under `~/.ideate`. */
private class FileText(private val file: File) : StoredText {
  override fun read(): String? = file.takeIf { it.isFile }?.readText()

  override fun write(text: String) = file.writeText(text)
}
