package io.github.safeuq.ideate.client.connection

import com.jetbrains.rd.framework.AbstractBuffer
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.UnsafeBuffer
import com.jetbrains.rd.framework.base.WireBase
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * rd wire over an accepted [Connection]: each rd message travels in one message frame `seq ||
 * lastReceivedSeq || RdId || message context || body` (docs/split-protocol.md, "Where rd starts").
 *
 * ponytail: no resend buffer and no reconnect. A dropped connection ends the session; add the
 * backend's resend-after-reconnect (WIRE_EXIST = 1) when a flaky network makes that matter.
 */
class SplitWire(
  private val connection: Connection,
  /**
   * False while the app is in the background: nothing is checked then. The phone may freeze the app
   * for minutes, which looks like a silent backend, and nobody is looking at the screen anyway.
   */
  private val inForeground: () -> Boolean = { true },
  private val onClosed: (Throwable?) -> Unit,
) : WireBase() {
  private val sendLock = Any()
  private var sentSequence = 0

  @Volatile private var lastReceivedSequence = 0

  @Volatile private var closed = false

  /** When something last came in, or the app was last seen in the background. */
  @Volatile private var lastActivityMillis = System.currentTimeMillis()

  /**
   * Starts the reader and ping threads. Call once the rd protocol exists (it starts message
   * delivery itself).
   */
  fun start() {
    connection.socket.soTimeout = IDLE_TIMEOUT_MILLIS
    connected.set(true)
    thread(name = "split-wire-reader", isDaemon = true) { readLoop() }
    thread(name = "split-wire-pings", isDaemon = true) {
      while (!closed) {
        runCatching { connection.frames.writePing() }.onFailure { failure -> close(failure) }
        // The idle time starts again when the app is back.
        if (!inForeground()) lastActivityMillis = System.currentTimeMillis()
        Thread.sleep(PING_INTERVAL_MILLIS)
      }
    }
  }

  override fun send(id: RdId, writer: (AbstractBuffer) -> Unit) {
    require(!id.isNull) { "rd id must not be null" }
    if (closed) return
    // Array-backed, so getArray() can hand the bytes to the frame writer (a native-memory buffer
    // can't).
    UnsafeBuffer(ByteArray(256)).use { buffer ->
      id.write(buffer)
      contexts.writeCurrentMessageContext(buffer)
      writer(buffer)
      // ponytail: writes on the caller's thread; move to a send queue if a slow network stalls the
      // rd scheduler.
      synchronized(sendLock) {
        sentSequence++
        runCatching {
          connection.frames.writeMessage(
            sentSequence,
            lastReceivedSequence,
            buffer.getArray(),
            buffer.position,
          )
        }
          .onFailure { failure -> close(failure) }
      }
    }
  }

  private fun readLoop() {
    try {
      while (!closed) {
        val frame =
          try {
            connection.frames.read()
          } catch (timeout: SocketTimeoutException) {
            // The 15 s ran out while the app was away, or since it came back: keep waiting.
            if (System.currentTimeMillis() - lastActivityMillis < IDLE_TIMEOUT_MILLIS) continue
            throw timeout
          }
        lastActivityMillis = System.currentTimeMillis()
        when (frame) {
          is Frame.Ping -> Unit
          is Frame.System ->
            when (frame.status) {
              WireStatus.FORCE_TERMINATE_WIRE -> error("Backend terminated the wire")
              else -> Unit // e.g. a late JOIN_PIN; nothing to do once connected
            }
          is Frame.Message -> {
            if (frame.sequence <= lastReceivedSequence) continue // duplicate after a resend
            lastReceivedSequence = frame.sequence
            val buffer = UnsafeBuffer(frame.payload)
            val id = RdId.read(buffer)
            messageBroker.dispatch(id, buffer, null)
          }
        }
      }
    } catch (timeout: SocketTimeoutException) {
      close(IllegalStateException("Nothing received for ${IDLE_TIMEOUT_MILLIS / 1000} s", timeout))
    } catch (failure: Throwable) {
      close(failure)
    }
  }

  /**
   * Ends the session on purpose: tells the backend to drop this wire (`FORCE_TERMINATE_WIRE`,
   * reason `ConnectionDeclineReason.None` = 0), then closes. Without it the backend keeps the wire
   * for a resume and declines this client's next connection once.
   */
  fun terminate() {
    if (closed) return
    runCatching {
      connection.frames.writeSystem(
        WireStatus.FORCE_TERMINATE_WIRE,
        java.nio.ByteBuffer.allocate(4).putInt(0).array(),
      )
    }
    close()
  }

  fun close(failure: Throwable? = null) {
    if (closed) return
    closed = true
    // First, so the session says "connection lost" before `connected = false` makes rd end its
    // models, which empties the session's state; the screen keeps what it showed until then.
    onClosed(failure)
    connected.set(false)
    connection.close()
  }

  private companion object {
    const val PING_INTERVAL_MILLIS = 1_000L
    const val IDLE_TIMEOUT_MILLIS = 15_000
  }
}
