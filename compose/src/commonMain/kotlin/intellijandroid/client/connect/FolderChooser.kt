package intellijandroid.client.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ProjectTree
import intellijandroid.client.session.TextFragment
import intellijandroid.client.session.TreeNode
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.ProjectTreeView
import intellijandroid.client.ui.component.IconButton
import intellijandroid.client.ui.component.TextField
import intellijandroid.client.ui.dialog.DialogAction
import intellijandroid.client.ui.dialog.DialogWrapper
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts

/**
 * IntelliJ's folder chooser ("Open File or Project", `FileChooserDialogImpl`), for folders in
 * Termux. Sizes and colors measured on the real dialog at 2x (docs/intellij-look.md). Left out:
 * Desktop, New Folder, Delete, the path history drop-down and the drag-and-drop hint.
 */
@Composable
fun FolderChooser(
  title: String,
  initialPath: String,
  listFolders: (path: String, onResult: (Result<List<Termux.Folder>>) -> Unit) -> Unit,
  onChoose: (String) -> Unit,
  onCancel: () -> Unit,
  /** The tree's top folder: Termux's files folder, or `/` on an SSH host. */
  root: String = TERMUX_ROOT,
  /** Where the Home button goes. */
  home: String = Termux.HOME,
) {
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val text =
    TextStyle(
      color = IslandsDark.text,
      fontFamily = fonts.ui,
      fontSize = IslandsDark.UI_FONT_SIZE.sp,
    )

  // Folder contents by path, loaded through Termux when a folder is first expanded.
  val children = remember { mutableStateMapOf<String, List<Termux.Folder>>() }
  var expanded by remember { mutableStateOf(emptySet<String>()) }
  var selected by remember { mutableStateOf<String?>(null) }
  var path by remember { mutableStateOf(initialPath.trimEnd('/')) }
  var showPath by remember { mutableStateOf(true) }
  var showHidden by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val ids = remember { mutableMapOf<String, Int>() }
  fun idOf(folder: String) = ids.getOrPut(folder) { ids.size + 1 }

  fun load(folder: String) =
    listFolders(folder) { result ->
      result.onSuccess { children[folder] = it }.onFailure { error = it.message }
    }

  /**
   * Expands the folders from [root] down to [target] and selects it, like the real chooser does for
   * its path.
   */
  fun reveal(target: String) {
    val clean = target.trimEnd('/').ifEmpty { "/" }
    if (!clean.startsWith(root)) return
    val chain =
      generateSequence(clean) { folder ->
          // `/home` → `/`, so a root of `/` is part of the chain too.
          folder
            .substringBeforeLast('/')
            .ifEmpty { "/" }
            .takeIf { it.length >= root.length && it != folder }
        }
        .toList()
        .reversed()
    chain.forEach { folder -> if (folder !in children) load(folder) }
    expanded = expanded + chain.dropLast(1)
    selected = clean
    path = clean
  }

  LaunchedEffect(Unit) { if (initialPath.startsWith(root)) reveal(initialPath) else load(root) }

  val tree =
    remember(children.toMap(), expanded, selected, showHidden) {
      val nodes = mutableMapOf<Int, TreeNode>()
      /** [listed]: what the parent's listing said about [folder]; null for [root]. */
      fun add(folder: String, parent: String?, listed: Termux.Folder?, sortKey: Int) {
        val loaded = children[folder]?.filter { showHidden || !it.name.startsWith(".") }
        nodes[idOf(folder)] =
          TreeNode(
            id = idOf(folder),
            parentId = parent?.let(::idOf),
            fragments =
              listOf(
                TextFragment(listed?.name ?: root.substringAfterLast('/').ifEmpty { "/" }, 0, null),
              ),
            iconPaths =
              listOf(
                if (listed?.isProject == true) "expui/nodes/ideaProject.svg"
                else "expui/nodes/folder.svg",
              ),
            hasChildren = hasChildFolders(loaded, listed, showHidden),
            isDirectory = true,
            path = folder,
            sortKey = sortKey,
          )
        if (folder in expanded)
          loaded?.forEachIndexed { index, child ->
            add("${folder.trimEnd('/')}/${child.name}", folder, child, index)
          }
      }
      add(root, null, null, 0)
      ProjectTree(nodes, expanded.map(::idOf).toSet(), selected?.let(::idOf))
    }

  DialogWrapper(
    title,
    actions =
      listOf(
        DialogAction("OK") { onChoose(path.trim().trimEnd('/')) },
        DialogAction("Cancel", onClick = onCancel),
      ),
    onCancel = onCancel,
    modifier = Modifier.fillMaxHeight(),
    maxHeight = Chooser.maxHeight,
    contentPadding = PaddingValues(0.dp),
    scrollable = false,
  ) {
    Row(
      Modifier.fillMaxWidth()
        .height(Chooser.toolbarHeight)
        .padding(start = Chooser.toolbarInset, end = Chooser.sideInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton("expui/nodes/homeFolder.svg", "Home", { reveal(home) })
      Box(
        Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(Chooser.separator),
      )
      IconButton(
        "expui/general/refresh.svg",
        "Refresh",
        {
          error = null
          val open = children.keys.toList()
          children.clear()
          open.forEach(::load)
        },
      )
      IconButton(
        "expui/actions/toggleVisibility.svg",
        "Show hidden files",
        { showHidden = !showHidden },
        selected = showHidden,
      )
      Spacer(Modifier.weight(1f))
      BasicText(
        if (showPath) "Hide path" else "Show path",
        style = text.copy(color = IslandsDark.link),
        modifier = Modifier.clickable { showPath = !showPath },
      )
    }
    if (showPath) {
      TextField(path, { path = it }, Modifier.padding(horizontal = Chooser.sideInset))
    }
    Box(
      Modifier.padding(horizontal = Chooser.treeInset)
        .padding(top = 6.dp)
        .weight(1f)
        .fillMaxWidth()
        .border(1.dp, Chooser.treeBorder),
    ) {
      ProjectTreeView(
        tree = tree,
        onToggle = { node ->
          val folder = node.path ?: return@ProjectTreeView
          if (folder in expanded) {
            expanded = expanded - folder
          } else {
            if (folder !in children) load(folder)
            expanded = expanded + folder
          }
        },
        onSelect = { node ->
          node.path?.let {
            selected = it
            path = it
          }
        },
        onOpen = { node -> node.path?.let(onChoose) },
        modifier = Modifier.fillMaxSize(),
      )
    }
    error?.let { message ->
      BasicText(
        message,
        style = text.copy(color = IslandsDark.textSecondary),
        modifier = Modifier.padding(horizontal = Chooser.sideInset, vertical = 4.dp),
      )
    }
  }
}

/**
 * Termux's files folder, the top of the tree: it holds `home` and `usr`. Folders above it aren't
 * readable.
 */
private const val TERMUX_ROOT = "/data/data/com.termux/files"

/**
 * Measured on IntelliJ's "Open File or Project" dialog (Islands Dark, macOS, 2x screenshot), in dp
 * = pt.
 */
private object Chooser {
  val maxHeight = 482.dp
  val toolbarHeight = 28.dp
  val toolbarInset = 17.dp // first icon's 16 px box starts 21 pt in; buttons have 2 pt padding
  val separator = Color(0xFF393B40)
  val sideInset = 14.dp // path field: 14.5 pt from the dialog's side
  val treeInset = 12.dp // tree panel: 12.5 pt from the dialog's side
  val treeBorder = Color(0xFF2B2D30)
}

/**
 * Whether a chooser row gets an expand arrow. Once the folder is listed, from its own listing
 * ([loaded], hidden folders already left out unless shown); before that, from what its parent's
 * listing said ([listed]), so an empty folder never shows an arrow that disappears when expanded.
 * [root] has no parent listing and keeps its arrow until loaded.
 */
internal fun hasChildFolders(
  loaded: List<Termux.Folder>?,
  listed: Termux.Folder?,
  showHidden: Boolean,
): Boolean =
  when {
    loaded != null -> loaded.isNotEmpty()
    listed != null -> listed.hasFolders || (showHidden && listed.hasHiddenFolders)
    else -> true
  }
