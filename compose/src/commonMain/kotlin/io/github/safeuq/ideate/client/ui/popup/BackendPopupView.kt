package io.github.safeuq.ideate.client.ui.popup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.safeuq.ideate.client.session.BackendPopup
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.LuxDialog
import io.github.safeuq.ideate.client.session.PopupLabel
import io.github.safeuq.ideate.client.session.PopupList
import io.github.safeuq.ideate.client.session.PopupLux
import io.github.safeuq.ideate.client.session.PopupTextField
import io.github.safeuq.ideate.client.ui.LocalBackHandler
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.ProjectAvatar
import io.github.safeuq.ideate.client.ui.component.GroupHeaderSeparator
import io.github.safeuq.ideate.client.ui.component.TextField
import io.github.safeuq.ideate.client.ui.component.popupContainer
import io.github.safeuq.ideate.client.ui.lux.LuxPainter
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label
import kotlin.math.roundToInt

/**
 * The backend's BeControl popups (session/BackendPopups.kt), newest on top. IntelliJ shows New ▸
 * File's at the top of the window (`BePopupPositionActiveWindow`, position TOP): its title, then
 * the name field, and under it the backend's validation message ("A file with this name already
 * exists"). Enter is the backend's (its shortcut listener creates the file and closes the popup);
 * Escape, Back or a tap outside cancels.
 */
@Composable
fun BackendPopups(popups: List<BackendPopup>, session: ClientSession) {
  val top = popups.lastOrNull() ?: return
  // Back and a tap outside go to the newest popup, as Escape does in IntelliJ.
  LocalBackHandler.current(true) { session.popupCancel(top) }
  // Modal: the screen under them takes no taps. A later popup (the name popup's error hint) shows
  // under the earlier one, where IntelliJ puts it.
  // A popup the backend placed next to something it paints (New ▾ under its button on the welcome
  // panel) goes there, kept inside the window; the others at the top, as IntelliJ shows New ▸ File.
  val anchor = popups.first().anchor
  // A list popup (More Actions, the project widget's) is as wide as its longest row, as
  // ListPopupImpl is, and at least the backend's minimumSize (More Actions: 310); the others keep
  // New ▸ File's 300.
  val width =
    if (popups.all { popup -> popup.items.any { it is PopupList && it.listPopup } })
      Modifier.width(IntrinsicSize.Max).widthIn(min = popups.maxOf { it.minimumWidth }.dp)
    else Modifier.widthIn(max = Look.width)
  Box(
    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
      if (top.cancelOnClickOutside) session.popupCancel(top)
    },
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      if (anchor == null) Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp).then(width)
      else Modifier.anchoredAt(anchor.x.dp, anchor.y.dp).then(width),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      popups.forEach { popup -> key(popup.id) { BackendPopupView(popup, session) } }
    }
  }
}

@Composable
private fun BackendPopupView(popup: BackendPopup, session: ClientSession) {
  val text = LocalClientFonts.current.label()
  // A popup without a text field (a list popup: More Actions, the project widget's) takes the
  // keyboard itself, as IntelliJ focuses its list (`isFocused`); otherwise Escape and the arrow
  // keys reach nothing.
  val popupFocus = remember { FocusRequester() }
  if (popup.items.none { it is PopupTextField })
    LaunchedEffect(popup.id) { runCatching { popupFocus.requestFocus() } }
  val list = popup.items.filterIsInstance<PopupList>().singleOrNull { it.listPopup }
  Column(
    Modifier.fillMaxWidth()
      .popupContainer(Look.radius)
      .focusRequester(popupFocus)
      // Taps inside the popup stay there.
      .clickable(remember { MutableInteractionSource() }, indication = null) {}
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // A list popup's keys stay in the client, as in IntelliJ's ListPopupImpl: its selection
        // and its speed search are not sent to the backend.
        if (list != null && listKey(list, event, session)) return@onPreviewKeyEvent true
        when (event.key) {
          Key.Enter,
          Key.NumPadEnter -> {
            session.popupEnter(popup)
            true
          }
          // Back too: with the keyboard hidden, the focused text field would otherwise take it
          // just to drop its focus.
          // The backend moves the list's selection (New ▸ Java Class's kinds).
          Key.DirectionUp -> session.popupKey(popup, "pressed UP")
          Key.DirectionDown -> session.popupKey(popup, "pressed DOWN")
          Key.Escape,
          Key.Back -> {
            session.popupCancel(popup)
            true
          }
          else -> false
        }
      }
      // A list popup's rows reach IntelliJ's selection edge (`Popup.Selection.leftRightInset`, 8
      // from the popup's side) and its body has 8 above and below (`Popup.Body.topInsetNoHeader`,
      // `bottomInsetNoAd`); other popups keep their own inset.
      .padding(if (list != null) Look.listInset else Look.inset),
    verticalArrangement = Arrangement.spacedBy(Look.gap),
  ) {
    if (popup.title.isNotEmpty()) {
      BasicText(
        popup.title,
        Modifier.fillMaxWidth(),
        style = text.copy(color = Islands.textSecondary),
      )
    }
    var first = true
    popup.items.forEach { item ->
      when (item) {
        is PopupLabel -> BasicText(item.text, style = text)
        is PopupLux -> LuxSurface(item.surface, session)
        is PopupList -> PopupListView(item, session)
        is PopupTextField -> {
          val focus = remember { FocusRequester() }
          TextField(
            item.text,
            { typed -> session.popupType(item, typed) },
            Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = item.placeholder,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { session.popupEnter(popup) }),
          )
          item.problem?.let { problem ->
            BasicText(
              problem,
              style =
                text.copy(
                  color = if (item.isError) Islands.textError else Islands.textSecondary,
                ),
            )
          }
          // The first field takes the keyboard, as IntelliJ focuses it (`isFocused`).
          if (first) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
          first = false
        }
      }
    }
  }
}

/** IntelliJ's New ▸ File popup (`NewItemSimplePopupPanel`), in dp. */
private object Look {
  val width = 300.dp
  val radius = 8.dp
  val inset = 10.dp
  val listInset = 8.dp
  val gap = 8.dp
  /** IntelliJ's list row (`List.rowHeight`), the same for mouse and touch. */
  val rowHeight = 24.dp
  val rowInset = 8.dp
  val iconGap = 6.dp
  val selectionRadius = 4.dp
}

/**
 * A list in a popup, e.g. New ▸ Java Class's kinds: icon and text per row, the selected one in
 * IntelliJ's list selection color. A tap selects a row; Enter in the name field then uses it.
 */
/**
 * A key in list popup [list]: Up and Down move the selection, Enter chooses the selected row, and
 * with `isSearchEnabled` a typed character adds to the filter (Backspace takes one off, Escape
 * clears it before a second Escape closes the popup, as `WizardPopup` resets its speed search).
 */
// ponytail: hardware keys only; an on-screen keyboard needs a text field, add one when the
// project popup is used on a phone without a keyboard.
private fun listKey(list: PopupList, event: KeyEvent, session: ClientSession): Boolean {
  when (event.key) {
    Key.DirectionUp -> list.move(-1)
    Key.DirectionDown -> list.move(1)
    Key.Enter,
    Key.NumPadEnter -> list.rows.getOrNull(list.selected)?.let { session.popupSelect(list, it) }
    Key.Escape -> if (list.filter.isEmpty()) return false else list.type("")
    Key.Backspace -> if (list.filter.isEmpty()) return false else list.type(list.filter.dropLast(1))
    else -> {
      val typed = event.utf16CodePoint
      val printable = typed >= ' '.code && typed != 127 && typed != 0xFFFF
      if (!list.searchEnabled || !printable || event.isCtrlPressed || event.isMetaPressed)
        return false
      list.type(list.filter + typed.toChar())
    }
  }
  return true
}

@Composable
private fun PopupListView(list: PopupList, session: ClientSession) {
  val text = LocalClientFonts.current.label()
  val icons = LocalIcons.current
  // As PopupFactoryImpl's ActionItem: once any row has an icon, a row without one gets an empty
  // icon of the same size, e.g. More Actions' Edit… lines up with "Run 'Forever'" above it.
  val iconSlot = list.rows.any { it.iconPath != null || it.avatar != null }
  Column {
    // The typed text, where IntelliJ's AbstractPopup puts its speed search field: above the rows.
    if (list.filter.isNotEmpty())
      Row(
        Modifier.fillMaxWidth().height(Look.rowHeight).padding(horizontal = Look.rowInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Look.iconGap),
      ) {
        icons.painter("expui/general/search.svg")?.let { Image(it, null, Modifier.size(16.dp)) }
        BasicText(list.filter, style = text, maxLines = 1)
      }
    list.rows.forEachIndexed { index, row ->
      if (!list.shows(row)) return@forEachIndexed
      // A filtered list has no separators.
      row.header
        ?.takeIf { list.filter.isEmpty() }
        ?.let { header ->
          // The rows' selection spans this list's width, so the cell is Look.rowInset outside it.
          GroupHeaderSeparator(
            header,
            hideLine = index == list.rows.indexOfFirst(list::shows),
            cellInset = Look.rowInset,
          )
        }
      // A project's row (name over path and branch) is as tall as its lines, with the 6 dp above
      // and below that the backend's BeStyleControl margin asks for.
      val details = row.details
      // The backend's margins count from IntelliJ's list cell, Look.rowInset outside the selection.
      val insets = row.insets
      val inside = Look.rowInset.value.toInt()
      // In a list popup the row under the pointer is the selected one, as ListPopupImpl moves its
      // selection with the mouse: one highlight for hover, Up and Down.
      val interaction = remember { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      if (list.listPopup) LaunchedEffect(hovered) { if (hovered) list.selected = index }
      Row(
        Modifier.fillMaxWidth()
          .then(
            if (insets.top == 0 && insets.bottom == 0) Modifier.height(Look.rowHeight)
            else Modifier,
          )
          .background(
            if (index == list.selected) Islands.selectionBackground else Color.Transparent,
            RoundedCornerShape(Look.selectionRadius),
          )
          .clickable(interaction, indication = null) {
            session.popupSelect(list, row)
          }
          .padding(
            start = (insets.left - inside).coerceAtLeast(0).dp,
            top = insets.top.dp,
            end = (insets.right - inside).coerceAtLeast(0).dp,
            bottom = insets.bottom.dp,
          ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(insets.iconGap.dp),
      ) {
        val avatar = row.avatar
        val painter = row.iconPath?.let(icons::painter)
        // A project's icon cell: 20 dp (AvatarIconModel.targetSize), at the top after the line
        // gap. The project's own icon file is 16 dp in the middle of it (the backend's
        // CompositionIconModel shifts it by 2), and the cell is kept while the icon is on its way.
        val iconCell = Modifier.align(Alignment.Top).padding(top = insets.lineGap.dp)
        if (avatar != null) ProjectAvatar(avatar, 20.dp, iconCell)
        else if (details.isNotEmpty())
          Box(iconCell.size(20.dp), contentAlignment = Alignment.Center) {
            if (painter != null) Image(painter, null, Modifier.size(16.dp))
          }
        else if (painter != null) Image(painter, null, Modifier.size(16.dp))
        else if (iconSlot) Spacer(Modifier.size(16.dp))
        if (details.isEmpty())
          BasicText(
            row.text,
            Modifier.padding(vertical = insets.lineGap.dp),
            style = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        else
          Column {
            val lineGap = Modifier.padding(vertical = insets.lineGap.dp)
            BasicText(
              row.text,
              lineGap,
              style = text,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            // The backend sends no font size; IntelliJ's renderer uses JBFont.smallOrNewUiMedium()
            // and the info colour for each of these lines, and for the branch line's icon.
            val small = text.copy(color = Islands.textSecondary, fontSize = 12.sp)
            details.forEach { detail ->
              Row(
                lineGap,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                detail.iconPath?.let(icons::painter)?.let {
                  Image(
                    it,
                    null,
                    Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(Islands.textSecondary),
                  )
                }
                BasicText(
                  detail.text,
                  style = small,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
      }
    }
  }
}

/** A Lux panel inside a popup, painted as the backend draws it; display only. */
@Composable
private fun LuxSurface(surface: LuxDialog, session: ClientSession) {
  val images = session.luxImages ?: return
  if (surface.width == 0 || surface.height == 0) return
  val density = LocalDensity.current
  val fonts = LocalClientFonts.current
  val fontResolver = LocalFontFamilyResolver.current
  val painter = remember(surface.id) { LuxPainter(images, fonts, fontResolver, density) }
  val pixels =
    IntSize(
      (surface.width * density.density).roundToInt(),
      (surface.height * density.density).roundToInt(),
    )
  val bitmap = remember(surface.id, pixels) { ImageBitmap(pixels.width, pixels.height) }
  var painted by remember { mutableIntStateOf(0) }
  LaunchedEffect(surface.version, bitmap) {
    val canvas = GraphicsCanvas(bitmap)
    while (true) painter.paint(canvas, density.density, surface.nextBatch() ?: break)
    painted++
  }
  Canvas(Modifier.size(surface.width.dp, surface.height.dp)) {
    painted // read, so a painted batch redraws
    drawImage(bitmap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
  }
}

/**
 * Places the content with its top-left corner at ([x], [y]) in the parent, moved left or up as far
 * as needed to stay inside it.
 */
private fun Modifier.anchoredAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
  val width = constraints.maxWidth
  val height = constraints.maxHeight
  layout(width, height) {
    placeable.place(
      x.roundToPx().coerceIn(0, (width - placeable.width).coerceAtLeast(0)),
      y.roundToPx().coerceIn(0, (height - placeable.height).coerceAtLeast(0)),
    )
  }
}
