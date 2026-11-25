package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.Termux
import io.github.safeuq.ideate.client.ssh.SshSession.Companion.shellQuote

/**
 * [RemoteHost] in POSIX `sh`, for hosts without Gateway's worker (docs/ssh-connections.md,
 * "Design"): reads the same RemoteDev files the worker reads. [run] runs one command line on the
 * host, e.g. [SshSession.execInLoginShell].
 */
class ShellHost(
  private val run: (String) -> ExecResult,
  /** Already known (RemoteHosts asks for both in its one probe), else asked on first use. */
  knownOs: HostOs? = null,
  knownRemoteDevDir: String? = null,
  knownHome: String? = null,
  /**
   * The system-wide Applications folder [INSTALLED_IDES] looks in on macOS; tests pass their own.
   */
  private val systemApplications: String = "/Applications",
) : RemoteHost {
  private val homeDir by lazy {
    knownHome ?: checked(run("printf '%s\\n' \"\$HOME\""), "HOME").stdout.trim().lines().last()
  }
  private val hostOs by lazy {
    knownOs ?: HostOs.parse(checked(run("uname -sm"), "uname").stdout)
  }
  private val devDir by lazy {
    if (knownRemoteDevDir != null) return@lazy knownRemoteDevDir
    val script =
      if (os().os == HostOs.Os.MAC) "printf '%s\\n' \"\$HOME/Library/Caches/JetBrains/RemoteDev\""
      else "printf '%s\\n' \"\${XDG_CACHE_HOME:-\$HOME/.cache}/JetBrains/RemoteDev\""
    checked(run(script), "RemoteDev folder").stdout.trim().lines().last()
  }

  override fun os() = hostOs

  override fun remoteDevDir(): String = devDir

  override fun home(): String = homeDir

  override fun installedIdes(): List<InstalledIde> =
    ides(records(runScript(INSTALLED_IDES, remoteDevDir(), systemApplications)))

  override fun recentProjects(): List<RecentProject> = projectsAndIdes().recent

  override fun projectsAndIdes(): HostProjects {
    // Subshells: a script that ends early with `exit` doesn't end the ones after it.
    val records =
      records(
        runScript(
          "($INSTALLED_IDES)\n($RECENT_STATUSES)\n($RECENT_IDE_PROJECTS)",
          remoteDevDir(),
          systemApplications,
        ),
      )
    val ides = ides(records)
    return HostProjects(
      HostData.mergedRecent(recent(statusFiles(records)), ideHistory(records, ides)),
      ides,
    )
  }

  /**
   * The installed IDEs and the projects the desktop IDEs opened (`recentProjects.xml`), in one
   * command: for [WorkerHost], whose worker reads neither the Mac apps nor that file.
   */
  internal fun idesAndIdeHistory(): HostProjects {
    val records =
      records(
        runScript("($INSTALLED_IDES)\n($RECENT_IDE_PROJECTS)", remoteDevDir(), systemApplications),
      )
    val ides = ides(records)
    return HostProjects(ideHistory(records, ides), ides)
  }

  private fun ideHistory(records: List<Record>, ides: List<InstalledIde>): List<RecentProject> {
    val files = records.filter { it.marker == XML_RECORD }
    if (files.isEmpty()) return emptyList()
    return HostData.ideRecentProjects(
      files.associate { it.fields[0] to it.body },
      home = files.first().fields[1],
      ides = ides,
      missing = records.filter { it.marker == GONE_RECORD }.map { it.fields[0] }.toSet(),
    )
  }

  private fun ides(records: List<Record>) =
    records
      .filter { it.marker == IDE_RECORD }
      .mapNotNull { HostData.installedIde(it.fields[0], it.fields.getOrNull(1), it.body) }

  private fun recent(statuses: List<StatusFile>) =
    HostData.recentProjects(
      statuses.map { it.text },
      statuses.filter { it.alive }.map { it.pid }.toSet(),
    )

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

  override fun launch(idePath: String, projectPath: String): String {
    val productCode =
      installedIdes().firstOrNull { it.idePath == idePath }?.productCode
        ?: throw RemoteHostException("No IDE installed at $idePath")
    val logs = HostData.logsDir(remoteDevDir(), productCode, projectPath)
    // Gateway's shell form, with nohup and all three streams redirected so the SSH channel can
    // close. The inner sh waits for the launcher, which runs as long as the backend does, then
    // writes its exit code for launchFailure.
    val runAndRecord =
      "${shellQuote("$idePath/bin/remote-dev-server.sh")} run \"\$0\"; " +
        "echo \"${HostData.LAUNCH_EXIT} \$?\""
    checked(
      run(
        // Braces: a trailing & would put the whole && list in the background, and that subshell
        // keeps the channel's output open until the backend exits.
        "${markLaunch(logs)} && { nohup sh -c ${shellQuote(runAndRecord)} ${shellQuote(projectPath)} " +
          ">> ${shellQuote("$logs/stdout.log")} 2>> ${shellQuote("$logs/stderr.log")} < /dev/null & }",
      ),
      "remote-dev-server.sh run",
    )
    return logs
  }

  override fun stop(idePath: String, projectPath: String) {
    checked(
      run("${shellQuote("$idePath/bin/remote-dev-server.sh")} stop ${shellQuote(projectPath)}"),
      "remote-dev-server.sh stop",
    )
  }

  /** The shell commands that create [logs] and write [HostData.LAUNCH_START] to both logs. */
  internal fun markLaunch(logs: String) =
    "mkdir -p ${shellQuote(logs)} && " +
      "echo ${HostData.LAUNCH_START} >> ${shellQuote("$logs/stdout.log")} && " +
      "echo ${HostData.LAUNCH_START} >> ${shellQuote("$logs/stderr.log")}"

  override fun launchFailure(logsDir: String): LaunchFailure? =
    HostData.launchFailure(
      run(
          "tail -n 200 ${shellQuote("$logsDir/stdout.log")} 2>/dev/null; " +
            "echo ${HostData.STDERR_START}; tail -n 200 ${shellQuote("$logsDir/stderr.log")} 2>/dev/null",
        )
        .stdout,
    )

  override fun listFolders(path: String): List<RemoteFolder> =
    Termux.parseFolders(checked(runScript(Termux.LIST_FOLDERS, path), "List $path").stdout).map {
      RemoteFolder(it.name, it.isProject, it.hasFolders, it.hasHiddenFolders)
    }

  override fun removeRecent(projectPath: String, productCode: String) {
    val files =
      statusFiles(records(runScript(RECENT_STATUSES, remoteDevDir())))
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

  private fun statusFiles(records: List<Record>): List<StatusFile> =
    records
      .filter { it.marker == STATUS_RECORD }
      .mapNotNull { record ->
        val file = record.fields[0]
        val pid = file.substringAfterLast('/').substringBefore('_').toLongOrNull()
        if (pid == null || record.body.isBlank()) null
        else StatusFile(file, pid, record.fields.getOrNull(1) == "1", record.body)
      }

  private fun runScript(script: String, vararg arguments: String) =
    run("sh -c ${shellQuote(script)} sh " + arguments.joinToString(" ") { shellQuote(it) })

  companion object {
    private const val IDE_RECORD = "@@IDE@@"
    private const val STATUS_RECORD = "@@STATUS@@"
    private const val XML_RECORD = "@@XML@@"
    private const val GONE_RECORD = "@@GONE@@"

    /**
     * `$1`: the RemoteDev folder, `$2`: the system Applications folder. Per IDE that has
     * `bin/remote-dev-server.sh` (symlinks resolved, the first copy of each path kept): a line
     * `@@IDE@@<tab>idePath<tab>linkPath`, then its product-info.json (`<ide>/product-info.json`, or
     * `<app>/Contents/Resources/` on macOS, where idePath is `<app>/Contents`). Looks in `dist/`
     * and `userProvidedDist/`, then on macOS in Toolbox's install folders (`installationDirectory`
     * of each channel file), then the apps in `$2` and `~/Applications`.
     */
    internal const val INSTALLED_IDES =
      """seen='
'
emit() {
  ide=${'$'}(cd "${'$'}1" 2>/dev/null && pwd -P) || return 0
  case "${'$'}seen" in *"
${'$'}ide
"*) return 0 ;; esac
  info="${'$'}ide/product-info.json"
  [ -f "${'$'}info" ] || info="${'$'}ide/Resources/product-info.json"
  [ -f "${'$'}info" ] && [ -f "${'$'}ide/bin/remote-dev-server.sh" ] || return 0
  seen="${'$'}seen${'$'}ide
"
  printf '@@IDE@@\t%s\t%s\n' "${'$'}ide" "${'$'}2"
  cat "${'$'}info"
  printf '\n'
}
for entry in "${'$'}1"/dist/* "${'$'}1"/userProvidedDist/*; do
  [ -e "${'$'}entry" ] || continue
  link=
  [ -L "${'$'}entry" ] && link="${'$'}entry"
  emit "${'$'}entry" "${'$'}link"
done
[ "${'$'}(uname)" = Darwin ] || exit 0
toolbox=${'$'}(sed -n 's/.*"installationDirectory" *: *"\([^"]*\)".*/\1/p' \
  "${'$'}HOME/Library/Application Support/JetBrains/Toolbox/channels"/*.json 2>/dev/null)
IFS='
'
for app in ${'$'}toolbox "${'$'}2"/*.app "${'$'}HOME"/Applications/*.app; do
  [ -d "${'$'}app/Contents" ] && emit "${'$'}app/Contents" ""
done
exit 0"""

    /**
     * Per settings folder with `options/recentProjects.xml` (macOS `~/Library/Application
     * Support/JetBrains/<Product><Version>`, else
     * `${'$'}{XDG_CONFIG_HOME:-~/.config}/JetBrains/…`): a line `@@XML@@<tab>folder
     * name<tab>$HOME`, the file, then `@@GONE@@<tab>key` for each entry key whose folder no longer
     * exists. The XML is parsed in Kotlin; `sed` only pulls out the keys.
     */
    internal const val RECENT_IDE_PROJECTS =
      """if [ "${'$'}(uname)" = Darwin ]; then config="${'$'}HOME/Library/Application Support/JetBrains"
else config="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/JetBrains"; fi
for xml in "${'$'}config"/*/options/recentProjects.xml; do
  [ -f "${'$'}xml" ] || continue
  folder=${'$'}{xml%/options/recentProjects.xml}
  printf '@@XML@@\t%s\t%s\n' "${'$'}{folder##*/}" "${'$'}HOME"
  cat "${'$'}xml"
  printf '\n'
  sed -n 's/.*<entry key="\([^"]*\)".*/\1/p' "${'$'}xml" | while IFS= read -r key; do
    case "${'$'}key" in
      '${'$'}USER_HOME${'$'}'*) path="${'$'}HOME${'$'}{key#'${'$'}USER_HOME${'$'}'}" ;;
      *) path=${'$'}key ;;
    esac
    [ -d "${'$'}path" ] || printf '@@GONE@@\t%s\n' "${'$'}key"
  done
done
exit 0"""

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
     * A script's record: the marker line's tab-separated fields, and the lines up to the next
     * record.
     */
    internal data class Record(val marker: String, val fields: List<String>, val body: String)

    private val RECORD_LINE = Regex("^(@@[A-Z]+@@)\t(.*)$")

    /** Splits a script's output into [Record]s; lines before the first marker are dropped. */
    internal fun records(result: ExecResult): List<Record> {
      checked(result, "Host script")
      val records = mutableListOf<Record>()
      var current: Pair<String, List<String>>? = null
      val body = StringBuilder()
      fun flush() {
        current?.let { (marker, fields) ->
          records += Record(marker, fields, body.toString().trim())
        }
        body.clear()
      }
      for (line in result.stdout.lines()) {
        val match = RECORD_LINE.find(line)
        if (match != null) {
          flush()
          current = match.groupValues[1] to match.groupValues[2].split('\t')
        } else if (current != null) body.appendLine(line)
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
