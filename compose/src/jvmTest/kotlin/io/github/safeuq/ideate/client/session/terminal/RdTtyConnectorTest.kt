package io.github.safeuq.ideate.client.session.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** The backend cuts its output into chunks anywhere, also inside a character (spec 3.4). */
class RdTtyConnectorTest {
  private val connector = RdTtyConnector(sendInput = {}, sendSize = {})

  private fun readAvailable(): String {
    val text = StringBuilder()
    val buffer = CharArray(64)
    while (connector.ready()) text.append(buffer, 0, connector.read(buffer, 0, buffer.size))
    return text.toString()
  }

  @Test
  fun decodesACharacterSplitAcrossTwoChunks() {
    // "€" is E2 82 AC; "😀" is F0 9F 98 80. Each is cut after its first byte, then 1 and 3 bytes.
    val bytes = "a€😀b".toByteArray()
    connector.onOutput(bytes.copyOfRange(0, 2)) // 'a', first byte of '€'
    assertEquals("a", readAvailable())
    connector.onOutput(bytes.copyOfRange(2, 5)) // rest of '€', first byte of '😀'
    assertEquals("€", readAvailable())
    connector.onOutput(bytes.copyOfRange(5, bytes.size)) // rest of '😀', 'b'
    assertEquals("😀b", readAvailable())
  }

  @Test
  fun readEndsWithMinusOneAfterClose() {
    connector.close()
    assertEquals(-1, connector.read(CharArray(8), 0, 8))
  }
}
