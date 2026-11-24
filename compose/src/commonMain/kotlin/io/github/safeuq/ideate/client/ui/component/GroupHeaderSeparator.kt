package io.github.safeuq.ideate.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.ui.theme.Islands
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.medium

/**
 * IntelliJ's `GroupHeaderSeparator` (new UI), the heading above a group of rows in list popups,
 * combo boxes and completion, e.g. "Configuration" over More Actions' Edit… and Delete: a 1 px line
 * (`Popup.separatorInsets` 4,8), then the caption in bold `JBFont.medium()` (12 next to the 13 UI
 * font) with `Popup.separatorLabelInsets` 3,16 around it.
 *
 * The insets are from IntelliJ's list cell, whose rows draw their selection
 * `Popup.Selection.leftRightInset` (8) in from it. [cellInset] is how much of that the caller's
 * edge already is in from the cell: 8 when the separator spans the selection's width, as Ideate's
 * list rows do, so the line spans the selection and the caption starts with the rows' content.
 *
 * [caption] null or empty: only the line. [hideLine]: only the caption, as IntelliJ does for the
 * first row of a list.
 */
@Composable
fun GroupHeaderSeparator(
  caption: String?,
  modifier: Modifier = Modifier,
  hideLine: Boolean = false,
  cellInset: Dp = 0.dp,
) {
  Column(modifier.fillMaxWidth()) {
    if (!hideLine)
      Box(
        Modifier.padding(vertical = 4.dp, horizontal = 8.dp - cellInset)
          .fillMaxWidth()
          .height(1.dp)
          .background(Islands.color("Popup.separatorColor")),
      )
    if (!caption.isNullOrEmpty())
      BasicText(
        caption,
        Modifier.padding(vertical = 3.dp, horizontal = 16.dp - cellInset),
        style =
          LocalClientFonts.current
            .medium(Islands.color("Popup.separatorForeground"))
            .copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
  }
}
