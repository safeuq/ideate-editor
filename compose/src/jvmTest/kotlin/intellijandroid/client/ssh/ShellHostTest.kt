package intellijandroid.client.ssh

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ShellHost's real scripts, run with this computer's `/bin/sh` in a made-up home folder: a
 * RemoteDev folder with one registered IDE (a fake `remote-dev-server.sh`) and status files.
 */
class ShellHostTest {
  private val home = Files.createTempDirectory("shell-host").toFile()

  @AfterTest fun cleanUp() = home.deleteRecursively().let {}

  /** Runs [command] as `SshSession.execInLoginShell` would, with [home] as `$HOME`. */
  private fun run(command: String): ExecResult {
    val process =
      ProcessBuilder("/bin/sh", "-c", command)
        .apply {
          environment()["HOME"] = home.path
          environment().remove("XDG_CACHE_HOME")
        }
        .start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    return ExecResult(process.waitFor(), stdout, stderr)
  }

  private val host = ShellHost(::run)

  /** An IDE install whose `remote-dev-server.sh` answers `status` and records `run`. */
  private fun installIde(): File {
    val ide = home.resolve("intellij/idea-IU-263.7307.200").apply { mkdirs() }
    ide
      .resolve("product-info.json")
      .writeText(
        """{"name":"IntelliJ IDEA","version":"2026.3","buildNumber":"263.7307.200","productCode":"IU"}""",
      )
    ide.resolve("bin").mkdirs()
    ide.resolve("bin/remote-dev-server.sh").apply {
      writeText(
        """
        #!/bin/sh
        case "${'$'}1" in
          status) echo "starting…"; echo "STATUS:"; printf '{"appPid": 42, "unattendedMode": true, "backendUnresponsive": false, "idePath": "%s", "joinLink": "tcp://127.0.0.1:5990#jt=x", "projects": []}\n' "${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd -P)" ;;
          run)
            case "${'$'}2" in
              */in-use) echo "The command 'remoteDevHost' requires Remote Development backend"; exit 1 ;;
              */crash) echo "Starting…"; echo "Unrecognized VM option" >&2; exit 1 ;;
              */slow) sleep 3 ;;
              *) echo "run ${'$'}2" > "${'$'}HOME/launched" ;;
            esac ;;
        esac
        """
          .trimIndent(),
      )
      setExecutable(true)
    }
    return ide.canonicalFile
  }

  @Test
  fun readsTheRemoteDevFolder() {
    val remoteDev = File(host.remoteDevDir())
    val expected =
      if (host.os().os == HostOs.Os.MAC) home.resolve("Library/Caches/JetBrains/RemoteDev")
      else home.resolve(".cache/JetBrains/RemoteDev")
    assertEquals(expected.path, remoteDev.path)

    val ide = installIde()
    val link = remoteDev.resolve("userProvidedDist/_intellij").apply { parentFile.mkdirs() }
    Files.createSymbolicLink(link.toPath(), ide.toPath())
    // Not an IDE (no bin/remote-dev-server.sh): skipped.
    remoteDev.resolve("dist/broken").mkdirs()
    assertEquals(
      listOf(InstalledIde(ide.path, link.path, "IU", "263.7307.200", "2026.3", "IntelliJ IDEA")),
      host.installedIdes(),
    )

    val ownPid = ProcessHandle.current().pid()
    remoteDev.resolve("recent").mkdirs()
    remoteDev.resolve("active").mkdirs()
    remoteDev
      .resolve("recent/999999_-1431083394")
      .writeText(statusFile(999999, "/work/xtool", 2000))
    remoteDev.resolve("recent/999998").writeText(statusFile(999998, null, 0))
    remoteDev.resolve("active/$ownPid").writeText(statusFile(ownPid, "/work/smoke", 1000))
    assertEquals(
      listOf(
        RecentProject("/work/xtool", ide.path, 2000, running = false),
        RecentProject("/work/smoke", ide.path, 1000, running = true),
      ),
      host.recentProjects(),
    )

    // The SSH page's refresh: both in one script, the same answers.
    assertEquals(HostProjects(host.recentProjects(), host.installedIdes()), host.projectsAndIdes())

    host.removeRecent("/work/xtool", "IU")
    assertEquals(listOf("/work/smoke"), host.recentProjects().map { it.projectPath })
  }

  @Test
  fun statusAndLaunchUseTheIdesScript() {
    val ide = installIde()
    val link =
      File(host.remoteDevDir()).resolve("userProvidedDist/_intellij").apply { parentFile.mkdirs() }
    Files.createSymbolicLink(link.toPath(), ide.toPath())

    val status = host.status(ide.path, "/work/smoke")
    assertEquals(42, status?.appPid)
    assertTrue(status?.isReady == true)

    host.launch(ide.path, "/work/smoke")
    val launched = home.resolve("launched")
    repeat(50) { if (!launched.isFile) Thread.sleep(100) }
    assertEquals("run /work/smoke\n", launched.readText())
    assertTrue(File(HostData.logsDir(host.remoteDevDir(), "IU", "/work/smoke")).isDirectory)
  }

  @Test
  fun aLaunchThatEndsEarlyIsReported() {
    val ide = installIde()
    val link =
      File(host.remoteDevDir()).resolve("userProvidedDist/_intellij").apply { parentFile.mkdirs() }
    Files.createSymbolicLink(link.toPath(), ide.toPath())

    fun failureOf(project: String): LaunchFailure? {
      val logs = host.launch(ide.path, project)
      // Until the exit line is in: the in-use message comes a moment before it.
      repeat(100) {
        host.launchFailure(logs)?.takeIf { it.exitCode != null }?.let {
          return it
        } ?: Thread.sleep(100)
      }
      return null
    }
    // The desktop IDE is open with the same settings: it takes `run`, and the launcher exits 1
    // (IntelliJ 2026.3 on macOS, 2026-11-18).
    val inUse = failureOf("/work/in-use")
    assertEquals(true, inUse?.settingsInUse)
    assertEquals(1, inUse?.exitCode)
    val crash = failureOf("/work/crash")
    assertEquals(LaunchFailure(1, false, "Starting…\nUnrecognized VM option"), crash)
    // Launching again appends to the same logs: only the newest launch counts.
    assertEquals(crash, failureOf("/work/crash"))

    // Still running: no failure, and launch returned without waiting for the backend.
    val started = System.nanoTime()
    val slow = host.launch(ide.path, "/work/slow")
    assertTrue(System.nanoTime() - started < 2_000_000_000L, "launch waited for the backend")
    Thread.sleep(300)
    assertEquals(null, host.launchFailure(slow))
  }

  @Test
  fun launchFailureReadsOnlyTheNewestLaunch() {
    val start = HostData.LAUNCH_START
    val exit = HostData.LAUNCH_EXIT
    val split = HostData.STDERR_START
    // An earlier launch's end before the newest start line: still starting.
    assertEquals(
      null,
      HostData.launchFailure("$start\nold\n$exit 1\n$start\nbanner\n$split\n$start\n"),
    )
    // The start line was beyond the read end of a long log: every line is this launch's.
    assertEquals(
      LaunchFailure(143, false, "line 199"),
      HostData.launchFailure("line 199\n$exit 143\n$split\n"),
    )
  }

  @Test
  fun theProbePicksAWorkerThatPassesItsCheck() {
    val workers = File(host.remoteDevDir()).resolve("remote-dev-worker").apply { mkdirs() }
    assertTrue(RemoteHosts.forCommands(::run) is ShellHost)
    // Like Gateway's: HELLO_STDOUT and the test error on stdout, exit 1.
    val worker =
      workers.resolve("remote-dev-worker_0123abcd").apply {
        writeText(
          "#!/bin/sh\necho HELLO_STDOUT\necho HELLO_STDERR >&2\n" +
            "echo '{\"type\":\"error\",\"errorCode\":\"CommandError\",\"data\":\"ERROR_TEST Nothing to worry about\"}'\nexit 1\n",
        )
      }
    assertTrue(RemoteHosts.forCommands(::run) is ShellHost, "not executable yet")
    worker.setExecutable(true)
    val picked = RemoteHosts.forCommands(::run)
    assertTrue(picked is WorkerHost, "$picked")
    assertEquals(host.os(), picked.os())
    assertEquals(host.remoteDevDir(), picked.remoteDevDir())
  }

  @Test
  fun listsFolders() {
    home.resolve("projects/xtool/.idea").mkdirs()
    home.resolve("projects/empty").mkdirs()
    assertEquals(
      listOf(RemoteFolder("empty", false, false, false), RemoteFolder("xtool", true, false, true)),
      host.listFolders(home.resolve("projects").path),
    )
  }

  private fun statusFile(pid: Long, project: String?, date: Long) =
    """{"appPid": $pid, "unattendedMode": true, "backendUnresponsive": false, "idePath": "${installIdePath()}", "appVersion": "IU-263.7307.200", "projects": [""" +
      (project?.let { """{"projectPath": "$it", "dateLastOpened": $date}""" } ?: "") +
      "]}"

  private fun installIdePath() = home.resolve("intellij/idea-IU-263.7307.200").canonicalPath
}
