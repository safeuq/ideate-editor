package intellijandroid.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.MenuItem

/**
 * IntelliJ's `SearchTextField` (platform/platform-api/src/com/intellij/ui/SearchTextField.java,
 * painted by `TextFieldWithPopupHandlerUI`), Jewel's `SearchTextField`: a [TextField] with the
 * search icon before the text and, once there is text, a clear icon after it that empties the
 * field.
 *
 * With a [history], the icon is the search-with-history one, and a click or tap on it opens the
 * recent searches underneath; choosing one puts it in the field. As in IntelliJ, the text joins the
 * history when the field loses focus and when the popup opens. [undecorated] drops the border, as
 * on the welcome screen. Example: `SearchTextField(query, { query = it }, history = searches)`.
 */
@Composable
fun SearchTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "Search",
  history: SearchHistory? = null,
  undecorated: Boolean = false,
) {
  val icons = LocalIcons.current
  val currentValue by rememberUpdatedState(value)
  // Rows as tall as the context menu's: 40 when the popup was opened by touch.
  var popupByTouch by remember { mutableStateOf<Boolean?>(null) }
  Box {
    TextField(
      value,
      onValueChange,
      modifier.onFocusChanged { if (!it.isFocused) history?.add(currentValue) },
      placeholder = placeholder,
      undecorated = undecorated,
      leadingIcon = {
        // AllIcons.Actions.Search / SearchWithHistory, New UI paths from PlatformIconMappings.json.
        val path =
          if (history != null) "expui/inline/searchHistory.svg" else "expui/general/search.svg"
        icons.painter(path)?.let {
          Image(
            it,
            contentDescription = if (history != null) "Recent searches" else "Search",
            modifier =
              Modifier.padding(end = SearchLook.iconGap)
                .size(width = if (history != null) 22.dp else 16.dp, height = 16.dp)
                .then(
                  if (history == null) Modifier
                  else
                    Modifier.pointerInput(history) {
                      awaitEachGesture {
                        val down = awaitFirstDown()
                        if (waitForUpOrCancellation() == null) return@awaitEachGesture
                        // showPopup(): the current text joins the history first.
                        history.add(currentValue)
                        if (history.items.isNotEmpty())
                          popupByTouch = down.type == PointerType.Touch
                      }
                    },
                ),
          )
        }
      },
      trailingIcon =
        if (value.isEmpty()) null
        else {
          { ClearButton { onValueChange("") } }
        },
    )
    val byTouch = popupByTouch
    if (history != null && byTouch != null) {
      val below = with(LocalDensity.current) { 30.dp.roundToPx() }
      Popup(
        offset = IntOffset(0, below),
        onDismissRequest = { popupByTouch = null },
        properties = PopupProperties(focusable = true),
      ) {
        Menu(
          { history.items.map { item -> MenuItem(item, onClick = { onValueChange(item) }) } },
          rowHeight = if (byTouch) Menus.touchRowHeight else Menus.pointerRowHeight,
          close = { popupByTouch = null },
        )
      }
    }
  }
}

/**
 * Recent searches, newest first, as `SearchTextField.MyModel.addElement` keeps them: at most [size]
 * (IntelliJ's default 5), no empty entries, and a search already there (ignoring case) moves to the
 * top instead of appearing twice. Example: after `a`, `b`, `A` the list is `A`, `b`; one already at
 * the top stays as it is.
 *
 * ponytail: kept in memory for the app's life; IntelliJ saves it under a property name only when
 * the field has one. Save it when a field needs its history across restarts.
 */
class SearchHistory(private val size: Int = 5) {
  val items = mutableStateListOf<String>()

  fun add(search: String) {
    if (search.isBlank()) return
    val existing = items.indexOfFirst { it.equals(search, ignoreCase = true) }
    if (existing == 0) return // already the newest
    if (existing > 0) items.removeAt(existing)
    else if (items.size >= size) items.removeAt(items.lastIndex)
    items.add(0, search)
  }
}

/** `getClearIcon`: `AllIcons.Actions.Close`, `CloseHovered` under the pointer; no background. */
@Composable
private fun ClearButton(onClear: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val icon =
    LocalIcons.current.painter(
      if (hovered) "expui/general/closeSmallHovered.svg" else "expui/general/closeSmall.svg",
    ) ?: return
  Image(
    icon,
    contentDescription = "Clear",
    modifier =
      Modifier.padding(start = SearchLook.iconGap)
        .size(16.dp)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = onClear),
  )
}

private object SearchLook {
  val iconGap = 2.dp // TextFieldWithPopupHandlerUI.getSearchIconGap / getClearIconGap
}
