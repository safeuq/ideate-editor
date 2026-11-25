package io.github.safeuq.ideate.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.connection.ClientCertificate
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.OpenEditors
import io.github.safeuq.ideate.client.session.SavedScreen
import io.github.safeuq.ideate.client.session.SavedSession
import io.github.safeuq.ideate.client.session.SessionState
import io.github.safeuq.ideate.client.session.handOver
import io.github.safeuq.ideate.client.ui.ClientScreen
import io.github.safeuq.ideate.client.ui.LocalBackHandler
import io.github.safeuq.ideate.client.ui.LocalProjectSwipeArea
import io.github.safeuq.ideate.client.ui.LocalTitleBarArea
import io.github.safeuq.ideate.client.ui.ProjectSwipeArea
import io.github.safeuq.ideate.client.ui.ScreenLayout
import io.github.safeuq.ideate.client.ui.SlidePager
import io.github.safeuq.ideate.client.ui.TitleBarSpacer
import io.github.safeuq.ideate.client.ui.ToolWindowState
import io.github.safeuq.ideate.client.ui.dialog.MessageKind
import io.github.safeuq.ideate.client.ui.neighbourProject
import io.github.safeuq.ideate.client.ui.screenLayout
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.toolWindowButtons
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
  /**
   * The screen as it was when the app went to the background, for a start after Android ended the
   * process (docs/android-app.md, "Addendum (2026-11-23)"); null keeps none (the desktop).
   */
  private val savedSession: StoredText? = null,
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

  /**
   * After a reconnect: the new session and the old session's last state, which the screen shows
   * while the new session catches up (`handOver`); null when not handing over. The pair says whose
   * state the screen may compare with: for a frame the screen can still have the old session, whose
   * full state would count as "the new session has sent everything".
   */
  internal var handOverFrom by mutableStateOf<Pair<ClientSession, SessionState>?>(null)

  /**
   * What the screen last showed for a session that still had its connection, so a new session that
   * breaks while filling in doesn't blank the screen: the next hand-over starts from this.
   */
  internal var lastShown: Pair<ClientSession, SessionState>? = null

  /** The editor window's open tool windows, kept for [saveScreen]. */
  internal var windows: ToolWindowState? = null

  /** After [restoreSaved]: the tool windows that were open, for the editor window to open again. */
  internal var restoredWindows: Map<String, String>? = null

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
   * The connection dropped and Ideate is trying to get it back: the attempt number, shown under "No
   * connection to IDE Backend. Trying to reconnect…". Null otherwise.
   */
  var reconnecting by mutableStateOf<Int?>(null)
    private set

  /**
   * The project a new session is being opened for after a choice in the project widget's popup,
   * e.g. `beta`: the first try shows "Opening Project" instead of the lost-connection message.
   */
  var switching by mutableStateOf<String?>(null)
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
    pending?.stop()
    stopSessions()
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
    stopSessions()
    endSession()
    inform("Disconnected. The backend keeps running; tap it under History to reconnect.")
  }

  /** No session any more: the editor window closes, and there is nothing to restore later. */
  private fun endSession() {
    switching = null
    session = null
    handOverFrom = null
    lastShown = null
    savedSession?.write("")
  }

  /**
   * The app goes to the background (Android: the editor window stops): keeps what the screen shows
   * and how to reconnect, in case Android ends the process there. [restoreSaved] reads it back.
   * Nothing for an SSH connection (its local forward is gone with the process).
   */
  fun saveScreen() {
    val storage = savedSession ?: return
    val current = attempt ?: return
    val identity = current.identity ?: return
    if (session == null || current.kind == ConnectionKind.SSH) return
    val state = shownBeforeReconnect() ?: return
    val screen = SavedScreen.of(state, windows?.saved().orEmpty())
    storage.write(
      SavedSession(
          current.kind,
          current.text,
          current.project,
          current.openFile,
          identity,
          current.editorsKey,
          screen,
        )
        .encode(),
    )
  }

  /**
   * The app started with the editor window open when it last went to the background: shows that
   * screen (not interactive, under the reconnect popup) and connects again with the saved link and
   * identity, as a lost connection would. True when there was one to restore.
   */
  fun restoreSaved(): Boolean {
    val saved = SavedSession.decode(savedSession?.read()) ?: return false
    val joinLink = runCatching { JoinLink.parse(saved.text) }.getOrNull()
    val certificate = runCatching { ClientCertificate.decode(saved.identity) }.getOrNull()
    if (joinLink == null || certificate == null) {
      savedSession?.write("")
      return false
    }
    val lost =
      Attempt(
        saved.kind,
        joinLink,
        saved.text,
        saved.project,
        saved.openFile,
        resumed = true,
        saved.editorsKey,
      )
    lost.identity = saved.identity
    attempt = lost
    accepted = true
    restoredWindows = saved.screen.openWindows
    // A session that never starts stands in for the one the old process had; the reconnect below
    // replaces it, and the screen shows the saved state until then.
    val standIn =
      ClientSession(
        joinLink,
        saved.openFile,
        certificate,
        openEditors.slot(lost.editorsKey),
        saved.project,
      )
    session = standIn
    opener = standIn
    val screen = saved.screen.toState()
    lastShown = standIn to screen
    handOverFrom = standIn to screen
    reconnect(lost, saved.identity, "The app was closed in the background")
    return true
  }

  /**
   * The session ended: back to the connect screen, or on to Termux when our saved link was refused.
   */
  internal fun connectionLost(lost: String) {
    // A reconnect is already replacing this session, e.g. [switchProject]'s: the backend closes the
    // old session when the new one connects.
    if (reconnectJob?.isActive == true) return
    val failed = attempt
    stopSessions()
    // Like JetBrains Client ("No connection to IDE Backend. Trying to reconnect…"): keep the screen
    // and connect again with the same link and identity. Not when another client took the backend
    // over (that asks "Take back").
    val identity = failed?.identity
    if (accepted && failed != null && identity != null && "Backend terminated the wire" !in lost)
      return reconnect(failed, identity, lost)
    endSession()
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

  private fun requireTermux(): Termux = checkNotNull(termux) { "No Termux on $THIS_DEVICE" }

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
    startSession(current, certificate)
  }

  /**
   * Makes [current] the attempt and starts its session. The connect screen shows "Connecting to …"
   * until the backend accepts; only then does the session become [session] and the editor window
   * open. A failure before that shows its message on the connect screen.
   */
  private fun startSession(current: Attempt, certificate: ClientCertificate?) {
    attempt = current
    accepted = false
    message = null
    question = null
    handOverFrom = null
    pending?.stop()
    opening =
      OpeningProgress(
        opening?.project ?: current.project ?: "${current.link.host}:${current.link.port}",
        "Connecting…",
        cancellable = true,
      )
    var failure: Job? = null
    lateinit var candidate: ClientSession
    candidate =
      ClientSession(
        current.link,
        current.openFile,
        certificate,
        openEditors.slot(current.editorsKey),
        current.project,
        inForeground = { inForeground },
        onProjectSwitch = projectSwitch(current),
      ) { acceptedCertificate ->
        val encoded = acceptedCertificate.encode()
        main.launch {
          if (pending !== candidate || attempt !== current) return@launch
          failure?.cancel()
          pending = null
          session = candidate
          opener = candidate
          onAccepted(current, encoded)
        }
      }
    pending = candidate
    failure = main.launch {
      val lost = candidate.state.first { it.connectionLost != null }.connectionLost.orEmpty()
      if (pending !== candidate) return@launch
      pending = null
      candidate.stop()
      connectionLost(lost)
    }
    candidate.start()
  }

  private fun projectSwitch(current: Attempt): (String) -> Unit = { hostPath ->
    main.launch { switchProject(current, hostPath) }
  }

  /** The session that opened the connection; the others in [joined] show their project on it. */
  private var opener: ClientSession? = null

  /** The sessions of the other open projects, by host path, e.g. `/work/beta`. */
  private val joined = mutableMapOf<String, ClientSession>()

  /** Ends every session of the connection: the joined ones, then the one that opened it. */
  private fun stopSessions() {
    joined.values.forEach { it.stop() }
    joined.clear()
    (opener ?: session)?.stop()
    opener = null
  }

  /**
   * Another open project is to be shown (a project tab, or a row of the project widget's popup).
   * The backend lists every open project on the one connection, so the project gets a session of
   * its own on it the first time, and after that the screen only changes which session it shows: no
   * new connection, nothing the backend could refuse. Every project stays live.
   *
   * ponytail: the tool window layout on screen is one for all projects (`ToolWindowState` belongs
   * to the screen); give each session its own when that matters. The first project of a link keeps
   * the link's own editors key, the others get `<key>:<host path>`.
   */
  private fun switchProject(current: Attempt, hostPath: String) {
    if (attempt?.link != current.link) return
    val shown = session
    val target = sessionFor(current, hostPath) ?: return
    if (session === target) return
    // A closed project's session goes with its tab; the one that opened the connection stays.
    val open = shown?.state?.value?.openProjects.orEmpty().map { it.hostPath }
    joined.entries
      .filter { (path, session) -> session !== target && open.isNotEmpty() && path !in open }
      .forEach { (path, session) ->
        session.stop()
        joined.remove(path)
      }
    val next =
      Attempt(
        current.kind,
        current.link,
        current.text,
        project = hostPath,
        openFile = null,
        resumed = true,
        editorsKey = current.editorsKey,
      )
    next.identity = attempt?.identity ?: current.identity
    attempt = next
    handOverFrom = null
    session = target
  }

  /**
   * The session that shows the open project at [hostPath], made on the open connection when the
   * project has none yet. It does not change what is on screen.
   */
  private fun sessionFor(current: Attempt, hostPath: String): ClientSession? {
    val first = opener ?: session ?: return null
    return (first.takeIf { it.state.value.shownProject == hostPath }
      ?: joined[hostPath]?.takeUnless { it.projectClosed }
      ?: run {
        // A session whose project was closed is never used again: the project opened again
        // starts from an empty screen.
        joined.remove(hostPath)?.stop()
        lateinit var made: ClientSession
        made =
          ClientSession(
            current.link,
            null,
            null,
            openEditors.slot("${current.editorsKey}:$hostPath"),
            hostPath,
            inForeground = { inForeground },
            onProjectSwitch = { path ->
              main.launch {
                // Its project was closed while another one is on screen (the close button of a
                // tab that is not selected): the screen stays, only the session ends.
                if (made.projectClosed && session !== made) {
                  if (joined[hostPath] === made) joined.remove(hostPath)
                  made.stop()
                } else switchProject(current, path)
              }
            },
            sharedConnection = first.connection,
          )
        joined[hostPath] = made
        made.start()
        made
      })
  }

  /** [sessionFor] for the screen: a project sliding in next to the one shown needs its session. */
  fun projectSession(hostPath: String): ClientSession? = attempt?.let { sessionFor(it, hostPath) }

  /** The open project [shownSession] shows, e.g. `/work/beta`; null before it knows. */
  fun projectPath(shownSession: ClientSession): String? =
    joined.entries.firstOrNull { it.value === shownSession }?.key
      ?: shownSession.state.value.shownProject

  /** Shows the open project at [hostPath] at once (the end of a slide on a phone). */
  fun showProject(hostPath: String) {
    attempt?.let { switchProject(it, hostPath) }
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
    switching = null
    accepted = true
    current.identity = identity
    // The SSH page lists the host's projects itself; a forwarded link is no use after a restart.
    if (current.kind == ConnectionKind.SSH) return
    val previous = saved.firstOrNull { it.key == keyOf(current) }
    val name =
      when (current.kind) {
        ConnectionKind.TERMUX -> "$THIS_DEVICE · ${current.project?.substringAfterLast('/')}"
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
   * Tries [lost]'s link and identity again, after 1 s, 2 s, 4 s, … (at most 10 s apart) until 2
   * minutes of the app being in the foreground have passed ([ForegroundClock]: time in the
   * background, or frozen by the phone, doesn't count), while the old session's screen stays under
   * the reconnect popup; the first session the backend accepts replaces it. The same identity is
   * allowed to use the join token again (docs/split-protocol.md, "Same join token, same client
   * id").
   */
  /** Set by [reconnectOnce] when the backend's certificate changed since the link was made. */
  private var backendRestarted = false

  private fun reconnect(
    lost: Attempt,
    identity: String,
    reason: String,
    /** How long to wait before the first try; see [switchProject]. */
    startAfterMillis: Long = 0,
  ) {
    val certificate = runCatching { ClientCertificate.decode(identity) }.getOrNull()
    if (certificate == null) {
      endSession()
      warn("Connection lost: $reason. Tap it under History to reconnect.")
      return
    }
    reconnectJob?.cancel()
    reconnectJob = main.launch {
      delay(startAfterMillis)
      val clock = ForegroundClock()
      val watching = launch { snapshotFlow { inForeground }.collect(clock::inForeground) }
      var pause = 1_000L
      var number = 0
      while (clock.elapsed() < RECONNECT_FOR_MILLIS) {
        if (!inForeground) {
          // Back in the foreground: try at once.
          snapshotFlow { inForeground }.first { it }
          pause = 1_000L
        }
        number++
        reconnecting = number
        if (reconnectOnce(lost, identity, certificate)) {
          watching.cancel()
          reconnecting = null
          return@launch
        }
        // The backend restarted: a new certificate, so the saved link can never work again.
        if (backendRestarted) {
          watching.cancel()
          reconnecting = null
          endSession()
          if (lost.kind == ConnectionKind.TERMUX) termuxStatus(lost.project, resumeTried = true)
          else warn("The backend was restarted; connect to it again with its new link.")
          return@launch
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(10_000L)
      }
      watching.cancel()
      reconnecting = null
      endSession()
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
    // A project switch starts from an empty screen: the other project's tree, tabs and tool
    // windows are not this project's, and the new tree would never match the old one, so the old
    // one would stay (seen live: beta's tree under alpha's name). [onAccepted] clears [switching].
    val projectSwitch = switching != null
    val shownTree = if (projectSwitch) null else shownBeforeReconnect()?.tree
    val accepted = CompletableDeferred<Boolean>()
    val candidate =
      ClientSession(
        current.link,
        current.openFile,
        certificate,
        openEditors.slot(current.editorsKey),
        current.project,
        expandedPaths =
          shownTree
            ?.let { tree -> tree.expanded.mapNotNull { tree.nodes[it]?.path }.toSet() }
            .orEmpty(),
        selectedPath = shownTree?.let { tree -> tree.selected?.let { tree.nodes[it]?.path } },
        inForeground = { inForeground },
        onProjectSwitch = projectSwitch(current),
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
      // Connection.kt's FingerprintTrustManager: "Server fingerprint … does not match fp …".
      backendRestarted = "does not match fp" in candidate.state.value.connectionLost.orEmpty()
      candidate.stop()
      return false
    }
    handOverFrom = if (projectSwitch) null else shownBeforeReconnect()?.let { candidate to it }
    stopSessions()
    session = candidate
    opener = candidate
    return true
  }

  /** What the screen shows of the session being replaced. */
  private fun shownBeforeReconnect(): SessionState? =
    lastShown?.takeIf { it.first === session }?.second ?: session?.state?.value

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
    openTermux(backend.project)
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
                  "Another IntelliJ process is using the same settings, so a backend can't start. Close it, then try again."
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
    stopping = localBackends.firstOrNull()?.project ?: "Termux"
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
    // A first connection still waiting for the backend: stop it, no message.
    if (!accepted && pending != null) {
      pending?.stop()
      pending = null
      attempt = null
      return
    }
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

  /** Asks Termux for its backend (docs/connections.md, "Discovery"). */
  fun refresh() {
    termuxAccess = termux?.let {
      when {
        !it.isInstalled -> TermuxAccess.NOT_INSTALLED
        !it.hasPermission -> TermuxAccess.NO_PERMISSION
        else -> TermuxAccess.READY
      }
    }
    if (termuxAccess != TermuxAccess.READY) {
      localBackends = emptyList()
      return
    }
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
                    "$THIS_DEVICE · ${project.substringAfterLast('/')}",
                    port,
                    project,
                    joinLink,
                  )
                }
              localBackends = backends
            }
            else -> localBackends = emptyList()
          }
        },
        onFailure = { failure -> fail(failure.message ?: failure.toString()) },
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
 * How long after the new session says it has loaded that panels it hasn't sent anything for show
 * its (empty) value: the status bar and breadcrumbs arrive a little after the project loads.
 */
private const val SETTLE_MS = 2_000L

/** After that, the project name, tree and terminals show the new value even if none came. */
private const val FORCE_MS = 15_000L

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
  BoxWithConstraints(Modifier.fillMaxSize().background(Islands.windowBackground)) {
    val phone = screenLayout(maxWidth.value, maxHeight.value) != ScreenLayout.WIDE
    // collectAsState alone keeps the old session's last value for a frame after [session] changes,
    // which looked like "the new session has sent everything"; so read the value itself. Reading
    // the collected value is what makes this recompose on a change.
    val collected by session.state.collectAsState()
    val current = collected.let { session.state.value }
    val source = controller.handOverFrom
    val previous = source?.second
    // False for a frame after a reconnect: the screen still has the old session.
    val incoming = source?.first === session
    var switched by remember(previous) { mutableStateOf(emptySet<String>()) }
    var settled by remember(previous) { mutableStateOf(false) }
    var forced by remember(previous) { mutableStateOf(false) }
    LaunchedEffect(previous, incoming && current.loaded) {
      if (previous != null && incoming && current.loaded) {
        delay(SETTLE_MS)
        settled = true
        delay(FORCE_MS)
        forced = true
      }
    }
    val handedOver = previous?.let {
      handOver(it, if (incoming) current else SessionState(), switched, settled, forced)
    }
    val shown = handedOver?.shown ?: current
    // A session that broke clears its own state; keep showing what was there before.
    val state =
      if (current.connectionLost != null)
        controller.lastShown?.takeIf { it.first === session }?.second ?: shown
      else shown
    if (current.connectionLost == null && (source == null || incoming))
      controller.lastShown = session to shown
    LaunchedEffect(handedOver?.switched, handedOver?.done) {
      handedOver?.let {
        switched = it.switched
        if (it.done) controller.handOverFrom = null
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
    val windows = remember {
      ToolWindowState().also { opened ->
        controller.restoredWindows?.let { opened.restore(it, toolWindowButtons(state.toolWindows)) }
      }
    }
    controller.windows = windows
    if (!phone) ClientScreen(state, session, insets = modifier, windows = windows)
    else {
      // A phone has no project tabs: the projects are pages that slide, title bar included, as
      // the island pages do (SlidePager). A drag that starts on the title bar moves them with the
      // finger; a project chosen in the project popup slides in as well.
      // ponytail: a tablet turned between upright and sideways changes branch here, which resets
      // what ClientScreen remembers (its page, the notice); movableContentOf if that matters.
      val titleBar = remember { ProjectSwipeArea() }
      SlidePager(
        current = session,
        neighbour = { step ->
          neighbourProject(state.openProjects, controller.projectPath(session), step)
            ?.let(controller::projectSession)
        },
        order = { shownSession ->
          state.openProjects.indexOfFirst { it.hostPath == controller.projectPath(shownSession) }
        },
        onShow = { target -> controller.projectPath(target)?.let(controller::showProject) },
        canSlide = { down, _ ->
          state.openProjects.size > 1 && down.y >= titleBar.top && down.y <= titleBar.bottom
        },
        // Shows in the gap and behind the rounded corners while the projects slide.
        modifier = Modifier.fillMaxSize().background(Islands.islandBackground),
      ) { shownSession, radius ->
        // The project sliding in or out shows its own state.
        val shownState =
          if (shownSession === session) state else shownSession.state.collectAsState().value
        val shape = RoundedCornerShape(radius)
        Box(Modifier.fillMaxSize().clip(shape)) {
          CompositionLocalProvider(LocalProjectSwipeArea provides titleBar) {
            ClientScreen(shownState, shownSession, insets = modifier, windows = windows)
          }
          // An outline while the project slides, fading in and out with the corners: the backdrop
          // alone is nearly the editor's colour, so the gap would not show between two editors.
          if (radius > 0.dp)
            Box(
              Modifier.matchParentSize()
                .border(
                  1.dp,
                  Islands.controlBorder.copy(alpha = radius / Islands.islandRadius),
                  shape,
                ),
            )
        }
      }
    }
    controller.reconnecting?.let { attemptNumber ->
      val project = controller.switching
      if (project != null)
        StartProgress("Opening Project", project, fonts, onCancel = controller::leave)
      else
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
