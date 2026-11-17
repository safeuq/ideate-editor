package intellijandroid.client.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jetbrains.ide.model.uiautomation.BeBehaviorControl
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeEmptyData
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeLabel
import com.jetbrains.ide.model.uiautomation.BeShortcutListener
import com.jetbrains.ide.model.uiautomation.BeSpacer
import com.jetbrains.ide.model.uiautomation.BeTextBox
import com.jetbrains.ide.model.uiautomation.BeneluxView
import com.jetbrains.ide.model.uiautomation.DialogResult
import com.jetbrains.ide.model.uiautomation.ValidationStates
import com.jetbrains.ide.model.uiautomation.uIAutomationInteractionModel
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.ide.model.BeAbstractPopup
import com.jetbrains.rd.ide.model.DefaultPopupContent
import com.jetbrains.rd.ide.model.LxPanel
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.luxModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * One line of a backend popup, flattened from its `BeControl` tree: a label, or a text box the user
 * types into.
 */
sealed interface PopupItem

class PopupLabel(text: String) : PopupItem {
  var text by mutableStateOf(text)
}

/**
 * A `BeTextBox`: [text] mirrors the backend's `text` property both ways; [problem] is the backend's
 * validation message (`validationError` or `validationWarning`), null when it passes.
 */
/** A Lux panel the backend paints, e.g. New ▸ File's "already exists" hint. */
class PopupLux(val surface: LuxDialog) : PopupItem

class PopupTextField(val placeholder: String, internal val box: BeTextBox) : PopupItem {
  var text by mutableStateOf(box.text.value)
  var problem by mutableStateOf<String?>(null)
  var isError by mutableStateOf(false)
}

/**
 * A popup the backend sends as a `BeControl` tree (`UIAutomationInteractionModel.activePopups`,
 * docs/lux-protocol.md section 10) rather than as Lux pixels, e.g. New ▸ File's name popup:
 * `BackendPopup(id = 1, title = "New File", items = [PopupTextField(placeholder = "Name")])`.
 */
class BackendPopup(
  val id: Int,
  title: String,
  val items: List<PopupItem>,
  /** `cancelOnClickOutside`: a tap or click outside cancels it. */
  val cancelOnClickOutside: Boolean,
  internal val model: BeAbstractPopup,
  /** The `BeShortcutListener`s found on the way down, by AWT keystroke text ("pressed ENTER"). */
  internal val shortcuts: Map<String, List<BeShortcutListener>>,
) {
  var title by mutableStateOf(title)
}

/**
 * The backend's `BeControl` popups, as the JetBrains Client's `PopupViewControl` handles them:
 * shown once `loaded` fires; Enter fires the matching shortcut listeners, or with `closeOnEnter`
 * accepts (`accept` and result OK); Escape and a click outside set result CANCEL; the backend
 * setting result CLOSE, or removing the entry, closes it. Typing sets the text box's `text`.
 *
 * Touched on the rd scheduler thread; [ClientSession] queues the UI's calls there.
 */
internal class BackendPopups(
  private val lifetime: Lifetime,
  private val protocol: IProtocol,
  private val log: (String) -> Unit,
  private val publish: (List<BackendPopup>) -> Unit,
) {
  private val shown = linkedMapOf<Int, BackendPopup>()

  fun bind() {
    protocol.uIAutomationInteractionModel.activePopups.view(lifetime) { popupLifetime, id, model ->
      val content = (model.content as? DefaultPopupContent)?.content
      if (content == null) {
        log("Backend popup $id: ${model.content::class.simpleName} not shown")
        return@view
      }
      // Read the tree once it's loaded: New ▸ File's Enter listener is added between the popup
      // appearing in activePopups and `loaded`.
      model.loaded.advise(popupLifetime) {
        if (id in shown) return@advise
        val items = mutableListOf<PopupItem>()
        val shortcuts = mutableMapOf<String, MutableList<BeShortcutListener>>()
        val unknown = mutableListOf<String>()
        flatten(content, items, shortcuts, unknown, popupLifetime)
        if (unknown.isNotEmpty())
          log("Backend popup $id: not shown inside: ${unknown.joinToString()}")
        val popup =
          BackendPopup(
            id,
            model.title?.text?.value.orEmpty(),
            items,
            model.settings.cancelOnClickOutside,
            model,
            shortcuts,
          )
        model.title?.text?.advise(popupLifetime) { popup.title = it }
        shown[id] = popup
        log("Backend popup '${popup.title}'")
        publish(shown.values.toList())
      }
      model.result.advise(popupLifetime) { result -> if (result == DialogResult.CLOSE) close(id) }
      popupLifetime.onTermination { close(id) }
    }
  }

  private fun flatten(
    control: BeControl,
    items: MutableList<PopupItem>,
    shortcuts: MutableMap<String, MutableList<BeShortcutListener>>,
    unknown: MutableList<String>,
    popupLifetime: Lifetime,
  ) {
    when (control) {
      is BeTextBox -> {
        val field = PopupTextField(control.placeholder.value, control)
        control.text.advise(popupLifetime) { text -> if (text != field.text) field.text = text }
        control.validationResult.advise(popupLifetime) { result ->
          field.problem =
            result.resultMessage.takeIf {
              result.state != ValidationStates.validationPassed && it.isNotBlank()
            }
          field.isError = result.state == ValidationStates.validationError
        }
        items += field
      }
      is BeLabel ->
        items +=
          PopupLabel(control.text.value).also { label ->
            control.text.advise(popupLifetime) { label.text = it }
          }
      is BeSpacer -> Unit
      // New ▸ Java Class: the name field above the list of kinds (Class, Interface, …).
      is BeGrid ->
        control.items.valueOrNull.orEmpty().forEach { element ->
          flatten(element.content, items, shortcuts, unknown, popupLifetime)
        }
      // A Lux panel inside the popup, e.g. New ▸ File's "already exists" hint. As JetBrains
      // Client's
      // BeneluxViewControl: show it, give it the size it asks for, and paint what it draws.
      is BeneluxView -> {
        val panel = protocol.luxModel.components[control.luxId] as? LxPanel
        if (panel == null) {
          unknown += "BeneluxView ${control.luxId} (no LxPanel)"
        } else {
          val surface = LuxDialog(control.luxId, false, true, true, "", 0, 0)
          // The popup is laid out around the panel, so it gets the size it asks for (with either
          // layout source; the client does the same).
          panel.preferredSize.advise(popupLifetime) { preferred -> panel.size.set(preferred) }
          panel.size.advise(popupLifetime) { size ->
            surface.width = size.width
            surface.height = size.height
          }
          panel.graphics.drawOperations.advise(popupLifetime) { operations ->
            surface.batches += operations
            surface.version++
          }
          // Like a Lux window (LuxDialogs): drawn only once it has a screen and a place on it.
          if (panel.graphicsConfiguration.valueOrNull == null)
            panel.graphicsConfiguration.set(LuxDialogs.CONFIGURATION)
          if (panel.position.valueOrNull == null) panel.position.set(LxPoint(0.0, 0.0))
          panel.visible.set(true)
          items += PopupLux(surface)
        }
      }
      is BeBehaviorControl -> {
        control.behavior.filterIsInstance<BeShortcutListener>().forEach { listener ->
          listener.shortcuts.forEach { shortcuts.getOrPut(it) { mutableListOf() } += listener }
        }
        flatten(control.content, items, shortcuts, unknown, popupLifetime)
      }
      // BeStyleControl (margins, colors) and other wrappers: their content is what shows.
      is BeControlContainer -> flatten(control.content, items, shortcuts, unknown, popupLifetime)
      else -> unknown += control::class.simpleName.orEmpty()
    }
  }

  private fun close(id: Int) {
    if (shown.remove(id) != null) publish(shown.values.toList())
  }

  /**
   * The user typed into [field]: the backend's text box gets it (`updateSourceTrigger`
   * TextChanged).
   */
  fun type(field: PopupTextField, text: String) {
    if (field.box.text.value != text) field.box.text.set(text)
  }

  /** Enter: the shortcut listeners for it, or, with `closeOnEnter`, accept. */
  fun enter(popup: BackendPopup) {
    val listeners = popup.shortcuts["pressed ENTER"].orEmpty()
    when {
      listeners.isNotEmpty() -> listeners.forEach { it.reaction.fire(BeEmptyData()) }
      popup.model.settings.closeOnEnter -> {
        popup.model.accept.fire(Unit)
        popup.model.result.set(DialogResult.OK)
      }
      else -> popup.model.accept.fire(Unit)
    }
  }

  /**
   * Escape, Back or a click outside: cancelled, then closed, as `PopupProvider`'s cancel callback
   * does. The popups opened after it (New ▸ File's error hint) go with it.
   */
  fun cancel(popup: BackendPopup) {
    shown.values
      .toList()
      .dropWhile { it !== popup }
      .asReversed()
      .forEach { each ->
        log("Backend popup ${each.id} cancelled")
        if (each.model.settings.cancelKeyEnabled) {
          each.model.result.set(DialogResult.CANCEL)
          each.model.result.set(DialogResult.CLOSE)
        }
      }
  }
}
