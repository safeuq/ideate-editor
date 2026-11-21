package intellijandroid.client.desktop

import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer

/** A showing popup window (menu, tooltip, dialog) owned by the main window. */
data class PopupWindow(val window: Window, val bounds: Rectangle)

/**
 * Synthetic input and screenshots for any Compose Desktop [window], with nothing app specific. It
 * never moves the user's cursor (no `java.awt.Robot`: events go straight to the window's Skia
 * components), never takes keyboard focus and needs no macOS permission.
 *
 * Coordinates are in the target window's content, in dp (the main [window] by default; pass a
 * [PopupWindow.window] from [popupWindows] to aim at a popup). It sends `MOUSE_EXITED` and
 * `MOUSE_ENTERED` itself when the pointer changes component, as AWT would. Compose keeps keys only
 * for what has Compose focus: click it first.
 */
class ComposeWindowInput(private val window: Window) {
  private var hovered: Component? = null
  private var pressedButton = MouseEvent.NOBUTTON

  fun popupWindows(): List<PopupWindow> = onEventThread {
    window.ownedWindows.filter { it.isShowing }.map { PopupWindow(it, it.bounds) }
  }

  fun move(x: Int, y: Int, inside: Window = window) =
    sendMouse(inside, MouseEvent.MOUSE_MOVED, x, y)

  fun click(
    x: Int,
    y: Int,
    inside: Window = window,
    button: Int = MouseEvent.BUTTON1,
    clicks: Int = 1,
  ) {
    move(x, y, inside)
    for (count in 1..clicks) {
      sendMouse(inside, MouseEvent.MOUSE_PRESSED, x, y, button, count)
      sendMouse(inside, MouseEvent.MOUSE_RELEASED, x, y, button, count)
      sendMouse(inside, MouseEvent.MOUSE_CLICKED, x, y, button, count)
    }
  }

  fun doubleClick(x: Int, y: Int, inside: Window = window) = click(x, y, inside, clicks = 2)

  fun rightClick(x: Int, y: Int, inside: Window = window) =
    click(x, y, inside, button = MouseEvent.BUTTON3)

  fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, inside: Window = window) {
    move(fromX, fromY, inside)
    sendMouse(inside, MouseEvent.MOUSE_PRESSED, fromX, fromY, MouseEvent.BUTTON1, 1)
    // ponytail: ten even steps; a real drag's speed is not reproduced.
    for (step in 1..10) {
      val x = fromX + (toX - fromX) * step / 10
      val y = fromY + (toY - fromY) * step / 10
      sendMouse(inside, MouseEvent.MOUSE_DRAGGED, x, y, MouseEvent.BUTTON1)
    }
    sendMouse(inside, MouseEvent.MOUSE_RELEASED, toX, toY, MouseEvent.BUTTON1, 1)
  }

  /** Positive [notches] scroll down, negative up (AWT's `wheelRotation`). */
  fun scroll(x: Int, y: Int, notches: Int, inside: Window = window) {
    move(x, y, inside)
    onEventThread {
      val target = componentAt(inside, x, y)
      val point = SwingUtilities.convertPoint(inside.contentPane(), x, y, target)
      target.dispatchEvent(
        MouseWheelEvent(
          target,
          MouseEvent.MOUSE_WHEEL,
          System.currentTimeMillis(),
          0,
          point.x,
          point.y,
          0,
          false,
          MouseWheelEvent.WHEEL_UNIT_SCROLL,
          3,
          notches,
        ),
      )
    }
  }

  /** Each character as press, typed, release on the window's Skia layer. */
  fun type(text: String, inside: Window = window) {
    for (character in text) {
      val keyCode = KeyEvent.getExtendedKeyCodeForChar(character.code)
      sendKey(inside, KeyEvent.KEY_PRESSED, keyCode, character, 0)
      sendKey(inside, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character, 0)
      sendKey(inside, KeyEvent.KEY_RELEASED, keyCode, character, 0)
    }
  }

  /** [modifiers] are `InputEvent.*_DOWN_MASK` values, e.g. `InputEvent.META_DOWN_MASK`. */
  fun press(keyCode: Int, modifiers: Int = 0, inside: Window = window) {
    sendKey(inside, KeyEvent.KEY_PRESSED, keyCode, KeyEvent.CHAR_UNDEFINED, modifiers)
    sendKey(inside, KeyEvent.KEY_RELEASED, keyCode, KeyEvent.CHAR_UNDEFINED, modifiers)
  }

  /**
   * PNG bytes of the main window, then of each showing popup window. A new window has no picture
   * until its first frame, so this waits up to 3 s for every window's.
   */
  fun screenshots(): List<ByteArray> {
    val deadline = System.nanoTime() + 3.seconds.inWholeNanoseconds
    while (true) {
      onEventThread { pictures() }
        ?.let {
          return it
        }
      check(System.nanoTime() < deadline) {
        "Timed out after 3s waiting for every window's first frame"
      }
      Thread.sleep(20)
    }
  }

  private fun pictures(): List<ByteArray>? =
    (listOf(window) + popupWindows().map { it.window }).map { each ->
      val layer = checkNotNull(skiaLayer(each)) { "No Skia layer in ${each.javaClass.simpleName}" }
      val bitmap = layer.screenshot() ?: return null
      checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
    }

  private fun sendMouse(
    inside: Window,
    id: Int,
    x: Int,
    y: Int,
    button: Int = MouseEvent.NOBUTTON,
    clicks: Int = 0,
  ) = onEventThread {
    val target = componentAt(inside, x, y)
    val previous = hovered
    if (previous !== target) {
      if (previous != null) dispatch(previous, MouseEvent.MOUSE_EXITED, inside, x, y, 0, 0)
      dispatch(target, MouseEvent.MOUSE_ENTERED, inside, x, y, 0, 0)
      hovered = target
    }
    if (id == MouseEvent.MOUSE_PRESSED) pressedButton = button
    dispatch(target, id, inside, x, y, button, clicks)
    if (id == MouseEvent.MOUSE_RELEASED) pressedButton = MouseEvent.NOBUTTON
  }

  private fun dispatch(
    target: Component,
    id: Int,
    inside: Window,
    x: Int,
    y: Int,
    button: Int,
    clicks: Int,
  ) {
    val point = SwingUtilities.convertPoint(inside.contentPane(), x, y, target)
    val held = if (pressedButton == MouseEvent.NOBUTTON) 0 else buttonMask(pressedButton)
    val modifiers =
      when (id) {
        MouseEvent.MOUSE_PRESSED -> buttonMask(button)
        MouseEvent.MOUSE_DRAGGED -> held
        else -> 0
      }
    val popupTrigger = button == MouseEvent.BUTTON3 && id == MouseEvent.MOUSE_PRESSED
    target.dispatchEvent(
      MouseEvent(
        target,
        id,
        System.currentTimeMillis(),
        modifiers,
        point.x,
        point.y,
        clicks,
        popupTrigger,
        button,
      ),
    )
  }

  private fun buttonMask(button: Int) =
    when (button) {
      MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK
      MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK
      MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK
      else -> 0
    }

  private fun sendKey(inside: Window, id: Int, keyCode: Int, character: Char, modifiers: Int) =
    onEventThread {
      val target =
        checkNotNull(keyTarget(inside.contentPane())) {
          "No component with a key listener in ${inside.javaClass.simpleName}"
        }
      target.dispatchEvent(
        KeyEvent(target, id, System.currentTimeMillis(), modifiers, keyCode, character),
      )
    }

  private fun Window.contentPane() = (this as javax.swing.RootPaneContainer).contentPane

  private fun componentAt(inside: Window, x: Int, y: Int): Component {
    val pane = inside.contentPane()
    return SwingUtilities.getDeepestComponentAt(pane, x, y) ?: pane
  }

  /**
   * The component Compose listens for keys on (skiko's `SkiaLayer$1`, the same as for the mouse).
   */
  private fun keyTarget(component: Component): Component? {
    if (component.keyListeners.isNotEmpty()) return component
    if (component !is Container) return null
    for (child in component.components) keyTarget(child)?.let {
      return it
    }
    return null
  }

  private fun skiaLayer(component: Component): SkiaLayer? {
    if (component is SkiaLayer) return component
    if (component !is Container) return null
    for (child in component.components) skiaLayer(child)?.let {
      return it
    }
    return null
  }

  fun <T> onEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
