package intellijandroid.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import intellijandroid.client.ui.theme.IslandsDark
import intellijandroid.client.ui.theme.LocalClientFonts
import intellijandroid.client.ui.theme.label

/**
 * IntelliJ's text field: `JBTextField` under `DarculaTextFieldUI` and `DarculaTextBorderNew`
 * (Jewel's `TextField.kt`, values from `IntUiBridgeTextField.kt`). Focused, its 1 px border turns
 * into a 2 px ring in the focus color. [secret] hides the text (`JBPasswordField`); a multi-line
 * field ([singleLine] false) grows to [maxHeight], like a `JBTextArea` in a scroll pane.
 *
 * Example: SSH's "Host:" field, `TextField(form.host, { host -> … })`, 28 high (24 compact).
 *
 * As in Jewel: [placeholder] shows in `text-secondary` while the field is empty (Swing's
 * `emptyText`); [leadingIcon] and [trailingIcon] sit inside the border, before and after the text
 * ([SearchTextField] puts its search and clear icons there); [undecorated] drops the border, the
 * background and the focus ring, as IntelliJ's welcome screen does with its search field (`border =
 * JBUI.Borders.empty()`).
 */
@Composable
fun TextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  secret: Boolean = false,
  singleLine: Boolean = true,
  maxHeight: androidx.compose.ui.unit.Dp = 160.dp,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  placeholder: String? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  undecorated: Boolean = false,
) {
  val fonts = LocalClientFonts.current
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val shape = RoundedCornerShape(FieldLook.radius)
  val height = FieldLook.height
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = singleLine,
    textStyle = fonts.label(if (enabled) IslandsDark.text else IslandsDark.textDisabled),
    visualTransformation =
      if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    interactionSource = interaction,
    cursorBrush = SolidColor(IslandsDark.text),
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = height, max = if (singleLine) height else maxHeight)
        .drawBehind {
          if (!focused || undecorated) return@drawBehind
          // The border is 1 px; the focus ring adds 1 px outside it, 2 px of focus color in all.
          val ring = 1.dp.toPx()
          drawRoundRect(
            IslandsDark.focusBorder,
            topLeft = Offset(-ring / 2, -ring / 2),
            size = Size(size.width + ring, size.height + ring),
            cornerRadius = CornerRadius(FieldLook.radius.toPx() + ring / 2),
            style = Stroke(ring),
          )
        }
        .then(
          if (undecorated) Modifier
          else
            Modifier.background(FieldLook.background, shape)
              .border(
                1.dp,
                when {
                  !enabled -> FieldLook.disabledBorder
                  focused -> IslandsDark.focusBorder
                  else -> IslandsDark.controlBorder
                },
                shape,
              ),
        ),
    decorationBox = { inner ->
      Row(
        Modifier.padding(
          horizontal = if (undecorated) 0.dp else FieldLook.horizontalPadding,
          vertical = 4.dp,
        ),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
      ) {
        leadingIcon?.invoke()
        Box(
          Modifier.weight(1f),
          contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
          if (value.isEmpty() && placeholder != null)
            BasicText(placeholder, style = fonts.label(IslandsDark.textSecondary), maxLines = 1)
          inner()
        }
        trailingIcon?.invoke()
      }
    },
  )
}

/** DarculaTextBorderNew and the Islands Dark theme (ManyIslandsDark.theme.json). */
private object FieldLook {
  val radius = 4.dp // Component.arc 8, an arc diameter
  val height // TextField.minimumSize 49,28; compact 49,24
    get() = IslandsDark.byDensity(28.dp, 24.dp)

  val horizontalPadding = 9.dp // IntUiBridgeTextField: 8 + the 1 px border
  val background = Color(0xFF191A1C) // TextField.background = control-bg
  val disabledBorder = Color(0xFF33353B) // *.disabledBorderColor = control-border-disabled
}
