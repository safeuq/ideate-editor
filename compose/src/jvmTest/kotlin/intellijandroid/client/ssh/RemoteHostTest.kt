package intellijandroid.client.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Parsing and the worker's calls, with replies shaped like the phone's worker gave on 2026-11-17.
 */
class RemoteHostTest {
  private val ide = "/data/data/com.termux/files/home/intellij/idea-IU-263.7307.200"
  private val link =
    "/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev/userProvidedDist/_data_data_com.termux_files_home_intellij_idea-IU-263.7307.200"
  private val productInfo =
    """{"name":"IntelliJ IDEA","version":"2026.3","buildNumber":"263.7307.200","productCode":"IU","dataDirectoryName":"IntelliJIdea2026.3"}"""

  /** A backend status file (`recent/<pid>_<hash>`), pretty-printed as the backend writes it. */
  private fun status(pid: Long, projects: String = "", joinLink: Boolean = true) =
    """
    {
      "appPid": $pid,
      "appVersion": "IU-263.7307.200",
      "unattendedMode": true,
      "backendUnresponsive": false,
      "idePath": "$ide",
      ${if (joinLink) "\"joinLink\": \"tcp://127.0.0.1:5990#jt=00000000-0000-0000-0000-000000000000&p=IU\"," else ""}
      "projects": [$projects]
    }
    """
      .trimIndent()

  private fun project(path: String, date: Long?, connected: Boolean = false) =
    """{"projectName":"${path.substringAfterLast('/')}","projectPath":"$path",""" +
      (date?.let { "\"dateLastOpened\":$it," } ?: "") +
      """"joinLink":"tcp://127.0.0.1:5990#jt=…","controllerConnected":$connected}"""

  private val smoke = "/data/data/com.termux/files/home/intellij/smoke"
  private val xtool = "/data/data/com.termux/files/home/Files/Projects/Xflow/xtool"

  @Test
  fun parsesAStatus() {
    val parsed = HostData.status(status(30916, project(xtool, 1794898860473, connected = true)))
    assertEquals(30916, parsed.appPid)
    assertEquals(ide, parsed.idePath)
    assertEquals(
      listOf(HostStatus.Project(xtool, "tcp://127.0.0.1:5990#jt=…", true)),
      parsed.projects,
    )
    assertTrue(parsed.isReady)
  }

  @Test
  fun aStatusFromTheStatusCommandWithoutABackendIsNotReady() {
    // host-status when nothing runs: the status command's own process, not unattended.
    val output =
      "2026-11-17 13:01:00 INFO starting\nSTATUS:\n" +
        """{"appPid":13234,"unattendedMode":false,"backendUnresponsive":false,"idePath":"$ide"}"""
    val parsed = HostData.status(requireNotNull(HostData.statusCommandOutput(output)))
    assertEquals(false, parsed.isReady)
    assertNull(HostData.statusCommandOutput("no status here"))
  }

  @Test
  fun recentProjectsFollowGateway() {
    val projects =
      HostData.recentProjects(
        listOf(
          status(24051, project(smoke, 1794814333171)),
          status(30916, project(xtool, 1794898860473)),
          // An older backend for the same project: listed once, with the newest date.
          status(20000, project(xtool, 1794800000000)),
          // No projects, two projects, no date: not listed.
          status(30916),
          status(1, project(smoke, 1) + "," + project(xtool, 2)),
          status(2, project("/no/date", null)),
        ),
        alivePids = setOf(20000),
      )
    assertEquals(
      listOf(
        RecentProject(xtool, ide, 1794898860473, running = true),
        RecentProject(smoke, ide, 1794814333171, running = false),
      ),
      projects,
    )
  }

  @Test
  fun installedIdesDropUnknownProducts() {
    assertEquals(
      InstalledIde(ide, link, "IU", "263.7307.200", "2026.3", "IntelliJ IDEA"),
      HostData.installedIde(ide, link, productInfo),
    )
    assertNull(HostData.installedIde(ide, null, productInfo.replace("\"IU\"", "\"XX\"")))
  }

  @Test
  fun workerRepliesSkipNoiseAndThrowErrors() {
    val noise = "motd line\n{\"type\":\"debug\",\"data\":\"x\"}\n"
    assertEquals(
      listOf("a", "b"),
      HostData.workerReply(noise + """{"type":"multi","data":["a","b"]}""").data,
    )
    assertEquals("one", HostData.workerReply("""{"type":"single","data":"one"}""").single)
    val error =
      assertFailsWith<RemoteHostException> {
        HostData.workerReply(
          """{"type":"error","errorCode":"CommandError","data":"open /nonexistent: no such file or directory"}""",
        )
      }
    assertTrue("open /nonexistent" in error.message.orEmpty())
    assertFailsWith<RemoteHostException> {
      HostData.workerReply("panic: Flag 'ide-path' not found.")
    }
  }

  @Test
  fun logsDirIsGatewaysPerProjectFolder() {
    assertEquals(
      "/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev-IU/_data_data_com.termux_files_home_intellij_smoke/log",
      HostData.logsDir("/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev", "IU", smoke),
    )
  }

  /** A host whose commands answer from [replies], matched by the worker command name. */
  private class FakeHost(val replies: Map<String, ExecResult>) {
    val commands = mutableListOf<String>()

    fun run(command: String): ExecResult {
      commands += command
      return replies.entries.firstOrNull { (key, _) -> key in command }?.value
        ?: ExecResult(127, "", "unexpected: $command")
    }
  }

  private fun json(value: String) = ExecResult(0, "REMOTE_EXEC_OUTPUT_MARKER_ gone\n$value\n", "")

  private fun multi(vararg values: String) =
    json(
      buildJsonObject {
        put("type", "multi")
        put("data", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
      }
        .toString(),
    )

  private fun single(value: String) =
    json(
      buildJsonObject {
        put("type", "single")
        put("data", value)
      }
        .toString(),
    )

  private val worker =
    "/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev/remote-dev-worker/remote-dev-worker_37fb36"
  private val remoteDev = "/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev"

  /** RemoteHosts' probe answer (one login shell): OS, RemoteDev folder, the worker that passed. */
  private fun probe(withWorker: Boolean) =
    ExecResult(
      0,
      "@@OS@@\tLinux aarch64\n@@DIR@@\t$remoteDev\n" +
        (if (withWorker) "@@WORKER@@\t$worker\n" else ""),
      "",
    )

  private val ideEntry = buildJsonObject {
    put("idePath", ide)
    put("linkPath", link)
    put("productInfo", productInfo)
  }

  private fun fakeWorker(withWorker: Boolean = true) =
    FakeHost(
      mapOf(
        "@@WORKER@@" to probe(withWorker),
        "installed-ides" to multi(ideEntry.toString()),
        "recent-projects-data" to
          single(
            buildJsonObject {
              put(
                "rawStatuses",
                buildJsonArray {
                  add(JsonPrimitive(status(24051, project(smoke, 1794814333171))))
                  add(JsonPrimitive(status(30916, project(xtool, 1794898860473))))
                },
              )
              put("alivePids", buildJsonArray { add(JsonPrimitive(30916)) })
              put("installedIdes", buildJsonArray { add(ideEntry) })
            }
              .toString(),
          ),
        "active-projects" to multi(),
        "host-status" to single(status(30916, project(xtool, 1794898860473))),
      ),
    )

  @Test
  fun picksTheWorkerInOneCallAndCallsItGatewaysWay() {
    val fake = fakeWorker()
    val host = RemoteHosts.forCommands(fake::run)
    assertTrue(host is WorkerHost, "$host")
    assertEquals(1, fake.commands.size, "${fake.commands}")
    assertEquals(HostOs(HostOs.Os.LINUX, "aarch64"), host.os())
    assertEquals(remoteDev, host.remoteDevDir())
    assertEquals(1, fake.commands.size, "os and folder come from the probe: ${fake.commands}")
    assertEquals(listOf("IU"), host.installedIdes().map { it.productCode })
    assertEquals(
      listOf(xtool to true, smoke to false),
      host.recentProjects().map { it.projectPath to it.running },
    )
    assertEquals(30916, host.status(ide, xtool)?.appPid)
    assertTrue("'$worker' host-status '--ide-path=$ide' '--project-path=$xtool'" in fake.commands)
  }

  @Test
  fun fallsBackToTheShellWithoutAWorkingWorker() {
    assertTrue(RemoteHosts.forCommands(fakeWorker(withWorker = false)::run) is ShellHost)
  }

  @Test
  fun projectsAndIdesIsOneWorkerCall() {
    val fake = fakeWorker()
    val host = WorkerHost(worker, fake::run)
    val both = host.projectsAndIdes()
    assertEquals(listOf("'$worker' recent-projects-data"), fake.commands)
    assertEquals(host.recentProjects(), both.recent)
    assertEquals(host.installedIdes(), both.ides)
  }

  @Test
  fun statusIsNullWhileTheBackendStarts() {
    val fake = FakeHost(fakeWorker().replies + ("host-status" to ExecResult(14, "", "")))
    assertNull(WorkerHost(worker, fake::run).status(ide, xtool))
  }
}
