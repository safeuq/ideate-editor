package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.ssh.SshSession.Companion.shellQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * [RemoteHost] through JetBrains Gateway's `remote-dev-worker`, already on the host at [worker]
 * (docs/ssh-connections.md, "Commands on the host"), called the way Gateway calls it: in the login
 * shell, after the marker line, `--name=value` flags, JSON replies. The OS, the RemoteDev folder
 * and the folder browser come from [shell]: the worker has no `uname`, and its `list-files` doesn't
 * say which folders are projects or can be expanded.
 */
class WorkerHost(
  private val worker: String,
  private val run: (String) -> ExecResult,
  private val shell: ShellHost = ShellHost(run),
) : RemoteHost {
  override fun os() = shell.os()

  override fun remoteDevDir() = shell.remoteDevDir()

  override fun home() = shell.home()

  /** The worker's IDEs, then the ones only [ShellHost] finds (the Mac apps). */
  override fun installedIdes(): List<InstalledIde> =
    (call("installed-ides").data.mapNotNull(::ide) + shell.installedIdes()).distinctBy {
      it.idePath
    }

  override fun recentProjects(): List<RecentProject> {
    val recent = Json.parseToJsonElement(call("recent-projects-data").single).jsonObject
    val active = call("active-projects").data
    val alive =
      recent["alivePids"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.longOrNull }.toSet() +
        active.map { HostData.status(it).appPid }
    val raw = recent["rawStatuses"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    return HostData.mergedRecent(
      HostData.recentProjects(raw + active, alive),
      shell.idesAndIdeHistory().recent,
    )
  }

  /**
   * One `recent-projects-data`: its `rawStatuses`, `alivePids` and `installedIdes`. Unlike
   * [recentProjects] it leaves out `active-projects`; a running backend writes its `recent/` file
   * when it opens the project, so its project is there, and `alivePids` marks it running. Then
   * [ShellHost.idesAndIdeHistory] for what the worker doesn't read.
   */
  override fun projectsAndIdes(): HostProjects {
    val data = Json.parseToJsonElement(call("recent-projects-data").single).jsonObject
    val alive =
      data["alivePids"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.longOrNull }.toSet()
    val raw = data["rawStatuses"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    val workerIdes =
      data["installedIdes"]?.jsonArray.orEmpty().mapNotNull { ide(it.jsonObject.toString()) }
    // The worker reads neither the Mac apps nor recentProjects.xml: one shell command adds both.
    val fromShell = shell.idesAndIdeHistory()
    val ides = (workerIdes + fromShell.ides).distinctBy { it.idePath }
    return HostProjects(
      HostData.mergedRecent(HostData.recentProjects(raw, alive), fromShell.recent),
      ides,
    )
  }

  override fun status(idePath: String, projectPath: String): HostStatus? {
    val result = run(command("host-status", "ide-path" to idePath, "project-path" to projectPath))
    // Exit 14: the backend is still starting.
    if (result.exitCode == 14) return null
    return HostData.workerReply(result.stdout).data.singleOrNull()?.let(HostData::status)
  }

  override fun launch(idePath: String, projectPath: String): String {
    val productCode =
      installedIdes().firstOrNull { it.idePath == idePath }?.productCode
        ?: throw RemoteHostException("No IDE installed at $idePath")
    val logs =
      call("get-logs-dir", "product-code" to productCode, "project-path" to projectPath, raw = true)
        .single
    ShellHost.checked(run(shell.markLaunch(logs)), "Mark the launch in $logs")
    call(
      "host-launch",
      "ide-path" to idePath,
      "project-path" to projectPath,
      "stdout-file" to "$logs/stdout.log",
      "stderr-file" to "$logs/stderr.log",
    )
    return logs
  }

  /** The worker writes no exit line, so only the log's words show an end here. */
  override fun launchFailure(logsDir: String) = shell.launchFailure(logsDir)

  override fun listFolders(path: String) = shell.listFolders(path)

  override fun stop(idePath: String, projectPath: String) = shell.stop(idePath, projectPath)

  override fun removeRecent(projectPath: String, productCode: String) {
    call("remove-recent-project", "project-path" to projectPath, "product-code" to productCode)
  }

  private fun ide(entry: String): InstalledIde? {
    val ide = Json.parseToJsonElement(entry).jsonObject
    return HostData.installedIde(
      ide.getValue("idePath").jsonPrimitive.content,
      ide["linkPath"]?.jsonPrimitive?.content,
      ide.getValue("productInfo").jsonPrimitive.content,
    )
  }

  private fun command(name: String, vararg flags: Pair<String, String>) =
    (listOf(shellQuote(worker), name) + flags.map { (flag, value) -> shellQuote("--$flag=$value") })
      .joinToString(" ")

  /**
   * Runs [name] and returns its reply. [raw]: the command prints plain text instead of JSON
   * (`get-logs-dir`).
   */
  private fun call(
    name: String,
    vararg flags: Pair<String, String>,
    raw: Boolean = false,
  ): HostData.WorkerReply {
    val result = run(command(name, *flags))
    if (raw) {
      ShellHost.checked(result, name)
      return HostData.WorkerReply(listOf(result.stdout.trim().lines().last()))
    }
    return HostData.workerReply(result.stdout)
  }

  companion object {
    /**
     * Gateway's `worker-check`: the worker prints `HELLO_STDOUT` and a test error (`ERROR_TEST
     * Nothing to worry about`) on stdout, `HELLO_STDERR` on stderr, and exits 1 because of that
     * error. Seen on the phone, 2026-11-17.
     */
    fun passesCheck(result: ExecResult) =
      "HELLO_STDOUT" in result.stdout &&
        result.stdout.lines().any { it.startsWith("{") && "ERROR_TEST" in it }
  }
}
