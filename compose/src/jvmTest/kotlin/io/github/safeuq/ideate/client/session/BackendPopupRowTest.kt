package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.uiautomation.BeAlignment
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlWithGridConstraints
import com.jetbrains.ide.model.uiautomation.BeGridConstraints
import com.jetbrains.ide.model.uiautomation.BeGridLayoutPanel
import com.jetbrains.ide.model.uiautomation.BeMargin
import com.jetbrains.ide.model.uiautomation.BeMarginItem
import com.jetbrains.ide.model.uiautomation.BeMarginType
import com.jetbrains.ide.model.uiautomation.BeRichText
import com.jetbrains.ide.model.uiautomation.BeTreeGridLine
import com.jetbrains.rd.ide.model.AvatarIconModel
import com.jetbrains.rd.ide.model.DeferredIconModel
import com.jetbrains.rd.ide.model.EffectStyle
import com.jetbrains.rd.ide.model.EmptyIconModel
import com.jetbrains.rd.ide.model.FontStyle
import com.jetbrains.rd.ide.model.FullProjectAvatarIconPresentationModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.RichStringModel
import com.jetbrains.rd.ide.model.RichTextModel
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendPopupRowTest {
  private fun text(text: String, icon: IconModel? = null) =
    BeRichText(false).also {
      it.text.set(
        RichTextModel(
          listOf(RichStringModel(text, null, null, null, EffectStyle.None, FontStyle.Regular)),
        ),
      )
      it.icon.set(icon)
    }

  private fun cell(gridIndex: Int, content: BeControl): BeControlWithGridConstraints {
    val none = BeMarginItem(BeMarginType.OnePx, 0)
    val margin = BeMargin(none, none, none, none)
    return BeControlWithGridConstraints(
      BeGridConstraints(
        gridIndex,
        0,
        0,
        1,
        1,
        BeAlignment.Left,
        BeAlignment.Top,
        true,
        margin,
        margin,
        null,
      ),
      content,
    )
  }

  // A row of the project widget's popup, as a 263 backend sent it for the open project `beta`.
  @Test
  fun aProjectRowHasItsNameItsPathBelowAndItsDeferredIcon() {
    val panel = BeGridLayoutPanel()
    panel.items.set(
      listOf(
        cell(0, text("", DeferredIconModel(63, EmptyIconModel(20, 20)))),
        cell(2, text("beta")),
        cell(3, text("/projects/beta")),
      ),
    )
    val row = BeRow.of(BeTreeGridLine(listOf(panel))) { null }
    assertEquals("beta", row.text)
    assertEquals("/projects/beta", row.description)
    assertEquals(63L, row.deferredIcon)
  }

  // The same row in the popup opened from the second project: the avatar comes with the row.
  @Test
  fun aProjectRowCanCarryItsAvatar() {
    val avatar =
      AvatarIconModel(20, 0.4, FullProjectAvatarIconPresentationModel("beta", "/projects/beta"))
    val panel = BeGridLayoutPanel()
    panel.items.set(listOf(cell(0, text("", avatar)), cell(2, text("beta"))))
    val row = BeRow.of(BeTreeGridLine(listOf(panel))) { null }
    assertEquals("beta", row.avatar)
    assertEquals(null, row.deferredIcon)
  }

  // Typing in the project widget's popup: the rows that have the text in their name or path stay.
  @Test
  fun typingKeepsTheRowsThatHaveTheTextAndSelectsTheFirst() {
    fun row(name: String, path: String?) =
      PopupRow(null, name, BeTreeGridLine(listOf()), description = path)
    val list =
      PopupList(
        listOf(row("Open…", null), row("alpha", "/work/beta-old"), row("beta", "/work/beta")),
      )
    list.type("BET")
    assertEquals(listOf("alpha", "beta"), list.rows.filter(list::shows).map { it.text })
    assertEquals(1, list.selected)
    list.move(1)
    assertEquals(2, list.selected)
    list.move(1)
    assertEquals(1, list.selected)
  }
}
