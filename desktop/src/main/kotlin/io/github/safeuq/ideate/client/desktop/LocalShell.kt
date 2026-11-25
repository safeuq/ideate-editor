package io.github.safeuq.ideate.client.desktop

import io.github.safeuq.ideate.client.ssh.ExecResult
import kotlin.concurrent.thread

/**
 * Runs the SSH page's shell scripts on this computer with `/bin/sh` instead of over SSH, for the
 * This device page (docs/local-backends.md, "Build plan" item 1).
 */
object LocalShell {
  fun run(commandLine: String): ExecResult {
    val process = ProcessBuilder("/bin/sh", "-c", commandLine).start()
    process.outputStream.close()
    // Read on its own thread, so a full stderr pipe can't block the process while stdout is read.
    var stderr = ""
    val stderrReader =
      thread(name = "local-shell-stderr", isDaemon = true) {
        stderr = process.errorStream.bufferedReader().readText()
      }
    val stdout = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    stderrReader.join()
    return ExecResult(exitCode, stdout, stderr)
  }
}
