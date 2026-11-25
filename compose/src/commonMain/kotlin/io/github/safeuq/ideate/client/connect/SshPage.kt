package io.github.safeuq.ideate.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.safeuq.ideate.client.ssh.LoginMethod
import io.github.safeuq.ideate.client.ssh.OpenSshKeyState
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.component.CheckboxRow
import io.github.safeuq.ideate.client.ui.component.OutlinedButton
import io.github.safeuq.ideate.client.ui.component.RadioButtonRow
import io.github.safeuq.ideate.client.ui.component.TextField
import io.github.safeuq.ideate.client.ui.dialog.DialogAction
import io.github.safeuq.ideate.client.ui.dialog.DialogWrapper
import io.github.safeuq.ideate.client.ui.dialog.MessageDialog
import io.github.safeuq.ideate.client.ui.dialog.MessageKind
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.editor
import io.github.safeuq.ideate.client.ui.theme.label
import kotlinx.coroutines.delay

/**
 * Gateway's SSH page (captured from Gateway 2026.3, 2026-11-17): "Recent SSH Projects" with New
 * Project, a search row, then one group per host (`user@host`, its state at the right, Reconnect,
 * Open Project, the host menu) with the host's recent projects under it.
 */
@Composable
internal fun SshPage(state: SshPageState, actions: SshActions, styles: ConnectStyles) {
  var search by remember { mutableStateOf("") }
  // The host whose error popup is open, from a tap on its "Host unreachable".
  var shownError by remember { mutableStateOf<SshHostView?>(null) }
  // Gateway's polling while the page shows: every 10 s, unreachable hosts every 30 s.
  LaunchedEffect(Unit) {
    actions.poll(true)
    var tick = 0
    while (true) {
      delay(10_000)
      tick++
      actions.poll(tick % 3 == 0)
    }
  }
  Header("Recent SSH Projects", styles) {
    OutlinedButton("New Project", onClick = actions.newConnection)
  }
  SearchRow(search, { search = it }, styles)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    if (state.hosts.isEmpty()) {
      Notice("Work with a project on a machine reachable using SSH", styles)
      Box(Modifier.padding(horizontal = Gateway.contentInset)) {
        OutlinedButton("New Connection", onClick = actions.newConnection)
      }
    }
    state.hosts.forEachIndexed { index, host ->
      if (index > 0)
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(Gateway.divider))
      HostSection(host, host.entry.name, removable = true, search, actions, styles) {
        shownError = it
      }
    }
  }
  shownError?.let { host ->
    MessageDialogOk(
      ConnectMessage(host.error.orEmpty(), MessageKind.ERROR, title = host.entry.name),
    ) {
      shownError = null
    }
  }
}

/**
 * One host's group, its state and menu, and its recent projects: a host on the SSH page, or this
 * computer on the desktop's This device page ([removable] false: no Remove Host).
 */
@Composable
internal fun HostSection(
  host: SshHostView,
  title: String,
  removable: Boolean,
  search: String,
  actions: SshActions,
  styles: ConnectStyles,
  onShowError: (SshHostView) -> Unit,
) {
  val reachable = host.phase == SshHostView.Phase.READY
  val (status, color) =
    when (host.phase) {
      SshHostView.Phase.CONNECTING -> "Connecting…" to Islands.textSecondary
      SshHostView.Phase.READY -> null to Islands.textSecondary
      SshHostView.Phase.UNREACHABLE -> "Host unreachable" to Gateway.error
      SshHostView.Phase.AUTHENTICATE -> "Authenticate…" to Islands.link
    }
  HostGroup(
    title,
    styles,
    icons =
      buildList {
        if (host.phase == SshHostView.Phase.UNREACHABLE) {
          add(
            HeaderIcon("expui/general/refresh.svg", "Reconnect") {
              actions.reconnect(host.entry)
            },
          )
        }
        add(
          HeaderIcon("expui/general/add.svg", "Open Project") {
            actions.newProjectOn(host.entry)
          },
        )
      },
    menu =
      listOfNotNull(
        MenuItem("Reconnect") { actions.reconnect(host.entry) },
        MenuItem("Open Project") { actions.newProjectOn(host.entry) },
        if (removable) MenuItem("Remove Host") { actions.removeHost(host.entry) } else null,
      ),
    status = status,
    statusColor = color,
    onStatusClick =
      when {
        host.phase == SshHostView.Phase.AUTHENTICATE -> ({
            actions.authenticateHost(host.entry)
          })
        host.phase == SshHostView.Phase.UNREACHABLE && host.error != null -> ({
            onShowError(host)
          })
        else -> null
      },
  )
  val projects =
    host.projects.filter {
      search.isBlank() || it.projectPath.contains(search.trim(), ignoreCase = true)
    }
  if (reachable && host.projects.isEmpty())
    Notice("Projects will appear after the first connection…", styles)
  projects.forEach { project ->
    ProjectRowView(
      ProjectRow(
        title = project.projectPath,
        running = project.running,
        lastActive = project.dateLastOpened,
        onOpen = { actions.openProject(host.entry, project) },
        onStop = if (project.running) ({ actions.stopProject(host.entry, project) }) else null,
        onRemove = { actions.removeRecent(host.entry, project) },
        enabled = reachable,
        middleEllipsis = true,
      ),
      styles,
    )
  }
}

/** The SSH dialogs, the folder browser and the progress popup, above whichever page shows. */
@Composable
internal fun SshOverlays(state: SshPageState, actions: SshActions, fonts: ClientFonts) {
  var browsing by remember { mutableStateOf(false) }
  when (val dialog = state.dialog) {
    is SshDialog.NewConnection ->
      NewConnectionDialog(
        dialog,
        state.offersOpenSsh,
        state.openSshLabel,
        state.openSshFolder,
        state.openSshMissingKeyText,
        actions,
        fonts,
      )
    is SshDialog.ChooseIde ->
      if (browsing) {
        FolderChooser(
          title = "Choose Remote Project",
          initialPath = dialog.projectPath.ifBlank { dialog.home },
          listFolders = actions.listFolders(dialog.host),
          onChoose = { folder ->
            browsing = false
            actions.projectPathChanged(folder)
          },
          onCancel = { browsing = false },
          root = dialog.root,
          home = dialog.home,
        )
      } else ChooseIdeDialog(dialog, actions, fonts, onBrowse = { browsing = true })
    is SshDialog.Authenticate -> AuthenticateDialog(dialog, actions, fonts)
    null -> Unit
  }
  state.progress?.let { StartProgress(it.title, it.detail, fonts, actions.cancelProgress) }
  state.message?.let { message -> MessageDialogOk(message, actions.dismissMessage) }
  // Last, so it is above the Connect to SSH dialog that is waiting for the answer.
  state.question?.let { question -> QuestionDialog(question, actions) }
}

/**
 * A host key question (unknown or changed key) in IntelliJ's message dialog, while the connection
 * waits. It used to show in the page, under the modal Connect to SSH dialog, where its buttons
 * couldn't be reached.
 */
@Composable
private fun QuestionDialog(question: Question, actions: SshActions) {
  val cancel = DialogAction("Cancel", onClick = actions.cancelQuestion)
  // Gateway's title; Cancel starts focused, so a stray Enter doesn't trust a key.
  MessageDialog(
    title = "Connecting To Remote Host",
    message = question.text,
    kind = question.kind,
    actions = listOf(DialogAction(question.confirm, onClick = actions.answerQuestion), cancel),
    onCancel = actions.cancelQuestion,
    focused = cancel,
  )
}

/**
 * Gateway's "Connect to SSH": Username, Host, Port, how to log in, Check Connection and Continue.
 */
@Composable
private fun NewConnectionDialog(
  dialog: SshDialog.NewConnection,
  offersOpenSsh: Boolean,
  openSshLabel: String,
  openSshFolder: String,
  openSshMissingKeyText: String,
  actions: SshActions,
  fonts: ClientFonts,
) {
  val form = dialog.form
  DialogWrapper(
    "Connect to SSH",
    actions =
      listOf(
        DialogAction(
          if (dialog.checking) "Checking Connection…" else "Check Connection and Continue",
        ) {
          if (!dialog.checking) actions.checkAndContinue()
        },
        DialogAction("Cancel", onClick = actions.cancelDialog),
      ),
    onCancel = actions.cancelDialog,
    error = dialog.error,
  ) {
    LabeledField("Username:", form.user, { actions.formChanged(form.copy(user = it)) })
    LabeledField("Host:", form.host, { actions.formChanged(form.copy(host = it)) })
    LabeledField("Port:", form.port, { actions.formChanged(form.copy(port = it)) })
    BasicText("Authentication:", style = fonts.label())
    RadioButtonRow(
      "Ideate key (add it to the host's ~/.ssh/authorized_keys)",
      form.method == LoginMethod.GENERATED_KEY,
    ) {
      actions.formChanged(form.copy(method = LoginMethod.GENERATED_KEY))
    }
    if (form.method == LoginMethod.GENERATED_KEY) {
      dialog.publicKey?.let { publicKey -> PublicKeyBlock(publicKey, fonts) }
    }
    RadioButtonRow("Specify private key", form.method == LoginMethod.KEY_FILE) {
      actions.formChanged(form.copy(method = LoginMethod.KEY_FILE))
    }
    if (form.method == LoginMethod.KEY_FILE) {
      LabeledField(
        "Private key (paste the file's text):",
        form.privateKey,
        { actions.formChanged(form.copy(privateKey = it)) },
        singleLine = false,
      )
      LabeledField(
        "Key passphrase, if any:",
        form.passphrase,
        { actions.formChanged(form.copy(passphrase = it)) },
        secret = true,
      )
    }
    RadioButtonRow("Password", form.method == LoginMethod.PASSWORD) {
      actions.formChanged(form.copy(method = LoginMethod.PASSWORD))
    }
    if (form.method == LoginMethod.PASSWORD) {
      LabeledField(
        "Password:",
        form.password,
        { actions.formChanged(form.copy(password = it)) },
        secret = true,
      )
      CheckboxRow("Save password", form.savePassword) { checked ->
        actions.formChanged(form.copy(savePassword = checked))
      }
    }
    if (offersOpenSsh) {
      RadioButtonRow(openSshLabel, form.method == LoginMethod.OPEN_SSH) {
        actions.formChanged(form.copy(method = LoginMethod.OPEN_SSH))
      }
      if (form.method == LoginMethod.OPEN_SSH) {
        val note = fonts.label(Islands.textSecondary)
        when (val key = dialog.openSshKey) {
          null -> {}
          OpenSshKeyState.Loading ->
            BasicText(
              "Reading $openSshFolder…",
              style = note,
              modifier = Modifier.padding(start = 28.dp),
            )
          is OpenSshKeyState.Found -> {
            BasicText(
              "Key from $openSshFolder (add it to the host's ~/.ssh/authorized_keys)",
              style = note,
              modifier = Modifier.padding(start = 28.dp),
            )
            PublicKeyBlock(key.line, fonts)
          }
          OpenSshKeyState.Missing ->
            BasicText(
              openSshMissingKeyText,
              style = note,
              modifier = Modifier.padding(start = 28.dp),
            )
          is OpenSshKeyState.Failed ->
            BasicText(
              "Couldn't read $openSshFolder: ${key.reason}. Connecting may still work through " +
                "the ssh-agent.",
              style = note,
              modifier = Modifier.padding(start = 28.dp),
            )
        }
      }
    }
  }
}

/** A public key line and its Copy button, indented under its radio button. */
@Composable
private fun PublicKeyBlock(publicKey: String, fonts: ClientFonts) {
  val clipboard = LocalClipboardManager.current
  BasicText(
    publicKey,
    style = fonts.editor(Islands.textSecondary).copy(fontSize = 11.sp),
    modifier = Modifier.padding(start = 28.dp),
  )
  Box(Modifier.padding(start = 28.dp)) {
    OutlinedButton("Copy Public Key") { clipboard.setText(AnnotatedString(publicKey)) }
  }
}

/** Gateway's "Choose IDE and Project": IDE version, Project directory, Start IDE and Connect. */
@Composable
private fun ChooseIdeDialog(
  dialog: SshDialog.ChooseIde,
  actions: SshActions,
  fonts: ClientFonts,
  onBrowse: () -> Unit,
) {
  DialogWrapper(
    "Choose IDE and Project",
    actions =
      listOf(
        DialogAction("Start IDE and Connect", onClick = actions.startAndConnect),
        DialogAction("Cancel", onClick = actions.cancelDialog),
      ),
    onCancel = actions.cancelDialog,
    error = dialog.error,
  ) {
    BasicText(
      if (dialog.host.id == LOCAL_HOST_ID) THIS_DEVICE else dialog.host.name,
      style = fonts.label(Islands.textSecondary),
    )
    BasicText("IDE version:", style = fonts.label())
    dialog.ides.forEach { ide ->
      RadioButtonRow(
        "${ide.name} ${ide.version} (${ide.buildNumber}) installed",
        ide == dialog.ide,
      ) {
        actions.ideChosen(ide)
      }
    }
    BasicText("Project directory:", style = fonts.label())
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
        TextField(dialog.projectPath, actions.projectPathChanged)
      }
      Spacer(Modifier.width(6.dp))
      OutlinedButton("…", onClick = onBrowse)
    }
  }
}

/** Gateway's "Authentication": "Authenticating to:", then Password or Key passphrase. */
@Composable
private fun AuthenticateDialog(
  dialog: SshDialog.Authenticate,
  actions: SshActions,
  fonts: ClientFonts,
) {
  var typed by remember(dialog) { mutableStateOf("") }
  DialogWrapper(
    "Authentication",
    actions =
      listOf(
        DialogAction("Authenticate") { actions.authenticate(typed) },
        DialogAction("Cancel", onClick = actions.cancelDialog),
      ),
    onCancel = actions.cancelDialog,
    error = dialog.error,
  ) {
    BasicText("Authenticating to: ${dialog.host.name}", style = fonts.label())
    LabeledField(dialog.prompt, typed, { typed = it }, secret = true)
  }
}

@Composable
private fun LabeledField(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  secret: Boolean = false,
  singleLine: Boolean = true,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    BasicText(label, style = LocalClientFonts.current.label())
    TextField(value, onChange, secret = secret, singleLine = singleLine)
  }
}
