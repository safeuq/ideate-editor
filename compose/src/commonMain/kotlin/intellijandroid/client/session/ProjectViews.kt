package intellijandroid.client.session

import com.jetbrains.codeWithMe.model.ProjectViewModel
import com.jetbrains.codeWithMe.model.RdProjectViewBePane
import com.jetbrains.codeWithMe.model.RdProjectViewPaneBase
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeDynamicControlContainer
import com.jetbrains.ide.model.uiautomation.BeTreeGrid
import com.jetbrains.rd.ide.model.IconModel
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
)

/**
 * Follows `ProjectViewModel.panes` and `currentPane` for the selector, and the current pane's
 * BeTreeGrid when it is a [RdProjectViewBePane]. Called on the protocol thread.
 */
internal class ProjectViewPanes(
  private val iconPath: (IconModel) -> String?,
  private val publish: (ProjectViews) -> Unit,
) {
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
    views = views.copy(tree = ProjectTree())
    pane ?: return
    // control → BeDynamicControlContainer → BeContextActionBehavior → BeBehaviorControl →
    // BeTreeGrid
    fun find(control: BeControl) {
      when (control) {
        is BeTreeGrid -> rows.show(treeLifetime, control)
        is BeDynamicControlContainer -> control.content.advise(treeLifetime, ::find)
        is BeControlContainer -> find(control.content)
        else -> Unit
      }
    }
    pane.control.advise(treeLifetime, ::find)
  }
}
