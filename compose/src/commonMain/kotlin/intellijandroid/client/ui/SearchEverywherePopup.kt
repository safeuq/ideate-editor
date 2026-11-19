package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.session.LookupKey
import intellijandroid.client.session.SearchEverywhere
import intellijandroid.client.session.SearchPopup
import intellijandroid.client.session.SearchRow
import intellijandroid.client.session.SearchTab
import intellijandroid.client.ui.component.SearchHistory
import intellijandroid.client.ui.component.SearchTextField
import intellijandroid.client.ui.component.popupContainer
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.UiDensity
import intellijandroid.client.ui.theme.label

/**
 * IntelliJ's Search Everywhere popup (platform/searchEverywhere/frontend/src/ui): the tabs on top,
 * the search field, then the results, a row per item as `SeTargetItemPresentationRenderer` draws
 * it. 700 wide (`search.everywhere.new.minimum.width`), narrower when the window is; placed as
 * `SeFrontendService.calcPopupPositionAndShow` places it: centred, its middle at a quarter of the
 * window's height, and kept clear of [top] (the main toolbar).
 */
@Composable
internal fun SearchEverywherePopup(
  popup: SearchPopup,
  search: SearchEverywhere,
  top: Dp,
  maxHeight: Dp,
  onChosen: () -> Unit,
  onUnsupported: (String) -> Unit,
  /** The tab's earlier searches (SearchHistoryList: per tab, newest first, at most 50). */
  history: SearchHistory,
) {
  val fonts = LocalClientFonts.current
  val density = LocalDensity.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  // SeFrontendService saves the query to the tab's history when the popup closes.
  val query by rememberUpdatedState(popup.query)
  DisposableEffect(history) { onDispose { history.add(query) } }
  val listState = rememberLazyListState()
  LaunchedEffect(popup.selected) {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty() && popup.selected !in visible.first().index..visible.last().index)
      listState.scrollToItem(popup.selected)
  }
  fun choose(row: SearchRow) {
    search.choose(row, popup.query)
    onChosen()
  }
  Popup(
    popupPositionProvider =
      remember(top, density) { SearchPosition(with(density) { top.roundToPx() }) },
    onDismissRequest = search::close,
    properties = PopupProperties(focusable = true),
  ) {
    Column(
      Modifier.widthIn(max = Looks.width)
        .fillMaxWidth()
        .padding(horizontal = Looks.windowMargin)
        .popupContainer(Looks.radius),
    ) {
      Row(
        Modifier.fillMaxWidth()
          .padding(start = Looks.headerStart, end = Looks.headerEnd, top = Looks.headerTop)
          .height(Looks.headerHeight),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SearchTab.entries.forEach { tab ->
          val selected = tab == popup.tab
          Box(
            Modifier.height(Looks.tabHeight)
              .background(
                if (selected) Looks.tabSelected else Color.Transparent,
                RoundedCornerShape(Looks.tabRadius),
              )
              .clickable {
                if (tab.providerId == null) onUnsupported("The ${tab.title} tab")
                else search.tab(tab)
              }
              .padding(horizontal = Looks.tabPadding),
            contentAlignment = Alignment.Center,
          ) {
            BasicText(
              tab.title,
              style = fonts.label(if (selected) Looks.tabSelectedText else IslandsDark.text),
              maxLines = 1,
            )
          }
        }
      }
      Separator()
      SearchTextField(
        popup.query,
        search::query,
        Modifier.focusRequester(focus)
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val key =
              when (event.key) {
                Key.DirectionUp -> LookupKey.UP
                Key.DirectionDown -> LookupKey.DOWN
                Key.Enter,
                Key.NumPadEnter -> LookupKey.ACCEPT
                Key.Escape -> LookupKey.CANCEL
                else -> return@onPreviewKeyEvent false
              }
            if (key == LookupKey.ACCEPT) popup.rows.getOrNull(popup.selected)?.let(::choose)
            else search.key(key, popup)
            true
          }
          .padding(horizontal = Looks.fieldPadding, vertical = Looks.fieldVerticalPadding),
        placeholder = "",
        undecorated = true,
        // SePopupContentPane.addHistoryExtensionToTextField: the search-with-history icon, a 6 gap;
        // a click lists the tab's earlier searches.
        history = history,
        iconGap = 6.dp,
      )
      if (popup.rows.isNotEmpty() || (popup.query.isNotBlank() && !popup.searching)) Separator()
      if (popup.rows.isEmpty() && popup.query.isNotBlank() && !popup.searching) {
        // SeEmptyResultInfo's text when nothing matched in the tab.
        BasicText(
          "Nothing found.",
          style = fonts.label(IslandsDark.textSecondary),
          modifier = Modifier.padding(horizontal = Looks.rowInset * 2, vertical = 12.dp),
        )
      }
      LazyColumn(
        state = listState,
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(max = minOf(Looks.maxListHeight, maxHeight))
            .padding(vertical = if (popup.rows.isEmpty()) 0.dp else Looks.listVerticalPadding),
      ) {
        items(popup.rows.size, key = { popup.rows[it].uuid }) { position ->
          ResultRow(popup.rows[position], position == popup.selected) {
            choose(popup.rows[position])
          }
        }
      }
    }
  }
}

@Composable
private fun Separator() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(IslandsDark.popupBorder))
}

@Composable
private fun ResultRow(row: SearchRow, selected: Boolean, onClick: () -> Unit) {
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  val style = fonts.label(IslandsDark.text)
  val secondary = if (selected) IslandsDark.text else IslandsDark.textSecondary
  Row(
    Modifier.fillMaxWidth()
      .height(Looks.rowHeight)
      .padding(horizontal = Looks.rowInset)
      .background(
        if (selected) IslandsDark.selectionBackground else Color.Transparent,
        RoundedCornerShape(Looks.selectionRadius),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = Looks.rowInset),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // SeTargetItemPresentationRenderer: the name in full, the folder shortened to what is left
    // after the name and the location (less 16 and 20), the location last and cut at the row's end.
    BoxWithConstraints(Modifier.weight(1f)) {
      val measurer = rememberTextMeasurer()
      val density = LocalDensity.current
      val container =
        remember(row, constraints.maxWidth) {
          row.container?.let { text ->
            fun width(value: String) = measurer.measure(value, style).size.width
            val room =
              with(density) {
                constraints.maxWidth -
                  (Looks.iconSize + Looks.gap * 2).roundToPx() -
                  width(row.name) -
                  16.dp.roundToPx() -
                  (row.location?.let(::width) ?: 0) -
                  20.dp.roundToPx()
              }
            shortenContainerText(text, room, ::width)
          }
        }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(row.iconPath)
        Spacer(Modifier.width(Looks.gap))
        BasicText(
          if (selected) matched(row.name, row.nameMatches) else AnnotatedString(row.name),
          style = style,
          maxLines = 1,
          softWrap = false,
        )
        container?.let {
          Spacer(Modifier.width(Looks.gap))
          BasicText(
            it,
            style = style.copy(color = IslandsDark.textSecondary),
            maxLines = 1,
            softWrap = false,
          )
        }
        Spacer(Modifier.weight(1f))
        row.location?.let {
          Spacer(Modifier.width(Looks.gap))
          BasicText(it, style = style.copy(color = secondary), maxLines = 1, softWrap = false)
          if (row.locationIconPath != null) {
            Spacer(Modifier.width(Looks.gap))
            Icon(row.locationIconPath)
          }
        }
      }
    }
  }
}

/**
 * IntelliJ's `SETextShortener.getShortenContainerText`: [text] as it is when it fits in [maxWidth]
 * px (or [maxWidth] is negative); otherwise parts from the middle, split at `/`, `.`, `-` or a
 * space, give way to `..` (`...` for other separators) until it fits; then the middle of the text
 * goes. Example: `of org.junit.jupiter.api.function` → `of org.junit..api.function` → `of
 * org...api.function`. A leading `in ` is kept.
 */
internal fun shortenContainerText(text: String, maxWidth: Int, width: (String) -> Int): String {
  val startsWithIn = text.startsWith("in ")
  val left = if (startsWithIn) "in " else ""
  val body = if (startsWithIn) text.substring(3) else text
  val whole = left + body
  if (maxWidth < 0) return whole
  val fullWidth = width(whole)
  if (fullWidth < maxWidth) return whole
  val separator =
    when {
      "/" in body -> "/"
      "." in body -> "."
      "-" in body -> "-"
      else -> " "
    }
  val dots = if (separator != ".") "..." else ".."
  val parts = body.split(separator).toMutableList()
  while (parts.size > 1) {
    val index = parts.size / 2 - 1
    parts.removeAt(index)
    if (width(left + parts.joinToString(separator) + dots + separator) < maxWidth) {
      parts.add(index, dots)
      return left + parts.joinToString(separator)
    }
  }
  // StringUtil.trimMiddle: keep the start and the end, "…" between them.
  val keep = maxOf(whole.length * maxWidth / fullWidth - 1, left.length + 3)
  if (keep >= whole.length) return whole
  val head = (keep - 1) / 2
  return whole.take(head) + "…" + whole.takeLast(keep - 1 - head)
}

@Composable
private fun Icon(path: String?) {
  val icons = LocalIcons.current
  Box(Modifier.size(Looks.iconSize)) {
    path?.let(icons::painter)?.let { Image(it, null, Modifier.size(Looks.iconSize)) }
  }
}

/**
 * The selected row's name with the matched letters painted as IntelliJ's speed search paints them:
 * on `SearchMatch.startBackground` (`search-match-bg`), in black.
 */
private fun matched(text: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
  var position = 0
  for (range in ranges.sortedBy { it.first }) {
    val start = range.first.coerceIn(position, text.length)
    val end = (range.last + 1).coerceIn(start, text.length)
    append(text.substring(position, start))
    withStyle(SpanStyle(background = Looks.matchBackground, color = Color.Black)) {
      append(text.substring(start, end))
    }
    position = end
  }
  append(text.substring(position))
}

/**
 * Centred; the top a quarter down the window less half the popup's height (IntelliJ's place for the
 * popup while it shows no results), but not above [top] px. Measured once, on the first layout, so
 * the popup grows downward as results come.
 */
private class SearchPosition(private val top: Int) : PopupPositionProvider {
  private var y: Int? = null

  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
    val placed =
      y ?: maxOf(windowSize.height / 4 - popupContentSize.height / 2, top).also { y = it }
    return IntOffset(x, placed)
  }
}

/**
 * Sizes from IntelliJ's sources (JBUI.CurrentTheme.BigPopup, Popup.Selection) and colours from
 * ManyIslandsDark.
 */
private object Looks {
  val width = 700.dp // Registry search.everywhere.new.minimum.width
  val radius = 8.dp
  val windowMargin = 8.dp // from the window's sides when it is narrower than the popup
  val headerStart = 4.dp // SearchEverywhere.Header.insets 2,4,0,9
  val headerEnd = 9.dp
  val headerTop = 2.dp
  val headerHeight = 32.dp
  val tabHeight = 24.dp
  val tabPadding = 12.dp // BigPopup.tabInsets 0,12
  val tabRadius = 4.dp
  val tabSelected = Color(0xFF233558) // SearchEverywhere.Tab.selectedBackground = blue-40
  val tabSelectedText = Color.White // SearchEverywhere.Tab.selectedForeground = text-over-accent
  // ComplexPopup.TextField.borderInsets 0,12, then textFieldInputInsets' 2 inside: the icon is 14
  // in.
  val fieldPadding = 14.dp
  val fieldVerticalPadding = 2.dp // with the field's own 4: ComplexPopup.TextField.inputInsets 6,2
  val maxListHeight = 600.dp // BigPopup.maxListHeight
  val listVerticalPadding = 4.dp
  val rowInset = 8.dp // Popup.Selection.leftRightInset, then innerInsets 0,8

  val rowHeight
    get() = if (IslandsDark.density == UiDensity.COMPACT) 22.dp else 24.dp // List.rowHeight

  val selectionRadius = 4.dp // Popup.Selection.arc 8
  val iconSize = 16.dp
  val gap = 6.dp // the renderer's defaultGapWidth
  val matchBackground = Color(0xFFBA9752) // SearchMatch.startBackground = search-match-bg
}

/**
 * Double Shift, timed as IntelliJ's `ModifierKeyDoubleClickHandler` times it: press, release,
 * press, release, each within 300 ms of the one before, and no other key between. Example: Shift
 * down at 0, up at 80, down at 200, up at 260 → true at 260; with the second press at 450 → false.
 */
internal class DoubleShift {
  /** 0: nothing yet; 1: pressed; 2: released; 3: pressed again. */
  private var step = 0
  private var last = 0L

  /** One key event at [time] ms; true when it completes a double Shift. */
  fun onKey(shift: Boolean, down: Boolean, time: Long): Boolean {
    if (!shift) {
      step = 0
      return false
    }
    if (step > 0 && time - last > DOUBLE_SHIFT_MS) step = 0
    last = time
    step =
      when {
        down && (step == 0 || step == 1) -> 1 // a held key repeats its press
        !down && step == 1 -> 2
        down && step == 2 -> 3
        !down && step == 3 -> {
          step = 0
          return true
        }
        else -> 0
      }
    return false
  }
}

private const val DOUBLE_SHIFT_MS = 300
