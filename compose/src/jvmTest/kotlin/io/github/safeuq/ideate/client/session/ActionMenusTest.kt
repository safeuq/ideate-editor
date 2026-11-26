package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.ActionBehaviourSpecificationModel
import com.jetbrains.rd.ide.model.ActionIdAndPresentationModel
import com.jetbrains.rd.ide.model.ActionModel
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.AsyncActionGroupExpandResponse
import com.jetbrains.rd.ide.model.GroupIdAndChildrenModel
import com.jetbrains.rd.ide.model.NameBooleanValue
import com.jetbrains.rd.ide.model.OwnerAccessRequirementsModel
import com.jetbrains.rd.ide.model.PresentationModel
import com.jetbrains.rd.ide.model.ToggleActionModel
import io.github.safeuq.ideate.client.ui.MenuItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The main menu's items: toggles' check marks and who runs a chosen item (MenuTree). */
class ActionMenusTest {
  private val visibleAndEnabled = 0x3

  private fun presentation(text: String, selected: Boolean? = null) =
    PresentationModel(
      visibleAndEnabled,
      text,
      null,
      null,
      listOfNotNull(selected?.let { NameBooleanValue(it, "selected") }),
      false,
    )

  private val softWraps =
    ToggleActionModel(
      presentation("Soft-Wrap"),
      emptyList(),
      OwnerAccessRequirementsModel(),
      ActionBehaviourSpecificationModel.BackendOnly,
      false,
      false,
      false,
      "EditorToggleUseSoftWraps",
    )

  private val saveAll =
    ActionModel(
      presentation("Save _All"),
      emptyList(),
      OwnerAccessRequirementsModel(),
      ActionBehaviourSpecificationModel.BackendOnly,
      false,
      false,
      false,
      "SaveAll",
    )

  private fun items(selected: Boolean?, onItem: (String) -> Unit = {}): List<MenuItem> {
    val tree =
      MenuTree("MainMenu", ActionTimestampSetModel(emptyList()), { null }, {}) { actionId, _ ->
        onItem(actionId)
      }
    tree.add(
      AsyncActionGroupExpandResponse(
        listOf(
          ActionIdAndPresentationModel(
            "EditorToggleUseSoftWraps",
            presentation("Soft-Wrap", selected),
          ),
        ),
        listOf(GroupIdAndChildrenModel("Group", listOf(softWraps, saveAll))),
        emptyList(),
      ),
    )
    return tree.entries("Group", inUnshownGroup = false).filterIsInstance<MenuItem>()
  }

  @Test
  fun aToggleThatIsOnIsChecked() {
    assertEquals(listOf(true, null), items(selected = true).map { it.checked })
  }

  @Test
  fun aToggleWithoutItsStateIsUnchecked() {
    assertEquals(listOf(false, null), items(selected = null).map { it.checked })
  }

  @Test
  fun aChosenItemGoesToTheCallerWithItsId() {
    val chosen = mutableListOf<String>()
    items(selected = false, onItem = chosen::add).forEach { it.onClick() }
    assertEquals(listOf("EditorToggleUseSoftWraps", "SaveAll"), chosen)
  }
}
