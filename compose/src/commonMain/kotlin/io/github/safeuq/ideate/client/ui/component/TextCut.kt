package io.github.safeuq.ideate.client.ui.component

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/**
 * IntelliJ's `StringUtil.shortenTextWithEllipsis` (util-8.jar, 263.7307.200): [text] longer than
 * [maxLength] keeps its first `maxLength - suffixLength - ellipsis.length` and last [suffixLength]
 * characters with `...` (or `…`) between them.
 */
fun shortenTextWithEllipsis(
  text: String,
  maxLength: Int,
  suffixLength: Int,
  useEllipsisSymbol: Boolean = false,
): String {
  if (text.length <= maxLength) return text
  val ellipsis = if (useEllipsisSymbol) "…" else "..."
  val prefixLength = maxLength - suffixLength - ellipsis.length
  check(prefixLength >= 0) { "maxLength $maxLength too small for suffix $suffixLength" }
  return text.substring(0, prefixLength) + ellipsis + text.substring(text.length - suffixLength)
}

/** IntelliJ's `StringUtil.shortenPathWithEllipsis`: the last 70 % of [maxLength] is kept. */
fun shortenPathWithEllipsis(path: String, maxLength: Int, useEllipsisSymbol: Boolean = false) =
  shortenTextWithEllipsis(path, maxLength, (maxLength * 0.7).toInt(), useEllipsisSymbol)

/**
 * Gateway's `OurTextCutStrategy.calcShownText`: [text] when it fits [maxWidthPx], else the
 * [shortenPathWithEllipsis] cut with the largest length that fits.
 */
fun calcShownText(
  text: String,
  style: TextStyle,
  maxWidthPx: Int,
  textMeasurer: TextMeasurer,
): String {
  fun fits(candidate: String) =
    textMeasurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= maxWidthPx
  if (fits(text)) return text
  // The smallest cut shortenPathWithEllipsis accepts: room for "..." and the suffix.
  var low = 10
  var high = text.length - 1
  var best = shortenPathWithEllipsis(text, low)
  while (low <= high) {
    val middle = (low + high) / 2
    val candidate = shortenPathWithEllipsis(text, middle)
    if (fits(candidate)) {
      best = candidate
      low = middle + 1
    } else high = middle - 1
  }
  return best
}
