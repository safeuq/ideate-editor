package io.github.safeuq.ideate.client.ssh

import com.hierynomus.sshj.userauth.agent.AgentProxy

/** What `~/.ssh/config` says about one host alias; nulls where it says nothing. */
data class OpenSshHost(
  val hostName: String?,
  val port: Int?,
  val user: String?,
  /** In file order, `~` not yet expanded, e.g. `~/.ssh/id_ed25519`. */
  val identityFiles: List<String>,
)

/**
 * The part of OpenSSH's `ssh_config(5)` that the SSH page needs: `Host` blocks with `*`, `?` and
 * `!` patterns, and `HostName`, `Port`, `User`, `IdentityFile`. As in OpenSSH, the first value
 * found for a keyword wins, `IdentityFile` adds up, and lines before the first `Host` apply to
 * every host.
 *
 * Not supported: `Match` blocks (skipped), `Include` (ignored), `%` tokens other than `%%`.
 */
object OpenSshConfig {
  fun resolve(config: String, alias: String): OpenSshHost {
    var hostName: String? = null
    var port: Int? = null
    var user: String? = null
    val identityFiles = mutableListOf<String>()
    var applies = true
    for (rawLine in config.lines()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      val keyword = line.substringBefore(' ').substringBefore('\t').substringBefore('=').lowercase()
      val value =
        line.substring(keyword.length).trimStart(' ', '\t', '=').trim().removeSurrounding("\"")
      when (keyword) {
        "host" -> applies = matches(value.split(Regex("\\s+")), alias)
        "match" -> applies = false
        else ->
          if (applies) {
            when (keyword) {
              "hostname" ->
                if (hostName == null) hostName = value.replace("%h", alias).replace("%%", "%")
              "port" -> if (port == null) port = value.toIntOrNull()
              "user" -> if (user == null) user = value
              "identityfile" -> identityFiles += value
            }
          }
      }
    }
    return OpenSshHost(hostName, port, user, identityFiles)
  }

  /** OpenSSH's `match_hostname`: some pattern matches and no negated one does. */
  private fun matches(patterns: List<String>, alias: String): Boolean {
    var matched = false
    for (pattern in patterns) {
      val negated = pattern.startsWith("!")
      if (wildcard(pattern.removePrefix("!"), alias.lowercase())) {
        if (negated) return false
        matched = true
      }
    }
    return matched
  }

  private fun wildcard(pattern: String, text: String): Boolean {
    val regex =
      pattern.lowercase().map { character ->
        when (character) {
          '*' -> ".*"
          '?' -> "."
          else -> Regex.escape(character.toString())
        }
      }
    return Regex(regex.joinToString("")).matches(text)
  }
}

/** The files OPEN_SSH mode reads; the platform supplies them (the desktop's, or Termux's). */
interface OpenSshFiles {
  /** E.g. `/Users/dev`. */
  val home: String

  /**
   * A connection to the user's ssh-agent, or null when there is none (the desktop's
   * `SSH_AUTH_SOCK`, or Termux's agent through the bridge). Throws when an agent is there but can't
   * be used. May block, so not for the main thread.
   */
  fun agent(): AgentProxy?

  /**
   * A file's text, or null when it doesn't exist. Throws when the file can't be looked up at all.
   * May block, so not for the main thread.
   */
  fun read(path: String): String?
}

/** Where and how to log in to a saved host. */
data class ResolvedLogin(
  val host: String,
  val port: Int,
  val user: String,
  val logins: List<SshLogin>,
)

object SshLogins {
  /** OpenSSH's default identity files, tried when the config names none. */
  private val DEFAULT_IDENTITIES = listOf("~/.ssh/id_ed25519", "~/.ssh/id_ecdsa", "~/.ssh/id_rsa")

  /**
   * The log-in to try for [entry]. Throws [MissingSecret] when a password or key isn't saved, so
   * the UI can ask for it ([typed]) and call again.
   */
  fun resolve(
    entry: SshHostEntry,
    secrets: SecretStore,
    openSsh: OpenSshFiles?,
    deviceName: String,
    typed: String? = null,
  ): ResolvedLogin =
    when (entry.method) {
      LoginMethod.GENERATED_KEY ->
        ResolvedLogin(
          entry.host,
          entry.port,
          entry.user,
          listOf(SshLogin.PrivateKey(IdeateKey.get(secrets, "Ideate on $deviceName").privateKey)),
        )
      LoginMethod.KEY_FILE -> {
        val key =
          secrets.read(SecretNames.key(entry)) ?: throw MissingSecret(entry, "the private key")
        val passphrase = typed ?: secrets.read(SecretNames.passphrase(entry))
        ResolvedLogin(
          entry.host,
          entry.port,
          entry.user,
          listOf(SshLogin.PrivateKey(key, passphrase)),
        )
      }
      LoginMethod.PASSWORD -> {
        val password =
          typed
            ?: secrets.read(SecretNames.password(entry))
            ?: throw MissingSecret(entry, "the password")
        ResolvedLogin(entry.host, entry.port, entry.user, listOf(SshLogin.Password(password)))
      }
      LoginMethod.OPEN_SSH -> {
        val files =
          checkNotNull(openSsh) {
            "${entry.name} uses ~/.ssh, which isn't available now (on Android it needs Termux " +
              "installed and its RUN_COMMAND permission granted)"
          }
        val config =
          OpenSshConfig.resolve(files.read("${files.home}/.ssh/config").orEmpty(), entry.host)
        // The identity files are read only when the agent didn't log in (on Android each read
        // is a Termux call).
        val identityFiles = SshLogin.IdentityFiles {
          config.identityFiles
            .ifEmpty { DEFAULT_IDENTITIES }
            .mapNotNull { path ->
              files.read(path.replaceFirst(Regex("^~"), files.home))?.let {
                SshLogin.PrivateKey(it, typed)
              }
            }
        }
        ResolvedLogin(
          config.hostName ?: entry.host,
          // A port typed for the host wins, like `ssh -p`; the default 22 lets the config decide.
          if (entry.port == 22) config.port ?: 22 else entry.port,
          entry.user.ifBlank { config.user.orEmpty() },
          listOf(SshLogin.Agent(files::agent), identityFiles),
        )
      }
    }

  class MissingSecret(val entry: SshHostEntry, what: String) :
    IllegalStateException("${entry.name} needs $what")
}
