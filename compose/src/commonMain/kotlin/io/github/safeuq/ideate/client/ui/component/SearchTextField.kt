package io.github.safeuq.ideate.client.ui.component

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.MenuItem

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
  /** From the icon to the text: 2 (`getSearchIconGap`); Search Everywhere's history icon has 6. */
  iconGap: Dp = SearchLook.iconGap,
  /**
   * The soft keyboard's action key (shown as Go, →): it sends no Enter key event, so a field whose
   * Enter does something needs this too. Example: Search Everywhere opens the selected row.
   */
  onSubmit: (() -> Unit)? = null,
) {
  val icons = LocalIcons.current
  val currentValue by rememberUpdatedState(value)
  var historyShown by remember { mutableStateOf(false) }
  Box {
    TextField(
      value,
      onValueChange,
      modifier.onFocusChanged { if (!it.isFocused) history?.add(currentValue) },
      keyboardOptions =
        if (onSubmit == null) KeyboardOptions.Default
        else KeyboardOptions(imeAction = ImeAction.Go),
      keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }),
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
              Modifier.padding(end = iconGap)
                .size(width = if (history != null) 22.dp else 16.dp, height = 16.dp)
                .then(
                  if (history == null) Modifier
                  else
                    Modifier.pointerInput(history) {
                      awaitEachGesture {
                        awaitFirstDown()
                        if (waitForUpOrCancellation() == null) return@awaitEachGesture
                        // showPopup(): the current text joins the history first.
                        history.add(currentValue)
                        if (history.items.isNotEmpty()) historyShown = true
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
    if (history != null && historyShown) {
      val below = with(LocalDensity.current) { 30.dp.roundToPx() }
      AppPopup(
        PopupPlacement(IntOffset(0, below), below),
        onDismissRequest = { historyShown = false },
      ) {
        Menu(
          { history.items.map { item -> MenuItem(item, onClick = { onValueChange(item) }) } },
          close = { historyShown = false },
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
