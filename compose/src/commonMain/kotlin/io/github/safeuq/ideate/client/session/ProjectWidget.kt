package io.github.safeuq.ideate.client.session

import androidx.compose.ui.unit.IntRect
import com.jetbrains.ide.model.uiautomation.BeContextActionBehavior
import com.jetbrains.ide.model.uiautomation.BeToolbarComboButton
import com.jetbrains.rd.ide.model.ActionComponentRequest
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.AsyncActionUpdateRequest
import com.jetbrains.rd.ide.model.AsyncActionsModel
import com.jetbrains.rd.ide.model.DeferredIconModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RectangleModel
import com.jetbrains.rd.ide.model.ToolbarKindModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * What the main toolbar's project widget shows: the backend button's [text], and its icon as a path
 * in the icon jar ([iconPath]) or as the project whose avatar it is ([avatar]). E.g.
 * `ProjectWidgetLook("IntelliJ IDEA Home", "expui/nodes/homeFolder.svg", null)`,
 * `ProjectWidgetLook("beta", null, "beta")`.
 */
data class ProjectWidgetLook(val text: String, val iconPath: String?, val avatar: String?)

/**
 * The main toolbar's project widget (IntelliJ's ProjectToolbarWidgetAction,
 * `main.toolbar.Project`).
 */
internal class ProjectWidget(
  private val model: AsyncActionsModel,
  private val projectId: RdProjectId,
  private val log: (String) -> Unit,
  private val iconPath: (IconModel) -> String?,
  /** Runs the action with the icon of `DeferredIconModel` [id] once the backend has sent it. */
  private val whenIconReady: (id: Long, (IconModel) -> Unit) -> Unit,
  private val publish: (ProjectWidgetLook) -> Unit,
) {
  private var lifetime: Lifetime = Lifetime.Terminated
  /** The backend's `BeToolbarComboButton`, asked for once after the first `expandGroup`. */
  private var button: BeToolbarComboButton? = null
  /** The widget's place in the window at the last click, dp. */
  private var bounds: IntRect? = null
  /** True once the widget was clicked in this session; see `ClientSession.switchTo`. */
  var clicked = false
    private set

  fun bind(solutionLifetime: Lifetime) {
    lifetime = solutionLifetime
    solutionLifetime.onTermination { button = null }
    load {}
  }

  /**
   * The widget was clicked. The backend acts on the click only for a component asked for after the
   * main toolbar's `expandGroup` with the same `factoryId` (seen 2026-11-24), so that comes first.
   */
  fun click(bounds: IntRect) {
    this.bounds = bounds
    clicked = true
    load(::fire)
  }

  /**
   * Runs [then] with the backend's button, after the main toolbar's `expandGroup`. The first time
   * the button is asked for too.
   */
  private fun load(then: (BeToolbarComboButton) -> Unit) {
    val request =
      AsyncActionUpdateRequest(
        TOOLBAR_GROUP,
        ActionTimestampSetModel(listOf(ProjectTimestampModel(projectId))),
        PLACE,
        ToolbarKindModel(true),
        FACTORY,
      )
    model.expandGroup.start(lifetime, request).result.advise(lifetime) { expanded ->
      runCatching { expanded.unwrap() }
        .onFailure { failure ->
          log("Project widget: toolbar not expanded: ${failure.message}")
          return@advise
        }
      val known = button
      if (known != null) return@advise then(known)
      model.getComponent
        .start(lifetime, ActionComponentRequest(ACTION, PLACE, FACTORY, projectId))
        .result
        .advise(lifetime) { answer ->
          val control = runCatching {
            answer.unwrap()
          }
            .onFailure { failure ->
              log("Project widget: component not loaded: ${failure.message}")
            }
            .getOrNull()
          val found = (control as? BeContextActionBehavior)?.content as? BeToolbarComboButton
          if (found == null) {
            log("Project widget: no combo button, got ${control?.let { it::class.simpleName }}")
            return@advise
          }
          button = found
          show(found)
          // Not used here: a `doAction` fired inside this answer is lost (seen 2026-11-24, the
          // first click after connecting opened nothing; rd binds the component after the
          // answer's handlers ran). One more round trip to the backend, then [then] runs.
          load(then)
        }
    }
  }

  /**
   * The button's text and first left icon are what the toolbar shows, as JetBrains Client does:
   * `alpha` with its avatar or its own icon file, `IntelliJ IDEA Home` with the home icon for the
   * welcome project (whose folder is `IdeaHome`).
   */
  private fun show(comboButton: BeToolbarComboButton) {
    var text = ""
    var icon: IconModel? = null
    fun publishLook() {
      val model = icon
      publish(ProjectWidgetLook(text, model?.let(iconPath), avatarProject(model)))
    }
    comboButton.text.advise(lifetime) {
      text = it.orEmpty()
      publishLook()
    }
    comboButton.leftIcons.advise(lifetime) {
      val first = comboButton.leftIcons.firstOrNull()
      icon = first
      publishLook()
      if (first is DeferredIconModel)
        whenIconReady(first.id) { ready ->
          if (icon === first) {
            icon = ready
            publishLook()
          }
        }
    }
  }

  private fun fire(comboButton: BeToolbarComboButton) {
    val place = bounds ?: return
    comboButton.doAction.fire(RectangleModel(place.left, place.top, place.width, place.height))
  }

  /** Where a popup anchored to component [ownerId] goes: the widget's bottom-left corner. */
  fun popupPlace(ownerId: String): LxPoint? {
    val place = bounds ?: return null
    log("Project widget: popup owner $ownerId, button ${button?.uniqueId?.valueOrNull}")
    return LxPoint(place.left.toDouble(), place.bottom.toDouble())
  }
}

private const val ACTION = "main.toolbar.Project"
private const val PLACE = "MainToolbar"
private const val FACTORY = 1
private const val TOOLBAR_GROUP =
  "composite:MainToolbarGeneralActionsGroup;MainToolbarLeft;MainToolbarVCSGroup;ToolboxProjectPopupAction;UnattendedHostMenuGroup;main.toolbar.Project"
