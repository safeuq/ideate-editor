package intellijandroid.client.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.ssh.HostKeyCheck
import intellijandroid.client.ssh.IdeateKey
import intellijandroid.client.ssh.InstalledIde
import intellijandroid.client.ssh.KnownHosts
import intellijandroid.client.ssh.LoginMethod
import intellijandroid.client.ssh.OpenSshFiles
import intellijandroid.client.ssh.RecentProject
import intellijandroid.client.ssh.RemoteFolder
import intellijandroid.client.ssh.RemoteHost
import intellijandroid.client.ssh.SecretNames
import intellijandroid.client.ssh.SecretStore
import intellijandroid.client.ssh.SshHostEntry
import intellijandroid.client.ssh.SshHosts
import intellijandroid.client.ssh.SshLogins
import intellijandroid.client.ssh.SshSession
import intellijandroid.client.ui.dialog.MessageKind
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.schmizz.sshj.userauth.UserAuthException

/** What the platform gives the SSH page (docs/ssh-connections.md, "Design"). */
class SshSetup(
  /** The saved hosts. */
  val hosts: StoredText,
  /** IDEate's known_hosts. */
  val knownHosts: StoredText,
  val secrets: SecretStore,
  /** Desktop only: `~/.ssh`. Null on Android, where OPEN_SSH isn't offered. */
  val openSsh: OpenSshFiles?,
  /** More trusted known_hosts text (the desktop's `~/.ssh/known_hosts`). */
  val alsoKnownHosts: () -> String? = { null },
  /** Picks the worker or the shell for a connected host (RemoteHosts.forSession). */
  val remoteHostFor: (SshSession) -> RemoteHost,
  /** Ends IDEate's public key line, like ssh-keygen's user@host: `Pixel 3a`, `safeuq-mac`. */
  val deviceName: String,
)

/** A host's line in the SSH page, like Gateway's host panel. */
data class SshHostView(
  val entry: SshHostEntry,
  val phase: Phase,
  /** Why the host is unreachable or needs a log-in, e.g. `Connection refused`. */
  val error: String? = null,
  val projects: List<RecentProject> = emptyList(),
  val ides: List<InstalledIde> = emptyList(),
) {
  enum class Phase {
    /** Gateway's "Connecting…" / "Getting projects…". */
    CONNECTING,
    READY,
    /** Gateway's red "Host unreachable", with Reconnect. */
    UNREACHABLE,
    /** Gateway's "Authenticate…": the password or passphrase isn't saved, or was refused. */
    AUTHENTICATE,
  }
}

/** The new-connection form, Gateway's "Connect to SSH". */
data class SshForm(
  val user: String = "",
  val host: String = "",
  val port: String = "22",
  val method: LoginMethod = LoginMethod.GENERATED_KEY,
  /** KEY_FILE: the pasted private key. */
  val privateKey: String = "",
  val passphrase: String = "",
  val password: String = "",
  val savePassword: Boolean = true,
)

sealed interface SshDialog {
  /** Gateway's "Connect to SSH". [publicKey]: IDEate's key to show for GENERATED_KEY. */
  data class NewConnection(
    val form: SshForm,
    val publicKey: String?,
    val checking: Boolean = false,
    val error: String? = null,
  ) : SshDialog

  /** Gateway's "Choose IDE and Project". */
  data class ChooseIde(
    val host: SshHostEntry,
    val ides: List<InstalledIde>,
    val ide: InstalledIde?,
    val projectPath: String,
    /** The host's `$HOME` and the folder browser's top folder (`/`, or Termux's files folder). */
    val home: String,
    val root: String,
    val error: String? = null,
  ) : SshDialog

  /** Gateway's "Authentication": [prompt] is `Password:` or `Key passphrase for the file:`. */
  data class Authenticate(val host: SshHostEntry, val prompt: String, val error: String? = null) :
    SshDialog
}

/** Gateway's deploy progress: e.g. "Starting the remote IDE Backend", detail the project. */
data class SshProgress(val title: String, val detail: String)

data class SshPageState(
  val hosts: List<SshHostView> = emptyList(),
  val dialog: SshDialog? = null,
  val progress: SshProgress? = null,
  /** Host key questions, worded like IntelliJ's SshBundle. */
  val question: Question? = null,
  val message: String? = null,
  val offersOpenSsh: Boolean = false,
)

class SshActions(
  /** Gateway's polling: every call refreshes reachable hosts, [includeUnreachable] every third. */
  val poll: (includeUnreachable: Boolean) -> Unit,
  val authenticateHost: (SshHostEntry) -> Unit,
  val reconnect: (SshHostEntry) -> Unit,
  val newConnection: () -> Unit,
  val newProjectOn: (SshHostEntry) -> Unit,
  val formChanged: (SshForm) -> Unit,
  val checkAndContinue: () -> Unit,
  val ideChosen: (InstalledIde) -> Unit,
  val projectPathChanged: (String) -> Unit,
  val startAndConnect: () -> Unit,
  val authenticate: (String) -> Unit,
  val cancelDialog: () -> Unit,
  val openProject: (SshHostEntry, RecentProject) -> Unit,
  val removeRecent: (SshHostEntry, RecentProject) -> Unit,
  val removeHost: (SshHostEntry) -> Unit,
  val listFolders: (SshHostEntry) -> (String, (Result<List<Termux.Folder>>) -> Unit) -> Unit,
  val answerQuestion: () -> Unit,
  val cancelQuestion: () -> Unit,
  val cancelProgress: () -> Unit,
)

/**
 * The SSH page's logic: saved hosts, their sessions and projects, the new-connection and
 * open-project flows (docs/ssh-connections.md). SSH work runs on a background thread; state changes
 * on the main thread. [connect] hands the forwarded join link to [ConnectController].
 */
class SshController(
  private val setup: SshSetup,
  private val connect: (link: String, projectPath: String) -> Unit,
) {
  private val deviceName = setup.deviceName
  var state by mutableStateOf(SshPageState(offersOpenSsh = setup.openSsh != null))
    private set

  private val hosts = SshHosts(setup.hosts)
  private val knownHosts = KnownHosts(setup.knownHosts, setup.alsoKnownHosts)
  private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val work = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "ssh").apply { isDaemon = true }
  }
  private val sessions = ConcurrentHashMap<String, SshSession>()
  private val remotes = ConcurrentHashMap<String, RemoteHost>()
  private val forwards = mutableListOf<SshSession.LocalForward>()
  @Volatile private var pendingAnswer: CompletableFuture<Boolean>? = null
  /** The open-project run the progress popup's Cancel stops. */
  @Volatile private var openRun = 0

  val actions =
    SshActions(
      poll = { includeUnreachable -> if (includeUnreachable) refresh() else refreshReachable() },
      authenticateHost = ::askLogin,
      reconnect = { entry -> refreshHost(entry, forceReconnect = true) },
      newConnection = { showNewConnection(SshForm()) },
      newProjectOn = ::chooseIde,
      formChanged = { form ->
        updateDialog<SshDialog.NewConnection> { it.copy(form = form, error = null) }
      },
      checkAndContinue = ::checkAndContinue,
      ideChosen = { ide -> updateDialog<SshDialog.ChooseIde> { it.copy(ide = ide, error = null) } },
      projectPathChanged = { path ->
        updateDialog<SshDialog.ChooseIde> { it.copy(projectPath = path, error = null) }
      },
      startAndConnect = ::startAndConnect,
      authenticate = ::authenticate,
      cancelDialog = { state = state.copy(dialog = null) },
      openProject = { entry, project -> openProject(entry, project.idePath, project.projectPath) },
      removeRecent = ::removeRecent,
      removeHost = ::removeHost,
      listFolders = { entry -> { path, onResult -> listFolders(entry, path, onResult) } },
      answerQuestion = { answer(true) },
      cancelQuestion = { answer(false) },
      cancelProgress = {
        openRun++
        state = state.copy(progress = null, message = "Cancelled")
      },
    )

  init {
    state =
      state.copy(hosts = hosts.entries().map { SshHostView(it, SshHostView.Phase.CONNECTING) })
  }

  /**
   * Gateway refreshes every 10 s while the page shows (30 s for unreachable hosts); see SshPage.
   */
  fun refresh() = hosts.entries().forEach { refreshHost(it, forceReconnect = false) }

  /** Unreachable hosts are retried less often, like Gateway's 30 s. */
  fun refreshReachable() =
    state.hosts
      .filter { it.phase != SshHostView.Phase.UNREACHABLE }
      .forEach { refreshHost(it.entry, false) }

  /** Stops forwarding and closes the sessions: when the activity or window goes away. */
  fun close() {
    forwards.forEach { runCatching { it.close() } }
    sessions.values.forEach { runCatching { it.close() } }
    work.shutdownNow()
    main.cancel()
  }

  // ---- Hosts and their projects ----

  private fun refreshHost(entry: SshHostEntry, forceReconnect: Boolean, typed: String? = null) {
    if (forceReconnect) sessions.remove(entry.id)?.let { runCatching { it.close() } }
    setHost(entry) {
      it.copy(
        phase = if (it.phase == SshHostView.Phase.READY) it.phase else SshHostView.Phase.CONNECTING,
      )
    }
    work.execute {
      val result = runCatching {
        // One login shell per refresh (RemoteHost.projectsAndIdes).
        val both = remoteFor(entry, typed).projectsAndIdes()
        both.recent to both.ides
      }
      main.launch {
        result.fold(
          onSuccess = { (projects, ides) ->
            setHost(entry) {
              SshHostView(
                entry,
                SshHostView.Phase.READY,
                null,
                projects.sortedByDescending { p -> p.dateLastOpened },
                ides,
              )
            }
          },
          onFailure = { failure ->
            setHost(entry) { it.copy(phase = phaseOf(failure), error = failure.message) }
          },
        )
      }
    }
  }

  private fun phaseOf(failure: Throwable) =
    if (failure is SshLogins.MissingSecret || failure is UserAuthException)
      SshHostView.Phase.AUTHENTICATE
    else SshHostView.Phase.UNREACHABLE

  /**
   * The host's session and RemoteHost, connecting and logging in when needed. Background thread.
   */
  private fun remoteFor(entry: SshHostEntry, typed: String? = null): RemoteHost {
    val existing = sessions[entry.id]
    if (existing != null && existing.isConnected)
      remotes[entry.id]?.let {
        return it
      }
    val login = SshLogins.resolve(entry, setup.secrets, setup.openSsh, deviceName, typed)
    val session = SshSession.open(login.host, login.port, login.user, login.logins, hostKeyCheck())
    sessions.put(entry.id, session)?.let { runCatching { it.close() } }
    return setup.remoteHostFor(session).also { remotes[entry.id] = it }
  }

  private fun setHost(entry: SshHostEntry, change: (SshHostView) -> SshHostView) {
    val current =
      state.hosts.firstOrNull { it.entry.id == entry.id }
        ?: SshHostView(entry, SshHostView.Phase.CONNECTING)
    val updated = change(current)
    state =
      state.copy(
        hosts =
          if (state.hosts.any { it.entry.id == entry.id })
            state.hosts.map { if (it.entry.id == entry.id) updated else it }
          else state.hosts + updated,
      )
  }

  private fun removeHost(entry: SshHostEntry) {
    hosts.remove(entry, setup.secrets)
    sessions.remove(entry.id)?.let { runCatching { it.close() } }
    remotes.remove(entry.id)
    state = state.copy(hosts = state.hosts.filter { it.entry.id != entry.id })
  }

  private fun removeRecent(entry: SshHostEntry, project: RecentProject) {
    work.execute {
      val result = runCatching {
        val remote = remoteFor(entry)
        val productCode =
          remote.installedIdes().firstOrNull { it.idePath == project.idePath }?.productCode ?: "IU"
        remote.removeRecent(project.projectPath, productCode)
      }
      main.launch {
        result.exceptionOrNull()?.let {
          state = state.copy(message = "Couldn't remove ${project.projectPath}: ${it.message}")
        }
        refreshHost(entry, forceReconnect = false)
      }
    }
  }

  // ---- Host keys and log-in ----

  /**
   * IntelliJ's host key questions (SshBundle), answered in the page; the SSH thread waits for them.
   * Worded as Gateway's, e.g. "… ssh-ed25519 key fingerprint is c0:ec:02:…:1b:5a." with OK.
   */
  private fun hostKeyCheck() = HostKeyCheck { host, port, key ->
    val type = SshSession.keyType(key)
    val fingerprint = SshSession.fingerprint(key)
    val shown = KnownHosts.pattern(host, port)
    when (knownHosts.check(host, port, key)) {
      KnownHosts.Verdict.KNOWN -> true
      KnownHosts.Verdict.UNKNOWN ->
        ask(
            "The authenticity of host '$shown' cannot be established. $type key fingerprint is $fingerprint.\n\nAre you sure you want to continue connecting?",
            "OK",
          )
          .also { trusted -> if (trusted) knownHosts.trust(host, port, key) }
      KnownHosts.Verdict.CHANGED ->
        ask(
            "Warning: remote host identification has changed! IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY! Someone could be eavesdropping on you right now (man-in-the-middle attack)! It is also possible that the host key has just been changed. The fingerprint for the $type key sent by the remote host is $fingerprint.\n\nDo you want to update the key in IDEate's known hosts and resume connecting?",
            "Update and Connect",
            MessageKind.WARNING,
          )
          .also { trusted ->
            if (trusted) {
              knownHosts.forget(host, port)
              knownHosts.trust(host, port, key)
            }
          }
    }
  }

  /**
   * Shows [text] with [confirm] and Cancel; blocks the SSH thread until answered (Gateway: 30 s).
   */
  private fun ask(
    text: String,
    confirm: String,
    kind: MessageKind = MessageKind.QUESTION,
  ): Boolean {
    val answer = CompletableFuture<Boolean>()
    pendingAnswer = answer
    main.launch { state = state.copy(question = Question(text, confirm, kind) { answer(true) }) }
    return runCatching { answer.get(QUESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
      .getOrDefault(false)
      .also {
        main.launch { state = state.copy(question = null) }
      }
  }

  private fun answer(value: Boolean) {
    pendingAnswer?.complete(value)
    pendingAnswer = null
    state = state.copy(question = null)
  }

  /** Gateway's "Authenticate…": asks for what isn't saved, then connects with it. */
  private fun askLogin(entry: SshHostEntry) {
    val prompt =
      if (entry.method == LoginMethod.PASSWORD) "Password:" else "Key passphrase for the file:"
    state = state.copy(dialog = SshDialog.Authenticate(entry, prompt))
  }

  private fun authenticate(typed: String) {
    val dialog = state.dialog as? SshDialog.Authenticate ?: return
    state = state.copy(dialog = null)
    refreshHost(dialog.host, forceReconnect = true, typed = typed)
  }

  // ---- New connection ----

  private fun showNewConnection(form: SshForm) {
    val publicKey =
      if (form.method == LoginMethod.GENERATED_KEY)
        IdeateKey.get(setup.secrets, "IDEate on $deviceName").publicKey
      else null
    state = state.copy(dialog = SshDialog.NewConnection(form, publicKey))
  }

  private inline fun <reified T : SshDialog> updateDialog(change: (T) -> SshDialog) {
    val dialog = state.dialog as? T ?: return
    var changed = change(dialog)
    // The generated key's public line is shown as soon as that method is picked.
    if (
      changed is SshDialog.NewConnection &&
        changed.form.method == LoginMethod.GENERATED_KEY &&
        changed.publicKey == null
    ) {
      changed =
        changed.copy(publicKey = IdeateKey.get(setup.secrets, "IDEate on $deviceName").publicKey)
    }
    state = state.copy(dialog = changed)
  }

  /**
   * Gateway's "Check Connection and Continue": saves the host once it logs in, then "Choose IDE and
   * Project".
   */
  private fun checkAndContinue() {
    val dialog = state.dialog as? SshDialog.NewConnection ?: return
    val form = dialog.form
    val port = form.port.trim().toIntOrNull()
    val problem =
      when {
        form.host.isBlank() -> "Specify host"
        form.user.isBlank() && form.method != LoginMethod.OPEN_SSH -> "Specify username"
        port == null || port !in 1..65535 -> "The port must be a number from 1 to 65535"
        form.method == LoginMethod.KEY_FILE && !form.privateKey.contains("PRIVATE KEY") ->
          "Paste the private key (-----BEGIN OPENSSH PRIVATE KEY-----)"
        form.method == LoginMethod.PASSWORD && form.password.isEmpty() -> "Enter the password"
        else -> null
      }
    if (problem != null) return run { state = state.copy(dialog = dialog.copy(error = problem)) }
    val entry =
      SshHostEntry(
        UUID.randomUUID().toString(),
        form.host.trim(),
        port!!,
        form.user.trim(),
        form.method,
      )
    when (form.method) {
      LoginMethod.KEY_FILE -> {
        setup.secrets.write(SecretNames.key(entry), form.privateKey.trim() + "\n")
        if (form.passphrase.isNotEmpty())
          setup.secrets.write(SecretNames.passphrase(entry), form.passphrase)
      }
      LoginMethod.PASSWORD ->
        if (form.savePassword) setup.secrets.write(SecretNames.password(entry), form.password)
      LoginMethod.GENERATED_KEY,
      LoginMethod.OPEN_SSH -> Unit
    }
    state = state.copy(dialog = dialog.copy(checking = true, error = null))
    val typed = if (form.method == LoginMethod.PASSWORD) form.password else null
    work.execute {
      val result = runCatching {
        val remote = remoteFor(entry, typed)
        // $HOME comes with RemoteHosts' probe; projects and IDEs are one more login shell.
        val both = remote.projectsAndIdes()
        Triple(both.ides, both.recent, remote.home())
      }
      main.launch {
        result.fold(
          onSuccess = { (ides, projects, home) ->
            hosts.save(entry)
            setHost(entry) { SshHostView(entry, SshHostView.Phase.READY, null, projects, ides) }
            showChooseIde(entry, ides, home)
          },
          onFailure = { failure ->
            // Not saved: nothing is kept for a host that never logged in.
            listOf(
                SecretNames.key(entry),
                SecretNames.passphrase(entry),
                SecretNames.password(entry),
              )
              .forEach(setup.secrets::delete)
            sessions.remove(entry.id)?.let { runCatching { it.close() } }
            state =
              state.copy(
                dialog =
                  dialog.copy(
                    checking = false,
                    error = "SSH Connection Check Failed: ${failure.message}",
                  ),
              )
          },
        )
      }
    }
  }

  private fun showChooseIde(entry: SshHostEntry, ides: List<InstalledIde>, home: String) {
    val root = if (home.startsWith(TERMUX_FILES)) TERMUX_FILES else "/"
    state =
      state.copy(
        dialog =
          SshDialog.ChooseIde(
            entry,
            ides,
            ides.firstOrNull(),
            projectPath = "",
            home = home,
            root = root,
            error =
              if (ides.isEmpty())
                "No IDE backend is installed on ${entry.name}. Register one with remote-dev-server.sh registerBackendLocationForGateway."
              else null,
          ),
      )
  }

  /** "+" on a host: Choose IDE and Project for it. */
  private fun chooseIde(entry: SshHostEntry) {
    work.execute {
      val result = runCatching {
        val remote = remoteFor(entry)
        // $HOME comes with RemoteHosts' probe: no extra login shell.
        remote.installedIdes() to remote.home()
      }
      main.launch {
        result.fold(
          onSuccess = { (ides, home) -> showChooseIde(entry, ides, home) },
          onFailure = { failure ->
            setHost(entry) { it.copy(phase = phaseOf(failure), error = failure.message) }
          },
        )
      }
    }
  }

  private fun startAndConnect() {
    val dialog = state.dialog as? SshDialog.ChooseIde ?: return
    val ide =
      dialog.ide ?: return run { state = state.copy(dialog = dialog.copy(error = "Choose IDE")) }
    val path = dialog.projectPath.trim().replaceFirst(Regex("^~(?=/|$)"), dialog.home)
    if (!path.startsWith("/"))
      return run {
        state = state.copy(dialog = dialog.copy(error = "Path should be rooted or start with ~"))
      }
    state = state.copy(dialog = null)
    openProject(dialog.host, ide.idePath, path)
  }

  private fun listFolders(
    entry: SshHostEntry,
    path: String,
    onResult: (Result<List<Termux.Folder>>) -> Unit,
  ) {
    work.execute {
      val result = runCatching {
        remoteFor(entry).listFolders(path).map { folder: RemoteFolder ->
          Termux.Folder(folder.name, folder.isProject, folder.hasFolders, folder.hasHiddenFolders)
        }
      }
      main.launch { onResult(result) }
    }
  }

  // ---- Opening a project ----

  /**
   * Gateway's flow (docs/ssh-connections.md, "Opening a project"): the backend's status; if none
   * runs, start one and poll its status once a second; then forward its port and connect to the
   * forwarded link.
   */
  private fun openProject(entry: SshHostEntry, idePath: String, projectPath: String) {
    val run = ++openRun
    val name = projectPath.trimEnd('/').substringAfterLast('/')
    state =
      state.copy(
        progress = SshProgress("Connecting to the IDE Backend", "$name on ${entry.name}"),
        message = null,
      )
    fun progress(title: String) = main.launch {
      if (run == openRun)
        state = state.copy(progress = SshProgress(title, "$name on ${entry.name}"))
    }
    work.execute {
      val result = runCatching {
        val remote = remoteFor(entry)
        progress("Checking for already running IDE Backend")
        var status = remote.status(idePath, projectPath)
        val wasRunning = status?.isReady == true
        if (status?.isReady != true) {
          progress("Starting the remote IDE Backend")
          remote.launch(idePath, projectPath)
          progress("Waiting for the IDE Backend to start")
          val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
          while (status?.isReady != true) {
            check(run == openRun) { "Cancelled" }
            check(System.currentTimeMillis() < deadline) {
              "The IDE Backend didn't start within ${START_TIMEOUT_MILLIS / 60_000} minutes"
            }
            Thread.sleep(1_000)
            status = remote.status(idePath, projectPath)
          }
        }
        val link =
          status.joinLink?.takeIf { it.isNotEmpty() }
            ?: status.projects.firstOrNull { it.projectPath == projectPath }?.joinLink
            ?: error("The backend for $projectPath has no join link")
        // As for Termux's backend: a running backend with another client (Gateway on a laptop, or
        // IDEate on another device) hands the session over only if the user says so.
        val otherClient =
          wasRunning &&
            status.projects.firstOrNull { it.projectPath == projectPath }?.controllerConnected ==
              true
        if (otherClient && !ask(TAKE_OVER, "Take over")) return@runCatching null
        progress("Performing the SSH port forwarding")
        val joinLink = JoinLink.parse(link)
        val forward = sessions.getValue(entry.id).forward(joinLink.port)
        synchronized(forwards) { forwards += forward }
        // Gateway's rewrite: same fragment, local loopback port.
        "tcp://127.0.0.1:${forward.localPort}#" + link.substringAfter('#')
      }
      main.launch {
        if (run != openRun) return@launch
        state = state.copy(progress = null)
        result.fold(
          // null: the user kept the other client's session.
          onSuccess = { local -> local?.let { connect(it, projectPath) } },
          onFailure = { failure ->
            state = state.copy(message = "${entry.name}: ${failure.message}")
          },
        )
      }
    }
  }

  private companion object {
    const val QUESTION_TIMEOUT_SECONDS = 120L
    /** The Termux page's words for the same question (ConnectController.termuxStatus). */
    const val TAKE_OVER =
      "Another device is connected to this backend (for example Gateway on a laptop). Take over? It will be disconnected."
    /** Termux's `intellij-backend start` waits up to 180 s; a phone backend can take that long. */
    const val START_TIMEOUT_MILLIS = 180_000L
    const val TERMUX_FILES = "/data/data/com.termux/files"
  }
}
