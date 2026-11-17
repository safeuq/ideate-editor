package intellijandroid.client.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import intellijandroid.client.connect.ConnectController
import intellijandroid.client.connect.ConnectionHistory
import intellijandroid.client.connect.IdeateApp
import intellijandroid.client.connect.SshSetup
import intellijandroid.client.connect.StoredText
import intellijandroid.client.ssh.RemoteHosts
import intellijandroid.client.ui.LocalBackHandler
import intellijandroid.client.ui.LocalEditorTextInput
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.pngDecoder
import intellijandroid.client.ui.theme.LocalClientFonts
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The connect screen (link prompt, backends on this phone, history; docs/connections.md), then the
 * IDE screen; both shared with the desktop ([IdeateApp]). For scripted runs: `adb shell am start -n
 * intellijandroid.client.android/.MainActivity --es joinLink '<link>' [--es openFile '<path>']`
 * connects at once.
 */
class MainActivity : ComponentActivity() {
  private lateinit var controller: ConnectController
  private val termuxPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refresh() }
  /** The connection notification (ConnectionService); the service runs without it too. */
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Lux dialogs' images (ui/lux/LuxImages.kt).
    pngDecoder = PngDecoder { bytes ->
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    val fonts = AndroidResources.fonts(this)
    val icons =
      AndroidResources.SvgIcons(AndroidResources.iconJar(this), resources.displayMetrics.density)
    val termux = AndroidTermux(this) { termuxPermission.launch(AndroidTermux.PERMISSION) }
    // The same preference files and keys as before the connect screen moved to shared code, so
    // history survives updates.
    controller =
      ConnectController(
        history = ConnectionHistory(preferenceText("connection-history", "entries")),
        lastLink = preferenceText("connection", "joinLink"),
        lastFile = preferenceText("connection", "openFile"),
        termux = termux,
        device = "This phone",
        ssh =
          SshSetup(
            hosts = preferenceText("ssh", "hosts"),
            knownHosts = preferenceText("ssh", "known_hosts"),
            secrets = AndroidSecretStore(this),
            openSsh = null,
            remoteHostFor = RemoteHosts::forSession,
            deviceName = android.os.Build.MODEL,
          ),
      )
    intent.getStringExtra("joinLink")?.let { extra ->
      controller.connectLink(extra, intent.getStringExtra("openFile"))
    }

    setContent {
      CompositionLocalProvider(
        LocalClientFonts provides fonts,
        LocalIcons provides icons,
        LocalEditorTextInput provides ::editorTextInput,
        LocalBackHandler provides { enabled, onBack -> BackHandler(enabled, onBack) },
      ) {
        IdeateApp(controller, fonts, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        // A foreground service while a session is open keeps the network when the screen is off.
        val current = controller.session
        LaunchedEffect(current) {
          if (current == null) ConnectionService.stop(this@MainActivity)
          else {
            if (
              checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            )
              notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            current.state
              .map { it.projectName }
              .distinctUntilChanged()
              .collect { project -> ConnectionService.start(this@MainActivity, project) }
          }
        }
      }
    }
  }

  override fun onDestroy() {
    ConnectionService.stop(this)
    controller.close()
    super.onDestroy()
  }

  private fun preferenceText(file: String, key: String) =
    object : StoredText {
      private val preferences = getSharedPreferences(file, Context.MODE_PRIVATE)

      override fun read(): String? = preferences.getString(key, null)

      override fun write(text: String) = preferences.edit { putString(key, text) }
    }
}
