package intellijandroid.client.session

import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.toolWindowModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * A tool window the backend has registered, from `solution.toolWindowModel.availableToolWindows`
 * (docs/tool-windows.md). Project, Run, Debug, Terminal, Services and Notifications aren't among
 * them: JetBrains Client registers those itself.
 */
data class BackendToolWindow(
  /** E.g. `Commit`, `Problems View`, `Pull Requests`. */
  val id: String,
  /** E.g. `Problems` for `Problems View`. */
  val title: String,
  /** E.g. `expui/toolwindows/commit.svg`. */
  val iconPath: String?,
  /**
   * `BeToolWindow.position`, where it goes when the layout doesn't place it: LEFT, RIGHT, BOTTOM.
   */
  val anchor: String,
  /**
   * `BeIdeaToolWindow.available`: false while it has nothing to show, e.g. Commit in a project
   * without version control. True until the backend says otherwise.
   */
  val available: Boolean = true,
  /**
   * `BeIdeaToolWindow.isVisibleOnLargeStripe`: true when the backend wants a stripe button for it
   * in the new UI; null when it didn't say.
   */
  val onStripe: Boolean? = null,
)

/** Keeps [publish]ing the backend's tool windows, in the order the backend registered them. */
internal class BackendToolWindows(
  private val iconPath: (IconModel) -> String?,
  private val publish: (List<BackendToolWindow>) -> Unit,
) {
  private val windows = LinkedHashMap<String, BackendToolWindow>()

  fun bind(solutionLifetime: Lifetime, solution: Solution) {
    solution.toolWindowModel.availableToolWindows.view(solutionLifetime) { windowLifetime, id, model
      ->
      val toolWindow = model.toolWindow
      windows[id] =
        BackendToolWindow(
          id,
          toolWindow.title.text.value,
          toolWindow.title.icon.value?.let(iconPath),
          toolWindow.position.name,
        )
      publish(windows.values.toList())
      fun update(change: BackendToolWindow.() -> BackendToolWindow) {
        windows[id] = windows.getValue(id).change()
        publish(windows.values.toList())
      }
      model.available.advise(windowLifetime) { update { copy(available = it) } }
      model.isVisibleOnLargeStripe.advise(windowLifetime) { update { copy(onStripe = it) } }
      toolWindow.title.icon.advise(windowLifetime) { icon ->
        update { copy(iconPath = icon?.let(this@BackendToolWindows.iconPath)) }
      }
      windowLifetime.onTermination {
        windows.remove(id)
        publish(windows.values.toList())
      }
    }
  }
}
