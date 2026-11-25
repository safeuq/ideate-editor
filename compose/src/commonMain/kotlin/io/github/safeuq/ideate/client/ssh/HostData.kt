package io.github.safeuq.ideate.client.ssh

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** A host command failed: the worker's `error` reply, or a shell command's exit code and stderr. */
class RemoteHostException(message: String) : RuntimeException(message)

/**
 * Parsing shared by [WorkerHost] and [ShellHost]: both read the files the backend writes
 * (docs/ssh-connections.md, "Where the data lives on the host"), so their answers are the same.
 */
internal object HostData {
  /**
   * Product codes of the JetBrains IDEs that run as a remote-dev backend, with their names; Gateway
   * shows only these. Inferred from Gateway's product list. The name comes from here, not from
   * product-info.json: the worker's `installed-ides` sends a shortened product-info without `name`.
   */
  val PRODUCT_NAMES =
    mapOf(
      "IU" to "IntelliJ IDEA",
      "IC" to "IntelliJ IDEA Community",
      "PY" to "PyCharm",
      "PC" to "PyCharm Community",
      "WS" to "WebStorm",
      "PS" to "PhpStorm",
      "GO" to "GoLand",
      "RD" to "Rider",
      "CL" to "CLion",
      "RM" to "RubyMine",
      "DB" to "DataGrip",
      "DS" to "DataSpell",
      "RR" to "RustRover",
      "QA" to "Aqua",
    )

  /** `product-info.json` of an install, e.g. `{"name":"IntelliJ IDEA","productCode":"IU",…}`. */
  fun installedIde(idePath: String, linkPath: String?, productInfo: String): InstalledIde? {
    val info = Json.parseToJsonElement(productInfo).jsonObject
    val productCode = info.string("productCode") ?: return null
    val buildNumber = info.string("buildNumber") ?: return null
    val name = PRODUCT_NAMES[productCode] ?: return null
    return InstalledIde(
      idePath = idePath,
      linkPath = linkPath?.takeIf { it.isNotEmpty() },
      productCode = productCode,
      buildNumber = buildNumber,
      version = info.string("version").orEmpty(),
      name = name,
    )
  }

  /** An `UnattendedHostStatus`: a file in `active/` or `recent/`, or `host-status`'s answer. */
  fun status(json: String): HostStatus {
    val status = Json.parseToJsonElement(json).jsonObject
    return HostStatus(
      appPid =
        requireNotNull(status["appPid"]?.jsonPrimitive?.longOrNull) { "Status without appPid" },
      idePath = status.string("idePath").orEmpty(),
      unattendedMode = status.boolean("unattendedMode"),
      backendUnresponsive = status.boolean("backendUnresponsive"),
      joinLink = status.string("joinLink"),
      projects =
        status["projects"]?.jsonArray.orEmpty().map { element ->
          val project = element.jsonObject
          HostStatus.Project(
            projectPath = requireNotNull(project.string("projectPath")) { "Project without path" },
            joinLink = project.string("joinLink"),
            controllerConnected = project.boolean("controllerConnected"),
          )
        },
    )
  }

  /**
   * Gateway's recent projects (`ProjectPanelKt.fromStatus`): only statuses with exactly one
   * project, dated by its `dateLastOpened`, and dropped without one. A project several backends
   * served is listed once, with its latest date, running when any of their pids is alive. Newest
   * first.
   */
  fun recentProjects(statuses: List<String>, alivePids: Set<Long>): List<RecentProject> =
    statuses
      .mapNotNull { text ->
        val status = Json.parseToJsonElement(text).jsonObject
        val pid = status["appPid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val project = status["projects"]?.jsonArray?.singleOrNull()?.jsonObject
        val path = project?.string("projectPath") ?: return@mapNotNull null
        val date = project["dateLastOpened"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        RecentProject(path, status.string("idePath").orEmpty(), date, pid in alivePids)
      }
      .groupBy { it.projectPath to it.idePath }
      .map { (_, same) ->
        same.maxBy { it.dateLastOpened }.copy(running = same.any { it.running })
      }
      .sortedByDescending { it.dateLastOpened }

  /**
   * The desktop IDEs' recent projects from `recentProjects.xml` files, keyed by settings folder
   * name, e.g. `IntelliJIdea2026.3`. Only the newest folder per product counts
   * (`IntelliJIdea2026.3` over `IntelliJIdea2026.2`). An entry like `<entry
   * key="$USER_HOME$/work/xtool"><value><RecentProjectMetaInfo><option name="activationTimestamp"
   * value="1745454457133"/><option name="productionCode" value="IU"/>` becomes
   * `/home/dev/work/xtool` ([home] for `$USER_HOME$`), dated by `activationTimestamp`, else
   * `projectOpenTimestamp`, opened with the newest installed IDE of that product code. Dropped:
   * entries whose IDE isn't installed, without a date, or whose key is in [missing] (the folder is
   * gone).
   */
  fun ideRecentProjects(
    files: Map<String, String>,
    home: String,
    ides: List<InstalledIde>,
    missing: Set<String>,
  ): List<RecentProject> {
    val newestFolders =
      files.keys
        .mapNotNull { folder -> SETTINGS_FOLDER.matchEntire(folder)?.let { folder to it } }
        .groupBy { (_, match) -> match.groupValues[1] }
        .map { (_, folders) ->
          folders.maxWith(compareBy(VERSION_ORDER) { (_, match) -> match.groupValues[2] }).first
        }
    // The shell's keys are the raw attribute text; the parser's are decoded.
    val missingKeys = missing.map(::decodeXmlEntities).toSet()
    return newestFolders.flatMap { folder ->
      // Deliberately soft: the file belongs to the user's IDE, and failing here would hide the
      // host's backend history and every other IDE's projects too. Only this file is skipped.
      val entries = runCatching {
        recentProjectEntries(files.getValue(folder))
      }
        .getOrElse { emptyList() }
      entries.mapNotNull { entry ->
        if (entry.key in missingKeys) return@mapNotNull null
        val ide =
          ides
            .filter { it.productCode == entry.productCode }
            .maxWithOrNull(compareBy(VERSION_ORDER) { it.buildNumber }) ?: return@mapNotNull null
        RecentProject(
          entry.key.replace(USER_HOME, home),
          ide.idePath,
          entry.date ?: return@mapNotNull null,
          running = false,
        )
      }
    }
  }

  /** The five XML entities; `&amp;` last, so `&amp;lt;` stays `&lt;`. */
  private fun decodeXmlEntities(text: String) =
    text
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&amp;", "&")

  /** `IntelliJIdea2026.3`: product `IntelliJIdea`, version `2026.3`. */
  private val SETTINGS_FOLDER = Regex("(.*?)(\\d+(?:\\.\\d+)*)")
  private const val USER_HOME = "\$USER_HOME\$"

  /** Dotted numbers compared part by part: `263.7307.200` after `263.71.1`. */
  private val VERSION_ORDER =
    Comparator<String> { first, second ->
      val firstParts = first.split('.').map { it.toIntOrNull() ?: 0 }
      val secondParts = second.split('.').map { it.toIntOrNull() ?: 0 }
      (0 until maxOf(firstParts.size, secondParts.size))
        .map { index ->
          firstParts.getOrElse(index) { 0 }.compareTo(secondParts.getOrElse(index) { 0 })
        }
        .firstOrNull { it != 0 } ?: 0
    }

  private data class IdeEntry(val key: String, val productCode: String?, val date: Long?)

  private fun recentProjectEntries(xml: String): List<IdeEntry> {
    val factory = DocumentBuilderFactory.newInstance()
    // No DOCTYPE, so no external entities. Android's parser doesn't know this feature and never
    // loads external entities, so it is skipped there.
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val entries = document.getElementsByTagName("entry")
    return (0 until entries.length).mapNotNull { index ->
      val entry = entries.item(index) as Element
      val info =
        entry.getElementsByTagName("RecentProjectMetaInfo").item(0) as? Element
          ?: return@mapNotNull null
      val optionNodes = info.getElementsByTagName("option")
      val options =
        (0 until optionNodes.length)
          .map { optionNodes.item(it) as Element }
          .associate { it.getAttribute("name") to it.getAttribute("value") }
      IdeEntry(
        key = entry.getAttribute("key"),
        productCode =
          options["productionCode"]?.takeIf { it.isNotEmpty() }
            ?: options["build"]?.substringBefore('-', ""),
        date =
          options["activationTimestamp"]?.toLongOrNull()
            ?: options["projectOpenTimestamp"]?.toLongOrNull(),
      )
    }
  }

  /**
   * One row per project path, from the backends' history ([backend]) and the IDEs' ([ide]): a
   * running backend wins, else the latest date. Newest first.
   */
  fun mergedRecent(backend: List<RecentProject>, ide: List<RecentProject>): List<RecentProject> =
    (backend + ide)
      .groupBy { it.projectPath }
      .map { (_, same) -> same.firstOrNull { it.running } ?: same.maxBy { it.dateLastOpened } }
      .sortedByDescending { it.dateLastOpened }

  /**
   * The worker's answer: the last JSON line of type `single`, `multi` or `error` (lines before it
   * and `debug` lines are noise). An `error` throws.
   */
  fun workerReply(stdout: String): WorkerReply {
    val reply =
      stdout
        .lines()
        .filter { it.startsWith("{") }
        .map { Json.parseToJsonElement(it).jsonObject }
        .lastOrNull { it.string("type") in setOf("single", "multi", "error") }
        ?: throw RemoteHostException("No reply from the worker: ${stdout.take(300)}")
    val data: JsonElement? = reply["data"]
    return when (reply.string("type")) {
      "error" ->
        throw RemoteHostException(
          "Worker error ${reply.string("errorCode")}: ${(data as? JsonPrimitive)?.content}",
        )
      "multi" -> WorkerReply(data?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
      else -> WorkerReply(listOfNotNull((data as? JsonPrimitive)?.content))
    }
  }

  /** The worker's `data`: one string for `single`, several for `multi`. */
  data class WorkerReply(val data: List<String>) {
    val single: String
      get() = data.singleOrNull() ?: throw RemoteHostException("Expected one value, got $data")
  }

  /** `remote-dev-server.sh status` prints its logs, `STATUS:`, then the status as JSON. */
  fun statusCommandOutput(stdout: String): String? {
    val afterMarker = stdout.substringAfter("STATUS:", missingDelimiterValue = "")
    val start = afterMarker.indexOf('{')
    return if (start < 0) null else afterMarker.substring(start).trim()
  }

  /**
   * Gateway's per-project log folder (the worker's `get-logs-dir`): `<cache>/JetBrains/
   * RemoteDev-<code>/<project path with '/' as '_'>/log`, e.g.
   * `/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev-IU/_data_data_com.termux_files_home_intellij_smoke/log`.
   */
  fun logsDir(remoteDevDir: String, productCode: String, projectPath: String) =
    "$remoteDevDir-$productCode/${projectPath.replace('/', '_')}/log"

  /** Written to `stdout.log` and `stderr.log` before each launch: the logs are appended to. */
  const val LAUNCH_START = "@@IDEATE_LAUNCH@@"
  /** `@@IDEATE_EXIT@@ <code>` in `stdout.log` once `remote-dev-server.sh run` has returned. */
  const val LAUNCH_EXIT = "@@IDEATE_EXIT@@"
  /** Between `stdout.log` and `stderr.log` in [launchFailure]'s input. */
  const val STDERR_START = "@@IDEATE_STDERR@@"
  /**
   * What IntelliJ's launcher prints when an IDE with the same settings folder is already open: it
   * hands `run` to that IDE, which isn't a backend, and exits.
   */
  private const val SETTINGS_IN_USE = "requires Remote Development backend"

  /**
   * [logs]: the end of `stdout.log`, [STDERR_START], the end of `stderr.log`. Only lines after the
   * last [LAUNCH_START] count; if the start line is beyond the end that was read, every line is
   * from this launch. Null while the launch command still runs and printed nothing that shows an
   * end.
   */
  fun launchFailure(logs: String): LaunchFailure? {
    fun sinceLaunch(text: String) =
      text.lines().let { lines -> lines.drop(lines.lastIndexOf(LAUNCH_START) + 1) }
    val stdout = sinceLaunch(logs.substringBefore(STDERR_START))
    val stderr = sinceLaunch(logs.substringAfter(STDERR_START, ""))
    val exitCode =
      stdout.lastOrNull { it.startsWith(LAUNCH_EXIT) }?.substringAfter(' ')?.trim()?.toIntOrNull()
    val settingsInUse = (stdout + stderr).any { SETTINGS_IN_USE in it }
    if (exitCode == null && !settingsInUse) return null
    val output =
      (stdout + stderr)
        .filter { it.isNotBlank() && !it.startsWith(LAUNCH_EXIT) }
        .takeLast(5)
        .joinToString("\n")
    return LaunchFailure(exitCode, settingsInUse, output)
  }

  private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.boolean(key: String) =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
