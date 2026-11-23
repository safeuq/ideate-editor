package io.github.safeuq.ideate.client.ssh

import com.hierynomus.sshj.userauth.agent.AgentProxy
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxAgentBridgeTest {
  private val token = "3f9a".padEnd(64, 'c')

  /**
   * A bridge on an ephemeral port. For each connection it reads the token line, answers with the
   * next byte of [answers] (the last one repeats), and for `0x00` answers one
   * SSH_AGENTC_REQUEST_IDENTITIES with an empty SSH_AGENT_IDENTITIES_ANSWER.
   */
  private class FakeBridge(private val expectedToken: String, private vararg val answers: Int) :
    AutoCloseable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val connections = mutableListOf<String>()

    init {
      thread(isDaemon = true) {
        var count = 0
        while (!server.isClosed) {
          val socket = runCatching { server.accept() }.getOrNull() ?: break
          val answer = answers[minOf(count++, answers.size - 1)]
          val input = socket.getInputStream()
          val line = StringBuilder()
          while (true) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            line.append(next.toChar())
          }
          connections += line.toString()
          val output = socket.getOutputStream()
          val accepted = answer == 0 && line.toString() == expectedToken
          output.write(if (answer == 0 && !accepted) 'T'.code else answer)
          output.flush()
          // Tests that only connect close at once, so this read may end early.
          if (accepted)
            runCatching {
              val data = DataInputStream(input)
              val length = data.readInt()
              check(data.readByte().toInt() == 11) { "expected SSH_AGENTC_REQUEST_IDENTITIES" }
              check(length == 1)
              output.write(byteArrayOf(0, 0, 0, 5, 12, 0, 0, 0, 0))
              output.flush()
              data.read()
            }
          socket.close()
        }
      }
    }

    override fun close() = server.close()
  }

  @Test
  fun connectsSendsTheTokenAndSpeaksTheAgentProtocol() {
    FakeBridge(token, 0).use { bridge ->
      var reads = 0
      val connection = TermuxAgentBridge {
        reads++
        "${bridge.port} $token\n"
      }
        .connect()
      assertTrue(connection != null)
      AgentProxy(connection).use { assertEquals(emptyList(), it.identities.toList()) }
      assertEquals(listOf(token), bridge.connections)
      assertEquals(1, reads)
    }
  }

  @Test
  fun aWrongTokenFailsWithAClearMessage() {
    FakeBridge(token, 0).use { bridge ->
      val failure =
        assertFailsWith<IOException> {
          TermuxAgentBridge { "${bridge.port} ${"0".repeat(64)}" }.connect()
        }
      assertEquals("Termux's agent bridge rejected Ideate's token", failure.message)
    }
  }

  @Test
  fun aBridgeThatCantReachTheAgentSaysHowToStartIt() {
    FakeBridge(token, 'A'.code).use { bridge ->
      val failure =
        assertFailsWith<IOException> { TermuxAgentBridge { "${bridge.port} $token" }.connect() }
      assertEquals(
        "Termux's agent bridge can't reach ssh-agent (run `intellij-backend ssh-agent start`)",
        failure.message,
      )
    }
  }

  @Test
  fun noFileOrNothingListeningMeansNoAgent() {
    assertNull(TermuxAgentBridge { null }.connect())
    val freePort = ServerSocket(0).use { it.localPort }
    assertNull(TermuxAgentBridge { "$freePort $token" }.connect())
  }

  @Test
  fun aRejectedKeptTokenIsReadAgainOnceAndTried() {
    FakeBridge(token, 0).use { bridge ->
      val files = ArrayDeque(listOf("${bridge.port} ${"1".repeat(64)}", "${bridge.port} $token"))
      var reads = 0
      val agentBridge = TermuxAgentBridge {
        reads++
        files.removeFirst()
      }
      // First connect: the file holds a token the bridge doesn't know.
      assertFailsWith<IOException> { agentBridge.connect() }
      // The bridge was restarted with a new token: the next connect reads the file again.
      agentBridge.connect()!!.close()
      assertEquals(2, reads)
      // Now the token is kept: no more reads.
      agentBridge.connect()!!.close()
      assertEquals(2, reads)
    }
  }

  @Test
  fun aTokenThatTurnsStaleAfterBeingKeptIsReplacedByOneReadAndOneRetry() {
    val first = FakeBridge(token, 0)
    val newToken = "9".repeat(64)
    FakeBridge(newToken, 0).use { second ->
      val files = ArrayDeque(listOf("${first.port} $token", "${second.port} $newToken"))
      var reads = 0
      val agentBridge = TermuxAgentBridge {
        reads++
        files.removeFirst()
      }
      agentBridge.connect()!!.close()
      first.close()
      // The kept port is refused now: one read, one retry.
      agentBridge.connect()!!.close()
      assertEquals(2, reads)
      assertEquals(listOf(newToken), second.connections)
    }
  }

  @Test
  fun aMalformedFileIsReported() {
    assertFailsWith<IllegalStateException> { TermuxAgentBridge { "5980" }.connect() }
  }
}
