package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.connect.StoredText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenEditorsTest {
  private class MemoryText : StoredText {
    var text: String? = null

    override fun read() = text

    override fun write(text: String) {
      this.text = text
    }
  }

  @Test
  fun keepsEachProjectApart() {
    val store = OpenEditors(MemoryText())
    val smoke =
      SavedEditors(
        listOf("/p/smoke/A.java", "/p/smoke/B.java"),
        "/p/smoke/B.java",
        mapOf("/p/smoke/A.java" to 0, "/p/smoke/B.java" to 3120),
      )
    store.save("ssh:me@mac:/p/smoke", smoke)
    store.save("link:127.0.0.1:5991", SavedEditors(emptyList(), null))
    assertEquals(smoke, store.read("ssh:me@mac:/p/smoke"))
    assertEquals(SavedEditors(emptyList(), null), store.read("link:127.0.0.1:5991"))
    assertNull(store.read("termux:/other"))
  }

  @Test
  fun keepsPanesAndEditorsTogether() {
    val store = OpenEditors(MemoryText())
    val project = "link:127.0.0.1:5991"
    assertEquals(emptyMap(), store.readPanes(project))
    store.savePanes(project, mapOf("left" to 0.28f, "leftSplit" to 0.6f))
    val editors = SavedEditors(listOf("/p/A.java"), "/p/A.java")
    store.save(project, editors)
    assertEquals(mapOf("left" to 0.28f, "leftSplit" to 0.6f), store.readPanes(project))
    store.savePanes(project, mapOf("left" to 0.4f))
    assertEquals(editors, store.read(project))
    // Nothing selected any more: "selected" goes, the panes stay.
    store.save(project, SavedEditors(emptyList(), null))
    assertEquals(SavedEditors(emptyList(), null), store.read(project))
    assertEquals(mapOf("left" to 0.4f), store.readPanes(project))
  }

  @Test
  fun keepsEditorsByProjectIdAndPanesByConnection() {
    val store = OpenEditors(MemoryText())
    // One backend on one port opens different projects (no project folder: IdeaHome).
    val port5993 = store.slot("link:127.0.0.1:5993")
    val smokeId = "23888a6f-a97e-4246-b9a9-20a835ed6667:3MDuM4gn3BvOPth1xHooNd9f6Q3"
    val smoke = SavedEditors(listOf("/p/smoke/Main.java"), "/p/smoke/Main.java")
    port5993.save(smokeId, smoke)
    port5993.savePanes(mapOf("left" to 0.3f))
    assertNull(port5993.read("23888a6f-a97e-4246-b9a9-20a835ed6667:3LibQCLOYF1HXBYEh7knQaw3xks"))
    // The same project through another address finds its tabs; panes stay with the connection.
    val tunnel = store.slot("ssh:me@mac:/p/smoke")
    assertEquals(smoke, tunnel.read(smokeId))
    assertEquals(emptyMap(), tunnel.readPanes())
    assertEquals(mapOf("left" to 0.3f), port5993.readPanes())
  }
}
