package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.ActionBehaviourSpecificationModel
import com.jetbrains.rd.ide.model.ActionIdAndPresentationModel
import com.jetbrains.rd.ide.model.ActionModel
import com.jetbrains.rd.ide.model.AsyncActionGroupExpandResponse
import com.jetbrains.rd.ide.model.GroupIdAndChildrenModel
import com.jetbrains.rd.ide.model.GroupModel
import com.jetbrains.rd.ide.model.OwnerAccessRequirementsModel
import com.jetbrains.rd.ide.model.PresentationModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The run widget's three recorded states (docs/plans/run-toolbar.md, JetBrains Client
 * 263.7307.200): the flags are the recorded ones, `0x1` enabled, `0x2` visible.
 */
class RunToolbarTest {
  private fun presentation(flags: Int, text: String? = null, description: String? = null) =
    PresentationModel(flags, text, null, description, emptyList(), false)

  private fun action(id: String) =
    ActionModel(
      presentation(0x882, id),
      emptyList(),
      OwnerAccessRequirementsModel(),
      ActionBehaviourSpecificationModel.BackendOnly,
      false,
      false,
      false,
      id,
    )

  private fun group(id: String) =
    GroupModel(
      false,
      presentation(0x882, id),
      emptyList(),
      OwnerAccessRequirementsModel(),
      ActionBehaviourSpecificationModel.BackendOnly,
      false,
      false,
      false,
      id,
    )

  /** What the backend answers for the composite group, with [states] per action id. */
  private fun answer(states: Map<String, PresentationModel>) =
    AsyncActionGroupExpandResponse(
      states.map { (id, state) -> ActionIdAndPresentationModel(id, state) },
      listOf(
        GroupIdAndChildrenModel(
          "RunToolbarMainActionGroup",
          listOf(
            action("RedesignedRunConfigurationSelector"),
            group("compositeResumeGroup"),
            group("RunToolbarTopLevelExecutorActionGroup"),
            action("Stop"),
            action("MoreRunToolbarActions"),
            action("InactiveStopActionPlaceholder"),
          ),
        ),
        GroupIdAndChildrenModel(
          "compositeResumeGroup",
          listOf(action("ConfigurationXDebuggerResumeAction")),
        ),
        GroupIdAndChildrenModel(
          "RunToolbarTopLevelExecutorActionGroup",
          listOf(action("Run"), action("Debug")),
        ),
      ),
      emptyList(),
    )

  private fun buttons(states: Map<String, PresentationModel>) =
    RunToolbar.buttonsOf(answer(states)) { null }

  private val hidden = presentation(0x880)

  @Test
  fun nothingSelectedShowsDisabledRunAndDebugThenMoreAndTheEmptySlot() {
    val noEditor = "Cannot run the current file: the editor is not in focus"
    val result =
      buttons(
        mapOf(
          "RedesignedRunConfigurationSelector" to presentation(0x883),
          "ConfigurationXDebuggerResumeAction" to hidden,
          "Run" to presentation(0x882, "Run", noEditor),
          "Debug" to presentation(0x882, "Debug", noEditor),
          "Stop" to hidden,
          "MoreRunToolbarActions" to presentation(0x88b, "More Actions"),
          "InactiveStopActionPlaceholder" to presentation(0x882),
        ),
      )

    assertEquals(
      listOf("Run", "Debug", "MoreRunToolbarActions", "InactiveStopActionPlaceholder"),
      result.map { it.actionId },
    )
    assertEquals(listOf(false, false, true, false), result.map { it.enabled })
    assertEquals(noEditor, result.first().description)
  }

  @Test
  fun aSelectedConfigurationEnablesRunAndDebug() {
    val result =
      buttons(
        mapOf(
          "ConfigurationXDebuggerResumeAction" to hidden,
          "Run" to presentation(0x883, "Run 'Alpha'"),
          "Debug" to presentation(0x883, "Debug 'Alpha'"),
          "Stop" to hidden,
          "MoreRunToolbarActions" to presentation(0x88b, "More Actions"),
          "InactiveStopActionPlaceholder" to presentation(0x882),
        ),
      )

    assertEquals(
      listOf("Run", "Debug", "MoreRunToolbarActions", "InactiveStopActionPlaceholder"),
      result.map { it.actionId },
    )
    assertEquals(listOf("Run 'Alpha'", "Debug 'Alpha'"), result.take(2).map { it.text })
    assertEquals(listOf(true, true, true, false), result.map { it.enabled })
  }

  @Test
  fun aRunningConfigurationShowsRerunDebugStopAndMore() {
    val result =
      buttons(
        mapOf(
          "ConfigurationXDebuggerResumeAction" to presentation(0x880, "Pause 'Alpha'"),
          "Run" to presentation(0x883, "Rerun 'Application: Alpha'"),
          "Debug" to presentation(0x883, "Debug 'Alpha'"),
          "Stop" to presentation(0x883, "Stop 'Alpha'"),
          "MoreRunToolbarActions" to presentation(0x88b, "More Actions"),
          "InactiveStopActionPlaceholder" to hidden,
        ),
      )

    assertEquals(
      listOf("Run", "Debug", "Stop", "MoreRunToolbarActions"),
      result.map { it.actionId },
    )
    assertEquals("Rerun 'Application: Alpha'", result.first().text)
    assertEquals(listOf(true, true, true, true), result.map { it.enabled })
  }
}
