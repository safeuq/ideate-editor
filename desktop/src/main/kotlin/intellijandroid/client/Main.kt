package intellijandroid.client

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import intellijandroid.client.connect.ConnectController
import intellijandroid.client.connect.ConnectScreen
import intellijandroid.client.connect.ConnectionHistory
import intellijandroid.client.connect.IdeateApp
import intellijandroid.client.connect.SshSetup
import intellijandroid.client.connect.StoredText
import intellijandroid.client.desktop.ComponentGallery
import intellijandroid.client.desktop.DesktopOpenSshFiles
import intellijandroid.client.desktop.DesktopResources
import intellijandroid.client.desktop.FileSecretStore
import intellijandroid.client.desktop.Snapshots
import intellijandroid.client.desktop.rememberMacTitleBar
import intellijandroid.client.session.OpenEditors
import intellijandroid.client.ssh.RemoteHosts
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.LocalTitleBarArea
import intellijandroid.client.ui.component.LocalResizePointers
import intellijandroid.client.ui.component.ResizePointers
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.pngDecoder
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
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
    // Without IdeateApp's effects: the snapshot composes afresh every few seconds and must not
    // refresh or reconnect.
    Snapshots.start(File(target), clientLib, fonts) {
      val session = controller.session
      if (session == null) ConnectScreen(controller.screenState(), controller.actions, fonts)
      else ClientScreen(session.state.value, session)
    }
  }
  application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    Window(
      onCloseRequest = {
        controller.close()
        exitApplication()
      },
      title = "IDEate",
      state = windowState,
    ) {
      val density = LocalDensity.current
      val icons = remember(density) { DesktopResources.JarIcons(clientLib, density) }
      // On macOS the main toolbar takes the title bar's place, as in IntelliJ.
      val titleBar =
        rememberMacTitleBar(window, windowState.placement == WindowPlacement.Fullscreen)
      CompositionLocalProvider(
        LocalClientFonts provides fonts,
        LocalIcons provides icons,
        LocalTitleBarArea provides titleBar,
        // IntelliJ's Splitter cursors.
        LocalResizePointers provides
          ResizePointers(
            sideBySide =
              PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.W_RESIZE_CURSOR)),
            stacked =
              PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)),
          ),
      ) {
        IdeateApp(controller, fonts)
      }
    }
  }
}

/** One stored string in a file under `~/.ideate`. */
private class FileText(private val file: File) : StoredText {
  override fun read(): String? = file.takeIf { it.isFile }?.readText()

  override fun write(text: String) = file.writeText(text)
}
