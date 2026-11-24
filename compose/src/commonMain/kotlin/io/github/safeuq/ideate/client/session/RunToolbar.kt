package io.github.safeuq.ideate.client.session

import com.jetbrains.codeWithMe.model.CodeWithMeRunConfigurationsModel
import com.jetbrains.rd.ide.model.ActionBaseModel
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.AsyncActionGroupExpandResponse
import com.jetbrains.rd.ide.model.AsyncActionUpdateRequest
import com.jetbrains.rd.ide.model.AsyncActionsModel
import com.jetbrains.rd.ide.model.GroupModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.NameEnumValue
import com.jetbrains.rd.ide.model.ToolbarKindModel
import com.jetbrains.rd.util.lifetime.Lifetime

/**
 * One button of the run widget as the backend says it is now, e.g. `Run` with [text] "Rerun
 * 'Application: Alpha'" and its rerun icon while Alpha runs; `Stop` is not among the buttons while
 * nothing runs. [enabled] false is greyed out ("Cannot run the current file…" is then the
 * [description]).
 */
data class RunToolbarButton(
  val actionId: String,
  val text: String?,
  val description: String?,
  val iconPath: String?,
  val enabled: Boolean,
  /**
   * `EXECUTOR_ACTION_STATUS` (a client property the backend sends as an enum): Run and Debug have
   * it, `RUNNING` while their configuration runs; null for the other buttons.
   */
  val executorStatus: ExecutorStatus? = null,
)

/** IntelliJ's `ExecutorActionStatus`, in its order: the backend sends the ordinal. */
enum class ExecutorStatus {
  NORMAL,
  LOADING,
  RUNNING,
}

/** The run configuration selector's label: `Application.Alpha` shows `Alpha`; none shows this. */
data class RunSelector(val name: String = CURRENT_FILE, val iconPath: String? = null)

private const val CURRENT_FILE = "Current File"
private const val SELECTOR_ACTION = "RedesignedRunConfigurationSelector"
private const val MAIN_GROUP = "RunToolbarMainActionGroup"
private const val EXECUTOR_ACTION_STATUS = "EXECUTOR_ACTION_STATUS"

/**
 * The run widget (docs/plans/run-toolbar.md): the backend's `RunToolbarMainActionGroup` in the
 * place `NewUiRunToolbar`, asked for as JetBrains Client does: one composite group id that names
 * the buttons' groups, so a single answer holds every button's state. Asked again when the selected
 * configuration or the running ones change (the backend then has new text for Run and Stop), when
 * the selected editor changes, and after a button's action ran. Called on the rd thread.
 */
internal class RunToolbar(
  private val model: AsyncActionsModel,
  private val iconPath: (IconModel) -> String?,
  /** The selected editor's action context with the project; null while no project is open. */
  private val timestamps: () -> ActionTimestampSetModel?,
  private val publishButtons: (List<RunToolbarButton>) -> Unit,
  private val publishSelector: (RunSelector) -> Unit,
  private val log: (String) -> Unit,
  /** Runs the action on the rd thread after the delay in milliseconds. */
  private val later: (delayMillis: Long, action: () -> Unit) -> Unit,
) {
  private var lifetime: Lifetime = Lifetime.Terminated
  /** Answers of older requests that arrive late are dropped. */
  private var latestRequest = 0

  fun bind(solutionLifetime: Lifetime, configurations: CodeWithMeRunConfigurationsModel) {
    lifetime = solutionLifetime
    fun showSelection() {
      val current = configurations.clientCurrentConfiguration.value
      val configuration = current?.let { configurations.runConfigurations[it] }
      publishSelector(
        RunSelector(configuration?.name ?: CURRENT_FILE, configuration?.icon?.let(iconPath)),
      )
    }
    configurations.clientCurrentConfiguration.advise(solutionLifetime) {
      showSelection()
      refresh()
    }
    // The list arrives after the selection, so the name can only be looked up now.
    configurations.runConfigurations.advise(solutionLifetime) { showSelection() }
    configurations.executingConfigurations.advise(solutionLifetime) { refresh() }
    solutionLifetime.onTermination {
      publishButtons(emptyList())
      publishSelector(RunSelector())
    }
  }

  fun refresh() {
    val context = timestamps() ?: return
    val number = ++latestRequest
    // factoryId null: the backend answers a toolbar with it (seen 2026-11-24), no id of our own.
    val request =
      AsyncActionUpdateRequest(COMPOSITE_GROUP, context, PLACE, ToolbarKindModel(true), null)
    model.expandGroup.start(lifetime, request).result.advise(lifetime) { result ->
      if (number != latestRequest) return@advise
      runCatching { result.unwrap() }
        .onSuccess { response ->
          if (response == null) log("Run toolbar: the backend sent no answer")
          else {
            val buttons = buttonsOf(response, iconPath)
            publishButtons(buttons)
            // IntelliJ's toolbar updates on a timer while its window is active, and JetBrains
            // Client asks again on each update. Ideate asks every 500 ms while a session runs: a
            // debug session of a Gradle task changes no `executingConfigurations` entry (seen
            // 2026-11-24), so nothing else would show Pause, Resume or the end of the session.
            val running = buttons.any {
              it.executorStatus.let { status -> status != null && status != ExecutorStatus.NORMAL }
            }
            if (running && number == latestRequest) later(UPDATE_MILLIS) { refresh() }
          }
        }
        .onFailure { failure -> log("Run toolbar not loaded: ${failure.message}") }
    }
  }

  companion object {
    private const val UPDATE_MILLIS = 500L
    const val PLACE = "NewUiRunToolbar"

    /** The groups and actions of the widget, as JetBrains Client names them (sorted, `;` apart). */
    const val COMPOSITE_GROUP =
      "composite:Debug;MoreRunToolbarActions;Run;RunToolbarMainActionGroup;" +
        "RunToolbarTopLevelExecutorActionGroup;Stop;compositeResumeGroup"

    /**
     * IntelliJ's `filterOutRunIfDebugResumeIsPresent`: with the debugger's Resume or Pause shown,
     * Run is left out (the Debug button is then Restart); with an inline Stop, Debug is.
     */
    private fun filterOutRunIfDebugResumeIsPresent(
      buttons: List<RunToolbarButton>,
    ): List<RunToolbarButton> =
      when {
        buttons.any { "XDebuggerResumeAction" in it.actionId } ->
          buttons.filter { it.actionId != "Run" }
        buttons.any { it.actionId == "StopConfigurationInlineAction" } ->
          buttons.filter { it.actionId != "Debug" }
        else -> buttons
      }

    /**
     * The visible children of `RunToolbarMainActionGroup` in order, its plain groups (the executor
     * group's Run and Debug, the debugger's Resume) opened in place. The selector is left out: the
     * client draws it from [RunSelector].
     */
    fun buttonsOf(
      response: AsyncActionGroupExpandResponse,
      iconPath: (IconModel) -> String?,
    ): List<RunToolbarButton> {
      val presentations = response.presentations.associate { it.id to it.presentation }
      val children = response.children.associate { it.id to it.children }
      fun buttons(groupId: String): List<RunToolbarButton> =
        children[groupId].orEmpty().filterIsInstance<ActionBaseModel>().flatMap { action ->
          val presentation = presentationOf(action, presentations)
          when {
            !presentation.isVisible || action.actionId == SELECTOR_ACTION -> emptyList()
            action is GroupModel && !presentation.isPopup && action.actionId in children ->
              buttons(action.actionId)
            else ->
              listOf(
                RunToolbarButton(
                  action.actionId,
                  presentation.text,
                  presentation.description,
                  presentation.icon?.icon?.let(iconPath),
                  presentation.isEnabled,
                  presentation.clientProperties
                    .orEmpty()
                    .filterIsInstance<NameEnumValue>()
                    .firstOrNull { it.name == EXECUTOR_ACTION_STATUS }
                    ?.let { ExecutorStatus.entries.getOrNull(it.ordinal) },
                ),
              )
          }
        }
      return filterOutRunIfDebugResumeIsPresent(buttons(MAIN_GROUP))
    }
  }
}
