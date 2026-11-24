package io.github.safeuq.ideate.client.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import io.github.safeuq.ideate.client.ui.component.PopupHost
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dialog
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Each popup in its own borderless window owned by [owner], as IntelliJ's popups are, so a menu
 * near the window's edge goes past it (docs/intellij-look.md section 24). The popup's
 * [PopupPositionProvider] works in the screen's usable area (no menu bar, no Dock): `windowSize` is
 * that area and the anchor is placed in it, so the providers' "keep inside the window" keeps the
 * popup on screen. Content is measured against that area too, not against the window.
 *
 * Example, 2x screen of 1512 × 982 points with a 33 point menu bar, window content at (200, 100): a
 * menu asked for at (40, 600) in the window gets anchor `(400 + 80, 134 + 1200)` px and
 * `windowSize` `3024 × 1898` px.
 */
class WindowPopupHost(private val owner: Window) : PopupHost {
  // setContent's key listeners are experimental in Compose 1.12.
  @OptIn(ExperimentalComposeUiApi::class)
  @Composable
  override fun Show(
    position: PopupPositionProvider,
    onDismissRequest: (() -> Unit)?,
    focusable: Boolean,
    content: @Composable () -> Unit,
  ) {
    val locals = currentCompositionLocalContext
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentPosition by rememberUpdatedState(position)
    // The layout this popup is called in, in the owner window's px, as Compose's Popup anchors.
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    var contentSize by remember { mutableStateOf<IntSize?>(null) }
    Layout(
      content = {},
      modifier =
        Modifier.onGloballyPositioned { coordinates ->
          val bounds = (coordinates.parentLayoutCoordinates ?: coordinates).boundsInWindow()
          anchor =
            IntRect(
              bounds.left.roundToInt(),
              bounds.top.roundToInt(),
              bounds.right.roundToInt(),
              bounds.bottom.roundToInt(),
            )
        },
    ) { _, _ ->
      layout(0, 0) {}
    }
    val dialog =
      remember(focusable) {
        ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
          isUndecorated = true
          isTransparent = true
          isResizable = false
          type = Window.Type.POPUP
          focusableWindowState = focusable
        }
      }
    DisposableEffect(dialog) {
      dialog.setContent(
        onPreviewKeyEvent = { false },
        onKeyEvent = { event ->
          // Escape closes the popup, as Compose's Popup does (dismissOnBackPress).
          val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
          if (escape) currentDismiss?.invoke()
          escape && currentDismiss != null
        },
      ) {
        CompositionLocalProvider(locals) {
          val limit = screenArea(owner)
          // Measured against the screen, not the dialog's current size, which starts at 1 × 1.
          Layout(currentContent) { measurables, _ ->
            val constraints = Constraints(maxWidth = limit.width, maxHeight = limit.height)
            val placeables = measurables.map { it.measure(constraints) }
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val height = placeables.maxOfOrNull { it.height } ?: 0
            contentSize = IntSize(width, height)
            layout(width, height) { placeables.forEach { it.place(0, 0) } }
          }
        }
      }
      // A click outside (the owner, another app) takes the focus away and closes the popup.
      val focusListener =
        object : WindowFocusListener {
          override fun windowGainedFocus(event: WindowEvent) = Unit

          override fun windowLostFocus(event: WindowEvent) {
            if (event.oppositeWindow?.let { isOwnedBy(it, dialog) } != true)
              currentDismiss?.invoke()
          }
        }
      dialog.addWindowFocusListener(focusListener)
      // A popup that never takes the focus (the usages list after a ⌘-click) never loses it
      // either, so a press anywhere outside it closes it, as IntelliJ's popups with
      // cancelOnClickOutside (IdePopupManager on MOUSE_PRESSED).
      val pressListener = AWTEventListener { event ->
        if (focusable || event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        val source = event.source as? Component ?: return@AWTEventListener
        val window = source as? Window ?: SwingUtilities.getWindowAncestor(source)
        if (window == null || !isOwnedBy(window, dialog)) currentDismiss?.invoke()
      }
      Toolkit.getDefaultToolkit().addAWTEventListener(pressListener, AWTEvent.MOUSE_EVENT_MASK)
      dialog.setSize(1, 1)
      dialog.isVisible = true
      onDispose {
        Toolkit.getDefaultToolkit().removeAWTEventListener(pressListener)
        dialog.removeWindowFocusListener(focusListener)
        dialog.dispose()
      }
    }
    LaunchedEffect(dialog, anchor, contentSize, currentPosition) {
      val anchorInWindow = anchor ?: return@LaunchedEffect
      val size = contentSize ?: return@LaunchedEffect
      dialog.bounds = popupBounds(owner, anchorInWindow, size, currentPosition)
    }
  }
}

/**
 * Where a popup of [size] px goes on screen, in AWT points: [position] gets [anchorInWindow] moved
 * into the screen's usable area and that area's size, both in px.
 */
internal fun popupBounds(
  owner: Window,
  anchorInWindow: IntRect,
  size: IntSize,
  position: PopupPositionProvider,
): Rectangle {
  val area = usableScreen(owner)
  val scale = owner.graphicsConfiguration.defaultTransform.scaleX
  val origin =
    (owner as? RootPaneContainer)?.contentPane?.locationOnScreen ?: owner.locationOnScreen
  val shiftX = ((origin.x - area.x) * scale).roundToInt()
  val shiftY = ((origin.y - area.y) * scale).roundToInt()
  val placed =
    position.calculatePosition(
      anchorInWindow.translate(shiftX, shiftY),
      IntSize((area.width * scale).roundToInt(), (area.height * scale).roundToInt()),
      LayoutDirection.Ltr,
      size,
    )
  return Rectangle(
    area.x + (placed.x / scale).roundToInt(),
    area.y + (placed.y / scale).roundToInt(),
    ceil(size.width / scale).toInt(),
    ceil(size.height / scale).toInt(),
  )
}

/** The usable part of [window]'s screen (no menu bar, no Dock), in AWT points. */
private fun usableScreen(window: Window): Rectangle {
  val configuration = window.graphicsConfiguration
  val screen = configuration.bounds
  val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
  return Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    screen.width - insets.left - insets.right,
    screen.height - insets.top - insets.bottom,
  )
}

/** [usableScreen]'s size in px, the most a popup's content may take. */
private fun screenArea(window: Window): IntSize {
  val area = usableScreen(window)
  val scale = window.graphicsConfiguration.defaultTransform.scaleX
  return IntSize((area.width * scale).roundToInt(), (area.height * scale).roundToInt())
}

private fun isOwnedBy(window: Window, owner: Window): Boolean {
  var current: Window? = window
  while (current != null) {
    if (current === owner) return true
    current = current.owner
  }
  return false
}
