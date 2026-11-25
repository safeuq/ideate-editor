package io.github.safeuq.ideate.client.android

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.core.content.edit
import io.github.safeuq.ideate.client.connect.ConnectApp
import io.github.safeuq.ideate.client.connect.ConnectController
import io.github.safeuq.ideate.client.connect.ConnectionHistory
import io.github.safeuq.ideate.client.connect.SshSetup
import io.github.safeuq.ideate.client.connect.StoredText
import io.github.safeuq.ideate.client.session.JetBrainsWorkspace
import io.github.safeuq.ideate.client.session.OpenEditors
import io.github.safeuq.ideate.client.ssh.RemoteHosts
import io.github.safeuq.ideate.client.ui.lux.PngDecoder
import io.github.safeuq.ideate.client.ui.lux.luxScreenScale
import io.github.safeuq.ideate.client.ui.lux.luxScreenSize
import io.github.safeuq.ideate.client.ui.lux.pngDecoder

/**
 * The connections window: the connect screen (link prompt, backends on this phone, history;
 * docs/connections.md), shared with the desktop ([ConnectApp]). A session opens in
 * [EditorActivity]. For scripted runs: `adb shell am start -n
 * io.github.safeuq.ideate.client.android/.MainActivity --es joinLink '<link>'
 * [--es openFile '<path>']` connects at once.
 */
class MainActivity : ComponentActivity() {
  private lateinit var controller: ConnectController

  /** The restored editor window is on top of this one (this window has stopped). */
  private var editorCovers by mutableStateOf(false)
  private val termuxPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refresh() }

  override fun onCreate(savedInstanceState: Bundle?) {
    edgeToEdge()
    super.onCreate(savedInstanceState)
    // The launcher icon, when the task was started some other way (`adb shell am start -n`): its
    // intent doesn't match the task's first one, so Android stacks a second connections window
    // over the editor, with a controller of its own. Close it: the editor below shows again.
    if (
      !isTaskRoot &&
        intent.action == Intent.ACTION_MAIN &&
        intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    ) {
      finish()
      return
    }
    window.isNavigationBarContrastEnforced = false
    // Lux dialogs' images (ui/lux/LuxImages.kt).
    pngDecoder = PngDecoder { bytes ->
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    luxScreenScale = resources.displayMetrics.density
    luxScreenSize =
      IntSize(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp)
    val fonts = AndroidResources.fonts(this)
    val icons =
      AndroidResources.SvgIcons(AndroidResources.iconJar(this), resources.displayMetrics.density)
    val termux = AndroidTermux(this) { termuxPermission.launch(AndroidTermux.PERMISSION) }
    val termuxSsh = AndroidOpenSshFiles(termux)
    // The same preference files and keys as before the connect screen moved to shared code, so
    // history survives updates.
    controller =
      ConnectController(
        history = ConnectionHistory(preferenceText("connection-history", "entries")),
        lastLink = preferenceText("connection", "joinLink"),
        lastFile = preferenceText("connection", "openFile"),
        openEditors =
          OpenEditors(
            preferenceText("connection", "openEditors"),
            JetBrainsWorkspace(emptyList(), filesDir.resolve("jetbrains")),
          ),
        termux = termux,
        savedSession = preferenceText("connection", "savedSession"),
        ssh =
          SshSetup(
            hosts = preferenceText("ssh", "hosts"),
            knownHosts = preferenceText("ssh", "known_hosts"),
            hiddenProjects = preferenceText("ssh", "hiddenProjects"),
            secrets = AndroidSecretStore(this),
            // Termux's ~/.ssh, offered while Termux is installed and has the permission.
            openSsh = { if (termux.isInstalled && termux.hasPermission) termuxSsh else null },
            openSshLabel = "Termux's ~/.ssh",
            openSshFolder = "Termux's ~/.ssh",
            openSshMakeKeyAdvice =
              "Make one in Termux with `ssh-keygen -t ed25519` (or run `intellij-backend setup`)",
            openSshIsDefault = true,
            // Extra trust only: a Termux failure here must not stop hosts that don't use ~/.ssh.
            alsoKnownHosts = {
              if (termux.isInstalled && termux.hasPermission) {
                runCatching { termuxSsh.knownHosts() }
                  .onFailure { failure ->
                    println("Termux known_hosts not read: ${failure.message}")
                  }
                  .getOrNull()
              } else null
            },
            remoteHostFor = RemoteHosts::forSession,
            deviceName = android.os.Build.MODEL,
          ),
      )
    intent.getStringExtra("joinLink")?.let { extra ->
      controller.connectLink(extra, intent.getStringExtra("openFile"))
    }

    IdeateProcess.controller = controller
    IdeateProcess.fonts = fonts
    IdeateProcess.icons = icons
    // The editor window was open when Android ended the process: it opens again at once with its
    // saved screen, and this window shows nothing until it covers it.
    // Not when started with a link: that connection is the one wanted.
    val restored = intent.getStringExtra("joinLink") == null && controller.restoreSaved()

    setContent {
      // A session shows in the editor window (EditorActivity); this one stays the connections
      // window.
      val connected = controller.session != null
      LaunchedEffect(connected) { if (connected) EditorActivity.start(this@MainActivity) }
      IdeateWindow(fonts, icons) {
        if (!(restored && connected && !editorCovers))
          ConnectApp(controller, fonts, Modifier.windowInsetsPadding(appInsets()))
      }
    }
    transparentCaptionBar()
  }

  // With the screen off the activities stop; a dropped connection is retried once one starts
  // again.
  override fun onStart() {
    super.onStart()
    IdeateProcess.started(1)
  }

  override fun onStop() {
    editorCovers = true
    IdeateProcess.started(-1)
    super.onStop()
  }

  // Closing the connections window ends the session, as quitting the desktop app does.
  override fun onDestroy() {
    // A second window closed at once in onCreate has no controller.
    if (!::controller.isInitialized) return super.onDestroy()
    controller.close()
    if (IdeateProcess.controller === controller) IdeateProcess.controller = null
    super.onDestroy()
  }

  private fun preferenceText(file: String, key: String) =
    object : StoredText {
      private val preferences = getSharedPreferences(file, Context.MODE_PRIVATE)

      override fun read(): String? = preferences.getString(key, null)

      override fun write(text: String) = preferences.edit { putString(key, text) }
    }
}
