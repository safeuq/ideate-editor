package intellijandroid.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.OpenEditors
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalBackHandler
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
    private set

  /** The connection being made or in use; saved to history once the backend accepts it. */
  private var attempt: Attempt? = null
  private var accepted by mutableStateOf(false)

  private var link by mutableStateOf(lastLink.read().orEmpty())
  private var file by mutableStateOf(lastFile.read().orEmpty())
  private var message by mutableStateOf<String?>(null)
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
  private var question by mutableStateOf<Question?>(null)
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
      starting,
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
    attempt = null
    reconnectJob?.cancel()
    reconnecting = null
    pending?.stop()
    pending = null
    session?.stop()
    session = null
    message = "Disconnected. The backend keeps running; tap it under History to reconnect."
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
    if (!accepted && failed?.resumed == true) return termuxStatus(failed.project)
    // FORCE_TERMINATE_WIRE (SplitWire): the backend ends our session when another client connects
    // with a fresh token.
    if (
      accepted && failed?.kind == ConnectionKind.TERMUX && "Backend terminated the wire" in lost
    ) {
      question =
        Question("Another device took over this backend.", "Take back") {
          termuxStatus(failed.project, takingOver = true)
        }
      return
    }
    message =
      if (accepted) "Connection lost: $lost. Tap it under History to reconnect."
      else "Couldn't connect: $lost"
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
        message = failure.message
        return
      }
    val certificate = identity?.let { encoded ->
      runCatching { ClientCertificate.decode(encoded) }
        .getOrElse { failure ->
          message =
            "The saved client identity can't be read (${failure.message}). Remove the entry and paste the link again."
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
      message = "Connection lost: $reason. Tap it under History to reconnect."
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
      message =
        "Connection lost: $reason. Reconnecting didn't work for 2 minutes; tap it under History to try again."
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
   * Resumes with the saved link and identity; when that is refused, [connectionLost] asks Termux
   * (docs/connections.md).
   */
  private fun openTermux(project: String?) {
    val entry = saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }
    if (entry == null) termuxStatus(project)
    else
      connect(
        ConnectionKind.TERMUX,
        entry.lastLink,
        project,
        entry.openFile,
        entry.identity,
        resumed = true,
      )
  }

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
        message =
          "Paste the join link of the backend on port ${backend.port}. " +
            if (termux != null) "In Termux, `intellij-backend link` prints it."
            else "remote-dev-server.sh prints it when it starts."
      }
    }
  }

  // ---- Termux ----

  /**
   * `intellij-backend status --json`: connects when a backend runs, otherwise offers to start one
   * for [project] (docs/connections.md, "Runtime contract"). `controllerConnected` counts this app
   * too, so it is only checked after our own resume was refused; [takingOver]: the user already
   * chose to take the backend back.
   */
  private fun termuxStatus(project: String?, takingOver: Boolean = false) {
    val termux = requireTermux()
    if (!termux.hasPermission) return termux.requestPermission()
    question = null
    busy = "Asking Termux for the backend's link…"
    termux.run(listOf("status", "--json")) { result ->
      busy = null
      result.fold(
        onSuccess = { output ->
          val status = parseStatus(output.stdout)
          when {
            output.exitCode != 0 || status == null ->
              message =
                "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}"
            // Like Gateway: opening a project whose backend isn't running starts it.
            !status.flag("running") ->
              if (project != null) startInTermux(project)
              else message = "No backend is running in Termux. Tap New Project to start one."
            else -> {
              val joinLink = status.getValue("joinLink").jsonPrimitive.content
              val running = status["project"]?.jsonPrimitive?.contentOrNull
              if (takingOver || !status.flag("controllerConnected"))
                connectTermux(joinLink, running)
              else
                question =
                  Question(
                    "Another device is connected to this backend (for example Gateway on a laptop). Take over? It will be disconnected.",
                    "Take over",
                  ) {
                    connectTermux(joinLink, running)
                  }
            }
          }
        },
        onFailure = { failure -> message = failure.message },
      )
    }
  }

  private fun startInTermux(project: String) {
    if (busy != null || starting != null) return
    message = null
    starting = project
    requireTermux().run(listOf("start", project)) { result ->
      if (starting != project) return@run
      starting = null
      result.fold(
        onSuccess = { output ->
          when (output.exitCode) {
            0 -> connectTermux(output.stdout.trim(), project)
            Termux.EXIT_SETTINGS_IN_USE ->
              message =
                "Another IntelliJ in Termux is using the same settings. Stop it first (intellij-backend stop)."
            else ->
              message = "intellij-backend start failed (exit ${output.exitCode}): ${output.stderr}"
          }
        },
        onFailure = { failure -> message = failure.message },
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
            message = "intellij-backend stop failed (exit ${output.exitCode}): ${output.stderr}"
        },
        onFailure = { failure -> message = failure.message },
      )
      refresh()
    }
  }

  /** Like Gateway's Cancel: stops the backend that `start` is bringing up. */
  private fun cancelStart() {
    stopping = starting
    starting = null
    requireTermux().run(listOf("stop")) { result ->
      stopping = null
      message =
        result.fold(
          onSuccess = { output ->
            if (output.exitCode == 0) "Start cancelled; the backend is stopped."
            else "intellij-backend stop failed (exit ${output.exitCode}): ${output.stderr}"
          },
          onFailure = { failure -> failure.message },
        )
    }
  }

  /**
   * A fresh join link from Termux, with a new client identity (resuming with the saved one is
   * [openTermux]'s job). The backend prints `0.0.0.0` when it listens on every address; from this
   * phone it is `127.0.0.1`.
   */
  private fun connectTermux(printedLink: String, project: String?) {
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
    if (termuxAccess != TermuxAccess.READY) return
    requireTermux().run(listOf("status", "--json")) { result ->
      result.fold(
        onSuccess = { output ->
          val status = parseStatus(output.stdout)
          when {
            output.exitCode != 0 || status == null ->
              message =
                "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}"
            status.flag("running") -> {
              val project = status.getValue("project").jsonPrimitive.content
              val port = status.getValue("port").jsonPrimitive.int
              val backend =
                DiscoveredBackend(
                  "$device · ${project.substringAfterLast('/')}",
                  port,
                  project,
                  status.getValue("joinLink").jsonPrimitive.content,
                )
              localBackends = listOf(backend) + localBackends.filter { it.port != port }
            }
            else -> localBackends = localBackends.filter { it.joinLink == null }
          }
        },
        onFailure = { failure -> message = failure.message },
      )
    }
  }

  private fun parseStatus(stdout: String): JsonObject? = runCatching {
    Json.parseToJsonElement(stdout).jsonObject
  }
    .getOrNull()

  private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true
}

/**
 * The whole app: the connect screen until a session exists, then the IDE screen. Fonts and icons
 * come from the platform; [modifier] goes inside the window background (Android: the system bar
 * insets).
 */
@Composable
fun IdeateApp(controller: ConnectController, fonts: ClientFonts, modifier: Modifier = Modifier) {
  Box(Modifier.fillMaxSize().background(IslandsDark.windowBackground).then(modifier)) {
    val current = controller.session
    if (current == null) {
      LaunchedEffect(Unit) { controller.refresh() }
      ConnectScreen(controller.screenState(), controller.actions, fonts)
    } else {
      val state by current.state.collectAsState()
      // Back from the IDE screen: to the connect screen, not out of the app (the backend keeps
      // running; another project or connection can be opened from there).
      LocalBackHandler.current(true) { controller.leave() }
      LaunchedEffect(state.connectionLost) { state.connectionLost?.let(controller::connectionLost) }
      LaunchedEffect(state.projectName, controller.isAccepted) {
        state.projectName?.let(controller::projectNamed)
      }
      ClientScreen(state, current)
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
}
