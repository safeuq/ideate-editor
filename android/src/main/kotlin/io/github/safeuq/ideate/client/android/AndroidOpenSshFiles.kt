package io.github.safeuq.ideate.client.android

import android.os.Looper
import io.github.safeuq.ideate.client.connect.Termux
import io.github.safeuq.ideate.client.ssh.OpenSshFiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Termux's `~/.ssh` for OPEN_SSH hosts. This app can't read Termux's private files, so every read
 * runs `cat` in Termux (docs/ssh-connections.md, "Termux's ~/.ssh on Android"). Nothing is kept:
 * each connect reads the files again.
 *
 * There is no agent: its socket would be in Termux's private folder, out of this app's reach.
 */
class AndroidOpenSshFiles(private val termux: AndroidTermux) : OpenSshFiles {
  override val home = "/data/data/com.termux/files/home"

  override val hasAgent = false

  /**
   * Blocks until Termux answers. Termux's answer arrives on the main thread, so waiting there would
   * never end.
   */
  override fun read(path: String): String? {
    check(Looper.myLooper() != Looper.getMainLooper()) {
      "Termux's answer arrives on the main thread, so reading $path can't wait there"
    }
    val answered = CountDownLatch(1)
    val answer = AtomicReference<Result<Termux.Result>>()
    val startedAt = System.nanoTime()
    termux.cat(path) { result ->
      answer.set(result)
      answered.countDown()
    }
    check(answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "Termux didn't answer in $TIMEOUT_SECONDS s when reading $path"
    }
    println(
      "Termux read ${path.replace(home, "~")}: ${(System.nanoTime() - startedAt) / 1_000_000} ms",
    )
    // A Termux failure throws here; only a non-zero exit from cat means the file isn't there.
    val output = answer.get().getOrThrow()
    return if (output.exitCode == 0) output.stdout else null
  }

  /** `~/.ssh/known_hosts`, or null when there is none. */
  fun knownHosts(): String? = read("$home/.ssh/known_hosts")

  private companion object {
    const val TIMEOUT_SECONDS = 10L
  }
}
