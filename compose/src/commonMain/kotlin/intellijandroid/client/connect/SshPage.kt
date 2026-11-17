package intellijandroid.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import intellijandroid.client.ssh.LoginMethod
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.DialogButton
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.MenuItem
import intellijandroid.client.ui.MessageDialog
import intellijandroid.client.ui.MessageOption
import kotlinx.coroutines.delay

/**
 * Gateway's SSH page (captured from Gateway 2026.3, 2026-11-17): "Recent SSH Projects" with New
 * Project, a search row, then one group per host (`user@host`, its state at the right, Reconnect,
 * Open Project, the host menu) with the host's recent projects under it.
 */
@Composable
internal fun SshPage(state: SshPageState, actions: SshActions, styles: ConnectStyles) {
  var search by remember { mutableStateOf("") }
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
    OutlinedButton("New Project", styles, actions.newConnection)
  }
  SearchRow(search, { search = it }, styles)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    state.message?.let { Notice(it, styles, color = IslandsDark.text) }
    if (state.hosts.isEmpty()) {
      Notice("Work with a project on a machine reachable using SSH", styles)
      Box(Modifier.padding(horizontal = Gateway.contentInset)) {
        OutlinedButton("New Connection", styles, actions.newConnection)
      }
    }
    state.hosts.forEachIndexed { index, host ->
      if (index > 0)
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(Gateway.divider))
      val reachable = host.phase == SshHostView.Phase.READY
      val (status, color) =
        when (host.phase) {
          SshHostView.Phase.CONNECTING -> "Connecting…" to IslandsDark.textSecondary
          SshHostView.Phase.READY -> null to IslandsDark.textSecondary
          SshHostView.Phase.UNREACHABLE -> "Host unreachable" to Gateway.error
          SshHostView.Phase.AUTHENTICATE -> "Authenticate…" to IslandsDark.link
        }
      HostGroup(
        host.entry.name,
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
          listOf(
            MenuItem("Reconnect") { actions.reconnect(host.entry) },
            MenuItem("Open Project") { actions.newProjectOn(host.entry) },
            MenuItem("Remove Host") { actions.removeHost(host.entry) },
          ),
        status = status,
        statusColor = color,
        onStatusClick =
          if (host.phase == SshHostView.Phase.AUTHENTICATE)
            ({ actions.authenticateHost(host.entry) })
          else null,
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
            onStop = null,
            onRemove = { actions.removeRecent(host.entry, project) },
            enabled = reachable,
            middleEllipsis = true,
          ),
          styles,
        )
      }
      host.error?.takeIf { host.phase != SshHostView.Phase.READY }?.let { Notice(it, styles) }
    }
  }
}

/** The SSH dialogs, the folder browser and the progress popup, above whichever page shows. */
@Composable
internal fun SshOverlays(state: SshPageState, actions: SshActions, fonts: ClientFonts) {
  var browsing by remember { mutableStateOf(false) }
  when (val dialog = state.dialog) {
    is SshDialog.NewConnection -> NewConnectionDialog(dialog, state.offersOpenSsh, actions, fonts)
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
  // Last, so it is above the Connect to SSH dialog that is waiting for the answer.
  state.question?.let { question -> QuestionDialog(question, actions) }
}

/**
 * A host key question (unknown or changed key) in IntelliJ's message dialog, while the connection
 * waits. It used to show in the page, under the modal Connect to SSH dialog, where its buttons
 * couldn't be reached.
 */
@Composable
private fun QuestionDialog(question: Question, actions: SshActions) =
  MessageDialog(
    title = "SSH",
    message = question.text,
    kind = question.kind,
    options =
      listOf(
        MessageOption(question.confirm, actions.answerQuestion),
        MessageOption("Cancel", actions.cancelQuestion),
      ),
    onCancel = actions.cancelQuestion,
  )

/**
 * Gateway's "Connect to SSH": Username, Host, Port, how to log in, Check Connection and Continue.
 */
@Composable
private fun NewConnectionDialog(
  dialog: SshDialog.NewConnection,
  offersOpenSsh: Boolean,
  actions: SshActions,
  fonts: ClientFonts,
) {
  val form = dialog.form
  val styles = DialogStyles(fonts)
  DialogFrame("Connect to SSH", styles, actions.cancelDialog) {
    LabeledField("Username:", form.user, { actions.formChanged(form.copy(user = it)) }, styles)
    LabeledField("Host:", form.host, { actions.formChanged(form.copy(host = it)) }, styles)
    LabeledField("Port:", form.port, { actions.formChanged(form.copy(port = it)) }, styles)
    BasicText("Authentication:", style = styles.label)
    Choice(
      "IDEate key (add it to the host's ~/.ssh/authorized_keys)",
      form.method == LoginMethod.GENERATED_KEY,
      styles,
    ) {
      actions.formChanged(form.copy(method = LoginMethod.GENERATED_KEY))
    }
    if (form.method == LoginMethod.GENERATED_KEY) {
      dialog.publicKey?.let { publicKey ->
        val clipboard = LocalClipboardManager.current
        BasicText(publicKey, style = styles.code, modifier = Modifier.padding(start = 24.dp))
        Box(Modifier.padding(start = 24.dp)) {
          DialogButton("Copy Public Key", styles.label, primary = false) {
            clipboard.setText(AnnotatedString(publicKey))
          }
        }
      }
    }
    Choice("Specify private key", form.method == LoginMethod.KEY_FILE, styles) {
      actions.formChanged(form.copy(method = LoginMethod.KEY_FILE))
    }
    if (form.method == LoginMethod.KEY_FILE) {
      LabeledField(
        "Private key (paste the file's text):",
        form.privateKey,
        { actions.formChanged(form.copy(privateKey = it)) },
        styles,
        singleLine = false,
      )
      LabeledField(
        "Key passphrase, if any:",
        form.passphrase,
        { actions.formChanged(form.copy(passphrase = it)) },
        styles,
        secret = true,
      )
    }
    Choice("Password", form.method == LoginMethod.PASSWORD, styles) {
      actions.formChanged(form.copy(method = LoginMethod.PASSWORD))
    }
    if (form.method == LoginMethod.PASSWORD) {
      LabeledField(
        "Password:",
        form.password,
        { actions.formChanged(form.copy(password = it)) },
        styles,
        secret = true,
      )
      Choice("Save password", form.savePassword, styles) {
        actions.formChanged(form.copy(savePassword = !form.savePassword))
      }
    }
    if (offersOpenSsh) {
      Choice(
        "OpenSSH config and authentication agent",
        form.method == LoginMethod.OPEN_SSH,
        styles,
      ) {
        actions.formChanged(form.copy(method = LoginMethod.OPEN_SSH))
      }
    }
    dialog.error?.let { BasicText(it, style = styles.error) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
      DialogButton("Cancel", styles.label, primary = false, onClick = actions.cancelDialog)
      Spacer(Modifier.width(8.dp))
      DialogButton(
        if (dialog.checking) "Checking Connection…" else "Check Connection and Continue",
        styles.label,
        primary = true,
      ) {
        if (!dialog.checking) actions.checkAndContinue()
      }
    }
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
  val styles = DialogStyles(fonts)
  DialogFrame("Choose IDE and Project", styles, actions.cancelDialog) {
    BasicText(dialog.host.name, style = styles.label.copy(color = IslandsDark.textSecondary))
    BasicText("IDE version:", style = styles.label)
    dialog.ides.forEach { ide ->
      Choice(
        "${ide.name} ${ide.version} (${ide.buildNumber}) installed",
        ide == dialog.ide,
        styles,
      ) {
        actions.ideChosen(ide)
      }
    }
    BasicText("Project directory:", style = styles.label)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
        InputField(dialog.projectPath, actions.projectPathChanged, styles)
      }
      Spacer(Modifier.width(6.dp))
      DialogButton("…", styles.label, primary = false, onClick = onBrowse)
    }
    dialog.error?.let { BasicText(it, style = styles.error) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
      DialogButton("Cancel", styles.label, primary = false, onClick = actions.cancelDialog)
      Spacer(Modifier.width(8.dp))
      DialogButton(
        "Start IDE and Connect",
        styles.label,
        primary = true,
        onClick = actions.startAndConnect,
      )
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
  val styles = DialogStyles(fonts)
  var typed by remember(dialog) { mutableStateOf("") }
  DialogFrame("Authentication", styles, actions.cancelDialog) {
    BasicText("Authenticating to: ${dialog.host.name}", style = styles.label)
    LabeledField(dialog.prompt, typed, { typed = it }, styles, secret = true)
    dialog.error?.let { BasicText(it, style = styles.error) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
      DialogButton("Cancel", styles.label, primary = false, onClick = actions.cancelDialog)
      Spacer(Modifier.width(8.dp))
      DialogButton("Authenticate", styles.label, primary = true) { actions.authenticate(typed) }
    }
  }
}

private class DialogStyles(fonts: ClientFonts) {
  val label =
    TextStyle(
      color = IslandsDark.text,
      fontFamily = fonts.ui,
      fontSize = IslandsDark.UI_FONT_SIZE.sp,
    )
  val title = label.copy(fontWeight = FontWeight.SemiBold)
  val error = label.copy(color = Gateway.error)
  val code =
    TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.editor, fontSize = 11.sp)
  val input =
    TextStyle(
      color = IslandsDark.text,
      fontFamily = fonts.editor,
      fontSize = IslandsDark.EDITOR_FONT_SIZE.sp,
    )
}

/** The frame of IntelliJ's dialogs, as measured for the folder chooser: 475 wide, title 32 high. */
@Composable
private fun DialogFrame(
  title: String,
  styles: DialogStyles,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      Modifier.width(DialogLook.width)
        .heightIn(max = DialogLook.maxHeight)
        .background(IslandsDark.islandBackground, DialogLook.shape)
        .border(1.dp, DialogLook.outline, DialogLook.shape),
    ) {
      Box(
        Modifier.fillMaxWidth().height(DialogLook.titleHeight).padding(start = 14.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        BasicText(title, style = styles.title)
      }
      Column(
        Modifier.verticalScroll(rememberScrollState())
          .padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        content()
      }
    }
  }
}

@Composable
private fun LabeledField(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  styles: DialogStyles,
  secret: Boolean = false,
  singleLine: Boolean = true,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    BasicText(label, style = styles.label)
    InputField(value, onChange, styles, secret, singleLine)
  }
}

@Composable
private fun InputField(
  value: String,
  onChange: (String) -> Unit,
  styles: DialogStyles,
  secret: Boolean = false,
  singleLine: Boolean = true,
) {
  BasicTextField(
    value = value,
    onValueChange = onChange,
    textStyle = styles.input,
    singleLine = singleLine,
    visualTransformation =
      if (secret) PasswordVisualTransformation()
      else androidx.compose.ui.text.input.VisualTransformation.None,
    cursorBrush = SolidColor(IslandsDark.text),
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(
          min = DialogLook.fieldHeight,
          max = if (singleLine) DialogLook.fieldHeight else 160.dp,
        )
        .border(1.dp, DialogLook.fieldBorder, DialogLook.fieldShape)
        .padding(horizontal = 6.dp, vertical = 4.dp),
  )
}

/**
 * A radio button row (or a check box for Save password): IntelliJ's 14 px circle, accent when on.
 */
@Composable
private fun Choice(label: String, selected: Boolean, styles: DialogStyles, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(14.dp)
        .border(1.dp, if (selected) DialogLook.accent else DialogLook.fieldBorder, CircleShape)
        .padding(4.dp)
        .background(if (selected) DialogLook.accent else Color.Transparent, CircleShape),
    )
    BasicText(
      label,
      style = styles.label,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/** The values measured on IntelliJ's "Open File or Project" dialog (FolderChooser's Chooser). */
private object DialogLook {
  val width = 475.dp
  val maxHeight = 600.dp
  val shape = RoundedCornerShape(10.dp)
  val outline = Color(0xFF393B40)
  val titleHeight = 32.dp
  val fieldHeight = 26.dp
  val fieldShape = RoundedCornerShape(4.dp)
  val fieldBorder = Color(0xFF4E5157)
  val accent = Color(0xFF4670DA)
}
