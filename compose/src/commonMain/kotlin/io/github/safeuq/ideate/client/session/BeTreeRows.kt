package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.uiautomation.BeControl
import com.jetbrains.ide.model.uiautomation.BeControlContainer
import com.jetbrains.ide.model.uiautomation.BeGrid
import com.jetbrains.ide.model.uiautomation.BeIcon
import com.jetbrains.ide.model.uiautomation.BeRichText
import com.jetbrains.ide.model.uiautomation.BeTreeGrid
import com.jetbrains.ide.model.uiautomation.BeTreeGridLine
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.util.lifetime.Lifetime
import java.util.IdentityHashMap

/**
 * A tree the backend draws as a `BeTreeGrid`, as a [ProjectTree]: the Project view's Packages and
 * Scopes panes, and backend tool windows such as Gradle. Row ids are the client's own. The backend
 * keeps each row's expanded and selected state; expanding a row makes it send the children. Called
 * on the protocol thread.
 */
internal class BeTreeRows(
  private val iconPath: (IconModel) -> String?,
  private val publish: (ProjectTree) -> Unit,
) {
  private var grid: BeTreeGrid? = null
  private val lineIds = IdentityHashMap<BeTreeGridLine, Int>()
  private var lines = emptyMap<Int, BeTreeGridLine>()

  /** Follows [treeGrid]'s rows for [treeLifetime]. */
  fun show(treeLifetime: Lifetime, treeGrid: BeTreeGrid) {
    grid = treeGrid
    watch(treeLifetime, treeGrid.fakeRoot)
  }

  /** Forgets the tree; the next [show] starts over. */
  fun clear() {
    grid = null
    lineIds.clear()
    lines = emptyMap()
  }

  fun toggle(nodeId: Int) {
    val line = lines[nodeId] ?: return
    line.isExpanded.set(!line.isExpanded.value)
  }

  fun select(nodeId: Int) {
    val line = lines[nodeId] ?: return
    lines.values.filter { it !== line && it.isSelected.value }.forEach { it.isSelected.set(false) }
    line.isSelected.set(true)
  }

  /** Double tap or Enter: the backend's default action for the selected row, e.g. open a file. */
  fun open(nodeId: Int) {
    select(nodeId)
    grid?.enter?.fire(Unit)
  }

  private fun watch(lineLifetime: Lifetime, line: BeTreeGridLine) {
    line.children.view(lineLifetime) { childLifetime, _, child ->
      watch(childLifetime, child)
      childLifetime.onTermination { rebuild() }
    }
    line.isExpanded.advise(lineLifetime) { rebuild() }
    line.isSelected.advise(lineLifetime) { rebuild() }
    line.columnElements.forEach { watchCell(lineLifetime, it) }
    rebuild()
  }

  /** A row's cells can fill in after it arrives, e.g. its icon or its text. */
  private fun watchCell(lineLifetime: Lifetime, cell: BeControl) {
    when (cell) {
      is BeGrid ->
        cell.items.advise(lineLifetime) { items ->
          items.forEach { watchCell(lineLifetime, it.content) }
          rebuild()
        }
      is BeRichText -> {
        cell.text.advise(lineLifetime) { rebuild() }
        cell.icon.advise(lineLifetime) { rebuild() }
      }
      is BeIcon -> cell.icon.advise(lineLifetime) { rebuild() }
      is BeControlContainer -> watchCell(lineLifetime, cell.content)
      else -> Unit
    }
  }

  /** The visible part of the tree, published as a [ProjectTree]. */
  private fun rebuild() {
    val root = grid?.fakeRoot ?: return
    val nodes = mutableMapOf<Int, TreeNode>()
    val expanded = mutableSetOf<Int>()
    var selected: Int? = null
    val byId = mutableMapOf<Int, BeTreeGridLine>()
    fun walk(parent: BeTreeGridLine, parentId: Int?) {
      parent.children.forEachIndexed { index, line ->
        val row = BeRow.of(line, iconPath)
        if (row.fragments.isEmpty()) return@forEachIndexed // a placeholder until the children load
        val id = lineIds.getOrPut(line) { lineIds.size }
        byId[id] = line
        nodes[id] =
          TreeNode(
            id = id,
            parentId = parentId,
            fragments = row.fragments,
            iconPaths = listOfNotNull(row.icon),
            hasChildren = line.children.isNotEmpty(),
            isDirectory = line.children.isNotEmpty(),
            path = null,
            sortKey = index,
          )
        if (line.isSelected.value) selected = id
        if (line.isExpanded.value) {
          expanded += id
          walk(line, id)
        }
      }
    }
    walk(root, null)
    lines = byId
    publish(ProjectTree(nodes, expanded, selected))
  }
}
