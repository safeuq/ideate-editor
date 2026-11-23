package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.StoredText
import java.io.StringReader
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

/**
 * Host keys Ideate trusts, in OpenSSH's `known_hosts` format, e.g. `[192.168.0.107]:8022
 * ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI…`. Gateway's rule, with its default of asking: a known key
 * passes, an unknown one is shown to the user, a changed one is refused.
 *
 * @param also more `known_hosts` text that counts as trusted, read only. The desktop passes
 *   `~/.ssh/known_hosts`, so hosts the user's own ssh knows aren't asked about again.
 */
class KnownHosts(private val storage: StoredText, private val also: () -> String? = { null }) {
  enum class Verdict {
    KNOWN,
    UNKNOWN,
    /** The host sent a different key than the one trusted for it: possibly a man in the middle. */
    CHANGED,
  }

  fun check(host: String, port: Int, key: PublicKey): Verdict {
    val text = listOfNotNull(storage.read(), also()).joinToString("\n")
    var verdict = Verdict.KNOWN
    val parser =
      object : OpenSSHKnownHosts(StringReader(text)) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
          verdict = Verdict.UNKNOWN
          return false
        }

        override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
          verdict = Verdict.CHANGED
          return false
        }
      }
    return if (parser.verify(host, port, key)) Verdict.KNOWN else verdict
  }

  /** Adds [key] for [host]:[port] to Ideate's own list (never to `~/.ssh/known_hosts`). */
  fun trust(host: String, port: Int, key: PublicKey) {
    val line = "${pattern(host, port)} ${SshSession.keyType(key)} ${encode(key)}"
    val existing = storage.read().orEmpty().trimEnd()
    storage.write(if (existing.isEmpty()) "$line\n" else "$existing\n$line\n")
  }

  /** Drops Ideate's entries for [host]:[port], e.g. after the user accepted a changed key. */
  fun forget(host: String, port: Int) {
    val pattern = pattern(host, port)
    val kept =
      storage.read().orEmpty().lines().filter {
        it.isNotBlank() && it.substringBefore(' ') != pattern
      }
    storage.write(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
  }

  companion object {
    /** OpenSSH's host field: `host` on port 22, `[host]:port` otherwise. */
    fun pattern(host: String, port: Int) = if (port == 22) host else "[$host]:$port"

    private fun encode(key: PublicKey): String =
      Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)
  }
}
