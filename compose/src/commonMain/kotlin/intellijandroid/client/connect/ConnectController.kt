package intellijandroid.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.OpenEditors
import intellijandroid.client.session.SessionState
import intellijandroid.client.session.handOver
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalBackHandler
import intellijandroid.client.ui.LocalTitleBarArea
import intellijandroid.client.ui.TitleBarSpacer
import intellijandroid.client.ui.dialog.MessageKind
import intellijandroid.client.ui.theme.ClientFonts
import intellijandroid.client.ui.theme.IslandsDark
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The connect screen's logic, shared by the Android app and the desktop: link prompt, backends on
 * this device, history (docs/connections.md in safeuq/intellij-backend-android), then the session.
 * State is Compose state, changed on the main thread only.
 *
 * @param device the first page's label, `This phone` or `This computer`
 * @param termux null where there is no Termux (the desktop)
 * @param ssh what the SSH page needs from the platform; null hides the page
 */
class ConnectController(
  private val history: ConnectionHistory,
  /** The link and file last typed into the Link page. */
  private val lastLink: StoredText,
  private val lastFile: StoredText,
  /** Each project's open editors, reopened on the next connection. */
  private val openEditors: OpenEditors,
  private val termux: Termux?,
  private val device: String,
  ssh: SshSetup? = null,
) {
  /** The SSH page; it hands an opened project's forwarded link back to [connect]. */
  private val sshController = ssh?.let { setup ->
    SshController(setup) { forwarded, projectPath, hostName ->
      connect(
        ConnectionKind.SSH,
        forwarded,
        projectPath,
        openFile = null,
        identity = null,
        sshHost = hostName,
      )
    }
  }

  var session by mutableStateOf<ClientSession?>(null)

  /**
   * The old session's last state after a reconnect, which the screen shows while the new session
   * catches up (`handOver`); null when not handing over.
   */
  var handOverFrom by mutableStateOf<SessionState?>(null)

  /**
   * What the screen last showed for a session that still had its connection, so a new session that
   * breaks while filling in doesn't blank the screen: the next hand-over starts from this.
   */
  internal var lastShown: Pair<ClientSession, SessionState>? = null

  /** The connection being made or in use; saved to history once the backend accepts it. */
  private var attempt: Attempt? = null
  private var accepted by mutableStateOf(false)

  private var link by mutableStateOf(lastLink.read().orEmpty())
  private var file by mutableStateOf(lastFile.read().orEmpty())
  private val messageState = mutableStateOf<ConnectMessage?>(null)
  /** The last message, shown as a popup; setting one ends the [opening] popup. */
  private var message: ConnectMessage?
    get() = messageState.value
    set(value) {
      messageState.value = value
      if (value != null) opening = null
    }

  private fun inform(text: String) {
    message = ConnectMessage(text, MessageKind.INFORMATION)
  }

  private fun warn(text: String) {
    message = ConnectMessage(text, MessageKind.WARNING)
  }

  private fun fail(text: String) {
    message = ConnectMessage(text, MessageKind.ERROR)
  }

  /**
   * Opening a project on this phone: one popup from the first `status --json` until the session
   * opens or fails; only its detail line changes ([step]).
   */
  private var opening by mutableStateOf<OpeningProgress?>(null)
  /** `start` is opening a project in a backend that already serves another: Cancel keeps it. */
  private var startingOnRunningBackend = false
  private var busy by mutableStateOf<String?>(null)
  /** `intellij-backend stop` is running for this project: the "Stopping IDE Backend" popup. */
  private var stopping by mutableStateOf<String?>(null)
  private var page by
    mutableStateOf(if (termux?.isInstalled == true) ConnectPage.PHONE else ConnectPage.LINK)
  private var saved by mutableStateOf(history.entries())
  private var localBackends by mutableStateOf(emptyList<DiscoveredBackend>())
  private var termuxAccess by mutableStateOf<TermuxAccess?>(null)
  /**
   * "Take over?" (another client has Termux's backend) or "Take back" (another client took it from
   * us).
   */
  private val questionState = mutableStateOf<Question?>(null)
  private var question: Question?
    get() = questionState.value
    set(value) {
      questionState.value = value
      if (value != null) opening = null
    }

  /**
   * `intellij-backend start` is running; its project. Cancel clears it, and the start's late result
   * is then ignored.
   */
  private var starting by mutableStateOf<String?>(null)

  private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private companion object {
    /** How long a dropped connection is retried before the app shows the connect screen. */
    const val RECONNECT_FOR_MILLIS = 120_000L
  }

  /**
   * [resumed]: the saved link and identity of a Termux entry; if refused, the app asks Termux for a
   * fresh link.
   */
  private class Attempt(
    val kind: ConnectionKind,
    val link: JoinLink,
    val text: String,
    val project: String?,
    val openFile: String?,
    val resumed: Boolean,
    /** Where this project's open editors are kept, from [editorsKey]. */
    val editorsKey: String,
  ) {
    /** The identity the backend accepted (encoded); a reconnect presents it again. */
    var identity: String? = null
  }

  /**
   * The connection dropped and IDEate is trying to get it back: the attempt number, shown under "No
   * connection to IDE Backend. Trying to reconnect…". Null otherwise.
   */
  var reconnecting by mutableStateOf<Int?>(null)
    private set

  private var reconnectJob: Job? = null

  /**
   * False while the app is in the background (Android: the activity is stopped). A reconnect waits
   * then: under Doze the network is off, so attempts would only fail and use up the 2 minutes.
   */
  var inForeground by mutableStateOf(true)
  /** The session a reconnect attempt is bringing up; shown once the backend accepts it. */
  private var pending: ClientSession? = null

  val actions =
    ConnectActions(
      pageChanged = { page = it },
      linkChanged = { link = it },
      fileChanged = { file = it },
      connectLink = {
        lastLink.write(link)
        lastFile.write(file)
        connectLink(link, file.ifBlank { null })
      },
      openDiscovered = ::openDiscovered,
      openSaved = ::openSaved,
      removeSaved = { entry ->
        history.remove(entry)
        saved = history.entries()
      },
      allowTermux = { termux?.requestPermission() },
      newProject = ::startInTermux,
      stopTermux = ::stopTermux,
      listFolders = { path, onResult -> requireTermux().listFolders(path, onResult) },
      refresh = ::refresh,
      answerQuestion = { question?.onConfirm?.invoke() },
      cancelQuestion = { question = null },
      dismissMessage = { message = null },
      cancelStart = ::cancelStart,
      ssh = sshController?.actions,
    )

  fun screenState() =
    ConnectScreenState(
      device,
      page,
      link,
      file,
      localBackends,
      saved,
      termuxAccess,
      busy,
      message,
      question,
      opening,
      sshController?.state ?: SshPageState(),
      stopping,
    )

  /**
   * A pasted link: reuses the identity of a saved connection with the same link, else makes a new
   * one.
   */
  fun connectLink(text: String, openFile: String?) {
    val identity = saved.firstOrNull { it.lastLink == text.trim() }?.identity
    connect(ConnectionKind.LINK, text, project = null, openFile, identity)
  }

  fun close() {
    session?.stop()
    sshController?.close()
    main.cancel()
  }

  /**
   * Back from the IDE screen: ends this client's session and shows the connect screen. The backend
   * and its project keep running; the history entry reconnects.
   */
  fun leave() {
    opening = null
    attempt = null
    reconnectJob?.cancel()
    reconnecting = null
    pending?.stop()
    pending = null
    session?.stop()
    session = null
    inform("Disconnected. The backend keeps running; tap it under History to reconnect.")
  }

  /**
   * The session ended: back to the connect screen, or on to Termux when our saved link was refused.
   */
  internal fun connectionLost(lost: String) {
    val failed = attempt
    session?.stop()
    // Like JetBrains Client ("No connection to IDE Backend. Trying to reconnect…"): keep the screen
    // and connect again with the same link and identity. Not when another client took the backend
    // over (that asks "Take back").
    val identity = failed?.identity
    if (accepted && failed != null && identity != null && "Backend terminated the wire" !in lost)
      return reconnect(failed, identity, lost)
    session = null
    // A join token works once: after another client (e.g. Gateway) took over, the saved one is
    // declined.
    if (!accepted && failed?.resumed == true)
      return termuxStatus(failed.project, resumeTried = true)
    // FORCE_TERMINATE_WIRE (SplitWire): the backend ends our session when another client connects
    // with a fresh token.
    if (
      accepted && failed?.kind == ConnectionKind.TERMUX && "Backend terminated the wire" in lost
    ) {
      question =
        Question("Another device took over this backend.", "Take back", title = "Disconnected") {
          termuxStatus(failed.project, takingOver = true)
        }
      return
    }
    if (accepted) warn("Connection lost: $lost. Tap it under History to reconnect.")
    else fail("Couldn't connect: $lost")
  }

  /**
   * A LINK entry is first saved as `<host>:<port>`; once the backend names its project it becomes
   * `<project> · <host>:<port>`.
   */
  internal fun projectNamed(projectName: String) {
    val entry = saved.firstOrNull() ?: return
    if (accepted && entry.kind == ConnectionKind.LINK && !entry.name.startsWith(projectName)) {
      history.save(entry.copy(name = "$projectName · ${entry.host}:${entry.port}"))
      saved = history.entries()
    }
  }

  internal val isAccepted: Boolean
    get() = accepted

  private fun requireTermux(): Termux = checkNotNull(termux) { "No Termux on $device" }

  // ---- Connecting ----

  private fun connect(
    kind: ConnectionKind,
    text: String,
    project: String?,
    openFile: String?,
    identity: String?,
    resumed: Boolean = false,
    /** The SSH host's name, e.g. `me@mac.local`; SSH connections only. */
    sshHost: String? = null,
  ) {
    val joinLink = runCatching {
      JoinLink.parse(text)
    }
      .getOrElse { failure ->
        fail(failure.message ?: failure.toString())
        return
      }
    val certificate = identity?.let { encoded ->
      runCatching { ClientCertificate.decode(encoded) }
        .getOrElse { failure ->
          fail(
            "The saved client identity can't be read (${failure.message}). Remove the entry and paste the link again.",
          )
          return
        }
    }
    val current =
      Attempt(
        kind,
        joinLink,
        text.trim(),
        project,
        openFile,
        resumed,
        editorsKey(kind, joinLink, project, sshHost),
      )
    attempt = current
    accepted = false
    message = null
    question = null
    session =
      ClientSession(
          joinLink,
          openFile,
          certificate,
          openEditors.slot(current.editorsKey),
          current.project,
        ) { acceptedCertificate ->
          val encoded = acceptedCertificate.encode()
          main.launch { onAccepted(current, encoded) }
        }
        .also { it.start() }
  }

  /**
   * Which project a connection's editors belong to. E.g. `link:192.168.0.105:5990`,
   * `termux:/data/data/com.termux/files/home/intellij/smoke`, `ssh:me@mac.local:/Users/me/smoke`.
   * An SSH link's port is a local forward that changes each time, so SSH uses the host instead.
   */
  private fun editorsKey(
    kind: ConnectionKind,
    link: JoinLink,
    project: String?,
    sshHost: String?,
  ): String =
    when (kind) {
      ConnectionKind.LINK -> "link:${link.host}:${link.port}"
      ConnectionKind.TERMUX ->
        "termux:${requireNotNull(project) { "A Termux connection has a project" }}"
      ConnectionKind.SSH ->
        "ssh:${requireNotNull(sshHost) { "An SSH connection has a host" }}:${requireNotNull(project) { "An SSH connection has a project" }}"
    }

  private fun onAccepted(current: Attempt, identity: String) {
    if (attempt !== current) return
    opening = null
    accepted = true
    current.identity = identity
    // The SSH page lists the host's projects itself; a forwarded link is no use after a restart.
    if (current.kind == ConnectionKind.SSH) return
    val previous = saved.firstOrNull { it.key == keyOf(current) }
    val name =
      when (current.kind) {
        ConnectionKind.TERMUX -> "$device · ${current.project?.substringAfterLast('/')}"
        ConnectionKind.LINK,
        ConnectionKind.SSH -> previous?.name ?: "${current.link.host}:${current.link.port}"
      }
    history.save(
      SavedConnection(
        kind = current.kind,
        name = name,
        host = current.link.host,
        port = current.link.port,
        project = current.project,
        fingerprint = current.link.fingerprint,
        lastLink = current.text,
        identity = identity,
        openFile = current.openFile,
        lastConnected = System.currentTimeMillis(),
      ),
    )
    saved = history.entries()
  }

  /**
   * Tries [lost]'s link and identity again, after 1 s, 2 s, 4 s, … (at most 10 s apart) for 2
   * minutes of the app being in the foreground ([inForeground]), while the old session's screen
   * stays under the reconnect popup; the first session the backend accepts replaces it. The same
   * identity is allowed to use the join token again (docs/split-protocol.md, "Same join token, same
   * client id").
   */
  private fun reconnect(lost: Attempt, identity: String, reason: String) {
    val certificate = runCatching { ClientCertificate.decode(identity) }.getOrNull()
    if (certificate == null) {
      session = null
      warn("Connection lost: $reason. Tap it under History to reconnect.")
      return
    }
    reconnectJob?.cancel()
    reconnectJob = main.launch {
      var deadline = System.currentTimeMillis() + RECONNECT_FOR_MILLIS
      var pause = 1_000L
      var number = 0
      while (System.currentTimeMillis() < deadline) {
        if (!inForeground) {
          // Back in the foreground: try at once, with the full 2 minutes again.
          snapshotFlow { inForeground }.first { it }
          deadline = System.currentTimeMillis() + RECONNECT_FOR_MILLIS
          pause = 1_000L
        }
        number++
        reconnecting = number
        if (reconnectOnce(lost, identity, certificate)) {
          reconnecting = null
          return@launch
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(10_000L)
      }
      reconnecting = null
      session = null
      warn(
        "Connection lost: $reason. Reconnecting didn't work for 2 minutes; tap it under History to try again.",
      )
    }
  }

  /** One attempt: true once the backend accepted the new session, which then replaces the old. */
  private suspend fun reconnectOnce(
    lost: Attempt,
    identity: String,
    certificate: ClientCertificate,
  ): Boolean {
    val current =
      Attempt(
        lost.kind,
        lost.link,
        lost.text,
        lost.project,
        lost.openFile,
        resumed = true,
        lost.editorsKey,
      )
    current.identity = identity
    attempt = current
    val accepted = CompletableDeferred<Boolean>()
    val candidate =
      ClientSession(
        current.link,
        current.openFile,
        certificate,
        openEditors.slot(current.editorsKey),
        current.project,
      ) { acceptedCertificate ->
        val encoded = acceptedCertificate.encode()
        main.launch {
          onAccepted(current, encoded)
          accepted.complete(true)
        }
      }
    pending = candidate
    val failure = main.launch {
      candidate.state.first { it.connectionLost != null }
      accepted.complete(false)
    }
    candidate.start()
    val connected =
      try {
        accepted.await()
      } finally {
        failure.cancel()
        pending = null
      }
    if (!connected) {
      candidate.stop()
      return false
    }
    handOverFrom = lastShown?.takeIf { it.first === session }?.second ?: session?.state?.value
    session?.stop()
    session = candidate
    return true
  }

  private fun keyOf(current: Attempt) =
    if (current.kind == ConnectionKind.TERMUX) "termux:${current.project}"
    else "link:${current.link.host}:${current.link.port}"

  private fun openSaved(entry: SavedConnection) {
    if (busy != null) return
    when (entry.kind) {
      ConnectionKind.LINK -> {
        // Shown in the prompt, so that a refused link (the backend restarted) is easy to replace
        // with the new one.
        link = entry.lastLink
        connect(ConnectionKind.LINK, entry.lastLink, project = null, entry.openFile, entry.identity)
      }
      ConnectionKind.TERMUX -> openTermux(entry.project)
      // Never saved (onAccepted); kept for the exhaustive when.
      ConnectionKind.SSH -> page = ConnectPage.SSH
    }
  }

  /**
   * Like Gateway ("Checking for already running IDE Backend"): the backend's status first, so a
   * project that isn't open in the running backend is opened in it ([termuxStatus]).
   */
  private fun openTermux(project: String?) = termuxStatus(project)

  private fun openDiscovered(backend: DiscoveredBackend) {
    if (busy != null) return
    val known = saved.firstOrNull { entry ->
      entry.kind == ConnectionKind.LINK && entry.host == backend.host && entry.port == backend.port
    }
    when {
      backend.joinLink != null -> openTermux(backend.project)
      known != null -> openSaved(known)
      else -> {
        page = ConnectPage.LINK
        link = "tcp://${backend.host}:${backend.port}#"
        inform(
          "Paste the join link of the backend on port ${backend.port}. " +
            if (termux != null) "In Termux, `intellij-backend link` prints it."
            else "remote-dev-server.sh prints it when it starts.",
        )
      }
    }
  }

  // ---- Termux ----

  /**
   * `intellij-backend status --json`, then Gateway's steps (docs/connections.md, "What tapping an
   * entry does"):
   * - no backend: start one for [project];
   * - a backend without [project]: `start` opens it in that backend, as a second project. One
   *   backend serves all its projects over one connection and one join link, so the session then
   *   shows [project] (ClientSession's `project`);
   * - [project] is open: resume with our own identity for this backend, if we have one, so our own
   *   earlier connection isn't taken for another device; else connect, asking "Take over?" first
   *   when another client is connected. [takingOver]: the user already chose to. [resumeTried]: the
   *   backend refused our identity, so don't try it again.
   */
  private fun termuxStatus(
    project: String?,
    takingOver: Boolean = false,
    resumeTried: Boolean = false,
    started: Boolean = false,
  ) {
    val termux = requireTermux()
    if (!termux.hasPermission) return termux.requestPermission()
    question = null
    busy = "Asking Termux for the backend's link…"
    step(project, "Asking Termux for the backend's link…")
    termux.run(listOf("status", "--json")) { result ->
      busy = null
      result.fold(
        onSuccess = { output ->
          val status = parseStatus(output.stdout)
          when {
            output.exitCode != 0 || status == null ->
              fail(
                "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}",
              )
            // Like Gateway: opening a project whose backend isn't running starts it.
            !status.flag("running") ->
              if (project != null) startInTermux(project, takingOver, resumeTried)
              else inform("No backend is running in Termux. Tap New Project to start one.")
            // After `start` the project is open, whatever its path looks like here: no second
            // `start`, which would loop.
            project != null && !started && openProject(status, project, started) == null ->
              startInTermux(project, takingOver, resumeTried, onRunningBackend = true)
            else -> {
              val shown =
                project?.let { openProject(status, it, started) }
                  ?: openProjects(status).firstOrNull()
              if (shown == null) fail("intellij-backend status names no project: ${output.stdout}")
              else joinTermux(status, shown, takingOver, resumeTried)
            }
          }
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
      )
    }
  }

  /** [project] is open in the running backend that [status] describes; see [termuxStatus]. */
  private fun joinTermux(
    status: JsonObject,
    project: String,
    takingOver: Boolean,
    resumeTried: Boolean,
  ) {
    val joinLink = status.getValue("joinLink").jsonPrimitive.content
    val backend = runCatching { JoinLink.parse(joinLink) }.getOrNull()
    // The newest identity this backend accepted from us, from any of its projects' entries: the
    // backend has one controller connection for all its projects. Not when taking the backend back
    // from another client: it refuses our old identity then.
    val ours =
      if (resumeTried || takingOver || backend == null) null
      else
        saved
          .filter {
            it.kind == ConnectionKind.TERMUX &&
              it.fingerprint == backend.fingerprint &&
              it.port == backend.port
          }
          .maxByOrNull { it.lastConnected }
    val openFile =
      saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }?.openFile
    step(project, "Connecting…")
    when {
      ours != null ->
        connect(
          ConnectionKind.TERMUX,
          ours.lastLink,
          project,
          openFile,
          ours.identity,
          resumed = true,
        )
      takingOver || !status.flag("controllerConnected") -> connectTermux(joinLink, project)
      else ->
        question =
          Question(
            "Another device is connected to this backend (for example Gateway on a laptop). Take over? It will be disconnected.",
            "Take over",
            title = "IDE Backend In Use",
          ) {
            connectTermux(joinLink, project)
          }
    }
  }

  /**
   * The projects open in the backend: `projects[].projectPath`, or `project` from an
   * `intellij-backend` without `projects`.
   */
  private fun openProjects(status: JsonObject): List<String> =
    status["projects"]?.jsonArray?.mapNotNull {
      it.jsonObject["projectPath"]?.jsonPrimitive?.contentOrNull
    } ?: listOfNotNull(status["project"]?.jsonPrimitive?.contentOrNull)

  /**
   * [wanted]'s entry in `projects`, as the backend names it. `start` resolves symlinks, so after it
   * ([started]) `~/storage/shared/Files/Projects/Xflow/commons` is listed as
   * `/storage/emulated/0/Files/Projects/Xflow/commons`: then the one project with the same folder
   * name, else `project`, the backend's last opened. Null: not open.
   */
  private fun openProject(status: JsonObject, wanted: String, started: Boolean): String? =
    openProjectPath(
      openProjects(status),
      status["project"]?.jsonPrimitive?.contentOrNull,
      wanted,
      started,
    )

  /** The [opening] popup's next step for [project]. */
  private fun step(project: String?, detail: String, cancellable: Boolean = false) {
    opening = OpeningProgress(project ?: "Termux", detail, cancellable)
  }

  /**
   * `intellij-backend start <project>`: starts a backend, or opens [project] in the running one;
   * then [termuxStatus] again to connect.
   */
  private fun startInTermux(
    project: String,
    takingOver: Boolean = false,
    resumeTried: Boolean = false,
    /** The backend already serves another project; Cancel must not stop it. */
    onRunningBackend: Boolean = false,
  ) {
    if (busy != null || starting != null) return
    message = null
    starting = project
    startingOnRunningBackend = onRunningBackend
    step(
      project,
      "Running intellij-backend start for ${project.trimEnd('/').substringAfterLast('/')}; this takes up to 3 minutes",
      cancellable = true,
    )
    requireTermux().run(listOf("start", project)) { result ->
      if (starting != project) return@run
      starting = null
      result.fold(
        onSuccess = { output ->
          when (output.exitCode) {
            0 -> termuxStatus(project, takingOver, resumeTried, started = true)
            Termux.EXIT_SETTINGS_IN_USE ->
              fail(
                output.stderr.trim().ifEmpty {
                  "Another IntelliJ in Termux is using the same settings. Stop it first (intellij-backend stop)."
                },
              )
            else ->
              fail("intellij-backend start failed (exit ${output.exitCode}): ${output.stderr}")
          }
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
      )
    }
  }

  /** The red square next to a running project. */
  private fun stopTermux() {
    if (busy != null || stopping != null) return
    stopping = localBackends.firstOrNull { it.joinLink != null }?.project ?: "Termux"
    requireTermux().run(listOf("stop")) { result ->
      stopping = null
      result.fold(
        onSuccess = { output ->
          if (output.exitCode != 0)
            fail("intellij-backend stop failed (exit ${output.exitCode}): ${output.stderr}")
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
      )
      refresh()
    }
  }

  /**
   * Like Gateway's Cancel: stops the backend that `start` is bringing up. A backend that already
   * served another project keeps running; the app only stops waiting.
   */
  private fun cancelStart() {
    opening = null
    if (startingOnRunningBackend) {
      starting = null
      return
    }
    stopping = starting
    starting = null
    requireTermux().run(listOf("stop")) { result ->
      stopping = null
      result.fold(
        onSuccess = { output ->
          if (output.exitCode == 0) inform("Start cancelled; the backend is stopped.")
          else fail("intellij-backend stop failed (exit ${output.exitCode}): ${output.stderr}")
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
      )
    }
  }

  /**
   * A fresh join link from Termux, with a new client identity (resuming with the saved one is
   * [joinTermux]'s job). The backend prints `0.0.0.0` when it listens on every address; from this
   * phone it is `127.0.0.1`.
   */
  private fun connectTermux(printedLink: String, project: String?) {
    step(project, "Connecting…")
    val text = printedLink.trim().replace("tcp://0.0.0.0:", "tcp://127.0.0.1:")
    val entry = saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }
    connect(
      ConnectionKind.TERMUX,
      text,
      project,
      entry?.openFile ?: file.ifBlank { null },
      identity = null,
    )
  }

  // ---- Discovery ----

  /**
   * Probes this device's ports and asks Termux for its backend (docs/connections.md, "Discovery").
   */
  fun refresh() {
    termuxAccess = termux?.let {
      when {
        !it.isInstalled -> TermuxAccess.NOT_INSTALLED
        !it.hasPermission -> TermuxAccess.NO_PERMISSION
        else -> TermuxAccess.READY
      }
    }
    // With Termux the ports are probed once its status has answered (2 s or more on a phone):
    // before that, Termux's own backend port would show as an unknown "127.0.0.1:5990" row.
    if (termuxAccess != TermuxAccess.READY) return probePorts()
    requireTermux().run(listOf("status", "--json")) { result ->
      result.fold(
        onSuccess = { output ->
          val status = parseStatus(output.stdout)
          when {
            output.exitCode != 0 || status == null ->
              fail(
                "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}",
              )
            status.flag("running") -> {
              val port = status.getValue("port").jsonPrimitive.int
              val joinLink = status.getValue("joinLink").jsonPrimitive.content
              val backends =
                openProjects(status).map { project ->
                  DiscoveredBackend(
                    "$device · ${project.substringAfterLast('/')}",
                    port,
                    project,
                    joinLink,
                  )
                }
              localBackends = backends + localBackends.filter { it.port != port }
            }
            else -> localBackends = localBackends.filter { it.joinLink == null }
          }
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
      )
      probePorts()
    }
  }

  private fun probePorts() {
    thread(name = "local-ports", isDaemon = true) {
      val ports = LocalPorts.open()
      main.launch {
        val known = localBackends.filter { it.joinLink != null && it.port in ports }
        localBackends =
          known +
            ports
              .filter { port -> known.none { it.port == port } }
              .map { port ->
                DiscoveredBackend("$device · port $port", port, project = null, joinLink = null)
              }
      }
    }
  }

  private fun parseStatus(stdout: String): JsonObject? = runCatching {
    Json.parseToJsonElement(stdout).jsonObject
  }
    .getOrNull()

  private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true
}

/**
 * The connections window: the connect screen under the window's title bar strip. It stays as it is
 * while a session is open; the session shows in its own window ([EditorApp]). Fonts and icons come
 * from the platform; [modifier] goes inside the window background (Android: the system bar insets).
 */
@Composable
fun ConnectApp(controller: ConnectController, fonts: ClientFonts, modifier: Modifier = Modifier) {
  Box(Modifier.fillMaxSize().background(Gateway.background)) {
    LaunchedEffect(Unit) { controller.refresh() }
    Column(modifier) {
      // Where the app draws the window's title bar (ui/TitleBar.kt): an empty strip that drags
      // the window, with IntelliJ's welcome screen separator under it.
      if (LocalTitleBarArea.current != null) {
        TitleBarSpacer()
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
      }
      ConnectScreen(controller.screenState(), controller.actions, fonts)
    }
  }
}

/**
 * The longest the old screen stays up after a reconnect; what closed meanwhile then shows as
 * closed.
 */
private const val HAND_OVER_LIMIT_MS = 10_000L

/**
 * The editor window for [session]: the IDE screen, which paints its own background (with the
 * project tint) and takes [modifier] inside it. Back leaves the session; the platform then closes
 * this window, and the connections window is there again.
 */
@Composable
fun EditorApp(
  controller: ConnectController,
  session: ClientSession,
  fonts: ClientFonts,
  modifier: Modifier = Modifier,
) {
  Box(Modifier.fillMaxSize().background(IslandsDark.windowBackground)) {
    val current by session.state.collectAsState()
    val previous = controller.handOverFrom
    var switched by remember(previous) { mutableStateOf(emptySet<String>()) }
    val handedOver = previous?.let { handOver(it, current, switched) }
    val shown = handedOver?.shown ?: current
    // A session that broke clears its own state; keep showing what was there before.
    val state =
      if (current.connectionLost != null)
        controller.lastShown?.takeIf { it.first === session }?.second ?: shown
      else shown
    if (current.connectionLost == null) controller.lastShown = session to shown
    LaunchedEffect(handedOver?.switched, handedOver?.done) {
      handedOver?.let {
        switched = it.switched
        if (it.done) controller.handOverFrom = null
      }
    }
    LaunchedEffect(previous) {
      if (previous != null) {
        delay(HAND_OVER_LIMIT_MS)
        controller.handOverFrom = null
      }
    }
    // The backend keeps running; another project or connection can be opened from the
    // connections window.
    LocalBackHandler.current(true) { controller.leave() }
    LaunchedEffect(current.connectionLost) {
      current.connectionLost?.let(controller::connectionLost)
    }
    LaunchedEffect(state.projectName, controller.isAccepted) {
      state.projectName?.let(controller::projectNamed)
    }
    ClientScreen(state, session, insets = modifier)
    controller.reconnecting?.let { attemptNumber ->
      StartProgress(
        "No connection to IDE Backend",
        "Trying to reconnect… (attempt $attemptNumber)",
        fonts,
        onCancel = controller::leave,
      )
    }
  }
}

/**
 * Which of the backend's [open] projects is [wanted] (see `ConnectController.openProject`);
 * [lastOpened] is status's `project`.
 */
internal fun openProjectPath(
  open: List<String>,
  lastOpened: String?,
  wanted: String,
  started: Boolean,
): String? {
  fun withoutSlash(path: String) = path.trimEnd('/')
  open
    .firstOrNull { withoutSlash(it) == withoutSlash(wanted) }
    ?.let {
      return it
    }
  if (!started) return null
  val name = withoutSlash(wanted).substringAfterLast('/')
  return open.singleOrNull { withoutSlash(it).substringAfterLast('/') == name } ?: lastOpened
}
