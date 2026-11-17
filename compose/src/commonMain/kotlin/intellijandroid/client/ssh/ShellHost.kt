package intellijandroid.client.ssh

import intellijandroid.client.connect.Termux
import intellijandroid.client.ssh.SshSession.Companion.shellQuote

/**
 * [RemoteHost] in POSIX `sh`, for hosts without Gateway's worker (docs/ssh-connections.md,
 * "Design"): reads the same RemoteDev files the worker reads. [run] runs one command line on the
 * host, e.g. [SshSession.execInLoginShell].
 */
class ShellHost(private val run: (String) -> ExecResult) : RemoteHost {
  private val hostOs by lazy { HostOs.parse(checked(run("uname -sm"), "uname").stdout) }
  private val devDir by lazy {
    val script =
      if (os().os == HostOs.Os.MAC) "printf '%s\\n' \"\$HOME/Library/Caches/JetBrains/RemoteDev\""
      else "printf '%s\\n' \"\${XDG_CACHE_HOME:-\$HOME/.cache}/JetBrains/RemoteDev\""
    checked(run(script), "RemoteDev folder").stdout.trim().lines().last()
  }

  override fun os() = hostOs

  override fun remoteDevDir(): String = devDir

  override fun installedIdes(): List<InstalledIde> =
    records(runScript(INSTALLED_IDES, remoteDevDir()), IDE_RECORD).mapNotNull { (fields, body) ->
      HostData.installedIde(fields[0], fields.getOrNull(1), body)
    }

  override fun recentProjects(): List<RecentProject> {
    val statuses = statusFiles()
    return HostData.recentProjects(
      statuses.map { it.text },
      statuses.filter { it.alive }.map { it.pid }.toSet(),
    )
  }

  override fun status(idePath: String, projectPath: String): HostStatus? {
    val result =
      run("${shellQuote("$idePath/bin/remote-dev-server.sh")} status ${shellQuote(projectPath)}")
    val json = HostData.statusCommandOutput(result.stdout)
    if (json == null) {
      checked(result, "remote-dev-server.sh status")
      return null
    }
    return HostData.status(json)
  }

  override fun launch(idePath: String, projectPath: String) {
    val productCode =
      installedIdes().firstOrNull { it.idePath == idePath }?.productCode
        ?: throw RemoteHostException("No IDE installed at $idePath")
    val logs = HostData.logsDir(remoteDevDir(), productCode, projectPath)
    // Gateway's shell form; nohup and all three streams redirected, so the SSH channel can close.
    checked(
      run(
        "mkdir -p ${shellQuote(logs)} && nohup ${shellQuote("$idePath/bin/remote-dev-server.sh")} " +
          "run ${shellQuote(projectPath)} >> ${shellQuote("$logs/stdout.log")} " +
          "2>> ${shellQuote("$logs/stderr.log")} < /dev/null &",
      ),
      "remote-dev-server.sh run",
    )
  }

  override fun listFolders(path: String): List<RemoteFolder> =
    Termux.parseFolders(checked(runScript(Termux.LIST_FOLDERS, path), "List $path").stdout).map {
      RemoteFolder(it.name, it.isProject, it.hasFolders, it.hasHiddenFolders)
    }

  override fun removeRecent(projectPath: String, productCode: String) {
    val files =
      statusFiles()
        .filter { it.file.startsWith("recent/") }
        .filter { status ->
          val parsed = runCatching { HostData.status(status.text) }.getOrNull()
          parsed?.projects?.singleOrNull()?.projectPath == projectPath &&
            status.text.contains("\"$productCode-")
        }
    if (files.isEmpty()) return
    checked(
      run(
        "cd ${shellQuote(remoteDevDir())} && rm -f -- " +
          files.joinToString(" ") { shellQuote(it.file) },
      ),
      "Remove recent $projectPath",
    )
  }

  /**
   * One file of `recent/` or `active/`: its path relative to the RemoteDev folder, e.g.
   * `recent/30916_-1431083394`.
   */
  private data class StatusFile(
    val file: String,
    val pid: Long,
    val alive: Boolean,
    val text: String,
  )

  private fun statusFiles(): List<StatusFile> =
    records(runScript(RECENT_STATUSES, remoteDevDir()), STATUS_RECORD).mapNotNull { (fields, body)
      ->
      val file = fields[0]
      val pid = file.substringAfterLast('/').substringBefore('_').toLongOrNull()
      if (pid == null || body.isBlank()) null
      else StatusFile(file, pid, fields.getOrNull(1) == "1", body)
    }

  private fun runScript(script: String, vararg arguments: String) =
    run("sh -c ${shellQuote(script)} sh " + arguments.joinToString(" ") { shellQuote(it) })

  companion object {
    private const val IDE_RECORD = "@@IDE@@"
    private const val STATUS_RECORD = "@@STATUS@@"

    /**
     * `$1`: the RemoteDev folder. Per IDE under `dist/` and `userProvidedDist/` (symlinks resolved)
     * that has `bin/remote-dev-server.sh`: a line `@@IDE@@<tab>idePath<tab>linkPath`, then its
     * product-info.json (`<ide>/product-info.json`, or `<app>/Contents/Resources/` on macOS, where
     * the link points at `Contents`).
     */
    internal const val INSTALLED_IDES =
      """cd "${'$'}1" 2>/dev/null || exit 0
for entry in dist/* userProvidedDist/*; do
  [ -e "${'$'}entry" ] || continue
  ide=${'$'}(cd "${'$'}entry" 2>/dev/null && pwd -P) || continue
  info="${'$'}ide/product-info.json"
  [ -f "${'$'}info" ] || info="${'$'}ide/Resources/product-info.json"
  [ -f "${'$'}info" ] && [ -f "${'$'}ide/bin/remote-dev-server.sh" ] || continue
  link=
  [ -L "${'$'}entry" ] && link="${'$'}1/${'$'}entry"
  printf '@@IDE@@\t%s\t%s\n' "${'$'}ide" "${'$'}link"
  cat "${'$'}info"
  printf '\n'
done"""

    /**
     * `$1`: the RemoteDev folder. Per status file in `recent/` and `active/`: a line
     * `@@STATUS@@<tab>file<tab>alive`, alive 1 when `kill -0` reaches the pid its name starts with,
     * then the file.
     */
    internal const val RECENT_STATUSES =
      """cd "${'$'}1" 2>/dev/null || exit 0
for file in recent/* active/*; do
  [ -f "${'$'}file" ] || continue
  name=${'$'}{file##*/}
  alive=0
  kill -0 "${'$'}{name%%_*}" 2>/dev/null && alive=1
  printf '@@STATUS@@\t%s\t%s\n' "${'$'}file" "${'$'}alive"
  cat "${'$'}file"
  printf '\n'
done"""

    /**
     * Splits a script's output into records: the tab-separated fields after [marker], and the lines
     * up to the next record.
     */
    internal fun records(result: ExecResult, marker: String): List<Pair<List<String>, String>> {
      checked(result, marker)
      val records = mutableListOf<Pair<List<String>, String>>()
      var fields: List<String>? = null
      val body = StringBuilder()
      fun flush() {
        fields?.let { records += it to body.toString().trim() }
        body.clear()
      }
      for (line in result.stdout.lines()) {
        if (line.startsWith("$marker\t")) {
          flush()
          fields = line.removePrefix("$marker\t").split('\t')
        } else if (fields != null) body.appendLine(line)
      }
      flush()
      return records
    }

    internal fun checked(result: ExecResult, what: String): ExecResult {
      if (result.exitCode != 0)
        throw RemoteHostException(
          "$what failed (exit ${result.exitCode}): ${result.stderr.trim().ifEmpty { result.stdout.trim() }.take(500)}",
        )
      return result
    }
  }
}
