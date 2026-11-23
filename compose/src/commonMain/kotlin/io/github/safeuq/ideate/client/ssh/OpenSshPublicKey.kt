package io.github.safeuq.ideate.client.ssh

/** What the New Connection dialog knows about the public key in `~/.ssh`. */
sealed interface OpenSshKeyState {
  data object Loading : OpenSshKeyState

  /** [line] is the `.pub` file's text, e.g. `ssh-ed25519 AAAAC3Nza… termux-galaxy-s24-plus`. */
  data class Found(val line: String) : OpenSshKeyState

  /** None of [OpenSshPublicKey.FILES] exists. */
  data object Missing : OpenSshKeyState

  /** The files couldn't be looked up (e.g. Termux didn't answer). */
  data class Failed(val reason: String) : OpenSshKeyState
}

object OpenSshPublicKey {
  /** OpenSSH's default order, like `SshLogins`' default identity files. */
  val FILES = listOf("~/.ssh/id_ed25519.pub", "~/.ssh/id_ecdsa.pub", "~/.ssh/id_rsa.pub")

  /** The first existing, non-blank file of [FILES]. May block, so not for the main thread. */
  fun load(files: OpenSshFiles): OpenSshKeyState {
    try {
      for (path in FILES) {
        val text = files.read(path.replaceFirst(Regex("^~"), files.home))?.trim()
        if (!text.isNullOrEmpty()) return OpenSshKeyState.Found(text)
      }
      return OpenSshKeyState.Missing
    } catch (readFailure: Exception) {
      return OpenSshKeyState.Failed(readFailure.message ?: readFailure.javaClass.simpleName)
    }
  }

  /** Why a key is needed and what to do, for [OpenSshKeyState.Missing]. */
  fun missingText(folder: String, makeKeyAdvice: String) =
    "There is no SSH key in $folder. A key lets Ideate log in without a password: the host lets " +
      "you in when the key's public half is in its ~/.ssh/authorized_keys. $makeKeyAdvice, then " +
      "add ~/.ssh/id_ed25519.pub to the host."
}
