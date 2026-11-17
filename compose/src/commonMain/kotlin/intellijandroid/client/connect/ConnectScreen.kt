package intellijandroid.client.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.ui.ContextMenuArea
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.MenuItem
import intellijandroid.client.ui.component.OutlinedButton
import intellijandroid.client.ui.component.TextField
import intellijandroid.client.ui.dialog.MessageKind
import intellijandroid.client.ui.theme.ClientFonts
import intellijandroid.client.ui.theme.IslandsDark
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the connect screen shows; [ConnectController] owns it. */
data class ConnectScreenState(
  /** `This phone` or `This computer`: the first page's label. */
  val device: String,
  val page: ConnectPage,
  val link: String,
  val file: String,
  val discovered: List<DiscoveredBackend>,
  val history: List<SavedConnection>,
  /**
   * Null where there is no Termux (the desktop): the first page then lists open local ports only.
   */
  val termux: TermuxAccess?,
  /** Work in progress, e.g. "Asking Termux for the backend's link…"; taps are ignored meanwhile. */
  val busy: String?,
  /** The last error or hint. */
  val message: String?,
  /** A yes/no question, e.g. "Take over?" when Termux's backend has another client. */
  val question: Question?,
  /**
   * `intellij-backend start` is running for this project: shows Gateway's "Starting the remote IDE
   * Backend" popup.
   */
  val starting: String?,
  /** The SSH page (SshController). */
  val ssh: SshPageState = SshPageState(),
)

/** The sidebar's connection kinds: this device, Gateway's SSH and Link. */
enum class ConnectPage {
  PHONE,
  SSH,
  LINK,
}

enum class TermuxAccess {
  NOT_INSTALLED,
  NO_PERMISSION,
  READY,
}

class Question(
  val text: String,
  val confirm: String,
  /** The message dialog's icon: a question, or a warning for a changed host key. */
  val kind: MessageKind = MessageKind.QUESTION,
  val onConfirm: () -> Unit,
)

class ConnectActions(
  val pageChanged: (ConnectPage) -> Unit,
  val linkChanged: (String) -> Unit,
  val fileChanged: (String) -> Unit,
  val connectLink: () -> Unit,
  val openDiscovered: (DiscoveredBackend) -> Unit,
  val openSaved: (SavedConnection) -> Unit,
  val removeSaved: (SavedConnection) -> Unit,
  val allowTermux: () -> Unit,
  /** Starts a backend in Termux for the chosen folder. */
  val newProject: (String) -> Unit,
  val stopTermux: () -> Unit,
  val listFolders: (path: String, onResult: (Result<List<Termux.Folder>>) -> Unit) -> Unit,
  val refresh: () -> Unit,
  val answerQuestion: () -> Unit,
  val cancelQuestion: () -> Unit,
  val cancelStart: () -> Unit,
  val ssh: SshActions? = null,
)

/**
 * Gateway's welcome window ("Welcome to JetBrains Gateway", 2026.3): connection kinds in a sidebar,
 * recent projects grouped by host on the right. Sizes measured on a 2x screenshot, in dp = pt
 * (docs/intellij-look.md §10). Left out: the logo, All Providers, the settings gear and New
 * Project's drop-down. Upright, the sidebar becomes a tab bar at the bottom (Gateway has no
 * portrait layout).
 */
@Composable
fun ConnectScreen(state: ConnectScreenState, actions: ConnectActions, fonts: ClientFonts) {
  var choosingFolder by remember { mutableStateOf(false) }
  BoxWithConstraints(Modifier.fillMaxSize().background(Gateway.background)) {
    // Upright, the sidebar would take a third of the width for two entries: the connection kinds
    // move to a tab bar
    // at the bottom, like the main window's tool window bar, and project rows put the status under
    // the path.
    val portrait = maxHeight > maxWidth
    val styles = ConnectStyles(fonts, twoLineRows = portrait)
    @Composable
    fun Page(modifier: Modifier) =
      Column(modifier) {
        when (state.page) {
          ConnectPage.PHONE ->
            PhonePage(state, actions, styles, onNewProject = { choosingFolder = true })
          ConnectPage.SSH -> actions.ssh?.let { SshPage(state.ssh, it, styles) }
          ConnectPage.LINK -> LinkPage(state, actions, styles)
        }
      }
    if (portrait) {
      Column(Modifier.fillMaxSize()) {
        Page(Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
        BottomTabs(state.device, state.page, actions.pageChanged, styles)
      }
    } else {
      Row(Modifier.fillMaxSize()) {
        Sidebar(
          state.device,
          state.page,
          actions.pageChanged,
          styles,
          Modifier.width(Gateway.sidebarWidth),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(Gateway.divider))
        Page(Modifier.weight(1f).fillMaxHeight())
      }
    }
  }
  if (choosingFolder) {
    FolderChooser(
      title = "Select Project Folder",
      initialPath = "${Termux.HOME}/",
      listFolders = actions.listFolders,
      onChoose = { folder ->
        choosingFolder = false
        actions.newProject(folder)
      },
      onCancel = { choosingFolder = false },
    )
  }
  state.starting?.let { project ->
    StartProgress(
      "Starting the remote IDE Backend",
      "Running intellij-backend start for ${project.trimEnd('/').substringAfterLast('/')}; this takes up to 3 minutes",
      fonts,
      actions.cancelStart,
    )
  }
  actions.ssh?.let { ssh -> SshOverlays(state.ssh, ssh, fonts) }
}

/**
 * The two connection kinds side by side; the selected one gets the stripe buttons' selected
 * background.
 */
@Composable
private fun BottomTabs(
  device: String,
  page: ConnectPage,
  onSelect: (ConnectPage) -> Unit,
  styles: ConnectStyles,
) {
  Row(
    Modifier.fillMaxWidth().height(Gateway.tabBarHeight).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    pages(device).forEach { (target, iconPath, label) ->
      val icon = LocalIcons.current.painter(iconPath)
      Row(
        Modifier.weight(1f)
          .padding(horizontal = 2.dp)
          .height(Gateway.tabHeight)
          .background(
            if (page == target) Gateway.tabSelected else Color.Transparent,
            Gateway.tabShape,
          )
          .clickable { onSelect(target) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        BasicText(label, style = styles.body, modifier = Modifier.padding(start = 6.dp))
      }
    }
  }
}

/**
 * The connection kinds: page, icon, label. SSH takes Gateway's `ssh.svg`; this device gets a phone
 * (IntelliJ has none, so it's inlined in IconJar) or the platform's desktop icon.
 */
private fun pages(device: String) =
  listOf(
    Triple(
      ConnectPage.PHONE,
      if (device == "This phone") "ideate/phone.svg" else "expui/nodes/desktop.svg",
      device,
    ),
    Triple(ConnectPage.SSH, "expui/nodes/ssh.svg", "SSH"),
    Triple(ConnectPage.LINK, "markdown/link.svg", "Link"),
  )

@Composable
private fun Sidebar(
  device: String,
  page: ConnectPage,
  onSelect: (ConnectPage) -> Unit,
  styles: ConnectStyles,
  modifier: Modifier,
) {
  @Composable
  fun Item(target: ConnectPage, iconPath: String, label: String) {
    val icon = LocalIcons.current.painter(iconPath)
    Row(
      Modifier.fillMaxWidth()
        .height(Gateway.sidebarRowHeight)
        .background(if (page == target) Gateway.selection else Color.Transparent)
        .clickable { onSelect(target) }
        .padding(start = Gateway.sidebarIconInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
      BasicText(
        label,
        style = styles.sidebarItem,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp),
      )
    }
  }
  Column(modifier.fillMaxHeight().padding(top = Gateway.sidebarTop)) {
    Box(
      Modifier.height(Gateway.sidebarRowHeight).padding(start = Gateway.sidebarTextInset),
      contentAlignment = Alignment.CenterStart,
    ) {
      BasicText("Connections", style = styles.secondary)
    }
    val (phone, ssh, link) = pages(device)
    Item(phone.first, phone.second, phone.third)
    Item(ssh.first, ssh.second, ssh.third)
    Box(Modifier.padding(vertical = 7.dp).fillMaxWidth().height(1.dp).background(Gateway.divider))
    Item(link.first, link.second, link.third)
  }
}

/**
 * One project row: Gateway's product icon, the path in link blue, then Running with a stop square
 * or Last active.
 */
internal class ProjectRow(
  val title: String,
  val running: Boolean,
  val lastActive: Long?,
  val onOpen: () -> Unit,
  val onStop: (() -> Unit)?,
  val onRemove: (() -> Unit)?,
  /** False while the host is unreachable: Gateway greys the path and shows no status. */
  val enabled: Boolean = true,
  /** Gateway shortens long remote paths in the middle: `/data/data/c…es/home/Files/…/xtool`. */
  val middleEllipsis: Boolean = false,
)

@Composable
private fun PhonePage(
  state: ConnectScreenState,
  actions: ConnectActions,
  styles: ConnectStyles,
  onNewProject: () -> Unit,
) {
  var search by remember { mutableStateOf("") }
  val termuxReady = state.termux == TermuxAccess.READY
  Header(
    if (state.termux != null) "Recent Termux Projects" else "Backends on ${state.device}",
    styles,
  ) {
    if (termuxReady) OutlinedButton("New Project", onClick = onNewProject)
  }
  SearchRow(search, { search = it }, styles)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Notices(state, actions, styles)
    when (state.termux) {
      TermuxAccess.NOT_INSTALLED ->
        Notice(
          "Termux isn't installed. Use Link to connect to a backend on another machine.",
          styles,
        )
      TermuxAccess.NO_PERMISSION -> {
        Notice("This app needs permission to run intellij-backend in Termux.", styles)
        Box(Modifier.padding(horizontal = Gateway.contentInset)) {
          OutlinedButton("Allow", onClick = actions.allowTermux)
        }
      }
      TermuxAccess.READY,
      null -> Unit
    }
    val running = state.discovered.firstOrNull { it.joinLink != null }
    val rows = buildList {
      running?.let { backend ->
        val entry =
          state.history.firstOrNull {
            it.kind == ConnectionKind.TERMUX && it.project == backend.project
          }
        add(
          ProjectRow(
            shortPath(backend.project ?: "port ${backend.port}"),
            true,
            null,
            { actions.openDiscovered(backend) },
            actions.stopTermux,
            entry?.let { { actions.removeSaved(it) } },
          ),
        )
      }
      state.history
        .filter { it.kind == ConnectionKind.TERMUX && it.project != running?.project }
        .forEach { entry ->
          add(
            ProjectRow(
              shortPath(entry.project.orEmpty()),
              false,
              entry.lastConnected,
              { actions.openSaved(entry) },
              null,
              { actions.removeSaved(entry) },
            ),
          )
        }
      // An open port that Termux doesn't know about, e.g. remote-dev-server.sh started by hand:
      // needs its link.
      state.discovered
        .filter { it.joinLink == null }
        .forEach { backend ->
          add(
            ProjectRow(
              "127.0.0.1:${backend.port}",
              true,
              null,
              { actions.openDiscovered(backend) },
              null,
              null,
            ),
          )
        }
    }
      .filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) }
    HostGroup(
      state.device,
      styles,
      icons =
        buildList {
          if (termuxReady) add(HeaderIcon("expui/general/add.svg", "New Project", onNewProject))
          add(HeaderIcon("expui/general/refresh.svg", "Refresh", actions.refresh))
        },
    )
    if (rows.isEmpty()) {
      Notice(
        when {
          search.isNotBlank() -> "No projects"
          termuxReady -> "No recent projects. Tap New Project to start a backend in Termux."
          state.termux == null ->
            "No backend on ports 5990 to 5999 here. Use Link to connect by join link."
          else -> "No projects"
        },
        styles,
      )
    }
    rows.forEach { ProjectRowView(it, styles) }
  }
}

@Composable
private fun LinkPage(state: ConnectScreenState, actions: ConnectActions, styles: ConnectStyles) {
  Header("Connect by Link", styles) {}
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Column(
      Modifier.padding(horizontal = Gateway.contentInset, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      BasicText(
        "Join link (printed by intellij-backend link or remote-dev-server.sh)",
        style = styles.secondary,
      )
      TextField(state.link, actions.linkChanged)
      BasicText("File to open, optional (absolute path on the backend)", style = styles.secondary)
      TextField(state.file, actions.fileChanged)
      OutlinedButton("Connect", onClick = actions.connectLink)
    }
    Notices(state, actions, styles)
    // Grouped by address, like Gateway groups by user@host; LINK names are `<project> ·
    // <host>:<port>`.
    state.history
      .filter { it.kind == ConnectionKind.LINK }
      .groupBy { "${it.host}:${it.port}" }
      .forEach { (address, entries) ->
        HostGroup(address, styles, icons = emptyList())
        entries.forEach { entry ->
          val title =
            entry.name.substringBefore(" · ").takeIf { entry.name.contains(" · ") } ?: address
          ProjectRowView(
            ProjectRow(
              title,
              false,
              entry.lastConnected,
              { actions.openSaved(entry) },
              null,
              { actions.removeSaved(entry) },
            ),
            styles,
          )
        }
      }
  }
}

/** Busy text, the last message and a pending question, under the header. */
@Composable
private fun Notices(state: ConnectScreenState, actions: ConnectActions, styles: ConnectStyles) {
  state.busy?.let { Notice(it, styles) }
  state.message?.let { Notice(it, styles, color = IslandsDark.text) }
  state.question?.let { question ->
    Notice(question.text, styles, color = IslandsDark.text)
    Row(
      Modifier.padding(horizontal = Gateway.contentInset),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(question.confirm, onClick = actions.answerQuestion)
      OutlinedButton("Cancel", onClick = actions.cancelQuestion)
    }
  }
}

@Composable
internal fun Notice(text: String, styles: ConnectStyles, color: Color = IslandsDark.textSecondary) =
  BasicText(
    text,
    style = styles.body.copy(color = color),
    modifier = Modifier.padding(horizontal = Gateway.contentInset, vertical = 8.dp),
  )

@Composable
internal fun Header(title: String, styles: ConnectStyles, trailing: @Composable () -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .height(Gateway.headerHeight)
      .padding(start = Gateway.contentInset, end = Gateway.headerEndInset),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(
      title,
      style = styles.title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    trailing()
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
}

@Composable
internal fun SearchRow(value: String, onChange: (String) -> Unit, styles: ConnectStyles) {
  val icon = LocalIcons.current.painter("expui/inline/searchHistory.svg")
  Row(
    Modifier.fillMaxWidth().height(Gateway.searchHeight).padding(start = Gateway.searchIconInset),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon?.let {
      Image(
        it,
        contentDescription = "Search",
        modifier = Modifier.size(width = 22.dp, height = 16.dp),
      )
    }
    Box(Modifier.weight(1f).padding(start = 3.dp, end = Gateway.contentInset)) {
      if (value.isEmpty()) BasicText("Search", style = styles.secondary)
      BasicTextField(
        value,
        onChange,
        singleLine = true,
        textStyle = styles.body,
        cursorBrush = SolidColor(IslandsDark.text),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
}

internal class HeaderIcon(val path: String, val description: String, val onClick: () -> Unit)

@Composable
internal fun HostGroup(
  host: String,
  styles: ConnectStyles,
  icons: List<HeaderIcon>,
  /** Gateway's state at the right, e.g. red "Host unreachable" or "Connecting…". */
  status: String? = null,
  statusColor: Color = IslandsDark.textSecondary,
  onStatusClick: (() -> Unit)? = null,
  /** Gateway's host gear menu; also the header's right-click / long-press menu. */
  menu: List<MenuItem> = emptyList(),
) {
  var menuOpen by remember { mutableStateOf(false) }
  ContextMenuArea(entries = { menu.takeIf { it.isNotEmpty() } }) {
    Row(
      Modifier.fillMaxWidth()
        .padding(top = 17.dp)
        .height(28.dp)
        .padding(start = Gateway.contentInset, end = Gateway.iconColumnEndInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicText(
        host,
        style = styles.host,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      status?.let {
        BasicText(
          it,
          style = styles.body.copy(color = statusColor),
          maxLines = 1,
          modifier =
            Modifier.padding(end = 8.dp)
              .then(
                if (onStatusClick != null) Modifier.clickable(onClick = onStatusClick)
                else Modifier,
              ),
        )
      }
      icons.forEach { icon -> IconButton(icon.path, icon.description, icon.onClick) }
      if (menu.isNotEmpty()) {
        Box {
          IconButton("expui/general/settings.svg", "Host Settings") { menuOpen = true }
          if (menuOpen) ProjectMenu(onDismiss = { menuOpen = false }, items = menu, styles)
        }
      }
    }
  }
  Spacer(Modifier.height(5.dp))
}

@Composable
internal fun ProjectRowView(row: ProjectRow, styles: ConnectStyles) {
  val icon = LocalIcons.current.painter("expui/nodes/ideaProject.svg")
  var menuOpen by remember { mutableStateOf(false) }
  val status =
    if (!row.enabled) ""
    else if (row.running) "Running"
    else
      row.lastActive?.let { "Last active: ${lastActive(it, System.currentTimeMillis())}" }.orEmpty()
  val statusStyle =
    styles.body.copy(color = if (row.running) Gateway.running else IslandsDark.textSecondary)
  val menu = projectMenu(row)
  // Gateway gives an unreachable host's rows no menu.
  ContextMenuArea(entries = { menu.takeIf { row.enabled } }) {
    Row(
      Modifier.fillMaxWidth()
        .height(if (styles.twoLineRows) Gateway.twoLineRowHeight else Gateway.projectRowHeight)
        .padding(start = Gateway.contentInset, end = Gateway.iconColumnEndInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
      val title =
        @Composable { modifier: Modifier ->
          BasicText(
            row.title,
            style =
              styles.body.copy(color = if (row.enabled) IslandsDark.link else Gateway.disabledText),
            maxLines = 1,
            overflow =
              if (row.middleEllipsis) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = if (row.enabled) modifier.clickable(onClick = row.onOpen) else modifier,
          )
        }
      if (styles.twoLineRows) {
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
          title(Modifier)
          BasicText(status, style = statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      } else {
        // The status follows the path; the path gives way when both don't fit, and the icons stay
        // on
        // the right.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          title(Modifier.padding(start = 9.dp).weight(1f, fill = false))
          Spacer(Modifier.width(18.dp))
          BasicText(status, style = statusStyle, maxLines = 1)
        }
      }
      row.onStop?.let { stop -> IconButton("expui/run/stop.svg", "Stop", stop) }
      if (row.onRemove != null && row.enabled) {
        Box {
          IconButton("expui/general/moreVertical.svg", "More") { menuOpen = true }
          if (menuOpen) ProjectMenu(onDismiss = { menuOpen = false }, items = menu, styles)
        }
      }
    }
  }
}

/**
 * A project row's menu, for its ⋮ button and for right click / long press. Gateway's ⋮ menu has
 * Open With, Remove from Recent Projects and Collect Host and Client Logs; collecting logs needs
 * the host's log folders and a zip, so it is shown greyed out.
 */
private fun projectMenu(row: ProjectRow): List<MenuItem> = buildList {
  add(MenuItem("Open", onClick = row.onOpen))
  row.onStop?.let { add(MenuItem("Stop", onClick = it)) }
  row.onRemove?.let { add(MenuItem("Remove from Recent Projects", onClick = it)) }
  add(MenuItem("Collect Host and Client Logs", enabled = false))
}

/**
 * Gateway's ⋮ and gear popup, in its measured look; the items are the same ones the right-click /
 * long-press menu shows ([projectMenu], the SSH host menu). Greyed-out items can't be clicked.
 */
@Composable
private fun ProjectMenu(onDismiss: () -> Unit, items: List<MenuItem>, styles: ConnectStyles) {
  Popup(
    alignment = Alignment.TopEnd,
    offset = IntOffset(0, with(LocalDensity.current) { 28.dp.roundToPx() }),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Column(
      Modifier.width(Gateway.menuWidth)
        .background(Gateway.menuBackground, Gateway.menuShape)
        .border(1.dp, Gateway.menuBorder, Gateway.menuShape)
        .padding(vertical = 6.dp),
    ) {
      items.forEach { item ->
        Box(
          Modifier.fillMaxWidth()
            .height(Gateway.menuRowHeight)
            .padding(horizontal = 8.dp)
            .clickable(enabled = item.enabled) {
              onDismiss()
              item.onClick()
            }
            .padding(start = 12.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          BasicText(
            item.label,
            style =
              if (item.enabled) styles.body else styles.body.copy(color = Gateway.disabledText),
          )
        }
      }
    }
  }
}

@Composable
internal fun IconButton(path: String, description: String, onClick: () -> Unit) {
  val icon = LocalIcons.current.painter(path) ?: return
  Box(
    Modifier.size(width = Gateway.iconStep, height = 28.dp).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Image(icon, contentDescription = description, modifier = Modifier.size(16.dp))
  }
}

/** A bordered text field, for the join link and the file to open. */
/**
 * `/data/data/com.termux/files/home/intellij/smoke` → `~/intellij/smoke`: the full path doesn't fit
 * a phone.
 */
private fun shortPath(path: String) =
  if (path.startsWith(Termux.HOME)) "~" + path.removePrefix(Termux.HOME) else path

/**
 * IntelliJ's `DateFormatUtil.formatPrettyDateTime`, which Gateway's "Last active:" uses (Community
 * 263.7307.200): within about an hour "Moments ago", "A minute ago", "52 minutes ago", "1 hour ago"
 * (UtilBundle `date.format.minutes.ago`); then "Today 1:02 pm" or "Yesterday 1:02 pm"; else the
 * short date and time, e.g. "15/11/26, 10:21 am" (in the locale's own formats).
 */
internal fun lastActive(
  thenMillis: Long,
  nowMillis: Long,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
): String {
  val delta = nowMillis - thenMillis
  if (delta in 0..(61 * 60_000L)) {
    return when (val minutes = Math.rint(delta / 60_000.0).toInt()) {
      0 -> "Moments ago"
      1 -> "A minute ago"
      in 2..59 -> "$minutes minutes ago"
      else -> "1 hour ago"
    }
  }
  val then = Instant.ofEpochMilli(thenMillis).atZone(zone)
  val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
  val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then)
  return when (then.toLocalDate()) {
    today -> "Today $time"
    today.minusDays(1) -> "Yesterday $time"
    else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(then)
  }
}

/** [twoLineRows]: the status goes under the path, for narrow screens. */
internal class ConnectStyles(fonts: ClientFonts, val twoLineRows: Boolean) {
  val body =
    TextStyle(
      color = IslandsDark.text,
      fontFamily = fonts.ui,
      fontSize = IslandsDark.UI_FONT_SIZE.sp,
    )
  val secondary = body.copy(color = IslandsDark.textSecondary)
  val sidebarItem = body
  val title = body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
  val host = body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

/** Measured on Gateway 2026.3's welcome window and its ⋮ menu (2x screenshots), in dp = pt. */
internal object Gateway {
  val background = Color(0xFF1C1D20)
  val divider = Color(0xFF2B2D30)
  val selection = IslandsDark.selectionBackground // selected sidebar row, full width, square
  val running = Color(0xFF6AAB73)
  val error = Color(0xFFDB5C5C) // Label.errorForeground: Red7, "Host unreachable"
  val disabledText = IslandsDark.textDisabled // an unreachable host's paths
  val sidebarWidth = 224.dp
  val tabBarHeight = 40.dp // portrait: like the main window's tool window bar
  val tabHeight = 32.dp
  val tabShape = RoundedCornerShape(6.dp)
  val tabSelected = Color(0xFF393B40)
  val twoLineRowHeight = 48.dp
  val sidebarTop = 8.dp // Gateway has its logo here
  val sidebarRowHeight = 33.dp
  val sidebarIconInset = 17.dp
  val sidebarTextInset = 16.dp
  val headerHeight = 64.dp // title centred 37.5 pt down, divider at 63.5
  val headerEndInset = 27.dp // New Project: 27.5 pt from the right
  val contentInset = 25.dp // title, host and project rows start 25 pt in
  val searchHeight = 44.dp
  val searchIconInset = 7.dp
  val projectRowHeight = 39.dp
  val iconStep = 25.5.dp // trailing icons, one every 25.5 pt
  val iconColumnEndInset = 32.dp // last icon centred 44.5 pt from the right
  val buttonBorder = IslandsDark.controlBorder
  val buttonShape = RoundedCornerShape(4.dp)
  val menuWidth = 237.dp
  val menuRowHeight = 24.dp
  val menuBackground = IslandsDark.popupBackground
  val menuBorder = IslandsDark.popupBorder
  val menuShape = RoundedCornerShape(8.dp)
}
