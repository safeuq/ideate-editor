package io.github.safeuq.ideate.client.ui.popup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
  Box(
    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
      if (top.cancelOnClickOutside) session.popupCancel(top)
    },
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      if (anchor == null)
        Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp).widthIn(max = Look.width)
      else Modifier.anchoredAt(anchor.x.dp, anchor.y.dp).widthIn(max = Look.width),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      popups.forEach { popup -> key(popup.id) { BackendPopupView(popup, session) } }
    }
  }
}

@Composable
private fun BackendPopupView(popup: BackendPopup, session: ClientSession) {
  val text = LocalClientFonts.current.label()
  Column(
    Modifier.fillMaxWidth()
      .popupContainer(Look.radius)
      // Taps inside the popup stay there.
      .clickable(remember { MutableInteractionSource() }, indication = null) {}
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
      .padding(Look.inset),
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
@Composable
private fun PopupListView(list: PopupList, session: ClientSession) {
  val text = LocalClientFonts.current.label()
  val icons = LocalIcons.current
  Column {
    list.rows.forEachIndexed { index, row ->
      row.header?.let { header ->
        Box(
          Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Islands.popupBorder),
        )
        if (header.isNotEmpty())
          BasicText(
            header,
            Modifier.padding(horizontal = Look.rowInset),
            style = text.copy(color = Islands.textSecondary),
          )
      }
      // A project's row (name over path) is as tall as its two lines, with the 6 dp above and
      // below that the backend's BeStyleControl margin asks for.
      val description = row.description
      Row(
        Modifier.fillMaxWidth()
          .then(if (description == null) Modifier.height(Look.rowHeight) else Modifier)
          .background(
            if (index == list.selected) Islands.selectionBackground else Color.Transparent,
            RoundedCornerShape(Look.selectionRadius),
          )
          .clickable(remember { MutableInteractionSource() }, indication = null) {
            session.popupSelect(list, row)
          }
          .padding(horizontal = Look.rowInset, vertical = if (description == null) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Look.iconGap),
      ) {
        val avatar = row.avatar
        // The avatar's cell: 20 dp (AvatarIconModel.targetSize), at the top with a 2 dp gap.
        if (avatar != null)
          ProjectAvatar(avatar, 20.dp, Modifier.align(Alignment.Top).padding(top = 2.dp))
        else row.iconPath?.let(icons::painter)?.let { Image(it, null, Modifier.size(16.dp)) }
        if (description == null) BasicText(row.text, style = text)
        else
          Column {
            BasicText(row.text, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The backend sends no font size; IntelliJ's renderer uses JBFont.smallOrNewUiMedium().
            BasicText(
              description,
              style = text.copy(color = Islands.textSecondary, fontSize = 12.sp),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
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
