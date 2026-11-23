package io.github.safeuq.ideate.client.ssh

import com.hierynomus.sshj.userauth.agent.AgentProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenSshPublicKeyTest {
  private class FakeFiles(
    private val files: Map<String, String>,
    private val failure: Exception? = null,
  ) : OpenSshFiles {
    val asked = mutableListOf<String>()
    override val home = "/home/u0_a1"

    override fun agent(): AgentProxy? = null

    override fun read(path: String): String? {
      asked += path
      failure?.let { throw it }
      return files[path]
    }
  }

  private val ed25519 = "ssh-ed25519 AAAAC3Nza termux-galaxy-s24-plus"
  private val rsa = "ssh-rsa AAAAB3Nza termux-old"

  @Test
  fun firstExistingKeyWinsAndLaterFilesAreNotRead() {
    val files = FakeFiles(mapOf("/home/u0_a1/.ssh/id_ed25519.pub" to "$ed25519\n"))
    assertEquals(OpenSshKeyState.Found(ed25519), OpenSshPublicKey.load(files))
    assertEquals(listOf("/home/u0_a1/.ssh/id_ed25519.pub"), files.asked)
  }

  @Test
  fun fallsBackToRsa() {
    val files = FakeFiles(mapOf("/home/u0_a1/.ssh/id_rsa.pub" to rsa))
    assertEquals(OpenSshKeyState.Found(rsa), OpenSshPublicKey.load(files))
    assertEquals(3, files.asked.size)
  }

  @Test
  fun noFileOrBlankFileIsMissing() {
    assertEquals(OpenSshKeyState.Missing, OpenSshPublicKey.load(FakeFiles(emptyMap())))
    val blank = FakeFiles(mapOf("/home/u0_a1/.ssh/id_ed25519.pub" to "  \n"))
    assertEquals(OpenSshKeyState.Missing, OpenSshPublicKey.load(blank))
  }

  @Test
  fun aFailedReadIsFailedWithItsMessage() {
    val files = FakeFiles(emptyMap(), IllegalStateException("Termux didn't answer"))
    assertEquals(OpenSshKeyState.Failed("Termux didn't answer"), OpenSshPublicKey.load(files))
  }

  @Test
  fun missingTextSaysWhyAndWhatToDo() {
    val text = OpenSshPublicKey.missingText("~/.ssh", "Make one with `ssh-keygen -t ed25519`")
    assertTrue(text.startsWith("There is no SSH key in ~/.ssh."))
    assertTrue("authorized_keys" in text && "id_ed25519.pub" in text)
  }
}
