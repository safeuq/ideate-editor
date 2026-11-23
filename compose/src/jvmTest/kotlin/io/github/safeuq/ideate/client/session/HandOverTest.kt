package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandOverTest {
  private fun editor(path: String, editable: Boolean, text: String = "") =
    EditorFile(path, text, emptyList(), emptyList(), editable = editable)

  private val previous =
    SessionState(
      projectName = "demo",
      breadcrumbs = listOf(Crumb(1, "demo", null, false, true)),
      editors = listOf(editor("/a", true, "old")),
      lookup = null,
    )

  @Test
  fun emptyNewSessionShowsPreviousPanelsAndReadOnlyEditors() {
    val result = handOver(previous, SessionState(), emptySet())
    assertEquals("demo", result.shown.projectName)
    assertEquals(previous.breadcrumbs, result.shown.breadcrumbs)
    assertEquals(listOf(editor("/a", false, "old")), result.shown.editors)
    assertFalse(result.done)
  }

  @Test
  fun fieldSwitchesOnFirstValueAndNeverFallsBack() {
    val first = handOver(previous, SessionState(projectName = "new"), emptySet())
    assertEquals("new", first.shown.projectName)
    val later = handOver(previous, SessionState(projectName = null), first.switched)
    assertNull(later.shown.projectName)
  }

  @Test
  fun editorStaysReadOnlyUntilNewOneIsEditable() {
    val loading =
      handOver(previous, SessionState(editors = listOf(editor("/a", false, "new"))), emptySet())
    assertEquals(editor("/a", false, "old"), loading.shown.editors.single())
    val ready =
      handOver(previous, SessionState(editors = listOf(editor("/a", true, "new"))), emptySet())
    assertEquals(editor("/a", true, "new"), ready.shown.editors.single())
  }

  @Test
  fun newEditorsAreAppendedAndTransientThingsComeFromCurrent() {
    val current =
      SessionState(
        editors = listOf(editor("/b", true)),
        failure = "boom",
        connectionLost = "x",
        lookup = null,
      )
    val result = handOver(previous, current, emptySet())
    assertEquals(listOf("/a", "/b"), result.shown.editors.map { it.path })
    assertEquals("boom", result.shown.failure)
    assertEquals("x", result.shown.connectionLost)
  }

  @Test
  fun doneOnlyWhenEveryFieldAndEditorHasSwitched() {
    val allSwitched = handOverFieldNames
    assertTrue(handOver(SessionState(), SessionState(), allSwitched).done)
    assertFalse(handOver(SessionState(), SessionState(), allSwitched - "tree").done)
    assertFalse(handOver(previous, SessionState(), allSwitched).done)
    val editorsReady = SessionState(editors = listOf(editor("/a", true)))
    assertTrue(handOver(previous, editorsReady, allSwitched).done)
  }

  private fun node(id: Int, parentId: Int?, path: String) =
    TreeNode(id, parentId, emptyList(), emptyList(), true, true, path, id)

  @Test
  fun treeSwitchesOnlyOnceItShowsEveryOldRow() {
    // Old session: /p open, showing /p/src. The new session's ids differ.
    val old = ProjectTree(mapOf(1 to node(1, null, "/p"), 2 to node(2, 1, "/p/src")), setOf(1))
    val withOld = previous.copy(tree = old)
    val rootOnly = ProjectTree(mapOf(7 to node(7, null, "/p")))
    assertEquals(old, handOver(withOld, SessionState(tree = rootOnly), emptySet()).shown.tree)
    val reopened = ProjectTree(rootOnly.nodes + (8 to node(8, 7, "/p/src")), setOf(7))
    assertEquals(reopened, handOver(withOld, SessionState(tree = reopened), emptySet()).shown.tree)
  }
}
