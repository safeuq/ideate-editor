package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.LocalIcons
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.label

/**
 * IntelliJ's radio button with its label (`JBRadioButton` under `DarculaRadioButtonUI`; Jewel's
 * `RadioButtonRow` in `ui/component/RadioButton.kt`). The circle is the New UI's own icon for the
 * state, `themes/expUI/icons/dark/radio*.svg` (24 px, the circle 16 inside), then
 * `RadioButton.iconTextGap` 4 and the text.
 *
 * Example: SSH's "Authentication:" choices, `RadioButtonRow("Password", selected) { … }`.
 */
@Composable
fun RadioButtonRow(
  text: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  ToggleRow(
    text,
    toggleIcon("radio", selected, enabled, focused),
    enabled,
    modifier.selectable(
      selected = selected,
      enabled = enabled,
      role = Role.RadioButton,
      interactionSource = interaction,
      indication = null,
      onClick = onClick,
    ),
  )
}

/**
 * The icon for a radio button or check box: `radio.svg`, `radioSelected.svg`,
 * `radioSelectedFocused.svg`, `radioDisabled.svg`, … as `IconJar.EXTRA_ICONS` holds them.
 */
internal fun toggleIcon(kind: String, selected: Boolean, enabled: Boolean, focused: Boolean) =
  buildString {
    append("laf/").append(kind)
    if (selected) append("Selected")
    if (!enabled) append("Disabled") else if (focused) append("Focused")
    append(".svg")
  }

/** The icon, the 4 px gap and the label: the row both radio buttons and check boxes use. */
@Composable
internal fun ToggleRow(text: String, iconPath: String, enabled: Boolean, modifier: Modifier) {
  val fonts = LocalClientFonts.current
  val icons = LocalIcons.current
  Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      Arrangement.spacedBy(4.dp), // RadioButton.iconTextGap, CheckBox.textIconGap
  ) {
    // 24 with the circle's 4 px insets (RadioButton.iconSize 24, RadioButton.borderInsets 4).
    icons.painter(iconPath)?.let { Image(it, null, Modifier.size(24.dp)) }
    BasicText(
      text,
      style = fonts.label(if (enabled) IslandsDark.text else IslandsDark.textDisabled),
    )
  }
}
