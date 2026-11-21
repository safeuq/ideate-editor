package intellijandroid.client.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jetbrains.codeWithMe.model.RemoteGraphicsConfigurationModel
import com.jetbrains.codeWithMe.model.RemoteGraphicsDeviceModel
import com.jetbrains.codeWithMe.model.codeWithMeUnattendedHostControlModel
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.ide.model.LxAbstractComponent
import com.jetbrains.rd.ide.model.LxAffineTransform
import com.jetbrains.rd.ide.model.LxCacheableImage
import com.jetbrains.rd.ide.model.LxDialog
import com.jetbrains.rd.ide.model.LxDrawImageCommand
import com.jetbrains.rd.ide.model.LxDrawOperation
import com.jetbrains.rd.ide.model.LxEvent
import com.jetbrains.rd.ide.model.LxFrame
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxRect
import com.jetbrains.rd.ide.model.LxWindow
import com.jetbrains.rd.ide.model.LxWindowEvent
import com.jetbrains.rd.ide.model.LxWindowEventType
import com.jetbrains.rd.ide.model.luxModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.viewNotNull
import intellijandroid.client.ui.lux.LuxImages
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A backend dialog shown over Lux (docs/lux-protocol.md): the backend lays it out and paints it as
 * draw operations; the UI replays [batches] in order onto the dialog's canvas and sends input back
 * through [ClientSession.luxEvent]. [width] × [height] are backend pixels, which IDEate shows as dp
 * (the graphics device it reports has scale 1).
 *
 * Example: Rename on `smoke.iml`, `LuxDialog(id = 2, modal = true, popup = false, undecorated =
 * false, title = "Rename", width = 358, height = 254)`.
 */
class LuxDialog(
  val id: Int,
  val modal: Boolean,
  /**
   * A popup window (`LxWindow`, e.g. New ▸ File's name popup): no title row. It closes when its
   * window loses the focus, not on a click outside (docs/lux-protocol.md, 2026-11-21).
   */
  val popup: Boolean,
  /**
   * The backend paints the title itself, as IntelliJ's alerts (`Messages`, e.g. Delete's "Delete
   * directory …?") do: no title row.
   */
  val undecorated: Boolean,
  title: String,
  width: Int,
  height: Int,
) {
  var title by mutableStateOf(title)
  var width by mutableIntStateOf(width)
  var height by mutableIntStateOf(height)
  /**
   * Where the backend put the window on the screen it was told about, e.g. (282, 242) for Rename
   * and (290, 467) for its Scope list, just under the combo. Mouse events carry it added to their
   * point (`xAbs`, `yAbs`), and a popup goes at its offset from its dialog.
   */
  var x by mutableIntStateOf(0)
  var y by mutableIntStateOf(0)
  /**
   * A text field in the window has the focus: the backend reports where its caret is
   * (`LxPanel.inputLocation`, e.g. `LxRect(12, 40, 1, 18)`), and null when the focus leaves text.
   */
  var acceptsText by mutableStateOf(false)
  /**
   * The backend's `LxWindow.focusableWindowState`, e.g. true for a tooltip and for the Git branches
   * list. A popup's window takes the focus only when this is true, as JetBrains Client's
   * `LuxFrontendWindowAdapter` does with `setFocusableWindowState`; a window that is not focusable
   * never blocks the mouse for what is under it.
   */
  var focusableWindowState by mutableStateOf(false)
  /**
   * Controls the client draws itself inside the panel (`LxPanel.localComponents`), e.g. the Run
   * window's console at `LxRect(0, 28, 900, 272)`; see [LuxPanelHost].
   */
  var locals by mutableStateOf(emptyList<PanelLocal>())
  /** Bumped when [batches] gets more; the UI drains the queue then. */
  var version by mutableIntStateOf(0)
  internal val batches = ConcurrentLinkedQueue<List<LxDrawOperation>>()

  /** The next batch to paint, oldest first; null when all are painted. */
  fun nextBatch(): List<LxDrawOperation>? = batches.poll()
}

/**
 * The backend's dialogs over Lux. JetBrains Client's part (`LuxClientService`, `LuxFrontendDialog`)
 * for what IDEate shows: modal and non-modal `LxDialog`s. Popups and the backend's own frames
 * (`LxFrame` with `isMainWindow`) aren't shown.
 *
 * Touched on the rd scheduler thread.
 */
internal class LuxDialogs(
  private val lifetime: Lifetime,
  private val protocol: IProtocol,
  private val log: (String) -> Unit,
  private val publish: (List<LuxDialog>) -> Unit,
) {
  /** The image cache the painters share; `LuxModel.cleanImagesFromCache` empties parts of it. */
  val images = LuxImages()
  private val components = mutableMapOf<Int, LxAbstractComponent>()
  private val dialogs = linkedMapOf<Int, LuxDialog>()

  fun bind() {
    // Lux binds windows only for a client that reported its screens
    // (RemoteGraphicsEnvironment.handleNewSession sets LuxHostConnection.attached).
    protocol.codeWithMeUnattendedHostControlModel.session.viewNotNull(lifetime) { _, session ->
      session.graphicsDevices.set(
        listOf(RemoteGraphicsDeviceModel(DEVICE, listOf(CONFIGURATION), 0)),
      )
    }
    val lux = protocol.luxModel
    lux.cleanImagesFromCache.advise(lifetime) { ids -> images.remove(ids) }
    lux.components.view(lifetime) { componentLifetime, id, component ->
      // The backend sends an icon's pixels once, in the first frame that draws it, then refers to
      // it by id. That frame may belong to a component IDEate doesn't draw (the backend's main
      // window with its toolbar), so keep the pixels of every component's frames, or a later
      // panel's icons (the Run toolbar) are "unknown cached images".
      // ponytail: the pixels of a frame that IDEate draws too are decoded twice.
      component.graphics.drawOperations.advise(componentLifetime) { operations ->
        for (operation in operations) {
          val image = (operation.command as? LxDrawImageCommand)?.image as? LxCacheableImage
          if (image != null) images.store(image.id, image.content)
        }
      }
      // Dialogs, and popups (an LxWindow that isn't a frame); not the backend's frames.
      if (component !is LxWindow || (component is LxFrame && component !is LxDialog)) return@view
      components[id] = component
      val frame = component as? LxDialog
      val dialog =
        LuxDialog(
          id,
          frame?.modality == true,
          frame == null,
          frame?.undecorated == true,
          frame?.title?.valueOrNull.orEmpty(),
          0,
          0,
        )
      frame?.title?.advise(componentLifetime) { dialog.title = it }
      component.inputLocation.advise(componentLifetime) { location ->
        dialog.acceptsText = location != null
      }
      component.focusableWindowState.advise(componentLifetime) { focusable ->
        dialog.focusableWindowState = focusable
      }
      component.position.advise(componentLifetime) { position ->
        dialog.x = position.x.toInt()
        dialog.y = position.y.toInt()
      }
      component.preferredSize.advise(componentLifetime) { preferred ->
        // Like LuxFrontendPanelAdapter: every preferred size packs the window again, also after it
        // shows, and is written back as its size. The backend's own window size is not taken: for
        // the decompiler's licence dialog its DialogWrapper kept 555 × 158 ("has changed after
        // showing … resetting it back") while the content needed 555 × 389, which cut the buttons
        // off.
        dialog.width = preferred.width
        dialog.height = preferred.height
        if (component.size.valueOrNull != preferred) component.size.set(preferred)
      }
      component.size.advise(componentLifetime) {
        if (component.position.valueOrNull == null) component.position.set(LxPoint(0.0, 0.0))
        if (component.graphicsConfiguration.valueOrNull == null)
          component.graphicsConfiguration.set(CONFIGURATION)
      }
      component.graphics.drawOperations.advise(componentLifetime) { operations ->
        dialog.batches += operations
        dialog.version++
      }
      component.visible.advise(componentLifetime) { visible ->
        if (visible && dialogs.put(id, dialog) == null) {
          log(
            "Backend ${if (dialog.popup) "popup" else "dialog '${dialog.title}'"} (${dialog.width} × ${dialog.height})",
          )
          publish(dialogs.values.toList())
          // Without focus the backend's dialog ignores keys and draws no caret (recorded). A popup
          // leaves the focus with its dialog, as an OS does for a combo's list: the combo keeps it.
          // A focusable popup with no dialog open has a window of its own, which gets the focus as
          // an AWT window does on show (JetBrains Client reports it).
          if (!dialog.popup || (dialog.focusableWindowState && dialogs.values.none { !it.popup })) {
            send(id, LxWindowEvent(LxWindowEventType.Activated, null))
            send(id, LxWindowEvent(LxWindowEventType.GainedFocus, null))
          }
        } else if (!visible && dialogs.remove(id) != null) publish(dialogs.values.toList())
      }
      componentLifetime.onTermination {
        components.remove(id)
        if (dialogs.remove(id) != null) {
          log("Backend dialog '${dialog.title}' closed")
          publish(dialogs.values.toList())
        }
      }
    }
  }

  /** [event] to dialog [id]'s backend window; dropped when it closed meanwhile. */
  fun send(id: Int, event: LxEvent) {
    components[id]?.events?.fire(event)
  }

  internal companion object {
    private val IDENTITY = LxAffineTransform(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))

    /**
     * One screen at scale 1, so a backend pixel is a dp. Its size only bounds where the backend
     * places windows; IDEate centres its dialogs itself.
     */
    internal val CONFIGURATION =
      RemoteGraphicsConfigurationModel("ideate", LxRect(0, 0, 1920, 1200), IDENTITY, IDENTITY)
    private const val DEVICE = "ideate"
  }
}
