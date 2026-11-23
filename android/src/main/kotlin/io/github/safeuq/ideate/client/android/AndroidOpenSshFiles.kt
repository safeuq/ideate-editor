package io.github.safeuq.ideate.client.android

import android.os.Looper
import com.hierynomus.sshj.userauth.agent.AgentProxy
import io.github.safeuq.ideate.client.connect.Termux
import io.github.safeuq.ideate.client.ssh.OpenSshFiles
import io.github.safeuq.ideate.client.ssh.TermuxAgentBridge
import io.github.safeuq.ideate.client.ssh.TermuxAgentConnection
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Termux's `~/.ssh` for OPEN_SSH hosts. This app can't read Termux's private files, so every read
 * runs `cat` in Termux (docs/ssh-connections.md, "Termux's ~/.ssh on Android"). The files are read
 * again on each connect; only the agent bridge's port and token are kept.
 *
 * The agent is Termux's ssh-agent, reached through `ssh-agent-bridge` on localhost TCP, because its
 * Unix socket is out of this app's reach. When no bridge answers (the phone restarted, or Android
 * stopped Termux), Ideate starts the agent and the bridge with `intellij-backend ssh-agent start`,
 * which also loads the keys that have no passphrase.
 */
class AndroidOpenSshFiles(private val termux: AndroidTermux) : OpenSshFiles {
  override val home = "/data/data/com.termux/files/home"

  private val bridge = TermuxAgentBridge { read("$home/.ssh/agent-bridge") }

  /** Set once `ssh-agent start` failed, so later logins don't pay for it again. */
  @Volatile private var agentStartFailed = false

  override fun agent(): AgentProxy? {
    val connection =
      try {
        bridge.connect()
      } catch (rejected: TermuxAgentConnection.TokenRejected) {
        throw rejected
      } catch (agentDown: IOException) {
        // The bridge runs but ssh-agent doesn't; `ssh-agent start` starts it again.
        println("Termux agent bridge: ${agentDown.message}")
        null
      } ?: if (startAgent()) bridge.connect() else null
    return connection?.let { AgentProxy(it) }
  }

  /**
   * Runs `intellij-backend ssh-agent start` in Termux. False, and never tried again in this app
   * process, when it fails (e.g. ssh-agent-bridge isn't installed): the log-in then reads the key
   * files instead.
   */
  private fun startAgent(): Boolean {
    if (agentStartFailed) return false
    val startedAt = System.nanoTime()
    val result = runCatching {
      await("running intellij-backend ssh-agent start") {
        termux.run(listOf("ssh-agent", "start"), it)
      }
    }
    val milliseconds = (System.nanoTime() - startedAt) / 1_000_000
    val output = result.getOrNull()
    if (output != null && output.exitCode == 0) {
      println("Termux: intellij-backend ssh-agent start took $milliseconds ms")
      return true
    }
    agentStartFailed = true
    val reason =
      result.exceptionOrNull()?.message
        ?: output?.let {
          it.stderr.ifBlank { it.stdout }.trim().lines().lastOrNull() ?: "exit ${it.exitCode}"
        }
    println("Termux: intellij-backend ssh-agent start failed after $milliseconds ms: $reason")
    return false
  }

  /**
   * Blocks until Termux answers. Termux's answer arrives on the main thread, so waiting there would
   * never end.
   */
  override fun read(path: String): String? {
    val startedAt = System.nanoTime()
    val output = await("reading $path") { termux.cat(path, it) }
    println(
      "Termux read ${path.replace(home, "~")}: ${(System.nanoTime() - startedAt) / 1_000_000} ms",
    )
    // Only a non-zero exit from cat means the file isn't there; a Termux failure threw in await.
    return if (output.exitCode == 0) output.stdout else null
  }

  /**
   * Starts a Termux command with [start] and blocks until Termux answers, throwing when Termux
   * couldn't run it. Termux's answer arrives on the main thread, so waiting there would never end.
   */
  private fun await(
    what: String,
    start: (onResult: (Result<Termux.Result>) -> Unit) -> Unit,
  ): Termux.Result {
    check(Looper.myLooper() != Looper.getMainLooper()) {
      "Termux's answer arrives on the main thread, so $what can't wait there"
    }
    val answered = CountDownLatch(1)
    val answer = AtomicReference<Result<Termux.Result>>()
    start { result ->
      answer.set(result)
      answered.countDown()
    }
    check(answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "Termux didn't answer in $TIMEOUT_SECONDS s when $what"
    }
    return answer.get().getOrThrow()
  }

  /** `~/.ssh/known_hosts`, or null when there is none. */
  fun knownHosts(): String? = read("$home/.ssh/known_hosts")

  private companion object {
    const val TIMEOUT_SECONDS = 10L
  }
}
