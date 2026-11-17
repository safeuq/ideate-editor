package intellijandroid.client.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [RemoteHosts] against a real host, read only (it never launches or removes anything), and only
 * when IDEATE_SSH_TEST_HOST is set. Log-in with IDEATE_SSH_TEST_KEY (a key file without passphrase)
 * or IDEATE_SSH_TEST_PASSWORD. The phone (Termux, with Gateway's worker):
 * ```
 * IDEATE_SSH_TEST_HOST=192.168.0.107 IDEATE_SSH_TEST_PORT=8022 IDEATE_SSH_TEST_USER=u0_a404 \
 *   IDEATE_SSH_TEST_KEY=$HOME/.ssh/id_ed25519 ./gradlew :compose:jvmTest --tests '*RemoteHostSshTest*'
 * ```
 */
class RemoteHostSshTest {
  private val host = System.getenv("IDEATE_SSH_TEST_HOST")

  private fun open(): SshSession {
    assumeTrue(host != null, "IDEATE_SSH_TEST_HOST not set")
    val port = System.getenv("IDEATE_SSH_TEST_PORT")?.toInt() ?: 22
    val user =
      requireNotNull(System.getenv("IDEATE_SSH_TEST_USER")) { "IDEATE_SSH_TEST_USER not set" }
    val login =
      System.getenv("IDEATE_SSH_TEST_KEY")?.let { SshLogin.PrivateKey(File(it).readText()) }
        ?: SshLogin.Password(
          requireNotNull(System.getenv("IDEATE_SSH_TEST_PASSWORD")) {
            "Set IDEATE_SSH_TEST_KEY or IDEATE_SSH_TEST_PASSWORD"
          },
        )
    return SshSession.open(host, port, user, login) { _, _, _ -> true }
  }

  @Test
  fun answersAndAgreesWithTheShell() {
    open().use { session ->
      val remote = RemoteHosts.forSession(session)
      val shell = ShellHost(session::execInLoginShell)
      val ides = remote.installedIdes()
      val recent = remote.recentProjects()
      println(
        "Host ${remote.os()} via ${remote::class.simpleName}, RemoteDev ${remote.remoteDevDir()}",
      )
      ides.forEach { println("IDE $it") }
      recent.forEach { println("Recent $it") }
      assertTrue(remote.os().os != HostOs.Os.OTHER)
      // The worker and the shell read the same files, so they must agree.
      assertEquals(ides, shell.installedIdes())
      assertEquals(recent, shell.recentProjects())
      val home = session.execInLoginShell("printf '%s' \"\$HOME\"").stdout
      val folders = remote.listFolders(home)
      println("Folders in $home: ${folders.map { it.name }}")
      assertTrue(folders.isNotEmpty())
      // Status of a project a backend served: only reads the status (no launch).
      recent.firstOrNull()?.let { project ->
        val status = remote.status(project.idePath, project.projectPath)
        println("Status of ${project.projectPath}: ready=${status?.isReady} pid=${status?.appPid}")
      }
    }
  }
}
