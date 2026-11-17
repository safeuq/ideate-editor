package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import intellijandroid.client.session.ProjectTree
import intellijandroid.client.session.TreeNode
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label
import kotlin.math.roundToInt

/** The Project tool window: header plus the tree, laid out with IntelliJ's New UI tree metrics. */
@Composable
fun ProjectToolWindow(
  tree: ProjectTree,
  onToggle: (TreeNode) -> Unit,
  onSelect: (TreeNode) -> Unit,
  onOpen: (TreeNode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fonts = LocalClientFonts.current
  Column(modifier) {
    Box(
      Modifier.fillMaxWidth()
        .height(IslandsDark.headerHeight)
        .padding(start = IslandsDark.headerLeftInset),
      contentAlignment = Alignment.CenterStart,
    ) {
      BasicText(
        "Project",
        style = fonts.label(),
      )
    }
    ProjectTreeView(tree, onToggle, onSelect, onOpen, Modifier.fillMaxSize())
  }
}

@Composable
fun ProjectTreeView(
  tree: ProjectTree,
  onToggle: (TreeNode) -> Unit,
  onSelect: (TreeNode) -> Unit,
  onOpen: (TreeNode) -> Unit,
  modifier: Modifier = Modifier,
  /** Sideways scrolling for names wider than the panel; hoisted for the phone's swipe pager. */
  scroll: SidewaysScroll = remember { SidewaysScroll() },
  /** A right click or long press on a row, after it is selected; null: no context menu. */
  onMenu: ((TreeNode) -> Unit)? = null,
  /** The open menu's items; null while they load. */
  menuEntries: () -> List<MenuEntry>? = { null },
  /** The name colour for a [TreeNode.fileStatus], or null for the default text colour. */
  fileStatusColor: (String) -> Color? = { null },
) {
  val rows = remember(tree) { tree.visibleRows() }
  // Each laid-out row's right edge (text end plus the side inset), for the sideways scroll range.
  // ponytail: only rows that have been on screen count, like a lazy list; a wider row further
  // down widens the range when it scrolls into view.
  val rowRights = remember(rows) { mutableStateMapOf<Int, Float>() }
  var viewportWidth by remember { mutableFloatStateOf(0f) }
  val contentWidth = rowRights.values.maxOrNull() ?: 0f
  LaunchedEffect(contentWidth, viewportWidth) { scroll.fit(contentWidth, viewportWidth) }
  val focusRequester = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  // Arrow keys like IntelliJ: up/down move, right expands or enters, left collapses or goes to the
  // parent, Enter opens.
  fun onKey(key: Key): Boolean {
    val index = rows.indexOfFirst { it.first.id == tree.selected }
    val current = rows.getOrNull(index)?.first
    when (key) {
      Key.DirectionDown ->
        rows.getOrNull(if (index < 0) 0 else index + 1)?.let { onSelect(it.first) }
      Key.DirectionUp -> rows.getOrNull(index - 1)?.let { onSelect(it.first) }
      Key.DirectionRight ->
        current?.let { node ->
          if (node.hasChildren && node.id !in tree.expanded) onToggle(node)
          else
            rows
              .getOrNull(index + 1)
              ?.takeIf { it.first.parentId == node.id }
              ?.let { onSelect(it.first) }
        }
      Key.DirectionLeft ->
        current?.let { node ->
          if (node.id in tree.expanded) onToggle(node)
          else node.parentId?.let { parent -> tree.nodes[parent]?.let(onSelect) }
        }
      Key.Enter -> current?.let { node -> if (node.hasChildren) onToggle(node) else onOpen(node) }
      else -> return false
    }
    return true
  }

  LaunchedEffect(tree.selected) {
    val index = rows.indexOfFirst { it.first.id == tree.selected }
    val visible = listState.layoutInfo.visibleItemsInfo
    if (
      index >= 0 &&
        visible.isNotEmpty() &&
        (index < visible.first().index || index > visible.last().index)
    )
      listState.animateScrollToItem(index)
  }

  LazyColumn(
    state = listState,
    contentPadding =
      PaddingValues(top = IslandsDark.treeTopInset, bottom = IslandsDark.treeTopInset),
    modifier =
      modifier
        .onSizeChanged { viewportWidth = it.width.toFloat() }
        .scrollable(scroll.scrollable, Orientation.Horizontal, reverseDirection = true)
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.hasFocus }
        .focusable()
        .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event.key) },
  ) {
    items(rows, key = { it.first.id }) { (node, depth) ->
      MenuArea(
        onMenu?.let { menu ->
          {
            onSelect(node)
            menu(node)
          }
        },
        menuEntries,
      ) {
        TreeRow(
          node = node,
          nameColor = node.fileStatus?.let(fileStatusColor) ?: IslandsDark.text,
          depth = depth,
          expanded = node.id in tree.expanded,
          selected = node.id == tree.selected,
          treeFocused = focused,
          onPress = {
            focusRequester.requestFocus()
            onSelect(node)
          },
          onDoubleClick = { if (node.hasChildren) onToggle(node) else onOpen(node) },
          onChevron = { onToggle(node) },
          scroll = scroll,
          onRight = { right -> rowRights[node.id] = right },
        )
      }
    }
  }
}

/** [content] with a context menu when [onOpen] is set. */
@Composable
private fun MenuArea(
  onOpen: (() -> Unit)?,
  entries: () -> List<MenuEntry>?,
  content: @Composable () -> Unit,
) {
  if (onOpen == null) content()
  else ContextMenuArea(entries, Modifier.fillMaxWidth(), onOpen = { onOpen() }) { content() }
}

@Composable
private fun TreeRow(
  node: TreeNode,
  /** The colour of fragments that don't set their own. */
  nameColor: Color,
  depth: Int,
  expanded: Boolean,
  selected: Boolean,
  treeFocused: Boolean,
  onPress: () -> Unit,
  onDoubleClick: () -> Unit,
  onChevron: () -> Unit,
  scroll: SidewaysScroll,
  /** The row's content width in px, from its left edge to the text end plus the side inset. */
  onRight: (Float) -> Unit,
) {
  val fonts = LocalClientFonts.current
  val density = LocalDensity.current
  val icons = LocalIcons.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val highlight =
    when {
      selected ->
        if (treeFocused) IslandsDark.selectionBackground
        else IslandsDark.selectionInactiveBackground
      hovered -> IslandsDark.treeHover
      else -> null
    }
  val chevronX = IslandsDark.treeChevronInset + IslandsDark.treeIndent * (depth - 1)
  val rendererX = IslandsDark.treeChevronInset + IslandsDark.treeIndent * depth
  val iconX = rendererX + IslandsDark.treeIconTextGap
  val textX = iconX + 16.dp + IslandsDark.treeTextGap

  Box(
    Modifier.fillMaxWidth()
      .height(IslandsDark.treeRowHeight)
      // The scope's file colour fills the whole row, under the selection.
      .then(node.background?.let { Modifier.background(Color(it)) } ?: Modifier)
      .hoverable(interaction)
      .pointerInput(node.id) {
        detectTapGestures(onPress = { onPress() }, onDoubleTap = { onDoubleClick() })
      },
  ) {
    // Selection and hover: a rounded rectangle inset 12 px from both sides, not indented with the
    // node.
    if (highlight != null) {
      // Inset 12 from both sides; when the row is scrolled sideways, its left side follows the
      // content out of view and the row's edge cuts it square, as the viewport clips IntelliJ's
      // tree: scroll.x = 40 draws from -28, so no rounded corner shows at the edge. Drawn, not laid
      // out, so a sideways scroll only redraws it.
      Box(
        Modifier.fillMaxSize().clipToBounds().drawBehind {
          val inset = IslandsDark.treeSideInset.toPx()
          val left = inset - scroll.x
          val radius = IslandsDark.treeSelectionRadius.toPx()
          drawRoundRect(
            highlight,
            topLeft = Offset(left, 0f),
            size = Size(size.width - inset - left, size.height),
            cornerRadius = CornerRadius(radius, radius),
          )
        },
      )
    }
    // Everything but the selection band scrolls sideways.
    Box(
      Modifier.matchParentSize().clipToBounds().offset {
        IntOffset(-scroll.x.roundToInt(), 0)
      },
    ) {
      if (node.hasChildren) {
        val chevron =
          icons.painter(
            if (expanded) "expui/general/chevronDown.svg" else "expui/general/chevronRight.svg",
          )
        if (chevron != null) {
          Image(
            chevron,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier =
              Modifier.offset(x = chevronX).size(16.dp).align(Alignment.CenterStart).pointerInput(
                node.id,
              ) {
                detectTapGestures(onPress = { onChevron() })
              },
          )
        }
      }
      node.iconPaths.firstOrNull()?.let(icons::painter)?.let { icon ->
        Image(
          icon,
          contentDescription = null,
          modifier = Modifier.offset(x = iconX).size(16.dp).align(Alignment.CenterStart),
        )
      }
      val label =
        remember(node.fragments, nameColor) {
          buildAnnotatedString {
            for (fragment in node.fragments) {
              withStyle(
                SpanStyle(
                  color =
                    fragment.foreground?.let { androidx.compose.ui.graphics.Color(it) }
                      ?: nameColor,
                  // Inter has no Bold file; IntelliJ draws "bold" UI text with Inter SemiBold.
                  fontWeight =
                    if (fragment.style and 1 != 0) FontWeight.SemiBold else FontWeight.Normal,
                  fontStyle = if (fragment.style and 2 != 0) FontStyle.Italic else FontStyle.Normal,
                ),
              ) {
                append(fragment.text)
              }
            }
          }
        }
      BasicText(
        label,
        style = fonts.label(),
        maxLines = 1,
        softWrap = false,
        modifier =
          Modifier.offset(x = textX)
            .align(Alignment.CenterStart)
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .onSizeChanged {
              onRight(it.width + with(density) { (textX + IslandsDark.treeSideInset).toPx() })
            },
      )
    }
  }
}
