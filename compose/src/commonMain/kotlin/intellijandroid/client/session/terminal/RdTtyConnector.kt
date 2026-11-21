package intellijandroid.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.LinkedBlockingQueue

/**
 * JediTerm's [TtyConnector] over one rd terminal session (the shape of JetBrains Client's
 * `ThinClientTtyConnector`). The backend's PTY output comes in through [onOutput] as byte chunks
 * (up to 1,024 bytes each, cut anywhere); [read] hands JediTerm the text, blocking until there is
 * some. What the user types goes out through [sendInput] as bytes, and a new grid size through
 * [sendSize].
 */
internal class RdTtyConnector(
  private val sendInput: (ByteArray) -> Unit,
  private val sendSize: (TermSize) -> Unit,
) : TtyConnector {
  private val chunks = LinkedBlockingQueue<String>()
  private var pending = ""
  private var pendingAt = 0
  @Volatile private var closed = false

  // The start of a character cut by a chunk border (one to three bytes), kept for the next chunk.
  private var carriedBytes = ByteArray(0)
  private val decoder =
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)

  /** Call for each chunk, in order, from one thread (the rd scheduler). */
  fun onOutput(bytes: ByteArray) {
    val input = ByteBuffer.wrap(carriedBytes + bytes)
    val output = CharBuffer.allocate(input.remaining())
    // endOfInput = false: a character cut at the end stays in `input` for the next chunk.
    decoder.decode(input, output, false)
    carriedBytes = ByteArray(input.remaining()).also(input::get)
    output.flip()
    if (output.hasRemaining()) chunks.put(output.toString())
  }

  override fun read(buffer: CharArray, offset: Int, length: Int): Int {
    if (pendingAt >= pending.length) {
      pending = chunks.take()
      pendingAt = 0
      // The closing marker (an empty chunk, which real output never is): the read loop ends on -1.
      if (pending.isEmpty()) return -1
    }
    val count = minOf(length, pending.length - pendingAt)
    pending.toCharArray(buffer, offset, pendingAt, pendingAt + count)
    pendingAt += count
    return count
  }

  override fun write(bytes: ByteArray) = sendInput(bytes)

  override fun write(string: String) = sendInput(string.toByteArray())

  override fun resize(termSize: TermSize) = sendSize(termSize)

  override fun isConnected() = !closed

  override fun ready() = pendingAt < pending.length || chunks.isNotEmpty()

  // The backend doesn't send the shell's exit code.
  override fun waitFor() = 0

  override fun getName() = "Remote terminal"

  override fun close() {
    closed = true
    chunks.put("")
  }
}
