package io.github.safeuq.ideate.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.safeuq.ideate.client.session.UsageRow
import io.github.safeuq.ideate.client.session.UsagesPopup
import io.github.safeuq.ideate.client.ui.component.popupContainer
import io.github.safeuq.ideate.client.ui.theme.IslandsDark
import io.github.safeuq.ideate.client.ui.theme.LocalClientFonts
import io.github.safeuq.ideate.client.ui.theme.editor
import io.github.safeuq.ideate.client.ui.theme.medium

/**
 * IntelliJ's Show Usages popup: the target and the usage count on top, a row per usage (line
 * number, the line with the usage in bold, file name), the scope below. A click or Enter goes to
 * the usage.
 */
@Composable
internal fun UsagesPopupView(popup: UsagesPopup, onChoose: (Int) -> Unit) {
  val fonts = LocalClientFonts.current
  val textStyle = fonts.editor(Usages.foreground)
  val secondaryStyle = fonts.editor(Usages.secondary)
  val headerStyle = fonts.medium(Usages.foreground)
  val listState = rememberLazyListState()
  LaunchedEffect(popup.selected) {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty() && popup.selected !in visible.first().index..visible.last().index) {
      listState.scrollToItem(popup.selected)
    }
  }
  Column(Modifier.width(Usages.width).popupContainer(Usages.radius)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicText(
        popup.title,
        style = headerStyle.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(12.dp))
      BasicText(
        when {
          !popup.complete -> "${popup.rows.size} usages, searching…"
          popup.rows.size == 1 -> "1 usage"
          else -> "${popup.rows.size} usages"
        },
        style = headerStyle.copy(color = Usages.secondary),
        maxLines = 1,
      )
    }
    if (popup.complete && popup.rows.isEmpty()) {
      BasicText(
        "No usages found in ${popup.scope}",
        style = headerStyle.copy(color = Usages.secondary),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().heightIn(max = Usages.rowHeight * Usages.maxRows),
    ) {
      items(popup.rows.size) { position ->
        UsageRowView(
          popup.rows[position],
          position == popup.selected,
          textStyle,
          secondaryStyle,
          onChoose,
        )
      }
    }
    BasicText(
      popup.scope,
      style = headerStyle.copy(color = Usages.secondary),
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun UsageRowView(
  row: UsageRow,
  selected: Boolean,
  textStyle: androidx.compose.ui.text.TextStyle,
  secondaryStyle: androidx.compose.ui.text.TextStyle,
  onChoose: (Int) -> Unit,
) {
  val text = buildAnnotatedString {
    append(row.text.substring(0, row.matchStart))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
      append(row.text.substring(row.matchStart, row.matchEnd))
    }
    append(row.text.substring(row.matchEnd))
  }
  Row(
    Modifier.fillMaxWidth()
      .height(Usages.rowHeight)
      .padding(horizontal = 3.dp)
      .background(
        if (selected) IslandsDark.selectionBackground else Color.Transparent,
        RoundedCornerShape(4.dp),
      )
      .clickable { onChoose(row.index) }
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(
      if (row.line > 0) "${row.line}" else "",
      style = secondaryStyle.copy(textAlign = TextAlign.End),
      modifier = Modifier.width(32.dp),
    )
    Spacer(Modifier.width(10.dp))
    BasicText(
      text,
      style = textStyle,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(16.dp))
    BasicText(row.fileName, style = secondaryStyle, maxLines = 1)
  }
}

private object Usages {
  val foreground = Color(0xFFBCBEC4)
  val secondary = IslandsDark.textSecondary
  val rowHeight = 24.dp
  val maxRows = 15
  val radius = 6.dp
  val width = 600.dp
}
