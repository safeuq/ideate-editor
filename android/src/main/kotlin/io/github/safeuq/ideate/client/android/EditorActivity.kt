package io.github.safeuq.ideate.client.android

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import io.github.safeuq.ideate.client.connect.ConnectController
import io.github.safeuq.ideate.client.connect.EditorApp
import io.github.safeuq.ideate.client.ui.IconProvider
import io.github.safeuq.ideate.client.ui.LocalBackHandler
import io.github.safeuq.ideate.client.ui.LocalEditorTextInput
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.LocalLandscapeRequest
import io.github.safeuq.ideate.client.ui.LocalTitleBarArea
import io.github.safeuq.ideate.client.ui.component.LocalResizePointers
import io.github.safeuq.ideate.client.ui.component.ResizePointers
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts

/**
 * What [MainActivity] and [EditorActivity] share, since the session outlives neither's screen:
 * [MainActivity] makes the controller and closes it when it is destroyed.
 */
internal object IdeateProcess {
  var controller: ConnectController? = null
  var fonts: ClientFonts? = null
  var icons: IconProvider? = null
  private var started = 0

  /** A dropped connection is retried only while one of the two activities is on screen. */
  fun started(change: Int) {
    started += change
    controller?.inForeground = started > 0
  }
}

/**
 * The editor window: the IDE screen for the open session. [MainActivity] starts it when a session
 * opens. Under desktop windowing (Android 15+) it is a task of its own, so the connections window
 * and the editor are two windows; on a phone it sits on top of the connect screen in the same task.
 * It finishes when the session ends (Back, a lost connection that isn't retried).
 */
class EditorActivity : ComponentActivity() {
  /**
   * Sent on to [MainActivity] at once; its finishing must not end a session [MainActivity]
   * restores.
   */
  private var redirected = false

  override fun onCreate(savedInstanceState: Bundle?) {
    edgeToEdge()
    super.onCreate(savedInstanceState)
    window.isNavigationBarContrastEnforced = false
    val controller = IdeateProcess.controller
    val fonts = IdeateProcess.fonts
    // Recreated by the system after the process ended: the session is gone with it.
    if (controller == null || fonts == null || controller.session == null) {
      redirected = true
      startActivity(Intent(this, MainActivity::class.java))
      finish()
      return
    }
    setContent {
      val session = controller.session
      LaunchedEffect(session == null) { if (session == null) finish() }
      if (session != null)
        IdeateWindow(fonts, checkNotNull(IdeateProcess.icons)) {
          EditorApp(controller, session, fonts, Modifier.windowInsetsPadding(appInsets()))
        }
    }
    transparentCaptionBar()
  }

  override fun onStart() {
    super.onStart()
    IdeateProcess.started(1)
  }

  // Android may end the process while the app is away; the screen is kept for the next start.
  override fun onStop() {
    IdeateProcess.controller?.saveScreen()
    IdeateProcess.started(-1)
    super.onStop()
  }

  // The window was closed (its X under desktop windowing, or swiped away): leave the session, as
  // closing the desktop app's editor window does.
  override fun onDestroy() {
    val controller = IdeateProcess.controller
    if (isFinishing && !redirected && controller?.session != null) controller.leave()
    super.onDestroy()
  }

  companion object {
    /** Opens the editor from [from]: in a window of its own when [from] is in a window. */
    fun start(from: Activity) {
      val intent = Intent(from, EditorActivity::class.java)
      if (from.isInMultiWindowMode)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
      from.startActivity(intent)
    }
  }
}

/**
 * The app's own backgrounds run behind the status and navigation bars on every Android version
 * (Android 15+ does that anyway), with light bar icons over the dark UI; the content keeps clear of
 * the bars (appInsets). Also lays out into a camera cutout on the short edges, so a phone held
 * sideways has no black strip. Call before `super.onCreate`.
 */
internal fun ComponentActivity.edgeToEdge() =
  enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
  )

/** What every Ideate window's content gets from Android. */
@Composable
internal fun IdeateWindow(
  fonts: ClientFonts,
  icons: IconProvider,
  content: @Composable () -> Unit,
) =
  CompositionLocalProvider(
    LocalClientFonts provides fonts,
    LocalIcons provides icons,
    LocalEditorTextInput provides ::editorTextInput,
    LocalBackHandler provides { enabled, onBack -> BackHandler(enabled, onBack) },
    LocalLandscapeRequest provides { needed -> LandscapeRequest(needed) },
    LocalTitleBarArea provides rememberCaptionBar(),
    // A mouse over a split's border (component/SplitPane.kt).
    LocalResizePointers provides
      ResizePointers(
        sideBySide = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW),
        stacked = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW),
      ),
    content = content,
  )

/**
 * [LocalLandscapeRequest] on Android: the first request saves the activity's orientation and asks
 * for landscape (either way round, by the sensor); when the last one leaves, the saved orientation
 * comes back. Example: portrait phone, "Trust and Open Project" opens → landscape; the dialog
 * closes → portrait again. Nothing happens on a device without landscape, in a split screen
 * (Android ignores the request there), or when the screen is already sideways.
 */
@Composable
private fun LandscapeRequest(needed: Boolean) {
  val activity = LocalActivity.current ?: return
  DisposableEffect(activity, needed) {
    if (!needed || !activity.canTurnSideways()) return@DisposableEffect onDispose {}
    if (landscapeRequests++ == 0) {
      orientationBeforeLandscape = activity.requestedOrientation
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    onDispose {
      if (--landscapeRequests == 0) activity.requestedOrientation = orientationBeforeLandscape
    }
  }
}

private fun Activity.canTurnSideways(): Boolean =
  landscapeRequests > 0 ||
    (packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE) &&
      !isInMultiWindowMode &&
      resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)

// ponytail: one count for the app; only the editor window shows backend dialogs.
private var landscapeRequests = 0
private var orientationBeforeLandscape = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
