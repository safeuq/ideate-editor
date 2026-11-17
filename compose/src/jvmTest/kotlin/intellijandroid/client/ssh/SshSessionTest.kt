package intellijandroid.client.ssh

import java.io.File
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Against a real SSH server, only when IDEATE_SSH_TEST_HOST is set (docs/ssh-connections.md, "Test
 * hosts"). The Linux test host:
 * ```
 * docker run -d --name ideate-sshd -p 2222:2222 -e USER_NAME=dev -e PASSWORD_ACCESS=true \
 *   -e USER_PASSWORD=<password> -e PUBLIC_KEY="$(cat id_ed25519.pub)" lscr.io/linuxserver/openssh-server
 * IDEATE_SSH_TEST_HOST=127.0.0.1 IDEATE_SSH_TEST_PORT=2222 IDEATE_SSH_TEST_USER=dev \
 *   IDEATE_SSH_TEST_PASSWORD=<password> IDEATE_SSH_TEST_KEY=<path to id_ed25519> ./gradlew :compose:jvmTest
 * ```
 */
class SshSessionTest {
  private val host = System.getenv("IDEATE_SSH_TEST_HOST")
  private val port = System.getenv("IDEATE_SSH_TEST_PORT")?.toInt() ?: 22
  private val user = System.getenv("IDEATE_SSH_TEST_USER").orEmpty()
  private val seenKeys = mutableListOf<String>()
  private val trustAll = HostKeyCheck { _, _, keyType, fingerprint ->
    seenKeys += "$keyType $fingerprint"
    true
  }

  private fun open(login: SshLogin): SshSession {
    assumeTrue(host != null, "IDEATE_SSH_TEST_HOST not set")
    return SshSession.open(host, port, user, login, trustAll)
  }

  @Test
  fun passwordLoginRunsCommandsInTheLoginShell() {
    val password = System.getenv("IDEATE_SSH_TEST_PASSWORD")
    assumeTrue(password != null, "IDEATE_SSH_TEST_PASSWORD not set")
    open(SshLogin.Password(password)).use { session ->
      val os = HostOs.parse(session.execInLoginShell("uname -sm").stdout)
      assertEquals(HostOs.Os.LINUX, os.os)
      // The marker drops anything the profile prints first.
      val echoed = session.execInLoginShell("echo one; echo 'it'\\''s'")
      assertEquals("one\nit's\n", echoed.stdout)
      assertEquals(3, session.exec("exit 3").exitCode)
    }
    // OpenSSH's form: SHA256 and 43 base64 characters without padding.
    assertTrue(
      seenKeys.single().matches(Regex("ssh-ed25519 SHA256:[A-Za-z0-9+/]{43}")),
      seenKeys.single(),
    )
  }

  @Test
  fun keyFileLoginAndAGeneratedKeyWork() {
    val keyPath = System.getenv("IDEATE_SSH_TEST_KEY")
    assumeTrue(keyPath != null, "IDEATE_SSH_TEST_KEY not set")
    val generated = SshKeys.generateEd25519("IDEate test")
    open(SshLogin.PrivateKey(File(keyPath).readText())).use { session ->
      // Authorise the generated key on the test host, as the user would by hand.
      val add =
        session.exec(
          "mkdir -p ~/.ssh && echo ${SshSession.shellQuote(generated.publicKey)} >> ~/.ssh/authorized_keys",
        )
      assertEquals(0, add.exitCode, add.stderr)
    }
    open(SshLogin.PrivateKey(generated.privateKey)).use { session ->
      assertEquals("ok\n", session.exec("echo ok").stdout)
    }
  }

  @Test
  fun forwardReachesAPortOnTheHostsLoopback() {
    val keyPath = System.getenv("IDEATE_SSH_TEST_KEY")
    assumeTrue(keyPath != null, "IDEATE_SSH_TEST_KEY not set")
    open(SshLogin.PrivateKey(File(keyPath).readText())).use { session ->
      // The host's own SSH server is on its loopback at the container's port: its banner proves the
      // path.
      session.forward(port).use { forward ->
        Socket("127.0.0.1", forward.localPort).use { socket ->
          val banner = socket.getInputStream().bufferedReader().readLine()
          assertTrue(banner.startsWith("SSH-2.0-"), banner)
        }
      }
    }
  }
}
