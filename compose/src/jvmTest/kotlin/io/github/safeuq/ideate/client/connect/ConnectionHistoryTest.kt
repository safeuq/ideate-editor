package io.github.safeuq.ideate.client.connect

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionHistoryTest {
  private class MemoryText(var text: String?) : StoredText {
    override fun read() = text

    override fun write(text: String) {
      this.text = text
    }
  }

  /**
   * As the Android app wrote it with org.json before 2026-11-17: null values (here project,
   * openFile) left out.
   */
  private val writtenByOrgJson =
    """[{"kind":"LINK","name":"smoke · 127.0.0.1:5991","host":"127.0.0.1","port":5991,""" +
      """"fingerprint":"A7FD8A50","lastLink":"tcp://127.0.0.1:5991#jt=e29d662f&fp=A7FD8A50","identity":"MIIB…","lastConnected":1763290000000}]"""

  @Test
  fun readsHistoryWrittenByOrgJson() {
    val entry = ConnectionHistory(MemoryText(writtenByOrgJson)).entries().single()
    assertEquals(ConnectionKind.LINK, entry.kind)
    assertEquals(5991, entry.port)
    assertEquals(null, entry.project)
    assertEquals(null, entry.openFile)
    assertEquals(1763290000000, entry.lastConnected)
  }

  @Test
  fun savesNewestFirstOnePerKeyAndReadsBack() {
    val storage = MemoryText(writtenByOrgJson)
    val history = ConnectionHistory(storage)
    val old = history.entries().single()
    val termux =
      old.copy(
        kind = ConnectionKind.TERMUX,
        name = "This phone · smoke",
        project = "/data/data/com.termux/files/home/smoke",
        openFile = "/x/Main.java",
      )
    history.save(termux)
    history.save(old.copy(lastConnected = 1763290100000))
    assertEquals(
      listOf(ConnectionKind.LINK, ConnectionKind.TERMUX),
      history.entries().map { it.kind },
    )
    assertEquals(1763290100000, history.entries().first().lastConnected)
    assertEquals(termux, ConnectionHistory(MemoryText(storage.text)).entries()[1])
    history.remove(termux)
    assertEquals(1, history.entries().size)
  }
}
