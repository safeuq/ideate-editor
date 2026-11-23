package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.uiautomation.BeContextIdTimestampModel
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeDynamicControlContainer
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeIdeToolbar
import com.jetbrains.ide.model.uiautomation.BeLabel
import com.jetbrains.ide.model.uiautomation.BeTab
import com.jetbrains.ide.model.uiautomation.BeToolWindow
import com.jetbrains.ide.model.uiautomation.BeToolWindowIdTimestampModel
import com.jetbrains.ide.model.uiautomation.BeToolWindowState
import com.jetbrains.ide.model.uiautomation.BeTreeGrid
import com.jetbrains.ide.model.uiautomation.BeneluxView
import com.jetbrains.rd.ide.model.ActionModelWithId
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.LxEvent
import com.jetbrains.rd.ide.model.LxPanel
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.toolWindowModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.SequentialLifetimes
import io.github.safeuq.ideate.client.ui.MenuEntry

/**
 * What a backend tool window shows (docs/tool-windows.md, section 8): its action toolbar, e.g.
 * Gradle's Reload All Gradle Projects, Execute Gradle Task, Expand All, …, and its tree. [label]:
 * the text of a tab that has only a label, e.g. "Initializing Gradle projects data…".
 */
data class BackendToolWindowView(
  val toolbar: List<MenuEntry> = emptyList(),
  val tree: ProjectTree = ProjectTree(),
  val label: String? = null,
  /** The tabs' names, e.g. `["Main", "Slow"]` in Run; [selectedTab] is the one that shows. */
  val tabs: List<String> = emptyList(),
  val selectedTab: Int = 0,
  /** Per tab: whether it has a close button (`BeTab.isCloseable`). */
  val closeable: List<Boolean> = emptyList(),
  /** The selected tab's panel when the backend paints it (Run, Build): a `BeneluxView`. */
  val panel: LuxDialog? = null,
)

/** The names of [tabs], e.g. `["Main", "Slow"]`; a tab without a name has "". */
internal fun tabNames(tabs: List<BeTab>) = tabs.map { it.tabName.text.value }

/**
 * The content of the backend's tool windows while Ideate shows them, as JetBrains Client's
 * `FrontendGenericToolWindowFactory` builds it: the selected tab of `BeToolWindow.content`, in it a
 * `BeIdeToolbar` (an action group the backend fills, like any toolbar) and a `BeTreeGrid`. The
 * backend builds a window's content only once the client says it shows (`BeToolWindow.state`).
 * Called on the protocol thread.
 */
internal class BackendToolWindowViews(
  private val iconPath: (IconModel) -> String?,
  /**
   * Asks the backend for a toolbar's actions, as for a menu; the answer comes to [toolbarLoaded].
   */
  private val loadToolbar:
    (windowId: String, groupId: String, place: String, ActionTimestampSetModel) -> Unit,
  private val publish: (Map<String, BackendToolWindowView>) -> Unit,
  /** The backend set a hidden window Active, e.g. Run when a run starts: show it. */
  private val showRequested: (windowId: String) -> Unit = {},
  /** The Lux component with this id, e.g. a `BeneluxView`'s panel; null when it isn't there. */
  private val luxComponent: (Int) -> Any? = { null },
  private val log: (String) -> Unit = {},
) {
  /** A window on screen: [lifetime] until it hides, [tabLifetimes] for the shown tab's content. */
  private class Shown(val lifetime: LifetimeDefinition) {
    val tabLifetimes = SequentialLifetimes(lifetime)
    var toolbar: BeIdeToolbar? = null
    var tree: BeTreeRows? = null
    /** The tab whose content shows; null before the first. */
    var tab: BeTab? = null
    var host: LuxPanelHost? = null
  }

  private var projectId: RdProjectId? = null
  private val models = mutableMapOf<String, BeToolWindow>()
  private val shown = mutableMapOf<String, Shown>()
  private val views = mutableMapOf<String, BackendToolWindowView>()

  fun bind(solutionLifetime: Lifetime, solution: Solution) {
    projectId = solution.projectId
    solution.toolWindowModel.availableToolWindows.view(solutionLifetime) { windowLifetime, id, model
      ->
      models[id] = model.toolWindow
      // Logged so a live run shows whether the backend asks for a window to show (Run on a run).
      model.toolWindow.state.advise(windowLifetime) { state ->
        log("Tool window $id state $state")
        // A window the backend registers already Active (Run, as a run starts) or sets Active
        // later is to show. Our own changes (setShown) come with the window already in [shown].
        if (state != BeToolWindowState.Hidden && id !in shown) showRequested(id)
      }
      windowLifetime.onTermination {
        models.remove(id)
        hide(id)
      }
    }
  }

  /**
   * The backend tool windows on screen now, e.g. `{"Gradle"}`. Like `FrontendToolWindowHost`, the
   * client sets each window's state: Active while it shows, Hidden after; a window never shown
   * isn't told it is hidden.
   */
  fun setShown(ids: Set<String>) {
    for (id in shown.keys - ids) hide(id)
    for (id in ids - shown.keys) {
      val model = models[id] ?: continue
      val window = Shown(LifetimeDefinition())
      shown[id] = window
      model.state.set(BeToolWindowState.Active)
      model.content.selectedIndex.advise(window.lifetime) { showTab(id) }
      model.content.tabs.advise(window.lifetime) { showTab(id) }
    }
  }

  fun toggle(windowId: String, nodeId: Int) = shown[windowId]?.tree?.toggle(nodeId)

  /** Selects a row; the toolbar's actions are asked for again, as they depend on the selection. */
  fun select(windowId: String, nodeId: Int) {
    shown[windowId]?.tree?.select(nodeId) ?: return
    refreshToolbar(windowId)
  }

  fun open(windowId: String, nodeId: Int) = shown[windowId]?.tree?.open(nodeId)

  /** The user picked tab [index] of [windowId]; the backend selects it as it would on a click. */
  fun selectTab(windowId: String, index: Int) {
    models[windowId]?.content?.selectedIndex?.set(index)
  }

  /**
   * The user closed tab [index] of [windowId]: removed from `BeTabbedControl.tabs`, an rd list the
   * backend follows (`BeTab` has no close signal). A running process makes the backend ask in its
   * own dialog (Terminate, Disconnect, Cancel). ponytail: after Cancel the backend keeps the run
   * but doesn't give the tab back, so the client shows no tab until the next run or tab change.
   * `CloseContent` was disabled for every context we could build
   * (docs/terminal-and-consoles-spec.md 8.2 addendum); lift when a context works.
   */
  fun closeTab(windowId: String, index: Int) {
    val content = models[windowId]?.content ?: return
    val tabs = content.tabs
    val tab = tabs.getOrNull(index) ?: return
    require(tab.isCloseable) { "Tab $index of $windowId is not closeable" }
    log("Closing tab $index (${tab.tabName.text.value}) of $windowId")
    tabs.remove(tab)
    // The backend builds a tab's content when it is selected and keeps the old index: select the
    // neighbour as a tabbed control does, e.g. closing tab 1 of 2 selects tab 0.
    if (tabs.isNotEmpty()) content.selectedIndex.set(index.coerceAtMost(tabs.size - 1))
  }

  /** The window's content area; see [LuxPanelHost.resize]. */
  fun resizePanel(windowId: String, x: Int, y: Int, width: Int, height: Int) =
    shown[windowId]?.host?.resize(x, y, width, height)

  fun repaintPanel(windowId: String) = shown[windowId]?.host?.repaint()

  fun panelEvent(windowId: String, event: LxEvent) = shown[windowId]?.host?.event(event)

  /** The toolbar's items the backend sent for [windowId]. */
  fun toolbarLoaded(windowId: String, entries: List<MenuEntry>) {
    if (windowId !in shown) return
    update(windowId) { copy(toolbar = entries) }
  }

  /**
   * Asks for the toolbar's items again, e.g. after one of them ran: JetBrains Client updates
   * toolbars all the time; Ideate does it when something may have changed them.
   */
  fun refreshToolbar(windowId: String) {
    val toolbar = shown[windowId]?.toolbar ?: return
    val groupId = (toolbar.group.presentation as? ActionModelWithId)?.actionId ?: return
    val project = projectId ?: return
    // E.g. "Gradle View Toolbar"; the backend's update reads it as ActionPlaces would.
    val place = toolbar.place.value ?: return
    // The toolbar's target is the panel whose data the actions read, e.g. Gradle's
    // ExternalProjectsViewImpl: its dataId as `BeContextIdTimestampModel`, as
    // BeContextIdTimestampModelProvider sends it. The backend knows the tree's selection itself.
    val target = toolbar.target.value.toIntOrNull() ?: toolbar.dataId.valueOrNull
    val timestamps =
      ActionTimestampSetModel(
        listOfNotNull(
          ProjectTimestampModel(project),
          BeToolWindowIdTimestampModel(windowId),
          target?.let { BeContextIdTimestampModel(it, null) },
        ),
      )
    loadToolbar(windowId, groupId, place, timestamps)
  }

  private fun hide(id: String) {
    val window = shown.remove(id) ?: return
    window.lifetime.terminate()
    models[id]?.state?.set(BeToolWindowState.Hidden)
    views.remove(id)
    publish(views.toMap())
  }

  /** Shows the selected tab's toolbar and tree, e.g. Gradle's once its projects are loaded. */
  private fun showTab(id: String) {
    val window = shown[id] ?: return
    val content = models[id]?.content ?: return
    val tab =
      content.tabs.getOrNull(content.selectedIndex.valueOrNull ?: 0) ?: content.tabs.firstOrNull()
    // The tabs and the selection change together, e.g. Gradle replaces its "Initializing" tab; only
    // a different tab starts over, so a loaded toolbar isn't dropped.
    val names = tabNames(content.tabs.toList())
    log("Tool window $id: tabs $names, selected ${content.selectedIndex.valueOrNull}")
    val selected = content.tabs.indexOf(tab).coerceAtLeast(0)
    val closeable = content.tabs.map { it.isCloseable }
    if (tab != null && tab === window.tab) {
      update(id) { copy(tabs = names, selectedTab = selected, closeable = closeable) }
      return
    }
    window.tab = tab
    val tabLifetime = window.tabLifetimes.next()
    window.toolbar = null
    window.tree = null
    window.host = null
    views[id] = BackendToolWindowView(tabs = names, selectedTab = selected, closeable = closeable)
    publish(views.toMap())
    tab ?: return
    // A new run can take over a finished run's tab, and the backend renames it (Main → Slow).
    // ponytail: only the selected tab's name is followed; lift when a background tab's rename
    // shows.
    tab.tabName.text.advise(tabLifetime) {
      update(id) { copy(tabs = tabNames(content.tabs.toList())) }
    }
    find(id, window, tabLifetime, tab.content)
  }

  /**
   * Walks [control] for the toolbar, the tree and a lone label, e.g. Gradle's BeStyleControl →
   * BeGrid
   * [BeIdeToolbar, BeGrid → BeDynamicControlContainer → BeContextActionBehavior → BeBehaviorControl → BeTreeGrid].
   */
  private fun find(id: String, window: Shown, lifetime: Lifetime, control: BeControl) {
    when (control) {
      is BeIdeToolbar -> {
        window.toolbar = control
        refreshToolbar(id)
      }
      is BeTreeGrid -> {
        val rows = BeTreeRows(iconPath) { tree -> update(id) { copy(tree = tree) } }
        window.tree = rows
        rows.show(lifetime, control)
      }
      is BeneluxView -> {
        val panel = luxComponent(control.luxId) as? LxPanel
        if (panel == null) log("Tool window $id: no Lux panel ${control.luxId}")
        else {
          val host = LuxPanelHost(panel, lifetime, control.luxId, log)
          window.host = host
          update(id) { copy(panel = host.surface) }
        }
      }
      is BeLabel -> control.text.advise(lifetime) { text -> update(id) { copy(label = text) } }
      is BeGrid ->
        control.items.advise(lifetime) { items ->
          items.forEach { find(id, window, lifetime, it.content) }
        }
      is BeDynamicControlContainer ->
        control.content.advise(lifetime) { find(id, window, lifetime, it) }
      is BeControlContainer -> find(id, window, lifetime, control.content)
      else -> Unit
    }
  }

  private fun update(id: String, change: BackendToolWindowView.() -> BackendToolWindowView) {
    if (id !in shown) return
    views[id] = (views[id] ?: BackendToolWindowView()).change()
    publish(views.toMap())
  }
}
