package intellijandroid.client.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * IntelliJ's check box with its label (`JBCheckBox` under `DarculaCheckBoxUI`; Jewel's
 * `CheckboxRow` in `ui/component/Checkbox.kt`), drawn with the New UI's `checkBox*.svg`.
 *
 * Example: SSH's "Save password", `CheckboxRow("Save password", form.savePassword) { … }`.
 */
@Composable
fun CheckboxRow(
  text: String,
  checked: Boolean,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  ToggleRow(
    text,
    toggleIcon("checkBox", checked, enabled, focused),
    enabled,
    modifier.toggleable(
      value = checked,
      enabled = enabled,
      role = Role.Checkbox,
      interactionSource = interaction,
      indication = null,
      onValueChange = onCheckedChange,
    ),
  )
}
