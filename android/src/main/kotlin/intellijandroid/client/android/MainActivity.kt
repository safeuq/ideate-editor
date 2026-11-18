package intellijandroid.client.android

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import intellijandroid.client.connect.ConnectApp
import intellijandroid.client.connect.ConnectController
import intellijandroid.client.connect.ConnectionHistory
import intellijandroid.client.connect.SshSetup
import intellijandroid.client.connect.StoredText
import intellijandroid.client.session.OpenEditors
import intellijandroid.client.ssh.RemoteHosts
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.pngDecoder

/**
 * The connections window: the connect screen (link prompt, backends on this phone, history;
 * docs/connections.md), shared with the desktop ([ConnectApp]). A session opens in
 * [EditorActivity]. For scripted runs: `adb shell am start -n
 * intellijandroid.client.android/.MainActivity --es joinLink '<link>' [--es openFile '<path>']`
 * connects at once.
 */
class MainActivity : ComponentActivity() {
  private lateinit var controller: ConnectController
  private val termuxPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refresh() }

  override fun onCreate(savedInstanceState: Bundle?) {
    edgeToEdge()
    super.onCreate(savedInstanceState)
    window.isNavigationBarContrastEnforced = false
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
        openEditors = OpenEditors(preferenceText("connection", "openEditors")),
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

    IdeateProcess.controller = controller
    IdeateProcess.fonts = fonts
    IdeateProcess.icons = icons

    setContent {
      // A session shows in the editor window (EditorActivity); this one stays the connections
      // window.
      val connected = controller.session != null
      LaunchedEffect(connected) { if (connected) EditorActivity.start(this@MainActivity) }
      IdeateWindow(fonts, icons) {
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
    IdeateProcess.started(-1)
    super.onStop()
  }

  // Closing the connections window ends the session, as quitting the desktop app does.
  override fun onDestroy() {
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
