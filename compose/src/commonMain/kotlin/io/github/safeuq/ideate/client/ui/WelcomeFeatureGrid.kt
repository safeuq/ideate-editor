package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/** IntelliJ's `FeatureButtonModel`: one button of the welcome project's empty editor area. */
internal data class FeatureButtonModel(
  val text: String,
  val iconPath: String,
  val featureKey: String,
)

/**
 * The feature JetBrains Client runs itself (`IdeaTerminalFeatureFrontend`): the Terminal window.
 */
internal const val TERMINAL_FEATURE_KEY = "terminal.toolwindow"

/** `IdeaWelcomeRightTabContentProvider.getFeatureButtonModels`, with the New UI icons. */
internal val IDEA_FEATURE_BUTTONS =
  listOf(
    FeatureButtonModel("New Markdown File", "expui/fileTypes/markdown.svg", "New.Markdown.File"),
    FeatureButtonModel("Open File", "expui/general/open.svg", "Open.File"),
    FeatureButtonModel("Open Terminal", "expui/fileTypes/shell.svg", TERMINAL_FEATURE_KEY),
  )

/** Gap between drawn buttons: the grid's cell gap 10 plus the buttons' border insets 3 + 3. */
private val FEATURE_GAP = 16.dp

/**
 * `WelcomeScreenRightTabImpl.createFeatureGrid`: the buttons in one row, wrapping when the window
 * is narrow (IntelliJ counts `getButtonsPerRow` instead).
 */
@Composable
internal fun FeatureGrid(
  models: List<FeatureButtonModel>,
  onClick: (FeatureButtonModel) -> Unit,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(FEATURE_GAP, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(FEATURE_GAP),
  ) {
    for (model in models) DisclosureButton(model.text, model.iconPath) { onClick(model) }
  }
}

/**
 * IntelliJ's `DisclosureButton` (`DarculaDisclosureButtonUI`) without the arrow, as the welcome
 * grid uses it: the fill, with the hover or pressed overlay drawn over it.
 */
@Composable
internal fun DisclosureButton(text: String, iconPath: String, onClick: () -> Unit) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val pressed by interactionSource.collectIsPressedAsState()
  val shape = RoundedCornerShape(8.dp)
  val overlay =
    when {
      pressed -> Islands.color("DisclosureButton.pressedOverlay")
      hovered -> Islands.color("DisclosureButton.hoverOverlay")
      else -> Color.Transparent
    }
  Row(
    Modifier.height(48.dp)
      .widthIn(min = 72.dp)
      .background(Islands.color("DisclosureButton.defaultBackground"), shape)
      .background(overlay, shape)
      .clickable(interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .padding(start = 14.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LocalIcons.current.painter(iconPath)?.let { Image(it, null, Modifier.size(16.dp)) }
    Spacer(Modifier.size(12.dp))
    BasicText(text, style = LocalClientFonts.current.label(Islands.text), maxLines = 1)
  }
}
