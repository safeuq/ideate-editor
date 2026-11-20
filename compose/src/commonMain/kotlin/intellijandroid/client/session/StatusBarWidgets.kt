package intellijandroid.client.session

import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeLabel
import com.jetbrains.ide.model.uiautomation.BeTextPanel
import com.jetbrains.rd.ide.model.ComponentRect
import com.jetbrains.rd.ide.model.CustomWidgetModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.IconWidgetPresentationModel
import com.jetbrains.rd.ide.model.MouseEventModel
import com.jetbrains.rd.ide.model.MultipleTextValuesWidgetPresentationModel
import com.jetbrains.rd.ide.model.PredefinedWidgetModel
import com.jetbrains.rd.ide.model.RectangleModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.TextWidgetPresentationModel
import com.jetbrains.rd.ide.model.WidgetModel
import com.jetbrains.rd.ide.model.statusBarModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * One of the backend's status bar widgets (docs/status-bar.md), e.g. id `Position` text `65:12`,
 * `LineSeparator` `LF`, `Encoding` `UTF-8`, `CodeStyleStatusBarWidget` `2 spaces`,
 * `ReadOnlyAttribute` a lock icon.
 */
data class StatusWidget(
  val id: String,
  val text: String?,
  val iconPath: String?,
  /** E.g. "Go to Line" for Position. */
  val tooltip: String?,
)

/**
 * Follows `solution.statusBarModel.id2Widgets` as JetBrains Client's
 * `FrontendStatusBarModelListener` does and publishes the visible ones in IntelliJ's order (their
 * `anchor`s). A click goes back on the widget's own signal; the backend answers with its popup or
 * dialog (Go to Line, the line separators, the encodings). Called on the protocol thread.
 */
internal class StatusBarWidgets(
  private val iconPath: (IconModel) -> String?,
  private val publish: (List<StatusWidget>) -> Unit,
) {
  private class Entry(val model: WidgetModel, val anchor: String?) {
    var text: String? = null
    var icon: String? = null
    var tooltip: String? = null
  }

  /** In the order the backend added them, which breaks ties between anchors. */
  private val entries = LinkedHashMap<String, Entry>()

  fun bind(solutionLifetime: Lifetime, solution: Solution) {
    solution.statusBarModel.id2Widgets.view(solutionLifetime) { widgetLifetime, id, model ->
      val entry = Entry(model, model.anchor)
      entries[id] = entry
      when (model) {
        is PredefinedWidgetModel -> {
          val presentation = model.presentation
          presentation.tooltipText.advise(widgetLifetime) { update(id) { tooltip = it } }
          when (presentation) {
            is TextWidgetPresentationModel ->
              presentation.text.advise(widgetLifetime) { update(id) { text = it } }
            is IconWidgetPresentationModel ->
              presentation.icon.advise(widgetLifetime) { update(id) { icon = it?.let(iconPath) } }
            is MultipleTextValuesWidgetPresentationModel -> {
              presentation.selectedValue.advise(widgetLifetime) { update(id) { text = it } }
              presentation.icon.advise(widgetLifetime) { update(id) { icon = it?.let(iconPath) } }
            }
            else -> Unit
          }
        }
        is CustomWidgetModel -> watchContent(widgetLifetime, id, model.content)
        else -> Unit
      }
      publishAll()
      widgetLifetime.onTermination {
        entries.remove(id)
        publishAll()
      }
    }
  }

  /**
   * A tap on widget [id]; [bounds] is where it is in the window, in px. As JetBrains Client: a text
   * or icon widget gets a mouse click, a widget with several values asks for its popup, a custom
   * widget gets its bounds.
   */
  fun click(id: String, bounds: RectangleModel) {
    when (val model = entries[id]?.model ?: return) {
      is PredefinedWidgetModel ->
        when (val presentation = model.presentation) {
          is MultipleTextValuesWidgetPresentationModel -> presentation.showPopup.fire(bounds)
          else -> presentation.click.fire(mouseClick(bounds))
        }
      is CustomWidgetModel -> model.click.fire(bounds)
      else -> Unit
    }
  }

  /**
   * A custom widget is a small BeControl tree: a `BeTextPanel` with its text and icon (`LF`,
   * `UTF-8`), or a `BeGrid` of a `BeLabel` and a `BeTextPanel` (`2 spaces`). Its texts are joined
   * and its first icon kept.
   */
  private fun watchContent(lifetime: Lifetime, id: String, root: BeControl) {
    fun read() {
      val texts = mutableListOf<String>()
      var icon: String? = null
      fun walk(control: BeControl) {
        when (control) {
          is BeTextPanel -> {
            control.text.value?.takeIf { it.isNotBlank() }?.let(texts::add)
            if (icon == null) icon = control.icon.value?.let(iconPath)
          }
          is BeLabel -> control.text.value.takeIf { it.isNotBlank() }?.let(texts::add)
          is BeGrid -> control.items.valueOrNull.orEmpty().forEach { walk(it.content) }
          is BeControlContainer -> walk(control.content)
          else -> Unit
        }
      }
      walk(root)
      update(id) {
        text = texts.joinToString(" ").ifBlank { null }
        this.icon = icon
      }
    }
    fun watch(control: BeControl) {
      when (control) {
        is BeTextPanel -> {
          control.text.advise(lifetime) { read() }
          control.icon.advise(lifetime) { read() }
        }
        is BeLabel -> control.text.advise(lifetime) { read() }
        is BeGrid -> control.items.advise(lifetime) { items -> items.forEach { watch(it.content) } }
        is BeControlContainer -> watch(control.content)
        else -> Unit
      }
    }
    watch(root)
  }

  private fun update(id: String, change: Entry.() -> Unit) {
    entries[id]?.change() ?: return
    publishAll()
  }

  private fun publishAll() {
    val order = orderByAnchors(entries.map { (id, entry) -> id to entry.anchor })
    publish(
      order.mapNotNull { id ->
        val entry = entries.getValue(id)
        // Widgets with nothing to show are hidden, as IntelliJ hides their empty components.
        if (entry.text.isNullOrBlank() && entry.icon == null) null
        else StatusWidget(id, entry.text, entry.icon, entry.tooltip)
      },
    )
  }

  private companion object {
    /**
     * `MouseEvent.MOUSE_CLICKED` with the first button, as `DelegatingWidgetPresentation` sends.
     */
    fun mouseClick(bounds: RectangleModel): MouseEventModel {
      val none = ComponentRect(-1, -1, -1, -1)
      return MouseEventModel(500, 0, bounds.width / 2, bounds.height / 2, 1, 1, none, none, null)
    }
  }
}

/**
 * IntelliJ's status bar order from each widget's anchor, e.g. `after Position`, `after
 * LineSeparator`, `before Position`, `after InsertOverwrite, before ReadOnlyAttribute`, `ANY`,
 * `first`, `last`. Ids an anchor names that aren't there (IntelliJ's own widgets the backend
 * doesn't send) are ignored; otherwise the order the widgets came in decides. Example: `[Encoding:
 * after LineSeparator, LineSeparator: after Position, Position: ANY]` → `[Position, LineSeparator,
 * Encoding]`.
 */
internal fun orderByAnchors(widgets: List<Pair<String, String?>>): List<String> {
  val ids = widgets.map { it.first }
  val present = ids.toSet()
  val first = mutableSetOf<String>()
  val last = mutableSetOf<String>()
  // edges[a] contains b: a goes before b.
  val edges = ids.associateWith { mutableSetOf<String>() }
  for ((id, anchor) in widgets) {
    for (part in anchor.orEmpty().split(',').map { it.trim() }) {
      when {
        part.equals("first", ignoreCase = true) -> first += id
        part.equals("last", ignoreCase = true) -> last += id
        part.startsWith("after ", ignoreCase = true) -> {
          val other = part.substringAfter(' ').trim()
          if (other in present && other != id) edges.getValue(other) += id
        }
        part.startsWith("before ", ignoreCase = true) -> {
          val other = part.substringAfter(' ').trim()
          if (other in present && other != id) edges.getValue(id) += other
        }
      }
    }
  }
  // Kahn's algorithm, taking the earliest arrived among the ready ones, so the result is stable.
  val incoming = ids.associateWith { 0 }.toMutableMap()
  for (targets in edges.values) for (target in targets) incoming[target] =
    incoming.getValue(target) + 1
  val result = mutableListOf<String>()
  val done = mutableSetOf<String>()
  while (result.size < ids.size) {
    val ready = ids.filter { it !in done && incoming.getValue(it) == 0 }
    // A cycle: take the rest in arrival order.
    val next =
      (ready.firstOrNull { it in first }
        ?: ready.firstOrNull { it !in last }
        ?: ready.firstOrNull()
        ?: ids.first { it !in done })
    result += next
    done += next
    for (target in edges.getValue(next)) incoming[target] = incoming.getValue(target) - 1
  }
  return result
}
