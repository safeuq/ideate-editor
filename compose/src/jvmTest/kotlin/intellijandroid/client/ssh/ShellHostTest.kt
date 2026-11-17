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
          run) echo "run ${'$'}2" > "${'$'}HOME/launched" ;;
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
