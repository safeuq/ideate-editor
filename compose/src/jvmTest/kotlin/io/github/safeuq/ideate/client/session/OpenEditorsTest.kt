package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.connect.StoredText
import java.nio.file.Files
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

  private fun workspace() =
    JetBrainsWorkspace(emptyList(), Files.createTempDirectory("ideate").toFile())

  @Test
  fun readsWhatAnOlderVersionSaved() {
    val storage = MemoryText()
    storage.text =
      """{"project:p1": {"files": ["/p/A.java", "/p/B.java"], "selected": "/p/B.java",
        "carets": {"/p/B.java": 3120}}}"""
    val store = OpenEditors(storage, workspace())
    assertEquals(
      SavedEditors(listOf("/p/A.java", "/p/B.java"), "/p/B.java", mapOf("/p/B.java" to 3120)),
      store.read("project:p1"),
    )
    assertNull(store.read("project:other"))
  }

  @Test
  fun keepsPanesByConnection() {
    val store = OpenEditors(MemoryText(), workspace())
    val project = "link:127.0.0.1:5991"
    assertEquals(emptyMap(), store.readPanes(project))
    store.savePanes(project, mapOf("left" to 0.28f, "leftSplit" to 0.6f))
    assertEquals(mapOf("left" to 0.28f, "leftSplit" to 0.6f), store.readPanes(project))
    store.savePanes(project, mapOf("left" to 0.4f))
    assertEquals(mapOf("left" to 0.4f), store.readPanes(project))
  }

  @Test
  fun keepsTabsByProjectIdAndPanesByConnection() {
    val store = OpenEditors(MemoryText(), workspace())
    // One backend on one port opens different projects (no project folder: IdeaHome).
    val port5993 = store.slot("link:127.0.0.1:5993")
    val smokeId = "23888a6f-a97e-4246-b9a9-20a835ed6667:3MDuM4gn3BvOPth1xHooNd9f6Q3"
    val smoke = listOf(WorkspaceTab("/p/smoke/Main.java", current = true))
    port5993.saveTabs(smokeId, smoke)
    port5993.savePanes(mapOf("left" to 0.3f))
    assertNull(
      port5993.readTabs("23888a6f-a97e-4246-b9a9-20a835ed6667:3LibQCLOYF1HXBYEh7knQaw3xks"),
    )
    // The same project through another address finds its tabs; panes stay with the connection.
    val tunnel = store.slot("ssh:me@mac:/p/smoke")
    assertEquals(smoke, tunnel.readTabs(smokeId))
    assertEquals(emptyMap(), tunnel.readPanes())
    assertEquals(mapOf("left" to 0.3f), port5993.readPanes())
  }
}
