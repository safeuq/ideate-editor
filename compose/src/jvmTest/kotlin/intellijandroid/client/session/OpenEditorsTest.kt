package intellijandroid.client.session

import intellijandroid.client.connect.StoredText
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
}
