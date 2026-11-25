package io.github.safeuq.ideate.client.ssh

import com.hierynomus.sshj.userauth.agent.AgentConnection
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One connection to Termux's ssh-agent through `ssh-agent-bridge`, which listens on
 * `127.0.0.1:<port>` (Android lets apps talk over localhost TCP, not over each other's Unix
 * sockets). The bridge wants the token as the first line and answers one byte: `0x00` (then the
 * stream is the ssh-agent protocol), `T` (token rejected) or `A` (can't reach the agent). See
 * docs/ssh-connections.md, "Termux's ssh-agent through the localhost bridge".
 */
class TermuxAgentConnection private constructor(private val socket: Socket) : AgentConnection {
  override fun getInputStream(): InputStream = socket.getInputStream()

  override fun getOutputStream(): OutputStream = socket.getOutputStream()

  override fun close() = socket.close()

  /** The bridge didn't accept the token. */
  class TokenRejected : IOException("Termux's agent bridge rejected Ideate's token")

  companion object {
    private const val CONNECT_TIMEOUT_MILLIS = 2_000
    private const val ANSWER_TIMEOUT_MILLIS = 5_000

    /**
     * The connection, or null when nothing listens on [port] or the bridge closes at once (no
     * bridge running). Throws [TokenRejected], or an IOException saying that the bridge can't reach
     * ssh-agent.
     */
    fun open(port: Int, token: String): TermuxAgentConnection? {
      val socket = Socket()
      try {
        socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS)
        socket.soTimeout = ANSWER_TIMEOUT_MILLIS
        socket.getOutputStream().apply {
          write("$token\n".toByteArray())
          flush()
        }
        val status = socket.getInputStream().read()
        when (status) {
          0 -> {
            socket.soTimeout = 0
            return TermuxAgentConnection(socket)
          }
          -1 -> {
            socket.close()
            return null
          }
          'T'.code -> throw TokenRejected()
          'A'.code -> {
            println(
              "Termux's agent bridge can't reach ssh-agent (run `intellij-backend ssh-agent start`)",
            )
            throw IOException("Termux's agent bridge can't reach ssh-agent")
          }
          else -> throw IOException("Termux's agent bridge answered an unknown byte: $status")
        }
      } catch (refused: ConnectException) {
        socket.close()
        return null
      } catch (failure: Throwable) {
        socket.close()
        throw failure
      }
    }
  }
}

/**
 * Finds the bridge through [readBridgeFile] (the text of Termux's `~/.ssh/agent-bridge`, or null
 * when there is none) and keeps its port and token for the app process, so a login costs no
 * RUN_COMMAND call. When the kept token is rejected or nothing listens (the bridge was restarted),
 * the file is read once more and the connection tried once more.
 */
class TermuxAgentBridge(private val readBridgeFile: () -> String?) {
  private class Entry(val port: Int, val token: String)

  private var cached: Entry? = null

  /** A connected agent, or null when there is no bridge. Blocking. */
  @Synchronized
  fun connect(): TermuxAgentConnection? {
    val startedAt = System.nanoTime()
    val kept = cached
    if (kept != null) {
      open(kept, rejectionIsFinal = false)?.let {
        return connected(it, startedAt)
      }
      cached = null
    }
    val entry = load() ?: return null
    return open(entry, rejectionIsFinal = true)?.let { connected(it, startedAt) }
  }

  private fun connected(connection: TermuxAgentConnection, startedAt: Long): TermuxAgentConnection {
    println("Termux agent bridge: connected in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
    return connection
  }

  private fun open(entry: Entry, rejectionIsFinal: Boolean): TermuxAgentConnection? =
    try {
      TermuxAgentConnection.open(entry.port, entry.token)?.also { cached = entry }
    } catch (rejected: TermuxAgentConnection.TokenRejected) {
      if (rejectionIsFinal) throw rejected
      null
    }

  private fun load(): Entry? {
    val line = readBridgeFile()?.trim() ?: return null
    val parts = line.split(' ')
    val port = parts.firstOrNull()?.toIntOrNull()
    check(parts.size == 2 && port != null && Regex("[0-9a-f]{64}").matches(parts[1])) {
      "~/.ssh/agent-bridge in Termux isn't `<port> <64 hex characters>`"
    }
    return Entry(port, parts[1])
  }
}
