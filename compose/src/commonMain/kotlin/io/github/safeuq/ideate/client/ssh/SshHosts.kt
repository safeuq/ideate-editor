package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.StoredText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** How a saved host logs in; the secrets themselves are in the [SecretStore]. */
enum class LoginMethod {
  /** Ideate's own Ed25519 key ([IdeateKey]); the user adds its public key to the host once. */
  GENERATED_KEY,

  /** A private key the user imported; kept in the [SecretStore] under [SecretNames.key]. */
  KEY_FILE,

  /** A password, saved under [SecretNames.password] if the user chose to, else asked each time. */
  PASSWORD,

  /**
   * Desktop: `~/.ssh/config`, the agent and the identity files, like Gateway's OPEN_SSH (its
   * default).
   */
  OPEN_SSH,
}

/**
 * A host in the SSH page, like Gateway's `sshConfig`. [host] may be an alias from `~/.ssh/config`
 * when [method] is [LoginMethod.OPEN_SSH].
 */
data class SshHostEntry(
  /** A UUID, e.g. `586e63d9-ef81-4a4b-b63d-c77470910736`. */
  val id: String,
  val host: String,
  val port: Int,
  val user: String,
  val method: LoginMethod,
) {
  /** Gateway's `generateSshConfigName`: `user@host` on port 22, else `user@host:port`. */
  val name: String
    get() = if (port == 22) "$user@$host" else "$user@$host:$port"
}

/**
 * Secrets Ideate keeps encrypted: Android Keystore on the phone, owner-only files on the desktop.
 */
interface SecretStore {
  fun read(name: String): String?

  fun write(name: String, value: String)

  fun delete(name: String)
}

/** Names in the [SecretStore]. */
object SecretNames {
  const val IDEATE_PRIVATE_KEY = "ssh-key:ideate"
  const val IDEATE_PUBLIC_KEY = "ssh-key:ideate.pub"

  fun key(host: SshHostEntry) = "ssh-key:${host.id}"

  fun passphrase(host: SshHostEntry) = "ssh-passphrase:${host.id}"

  fun password(host: SshHostEntry) = "ssh-password:${host.id}"
}

/** Ideate's own key pair: made once, then the same public key for every host. */
object IdeateKey {
  /** [comment] ends the public key line, e.g. `Ideate on Pixel 3a`. */
  fun get(secrets: SecretStore, comment: String): GeneratedKey {
    val privateKey = secrets.read(SecretNames.IDEATE_PRIVATE_KEY)
    val publicKey = secrets.read(SecretNames.IDEATE_PUBLIC_KEY)
    if (privateKey != null && publicKey != null) return GeneratedKey(privateKey, publicKey)
    val generated = SshKeys.generateEd25519(comment)
    secrets.write(SecretNames.IDEATE_PRIVATE_KEY, generated.privateKey)
    secrets.write(SecretNames.IDEATE_PUBLIC_KEY, generated.publicKey)
    return generated
  }
}

/** The SSH page's hosts, in the order they were added, as a JSON array in [storage]. */
class SshHosts(private val storage: StoredText) {
  fun entries(): List<SshHostEntry> =
    Json.parseToJsonElement(storage.read() ?: "[]").jsonArray.map { it.jsonObject.toEntry() }

  /** Adds [entry], or replaces the one with its id. */
  fun save(entry: SshHostEntry) {
    val current = entries()
    val updated =
      if (current.any { it.id == entry.id }) current.map { if (it.id == entry.id) entry else it }
      else current + entry
    write(updated)
  }

  /** Removes [entry] and its secrets. */
  fun remove(entry: SshHostEntry, secrets: SecretStore) {
    write(entries().filter { it.id != entry.id })
    listOf(SecretNames.key(entry), SecretNames.passphrase(entry), SecretNames.password(entry))
      .forEach(secrets::delete)
  }

  private fun write(entries: List<SshHostEntry>) =
    storage.write(
      JsonArray(
          entries.map { entry ->
            buildJsonObject {
              put("id", entry.id)
              put("host", entry.host)
              put("port", entry.port)
              put("user", entry.user)
              put("method", entry.method.name)
            }
          },
        )
        .toString(),
    )

  private fun JsonObject.toEntry() =
    SshHostEntry(
      id = getValue("id").jsonPrimitive.content,
      host = getValue("host").jsonPrimitive.content,
      port = getValue("port").jsonPrimitive.int,
      user = getValue("user").jsonPrimitive.content,
      method = LoginMethod.valueOf(getValue("method").jsonPrimitive.content),
    )
}
