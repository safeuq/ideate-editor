package io.github.safeuq.ideate.client.session

import com.jetbrains.codeWithMe.model.NavBarElementModel
import com.jetbrains.codeWithMe.model.NavBarNavigateArgs
import com.jetbrains.codeWithMe.model.NavigationBarModel
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * One breadcrumb of the status bar (docs/status-bar.md), e.g. `xtool-server-lib`, `src`, `main`, …,
 * `ExecutionManager`. [nodeId] is the backend's id for [Breadcrumbs.children] and
 * [Breadcrumbs.navigate].
 */
data class Crumb(
  val nodeId: Long,
  val text: String,
  val iconPath: String?,
  val canNavigate: Boolean,
  /**
   * In a file: the file itself, a class or a member, which a choice in the list opens. A folder or
   * module (false) opens its own list instead, as in IntelliJ.
   */
  val inFile: Boolean,
)

/**
 * The status bar's breadcrumbs from `solution.navigationBarModel`, as JetBrains Client's
 * `ThinClientNavBarModelExtension` asks for them: `getNavBarElements` with the selected editor's
 * action context (its caret), again whenever the backend fires `updateNavBar` or Ideate's editor or
 * caret changes. Called on the protocol thread.
 */
internal class Breadcrumbs(
  private val iconPath: (IconModel) -> String?,
  private val publish: (List<Crumb>) -> Unit,
  private val log: (String) -> Unit,
) {
  private var model: NavigationBarModel? = null
  private var lifetime: Lifetime = Lifetime.Terminated
  /** Answers of older requests that arrive late are dropped. */
  private var request = 0

  fun bind(solutionLifetime: Lifetime, navigationBar: NavigationBarModel, refresh: () -> Unit) {
    model = navigationBar
    lifetime = solutionLifetime
    navigationBar.updateNavBar.advise(solutionLifetime) { refresh() }
    solutionLifetime.onTermination {
      model = null
      publish(emptyList())
    }
  }

  /** The path to the caret in [context], the selected editor's action context. */
  fun refresh(context: ActionTimestampSetModel) {
    val navigationBar = model ?: return
    val number = ++request
    navigationBar.getNavBarElements.start(lifetime, context).result.advise(lifetime) { result ->
      if (number != request) return@advise
      runCatching { result.unwrap() }
        // The backend lists them from the caret out (App, demo, java, …, gradle-smoke); the bar
        // shows them from the project in.
        .onSuccess { elements -> publish(elements.map(::crumbOf).asReversed()) }
        .onFailure { failure -> log("Breadcrumbs not loaded: ${failure.message}") }
    }
  }

  /** A crumb's children, for the list it opens, e.g. the files of `src`. */
  fun children(nodeId: Long, onResult: (List<Crumb>) -> Unit) {
    val navigationBar = model ?: return
    navigationBar.getChildren.start(lifetime, nodeId).result.advise(lifetime) { result ->
      runCatching { result.unwrap() }
        .onSuccess { elements -> onResult(elements.map(::crumbOf)) }
        .onFailure { failure -> log("Breadcrumb children not loaded: ${failure.message}") }
    }
  }

  /** Opens [nodeId] in the editor, as a click on a file or member in IntelliJ's list does. */
  fun navigate(nodeId: Long) {
    model?.navigate?.fire(NavBarNavigateArgs(nodeId, true))
  }

  private fun crumbOf(element: NavBarElementModel) =
    Crumb(
      element.nodeId,
      element.text,
      element.icon?.let(iconPath),
      element.canNavigate,
      element.hasContainingFile,
    )
}
