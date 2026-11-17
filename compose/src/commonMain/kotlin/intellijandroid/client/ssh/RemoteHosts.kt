package intellijandroid.client.ssh

import intellijandroid.client.ssh.SshSession.Companion.shellQuote

/** Picks how to talk to a host (docs/ssh-connections.md, "Design"). */
object RemoteHosts {
  /**
   * [WorkerHost] when Gateway left a worker in `<RemoteDev>/remote-dev-worker/` that is executable
   * and passes `worker-check` (the newest one when there are several), else [ShellHost]. Usable for
   * the life of [session].
   */
  fun forSession(session: SshSession): RemoteHost = forCommands(session::execInLoginShell)

  internal fun forCommands(run: (String) -> ExecResult): RemoteHost {
    val shell = ShellHost(run)
    val folder = "${shell.remoteDevDir()}/remote-dev-worker"
    // Newest first; names are remote-dev-worker_<sha256>, so they hold no spaces.
    val workers =
      run("ls -t ${shellQuote(folder)} 2>/dev/null")
        .stdout
        .lines()
        .filter { it.startsWith("remote-dev-worker_") }
        .map { "$folder/$it" }
    val worker = workers.firstOrNull { path ->
      WorkerHost.passesCheck(run("[ -x ${shellQuote(path)} ] && ${shellQuote(path)} worker-check"))
    }
    return if (worker != null) WorkerHost(worker, run, shell) else shell
  }
}
