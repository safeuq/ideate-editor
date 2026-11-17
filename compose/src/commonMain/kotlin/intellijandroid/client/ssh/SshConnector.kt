package intellijandroid.client.ssh

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** How IDEate logs in to a host (docs/ssh-connections.md, "Decisions"). */
sealed interface SshLogin {
  data class Password(val password: String) : SshLogin

  /**
   * An OpenSSH private key ("-----BEGIN OPENSSH PRIVATE KEY-----"): IDEate's own or an imported
   * one.
   */
  data class PrivateKey(val privateKey: String, val passphrase: String? = null) : SshLogin
}

/**
 * Asked about every host key: [fingerprint] is OpenSSH's SHA-256 form (`ssh-keygen -l`), for
 * example `SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU`. Returns whether to trust it.
 */
fun interface HostKeyCheck {
  fun accept(host: String, port: Int, keyType: String, fingerprint: String): Boolean
}

/** A command's exit code and output. */
data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * One SSH connection to a host, over SSHJ (the library JetBrains Gateway uses). Blocking calls; use
 * it off the UI thread.
 */
class SshSession
private constructor(private val client: SSHClient, val host: String, val port: Int) : Closeable {
  /**
   * Runs [command] the way Gateway runs its worker: through the user's login shell, so the PATH and
   * variables of the user's profile apply (Termux sets IDEA_JDK there), with a marker line so that
   * motd and rc-file output before it is dropped.
   */
  fun execInLoginShell(command: String, timeoutSeconds: Long = 30): ExecResult {
    val result =
      exec("\"\$SHELL\" -lc ${shellQuote("echo $OUTPUT_MARKER && $command")}", timeoutSeconds)
    val stdout =
      result.stdout.substringAfter("$OUTPUT_MARKER\n", missingDelimiterValue = result.stdout)
    return result.copy(stdout = stdout)
  }

  /** Runs [command] as the SSH server's exec request (the user's shell with `-c`). */
  fun exec(command: String, timeoutSeconds: Long = 30): ExecResult =
    client.startSession().use { session ->
      val running = session.exec(command)
      var stderr = ""
      val stderrReader =
        thread(name = "ssh-stderr", isDaemon = true) {
          stderr = IOUtils.readFully(running.errorStream).toString(Charsets.UTF_8)
        }
      val stdout = IOUtils.readFully(running.inputStream).toString(Charsets.UTF_8)
      running.join(timeoutSeconds, TimeUnit.SECONDS)
      stderrReader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
      ExecResult(
        checkNotNull(running.exitStatus) { "No exit status from '$command' on $host:$port" },
        stdout,
        stderr,
      )
    }

  /**
   * Forwards a local port to [remotePort] on the host's loopback, like Gateway's forwarder: the
   * listener takes [remotePort] locally when it's free (5990 to 5990), else a free one (57085).
   * Each accepted connection gets its own SSH channel. Close the result to stop forwarding.
   */
  fun forward(remotePort: Int, remoteHost: String = "127.0.0.1"): LocalForward {
    // No address reuse: on macOS it would let the bind succeed on a port another program listens
    // on.
    val listener = ServerSocket().apply { reuseAddress = false }
    runCatching { listener.bind(InetSocketAddress("127.0.0.1", remotePort)) }
      .onFailure { listener.bind(InetSocketAddress("127.0.0.1", 0)) }
    val forwarder =
      client.newLocalPortForwarder(
        Parameters("127.0.0.1", listener.localPort, remoteHost, remotePort),
        listener,
      )
    thread(name = "ssh-forward-${listener.localPort}", isDaemon = true) {
      runCatching { forwarder.listen() }
    }
    return LocalForward(listener.localPort) {
      forwarder.close()
      listener.close()
    }
  }

  val isConnected: Boolean
    get() = client.isConnected && client.isAuthenticated

  override fun close() = client.close()

  class LocalForward(val localPort: Int, private val stop: () -> Unit) : Closeable {
    override fun close() = stop()
  }

  companion object {
    /**
     * Gateway's marker (`REMOTE_EXEC_OUTPUT_MARKER_`), so output parsing matches its worker calls.
     */
    const val OUTPUT_MARKER = "REMOTE_EXEC_OUTPUT_MARKER_"

    /** Gateway's connection check timeout (`gateway.ssh.connection.check.timeout`). */
    const val CONNECT_TIMEOUT_MILLIS = 5_000

    /** Connects and logs in; fails with the server's or SSHJ's message. */
    fun open(
      host: String,
      port: Int,
      user: String,
      login: SshLogin,
      hostKeys: HostKeyCheck,
    ): SshSession {
      require(host.isNotBlank()) { "No host" }
      require(user.isNotBlank()) { "No user name for $host" }
      useFullBouncyCastle()
      val client = SSHClient(DefaultConfig())
      client.connectTimeout = CONNECT_TIMEOUT_MILLIS
      client.addHostKeyVerifier(
        object : HostKeyVerifier {
          override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
            hostKeys.accept(hostname, port, keyType(key), fingerprint(key))

          override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            emptyList()
        },
      )
      try {
        client.connect(host, port)
        when (login) {
          is SshLogin.Password -> client.authPassword(user, login.password)
          is SshLogin.PrivateKey ->
            client.authPublickey(
              user,
              client.loadKeys(
                login.privateKey,
                null,
                login.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) },
              ),
            )
        }
      } catch (failure: Exception) {
        client.close()
        throw failure
      }
      return SshSession(client, host, port)
    }

    /** OpenSSH's `SHA256:<base64 without padding>` of the key blob. */
    fun fingerprint(key: PublicKey): String {
      val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
      val digest = MessageDigest.getInstance("SHA-256").digest(blob)
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun keyType(key: PublicKey): String =
      net.schmizz.sshj.common.KeyType.fromKey(key).toString()

    /**
     * SSHJ asks the JCA for provider "BC". Android ships a cut-down provider under that name
     * (without X25519 or Ed25519), so SSHJ's key exchange fails there; the full BouncyCastle
     * replaces it. It's added last: SSHJ asks for it by name, and lookups by algorithm (the TLS
     * connection's) keep finding the JDK's or Android's providers first.
     */
    @Synchronized
    private fun useFullBouncyCastle() {
      if (Security.getProvider("BC") is BouncyCastleProvider) return
      Security.removeProvider("BC")
      Security.addProvider(BouncyCastleProvider())
    }

    /** POSIX single quotes: `it's` becomes `'it'\''s'`. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
  }
}
