package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.ssh.SshSession.Companion.shellQuote

/** Picks how to talk to a host (docs/ssh-connections.md, "Design"). */
object RemoteHosts {
  /**
   * [WorkerHost] when Gateway left a worker in `<RemoteDev>/remote-dev-worker/` that is executable
   * and passes `worker-check` (the newest one when there are several), else [ShellHost]. Usable for
   * the life of [session].
   */
  fun forSession(session: SshSession): RemoteHost = forCommands(session::execInLoginShell)

  internal fun forCommands(run: (String) -> ExecResult): RemoteHost {
    // One login shell for all of it: the OS, the RemoteDev folder and the worker (each login shell
    // costs about 0.3 to 2 s on a phone).
    val probe = ShellHost.records(run("sh -c ${shellQuote(PROBE)}"))
    fun field(marker: String) = probe.firstOrNull { it.marker == marker }?.fields?.firstOrNull()
    val os = HostOs.parse(requireNotNull(field("@@OS@@")) { "No uname from the host" })
    val remoteDevDir = requireNotNull(field("@@DIR@@")) { "No RemoteDev folder from the host" }
    val shell = ShellHost(run, os, remoteDevDir, field("@@HOME@@"))
    return field("@@WORKER@@")?.let { WorkerHost(it, run, shell) } ?: shell
  }

  /**
   * Prints `@@OS@@<tab>uname -sm`, `@@DIR@@<tab>the RemoteDev folder`, `@@HOME@@<tab>$HOME` and,
   * for the newest worker in `remote-dev-worker/` (`ls -t`) that is executable and passes
   * `worker-check` (stdout holds `HELLO_STDOUT` and the `ERROR_TEST` error; it exits 1 by design),
   * `@@WORKER@@<tab>its path`. Worker names are `remote-dev-worker_<sha256>`, without spaces.
   */
  internal const val PROBE =
    """os=${'$'}(uname -sm)
case "${'$'}os" in
  Darwin*) dir="${'$'}HOME/Library/Caches/JetBrains/RemoteDev" ;;
  *) dir="${'$'}{XDG_CACHE_HOME:-${'$'}HOME/.cache}/JetBrains/RemoteDev" ;;
esac
printf '@@OS@@\t%s\n@@DIR@@\t%s\n@@HOME@@\t%s\n' "${'$'}os" "${'$'}dir" "${'$'}HOME"
for name in ${'$'}(ls -t "${'$'}dir/remote-dev-worker" 2>/dev/null); do
  case "${'$'}name" in remote-dev-worker_*) ;; *) continue ;; esac
  worker="${'$'}dir/remote-dev-worker/${'$'}name"
  [ -x "${'$'}worker" ] || continue
  case "${'$'}("${'$'}worker" worker-check 2>/dev/null)" in
    *HELLO_STDOUT*ERROR_TEST*) printf '@@WORKER@@\t%s\n' "${'$'}worker"; break ;;
  esac
done"""
}
