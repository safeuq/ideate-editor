package intellijandroid.client.android

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.core.content.edit
import intellijandroid.client.connect.ConnectController
import intellijandroid.client.connect.ConnectionHistory
import intellijandroid.client.connect.IdeateApp
import intellijandroid.client.connect.SshSetup
import intellijandroid.client.connect.StoredText
import intellijandroid.client.session.OpenEditors
import intellijandroid.client.ssh.RemoteHosts
import intellijandroid.client.ui.LocalBackHandler
import intellijandroid.client.ui.LocalEditorTextInput
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.LocalTitleBarArea
import intellijandroid.client.ui.component.LocalResizePointers
import intellijandroid.client.ui.component.ResizePointers
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.pngDecoder
import intellijandroid.client.ui.theme.LocalClientFonts

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

    setContent {
      CompositionLocalProvider(
        LocalClientFonts provides fonts,
        LocalIcons provides icons,
        LocalEditorTextInput provides ::editorTextInput,
        LocalBackHandler provides { enabled, onBack -> BackHandler(enabled, onBack) },
        LocalTitleBarArea provides rememberCaptionBar(),
        // A mouse over a split's border (component/SplitPane.kt).
        LocalResizePointers provides
          ResizePointers(
            sideBySide = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW),
            stacked = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW),
          ),
      ) {
        IdeateApp(controller, fonts, Modifier.windowInsetsPadding(appInsets()))
      }
    }
    transparentCaptionBar()
  }

  // With the screen off the activity stops; a dropped connection is retried once it starts again.
  override fun onStart() {
    super.onStart()
    controller.inForeground = true
  }

  override fun onStop() {
    controller.inForeground = false
    super.onStop()
  }

  override fun onDestroy() {
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
