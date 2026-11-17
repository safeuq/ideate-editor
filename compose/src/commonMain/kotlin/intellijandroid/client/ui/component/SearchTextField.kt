package intellijandroid.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.LocalIcons

/**
 * IntelliJ's `SearchTextField` (platform/platform-api/src/com/intellij/ui/SearchTextField.java,
 * painted by `TextFieldWithPopupHandlerUI`), Jewel's `SearchTextField`: a [TextField] with the
 * search icon before the text and, once there is text, a clear icon after it that empties the
 * field.
 *
 * [history] draws the search-with-history icon, as IntelliJ does for a field with a history popup
 * (the popup itself isn't done). [undecorated] drops the border, as on the welcome screen. Example:
 * `SearchTextField(query, { query = it }, placeholder = "Search projects")`.
 */
@Composable
fun SearchTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "Search",
  history: Boolean = false,
  undecorated: Boolean = false,
) {
  val icons = LocalIcons.current
  TextField(
    value,
    onValueChange,
    modifier,
    placeholder = placeholder,
    undecorated = undecorated,
    leadingIcon = {
      // AllIcons.Actions.Search / SearchWithHistory, New UI paths from PlatformIconMappings.json.
      val path = if (history) "expui/inline/searchHistory.svg" else "expui/general/search.svg"
      icons.painter(path)?.let {
        Image(
          it,
          contentDescription = "Search",
          modifier =
            Modifier.padding(end = SearchLook.iconGap)
              .size(width = if (history) 22.dp else 16.dp, height = 16.dp),
        )
      }
    },
    trailingIcon =
      if (value.isEmpty()) null
      else {
        { ClearButton { onValueChange("") } }
      },
  )
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
