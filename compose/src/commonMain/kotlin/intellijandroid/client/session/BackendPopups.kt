package intellijandroid.client.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.jetbrains.ide.model.uiautomation.BeBehaviorControl
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeEmptyData
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeIcon
import com.jetbrains.ide.model.uiautomation.BeLabel
import com.jetbrains.ide.model.uiautomation.BeRichText
import com.jetbrains.ide.model.uiautomation.BeShortcutListener
import com.jetbrains.ide.model.uiautomation.BeSpacer
import com.jetbrains.ide.model.uiautomation.BeTextBox
import com.jetbrains.ide.model.uiautomation.BeTreeGrid
import com.jetbrains.ide.model.uiautomation.BeTreeGridLine
import com.jetbrains.ide.model.uiautomation.BeneluxView
import com.jetbrains.ide.model.uiautomation.DialogResult
import com.jetbrains.ide.model.uiautomation.ValidationStates
import com.jetbrains.ide.model.uiautomation.uIAutomationInteractionModel
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.ide.model.BeAbstractPopup
import com.jetbrains.rd.ide.model.BeListPopupLine
import com.jetbrains.rd.ide.model.DefaultPopupContent
import com.jetbrains.rd.ide.model.FontStyle
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.LxPanel
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.StepPopupContent
import com.jetbrains.rd.ide.model.WizardPopupContent
import com.jetbrains.rd.ide.model.luxModel
import com.jetbrains.rd.util.lifetime.Lifetime
import intellijandroid.client.ui.lux.resolveLuxColor

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
/**
 * A `BeTreeGrid` list, e.g. New ▸ Java Class's kinds: `PopupList(rows =
 * [Class (class.svg), Interface, Enum, Record, Annotation, Exception])`. [selected] follows each
 * row's `isSelected`, which the backend moves on Up and Down.
 */
class PopupList(
  val rows: List<PopupRow>,
  /**
   * A list popup (`WizardPopupContent`, e.g. the welcome screen's New ▾): a tap chooses the row, as
   * a click does in IntelliJ's `ListPopupImpl`, instead of only selecting it.
   */
  internal val wizard: WizardPopupContent? = null,
) : PopupItem {
  var selected by mutableStateOf(-1)
}

/** One row of a [PopupList]: its icon (a path in the client's icon jar) and text. */
class PopupRow(val iconPath: String?, val text: String, internal val line: BeTreeGridLine)

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
  /** A backend icon's path in the client's icon jar, e.g. `expui/nodes/class.svg`. */
  private val iconPath: (IconModel) -> String?,
  private val publish: (List<BackendPopup>) -> Unit,
) {
  private val shown = linkedMapOf<Int, BackendPopup>()

  fun bind() {
    protocol.uIAutomationInteractionModel.activePopups.view(lifetime) { popupLifetime, id, model ->
      (model.content as? WizardPopupContent)?.let { wizard ->
        model.loaded.advise(popupLifetime) {
          showList(popupLifetime, id, model, wizard, wizard.step)
        }
        model.result.advise(popupLifetime) { result -> if (result == DialogResult.CLOSE) close(id) }
        popupLifetime.onTermination { close(id) }
        return@view
      }
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
      is BeTreeGrid -> {
        val lines = control.fakeRoot.children.toList()
        val list =
          PopupList(
            lines.map { line ->
              val row = BeRow.of(line, iconPath)
              PopupRow(row.icon, row.text, line)
            },
          )
        lines.forEachIndexed { index, line ->
          line.isSelected.advise(popupLifetime) { selected ->
            if (selected) list.selected = index else if (list.selected == index) list.selected = -1
          }
        }
        items += list
      }
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

  /**
   * A list popup's [step] (its rows), as JetBrains Client's `WizardPopupProvider` shows it with a
   * `DefaultLineStep`. A row with a submenu ([BeListPopupLine.hasSubstep]) replaces the rows with
   * its `nextStep` once the backend sends it.
   */
  private fun showList(
    popupLifetime: Lifetime,
    id: Int,
    model: BeAbstractPopup,
    wizard: WizardPopupContent,
    step: StepPopupContent,
  ) {
    val lines = step.items.toList().filterIsInstance<BeListPopupLine>()
    val list =
      PopupList(
        lines.map { line ->
          val row = BeRow.of(line, iconPath)
          PopupRow(row.icon, row.text, line)
        },
        wizard,
      )
    val popup =
      BackendPopup(
        id,
        step.title.ifBlank { model.title?.text?.value.orEmpty() },
        listOf(list),
        model.settings.cancelOnClickOutside,
        model,
        emptyMap(),
      )
    shown[id] = popup
    log("Backend list popup '${popup.title}': ${list.rows.joinToString { it.text }}")
    publish(shown.values.toList())
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

  /** A tap on [row]: it becomes the list's selected row, as a click selects it in IntelliJ. */
  fun select(list: PopupList, row: PopupRow) {
    list.wizard?.let { wizard ->
      return choose(list, wizard, row)
    }
    list.rows.forEach { each -> if (each !== row) each.line.isSelected.set(false) }
    row.line.isSelected.set(true)
  }

  /** A list popup's row chosen, as `DefaultLineStep.onChosen` does. */
  private fun choose(list: PopupList, wizard: WizardPopupContent, row: PopupRow) {
    val line = row.line as? BeListPopupLine ?: return
    val popup = shown.values.firstOrNull { list in it.items } ?: return
    wizard.selectedValueChanged.fire(list.rows.indexOf(row))
    if (!line.hasSubstep) {
      log("Backend list popup ${popup.id}: chose '${row.text}'")
      line.signal.fire(Unit)
      // The popup closes on a final choice; an action list runs the action once it has closed
      // (IntelliJ's doFinalStep), which the backend hears as the result.
      popup.model.result.set(DialogResult.OK)
      return
    }
    list.rows.forEach { each ->
      (each.line as? BeListPopupLine)?.let {
        it.isExpanded.set(false)
        it.nextStep.set(null)
      }
    }
    line.isExpanded.set(true)
    line.nextStep.advise(lifetime) { next ->
      if (next != null) showList(lifetime, popup.id, popup.model, wizard, next)
    }
  }

  /** A key the backend listens for, by AWT keystroke text (`pressed UP`): its listeners fire. */
  fun key(popup: BackendPopup, keystroke: String) {
    popup.shortcuts[keystroke].orEmpty().forEach { it.reaction.fire(BeEmptyData()) }
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

/**
 * The icon and text of a [BeTreeGridLine], e.g. a New ▸ Java Class kind (BeStyleControl → BeGrid
 * [BeIcon, BeLabel]) or a Packages pane row (BeGrid [BeIcon, BeRichText], the rich text in parts
 * such as `src` and a grey ` smoke`). A row without text is the placeholder IntelliJ puts under a
 * node until its children load (a BeSpacer).
 */
internal class BeRow(val icon: String?, val fragments: List<TextFragment>) {
  val text
    get() = fragments.joinToString("") { it.text }

  companion object {
    fun of(line: BeTreeGridLine, iconPath: (IconModel) -> String?): BeRow {
      var icon: String? = null
      val fragments = mutableListOf<TextFragment>()
      fun look(each: BeControl) {
        when (each) {
          is BeIcon -> if (icon == null) icon = each.icon.valueOrNull?.let(iconPath)
          is BeLabel -> fragments += TextFragment(each.text.value, 0, null)
          is BeRichText -> {
            // Gradle's rows carry the icon on the text, e.g. `icons/expui/gradle.svg` on
            // "gradle-smoke" and `expui/build/taskGroup.svg` on "Tasks".
            if (icon == null) icon = each.icon.value?.let(iconPath)
            each.text.valueOrNull?.parts.orEmpty().forEach { part ->
              fragments +=
                TextFragment(
                  part.text,
                  when (part.fontStyle) {
                    FontStyle.Bold -> 1
                    FontStyle.Italic -> 2
                    else -> 0
                  },
                  // E.g. a NamedColor for the grey path after a Project Files root.
                  resolveLuxColor(part.foregroundColor)?.toArgb()?.toLong()?.and(0xFFFFFFFFL),
                )
            }
          }
          is BeGrid -> each.items.valueOrNull.orEmpty().forEach { look(it.content) }
          is BeControlContainer -> look(each.content)
          else -> Unit
        }
      }
      line.columnElements.forEach(::look)
      return BeRow(icon, fragments)
    }
  }
}
