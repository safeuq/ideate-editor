package io.github.safeuq.ideate.client.ssh

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil

/**
 * A key pair Ideate made: what `ssh-keygen -t ed25519` writes to `id_ed25519` and `id_ed25519.pub`.
 */
data class GeneratedKey(
  /** `-----BEGIN OPENSSH PRIVATE KEY-----`, unencrypted; the platform stores it encrypted. */
  val privateKey: String,
  /**
   * One `authorized_keys` line, e.g. `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI… Ideate on Pixel 3a`.
   */
  val publicKey: String,
)

object SshKeys {
  /** An Ed25519 key, the type `ssh-keygen` makes by default. [comment] ends the public key line. */
  fun generateEd25519(comment: String): GeneratedKey {
    require(comment.none { it == '\n' }) { "A key comment must be one line: $comment" }
    val generator = Ed25519KeyPairGenerator()
    generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair = generator.generateKeyPair()
    val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
    val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
    val privateKey = buildString {
      append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
      Base64.getEncoder().encodeToString(privateBlob).chunked(70).forEach {
        append(it).append('\n')
      }
      append("-----END OPENSSH PRIVATE KEY-----\n")
    }
    return GeneratedKey(
      privateKey,
      "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} $comment",
    )
  }
}
