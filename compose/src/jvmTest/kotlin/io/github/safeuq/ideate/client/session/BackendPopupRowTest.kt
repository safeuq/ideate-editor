package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.uiautomation.BeAlignment
import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlWithGridConstraints
import com.jetbrains.ide.model.uiautomation.BeGridConstraints
import com.jetbrains.ide.model.uiautomation.BeGridLayoutPanel
import com.jetbrains.ide.model.uiautomation.BeMargin
import com.jetbrains.ide.model.uiautomation.BeMarginItem
import com.jetbrains.ide.model.uiautomation.BeMarginModifier
import com.jetbrains.ide.model.uiautomation.BeMarginType
import com.jetbrains.ide.model.uiautomation.BeRichText
import com.jetbrains.ide.model.uiautomation.BeStyleControl
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
    assertEquals(listOf(RowDetail("/projects/beta", null)), row.details)
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
      PopupRow(
        null,
        name,
        BeTreeGridLine(listOf()),
        details = listOfNotNull(path).map { RowDetail(it, null) },
      )
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

  // A project in a git repository, as ProjectToolbarWidgetAction's renderer sends it: the branch is
  // a line of its own with the branch icon. Seen joined as "~/work/commonsmain", and the branch
  // icon as the row's icon, before the cells were read apart.
  @Test
  fun aProjectRowKeepsItsPathAndItsBranchApartAndItsOwnIcon() {
    val branchIcon = EmptyIconModel(16, 16)
    val panel = BeGridLayoutPanel()
    panel.items.set(
      listOf(
        cell(0, text("", DeferredIconModel(63, EmptyIconModel(20, 20)))),
        cell(2, text("commons")),
        cell(3, text("~/work/commons")),
        cell(4, text("main", DeferredIconModel(64, branchIcon))),
      ),
    )
    val row = BeRow.of(BeTreeGridLine(listOf(panel))) { "branch.svg".takeIf { _ -> true } }
    assertEquals("commons", row.text)
    assertEquals(listOf("~/work/commons", "main"), row.details.map { it.text })
    assertEquals("branch.svg", row.details[1].iconPath)
    assertEquals(63L, row.deferredIcon)
  }

  // The spacing of a project's row comes with the row: 16/6/16/6 around it, 8 after the avatar's
  // cell, 2 above and below each line (ProjectToolbarWidgetAction's renderer, seen on a 263
  // backend).
  @Test
  fun aProjectRowBringsItsSpacing() {
    fun margin(left: Int, top: Int, right: Int, bottom: Int) =
      BeMargin(
        BeMarginItem(BeMarginType.OnePx, left),
        BeMarginItem(BeMarginType.OnePx, top),
        BeMarginItem(BeMarginType.OnePx, right),
        BeMarginItem(BeMarginType.OnePx, bottom),
      )
    fun cell(gridIndex: Int, gaps: BeMargin, content: BeControl) =
      BeControlWithGridConstraints(
        BeGridConstraints(
          gridIndex,
          0,
          0,
          1,
          1,
          BeAlignment.Left,
          BeAlignment.Top,
          true,
          gaps,
          margin(0, 0, 0, 0),
          null,
        ),
        content,
      )
    val panel = BeGridLayoutPanel()
    panel.items.set(
      listOf(
        cell(0, margin(0, 2, 8, 2), text("", EmptyIconModel(20, 20))),
        cell(2, margin(0, 2, 0, 2), text("beta")),
        cell(3, margin(0, 2, 0, 2), text("/projects/beta")),
      ),
    )
    val styled = BeStyleControl(panel)
    styled.margin.set(BeMarginModifier(margin(16, 6, 16, 6)))
    val row = BeRow.of(BeTreeGridLine(listOf(styled))) { null }
    assertEquals(RowInsets(16, 6, 16, 6, iconGap = 8, lineGap = 2), row.insets)
  }
}
