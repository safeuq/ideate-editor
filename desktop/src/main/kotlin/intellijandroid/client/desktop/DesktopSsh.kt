package intellijandroid.client.desktop

import intellijandroid.client.ssh.OpenSshFiles
import intellijandroid.client.ssh.SecretStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * SSH secrets as files in [folder] (`~/.ideate/secrets`), readable by the user only, like `~/.ssh`
 * keys. ponytail: plain files with owner-only permissions; the OS keychain if the desktop build is
 * ever used on shared machines.
 */
class FileSecretStore(private val folder: File) : SecretStore {
  override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()

  override fun write(name: String, value: String) {
    if (folder.mkdirs()) ownerOnly(folder, "rwx------")
    val target = file(name)
    target.writeText(value)
    ownerOnly(target, "rw-------")
  }

  override fun delete(name: String) {
    file(name).delete()
  }

  /** Names like `ssh-key:586e63d9-…` become file names like `ssh-key_586e63d9-…`. */
  private fun file(name: String) = folder.resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

  private fun ownerOnly(file: File, permissions: String) {
    // Windows has no POSIX permissions; the user's profile folder protects the file there.
    runCatching {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }
  }
}

/** The user's own OpenSSH setup, for hosts saved with LoginMethod.OPEN_SSH. */
object DesktopOpenSshFiles : OpenSshFiles {
  override val home: String = System.getProperty("user.home")

  override val hasAgent: Boolean
    get() = System.getenv("SSH_AUTH_SOCK")?.let { File(it).exists() } == true

  override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

  /** `~/.ssh/known_hosts`, which KnownHosts also trusts on the desktop. */
  fun knownHosts(): String? = read("$home/.ssh/known_hosts")
}
