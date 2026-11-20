package intellijandroid.client.session

import com.jetbrains.codeWithMe.model.ProjectViewModel
import com.jetbrains.codeWithMe.model.RdProjectViewBePane
import com.jetbrains.codeWithMe.model.RdProjectViewPaneBase
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeDynamicControlContainer
import com.jetbrains.ide.model.uiautomation.BeTreeGrid
import com.jetbrains.ide.model.uiautomation.BeneluxView
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.LuxLayoutSource
import com.jetbrains.rd.ide.model.LxEvent
import com.jetbrains.rd.ide.model.LxPanel
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxSize
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.SequentialLifetimes

/**
 * One entry of the Project tool window's view selector (docs/tool-windows.md): a pane, e.g.
 * `ProjectPane` "Project", `PackagesPane` "Packages", or one scope of the `Scope` pane, e.g. subId
 * `Scope 'Project Files'; set:Project Files; class …ProjectFilesScope` "Project Files".
 */
data class ProjectViewOption(
  val paneId: String,
  val subId: String?,
  val name: String,
  val iconPath: String?,
)

/**
 * The selector's entries, the current one, and the current pane's tree when the backend draws it.
 */
data class ProjectViews(
  val options: List<ProjectViewOption> = emptyList(),
  val current: ProjectViewOption? = null,
  /**
   * Packages and Scopes rows (a BeTreeGrid, not `RdProjectViewItem`s); ids are the client's own.
   * Empty while the Project pane is current: that tree is [SessionState.tree].
   */
  val tree: ProjectTree = ProjectTree(),
  /**
   * The current pane's Lux panel when the backend paints the pane itself: the welcome project's
   * "Projects and Files" (`NonModalWelcomeScreenProjectPane`, a `BeneluxView`). Null otherwise.
   */
  val panel: LuxDialog? = null,
)

/**
 * Follows `ProjectViewModel.panes` and `currentPane` for the selector, and the current pane's
 * BeTreeGrid when it is a [RdProjectViewBePane]. Called on the protocol thread.
 */
internal class ProjectViewPanes(
  private val iconPath: (IconModel) -> String?,
  /** The Lux component with this id, e.g. a `BeneluxView`'s panel; null when it isn't there. */
  private val luxComponent: (Int) -> Any?,
  private val log: (String) -> Unit,
  private val publish: (ProjectViews) -> Unit,
) {
  private var luxPanel: LxPanel? = null
  private var panelLifetime: Lifetime? = null
  private var model: ProjectViewModel? = null
  private val panes = mutableListOf<RdProjectViewPaneBase>()
  /** The scope picked in the Scopes pane; the backend doesn't say which one it shows. */
  private val subIds = mutableMapOf<String, String>()
  private var views = ProjectViews()
  private val treeLifetimes = SequentialLifetimes(Lifetime.Eternal)
  private val rows =
    BeTreeRows(iconPath) { tree ->
      views = views.copy(tree = tree)
      publish(views)
    }

  fun bind(solutionLifetime: Lifetime, projectView: ProjectViewModel) {
    model = projectView
    projectView.panes.view(solutionLifetime) { paneLifetime, _, pane ->
      panes += pane
      // As JetBrains Client's ThinClientProjectViewHostProtocolListener: a pane the backend marks
      // as the default is shown when it arrives, e.g. the welcome project's "Projects and Files".
      if (pane.isDefaultPane && pane.id != PROJECT_PANE_ID) {
        // The backend keeps the current pane across connections: it may name this pane before
        // the pane arrives, and setting the same id again doesn't fire.
        if ((projectView.currentPane.value as String?) == pane.id)
          showTree(pane as? RdProjectViewBePane)
        else projectView.currentPane.set(pane.id)
      } else if ((projectView.currentPane.value as String?) == pane.id) {
        showTree(pane as? RdProjectViewBePane)
      }
      if (pane is RdProjectViewBePane)
        pane.subIds.advise(paneLifetime) { ids ->
          if (pane.id !in subIds) ids.firstOrNull()?.let { subIds[pane.id] = it.subId }
          update()
        }
      update()
      paneLifetime.onTermination {
        panes -= pane
        update()
      }
    }
    // The backend asks for a pane (ProjectViewModel.activate), e.g. after Select In.
    projectView.activate.advise(solutionLifetime) { paneId ->
      if (panes.any { it.id == paneId }) projectView.currentPane.set(paneId)
    }
    projectView.currentPane.advise(solutionLifetime) { paneId ->
      showTree(panes.firstOrNull { it.id == paneId } as? RdProjectViewBePane)
      update()
    }
    solutionLifetime.onTermination { treeLifetimes.terminateCurrent() }
  }

  /** Shows [option]'s pane (and scope) in the Project tool window. */
  fun choose(option: ProjectViewOption) {
    val projectView = model ?: return
    val pane = panes.firstOrNull { it.id == option.paneId } ?: return
    if (option.subId != null && pane is RdProjectViewBePane) {
      subIds[pane.id] = option.subId
      pane.setSubId.fire(option.subId)
    }
    if (projectView.currentPane.value != pane.id) projectView.currentPane.set(pane.id) else update()
  }

  fun toggle(nodeId: Int) = rows.toggle(nodeId)

  fun select(nodeId: Int) = rows.select(nodeId)

  /** Double tap or Enter: the backend opens the file or class of the selected row. */
  fun open(nodeId: Int) = rows.open(nodeId)

  private fun update() {
    val options =
      panes
        .sortedBy { it.weight }
        .flatMap { pane ->
          val icon = pane.icon?.let(iconPath)
          val scopes = (pane as? RdProjectViewBePane)?.subIds?.valueOrNull.orEmpty()
          if (scopes.isEmpty()) listOf(ProjectViewOption(pane.id, null, pane.name, icon))
          else scopes.map { ProjectViewOption(pane.id, it.subId, it.name, it.icon?.let(iconPath)) }
        }
    val paneId = model?.currentPane?.value
    val current =
      options.firstOrNull { it.paneId == paneId && it.subId == subIds[paneId] }
        ?: options.firstOrNull { it.paneId == paneId }
    views = views.copy(options = options, current = current)
    publish(views)
  }

  private fun showTree(pane: RdProjectViewBePane?) {
    val treeLifetime = treeLifetimes.next()
    rows.clear()
    luxPanel = null
    views = views.copy(tree = ProjectTree(), panel = null)
    pane ?: return
    // control → BeDynamicControlContainer → BeContextActionBehavior → BeBehaviorControl →
    // BeTreeGrid
    fun find(control: BeControl) {
      when (control) {
        is BeTreeGrid -> rows.show(treeLifetime, control)
        is BeneluxView -> showPanel(treeLifetime, control)
        is BeDynamicControlContainer -> control.content.advise(treeLifetime, ::find)
        is BeControlContainer -> find(control.content)
        else -> Unit
      }
    }
    pane.control.advise(treeLifetime, ::find)
  }

  /**
   * A pane the backend paints: its `LxPanel`, shown as JetBrains Client's `BeneluxViewControl`
   * does. It is drawn once it has a screen, a place and `visible`; its size is the tool window's
   * ([resizePanel]) when its layout source is the frontend, else what it asks for.
   */
  private fun showPanel(panelLifetime: Lifetime, view: BeneluxView) {
    val panel = luxComponent(view.luxId) as? LxPanel
    if (panel == null) {
      log("Project view pane: no Lux panel ${view.luxId}")
      return
    }
    val surface = LuxDialog(view.luxId, false, true, true, "", 0, 0)
    luxPanel = panel
    if (panel.layoutSource != LuxLayoutSource.Frontend)
      panel.preferredSize.advise(panelLifetime) { preferred -> panel.size.set(preferred) }
    panel.size.advise(panelLifetime) { size ->
      surface.width = size.width
      surface.height = size.height
    }
    panel.inputLocation.advise(panelLifetime) { location -> surface.acceptsText = location != null }
    panel.graphics.drawOperations.advise(panelLifetime) { operations ->
      surface.batches += operations
      surface.version++
    }
    if (panel.graphicsConfiguration.valueOrNull == null)
      panel.graphicsConfiguration.set(LuxDialogs.CONFIGURATION)
    if (panel.position.valueOrNull == null) panel.position.set(LxPoint(0.0, 0.0))
    panel.visible.set(true)
    // The panel may have been painted for an earlier connection (the backend keeps it), and then
    // nothing new arrives: ask for a frame, as LuxFrontendPanelAdapter.forceHostToRepaint does.
    panel.requestRepaint.start(panelLifetime, Unit)
    this.panelLifetime = panelLifetime
    panelLifetime.onTermination {
      luxPanel = null
      this.panelLifetime = null
    }
    views = views.copy(panel = surface)
    publish(views)
  }

  /**
   * The tool window's content area, in backend pixels (dp), and where it is on IDEate's screen, so
   * the backend places the panel's popups (New ▾) next to it.
   */
  fun resizePanel(x: Int, y: Int, width: Int, height: Int) {
    val panel = luxPanel ?: return
    val surface = views.panel ?: return
    surface.x = x
    surface.y = y
    panel.position.set(LxPoint(x.toDouble(), y.toDouble()))
    if (panel.layoutSource == LuxLayoutSource.Frontend) panel.size.set(LxSize(width, height))
  }

  /** Asks the backend to paint the pane's panel again, whole. */
  fun repaintPanel() {
    val panel = luxPanel ?: return
    val lifetime = panelLifetime ?: return
    panel.requestRepaint.start(lifetime, Unit)
  }

  /** A tap, a scroll or a key for the pane's panel. */
  fun panelEvent(event: LxEvent) {
    luxPanel?.events?.fire(event)
  }
}

/** `RdProjectViewAsyncPane` "ProjectPane": the Project view, which [ClientSession] draws itself. */
internal const val PROJECT_PANE_ID = "ProjectPane"
