package intellijandroid.client.ssh

/**
 * What the SSH page needs from a host (docs/ssh-connections.md, "Design"). Two implementations:
 * `WorkerHost` calls the remote-dev-worker JetBrains Gateway already put on the host, `ShellHost`
 * runs POSIX shell commands that read the same files. Both answer with the values below, which
 * mirror the worker's JSON, so IDEate shows what Gateway shows. Blocking; call off the UI thread.
 */
interface RemoteHost {
  /** From `uname -sm`, e.g. `Linux aarch64`. */
  fun os(): HostOs

  /**
   * The RemoteDev cache folder, e.g. `/home/dev/.cache/JetBrains/RemoteDev` (Linux,
   * `$XDG_CACHE_HOME` first) or `/Users/dev/Library/Caches/JetBrains/RemoteDev` (macOS).
   */
  fun remoteDevDir(): String

  /**
   * The worker's `installed-ides`: entries of `dist/` and `userProvidedDist/` with their
   * product-info.json.
   */
  fun installedIdes(): List<InstalledIde>

  /**
   * The worker's `recent-projects-data` plus `active-projects`: every project the host's backends
   * served.
   */
  fun recentProjects(): List<RecentProject>

  /**
   * The worker's `host-status`: the backend for [projectPath] started from [idePath], or null when
   * none runs.
   */
  fun status(idePath: String, projectPath: String): HostStatus?

  /**
   * The worker's `host-launch`: starts a backend for [projectPath], detached; poll [status] for its
   * link.
   */
  fun launch(idePath: String, projectPath: String)

  /** Sub-folders of [path] for the folder browser, like `Termux.listFolders`. */
  fun listFolders(path: String): List<RemoteFolder>

  /** The worker's `remove-recent-project`: forgets [projectPath] on the host, for every client. */
  fun removeRecent(projectPath: String, productCode: String)
}

/**
 * `uname -sm`. Only [Os.LINUX] and [Os.MAC] hosts are supported (docs/ssh-connections.md,
 * "Decisions").
 */
data class HostOs(val os: Os, val architecture: String) {
  enum class Os {
    LINUX,
    MAC,
    OTHER,
  }

  companion object {
    /** Gateway's parsing: `Linux aarch64` is Linux, `Darwin arm64` is macOS. */
    fun parse(unameSm: String): HostOs {
      val words = unameSm.trim().lines().last().trim().split(Regex("\\s+"))
      val os =
        when (words.first().lowercase()) {
          "linux" -> Os.LINUX
          "darwin" -> Os.MAC
          else -> Os.OTHER
        }
      return HostOs(os, words.getOrElse(1) { "" })
    }
  }
}

/**
 * An IDE install on the host. [idePath] is where `bin/remote-dev-server.sh` is, e.g.
 * `/data/data/com.termux/files/home/intellij/idea-IU-263.7307.200` or
 * `/Users/dev/Applications/IntelliJ IDEA.app/Contents`; [linkPath] the `userProvidedDist` symlink
 * that pointed to it, if any.
 */
data class InstalledIde(
  val idePath: String,
  val linkPath: String?,
  /** From product-info.json: `IU`, `IC`, `PY`, … */
  val productCode: String,
  /** E.g. `263.7307.200`. */
  val buildNumber: String,
  /** E.g. `2026.3`. */
  val version: String,
  /** E.g. `IntelliJ IDEA`. */
  val name: String,
)

/**
 * One project a backend on the host served: a status with one project, like Gateway's
 * `ProjectPanelKt.fromStatus`.
 */
data class RecentProject(
  val projectPath: String,
  val idePath: String,
  /** Epoch ms of the project's `dateLastOpened`; Gateway drops statuses without it. */
  val dateLastOpened: Long,
  /** A backend for it runs now (its `active/<pid>` file's pid is alive). */
  val running: Boolean,
)

/** The parts of `UnattendedHostStatus` the client uses. */
data class HostStatus(
  val appPid: Long,
  val idePath: String,
  val unattendedMode: Boolean,
  val backendUnresponsive: Boolean,
  /** The backend's join link, e.g. `tcp://127.0.0.1:5990#jt=…&p=IU&fp=…&cb=263.7307.200&jb=…`. */
  val joinLink: String?,
  val projects: List<Project>,
) {
  data class Project(
    val projectPath: String,
    val joinLink: String?,
    val controllerConnected: Boolean,
  )

  /** Gateway's "good status" (HostRunMethods): ready to connect to. */
  val isReady: Boolean
    get() =
      unattendedMode && !backendUnresponsive && (!joinLink.isNullOrEmpty() || projects.isNotEmpty())
}

/**
 * A sub-folder; [isProject] when it holds `.idea`. [hasFolders] and [hasHiddenFolders]: whether it
 * holds sub-folders whose names don't or do start with `.`, so a folder browser can show an expand
 * arrow before listing it, like `Termux.Folder`.
 */
data class RemoteFolder(
  val name: String,
  val isProject: Boolean,
  val hasFolders: Boolean,
  val hasHiddenFolders: Boolean,
)
