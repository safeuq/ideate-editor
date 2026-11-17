package intellijandroid.client.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * IntelliJ's `ActionLink` (platform/platform-api/src/com/intellij/ui/components/ActionLink.kt),
 * Jewel's `Link`: text in `Link.activeForeground` that runs [onClick], underlined under the
 * pointer. Disabled, it is plain `*.disabledForeground` text that does nothing. Examples: a recent
 * project's path, "Authenticate…", the folder chooser's "Show path".
 *
 * [style] sets the font (default `JBFont.label()`); the color comes from the link state.
 */
@Composable
fun Link(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  style: TextStyle = LocalClientFonts.current.label(),
  overflow: TextOverflow = TextOverflow.Ellipsis,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  BasicText(
    text,
    style =
      style.copy(
        color = if (enabled) IslandsDark.link else IslandsDark.textDisabled,
        textDecoration = if (enabled && hovered) TextDecoration.Underline else null,
      ),
    maxLines = 1,
    overflow = overflow,
    modifier =
      if (!enabled) modifier
      else
        modifier
          .hoverable(interaction)
          .clickable(interactionSource = interaction, indication = null, onClick = onClick),
  )
}
