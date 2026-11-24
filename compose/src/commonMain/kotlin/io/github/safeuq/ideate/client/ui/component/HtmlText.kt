package io.github.safeuq.ideate.client.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import java.io.StringReader
import org.ccil.cowan.tagsoup.HTMLSchema
import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * A label from the backend as a Swing `JLabel` shows it: text starting with `<html>` (any case) is
 * HTML, anything else is plain. With [colored] false the colour spans are left out, so a disabled
 * row is grey all through.
 */
fun swingText(text: String, colored: Boolean = true): AnnotatedString =
  if (text.startsWith("<html>", ignoreCase = true)) convertHtml(text, colored)
  else AnnotatedString(text)

/**
 * HTML as androidx Compose's `AnnotatedString.fromHtml` reads it on Android. That function runs
 * `android.text.Html.fromHtml(source, FROM_HTML_MODE_COMPACT)` (TagSoup plus
 * `HtmlToSpannedConverter`) and maps the Android spans to Compose styles in `Html.android.kt`. This
 * is a port of both: the tag handling below follows `HtmlToSpannedConverter`, and each span is
 * written straight as the [SpanStyle], [ParagraphStyle] or [LinkAnnotation] Compose maps it to.
 */
fun htmlText(html: String): AnnotatedString = convertHtml(html, colored = true)

/** Android's `Html.HtmlParser.schema`: one schema, shared by every parser. */
private val htmlSchema: HTMLSchema by lazy { HTMLSchema() }

private fun convertHtml(html: String, colored: Boolean): AnnotatedString {
  // Html.fromHtml: a TagSoup parser with only the HTML schema set.
  val parser = Parser()
  parser.setProperty(Parser.schemaProperty, htmlSchema)
  val converter = HtmlConverter(colored)
  parser.contentHandler = converter
  parser.parse(InputSource(StringReader(html)))
  return converter.result()
}

/** The open-tag records `HtmlToSpannedConverter` keeps as zero-width mark spans. */
private sealed class Mark {
  var start: Int = 0

  class Bold : Mark()

  class Italic : Mark()

  class Underline : Mark()

  class Strikethrough : Mark()

  class Big : Mark()

  class Small : Mark()

  class Monospace : Mark()

  class Blockquote : Mark()

  class Super : Mark()

  class Sub : Mark()

  class Bullet : Mark()

  class Font(val face: String) : Mark()

  class Href(val href: String?) : Mark()

  class Foreground(val color: Int) : Mark()

  class Background(val color: Int) : Mark()

  class Heading(val level: Int) : Mark()

  class Newline(val count: Int) : Mark()

  class Alignment(val align: TextAlign) : Mark()
}

/** `HtmlToSpannedConverter.HEADING_SIZES`. */
private val headingSizes = floatArrayOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1f)

/** `HtmlToSpannedConverter`'s CSS patterns. */
private val textAlignPattern = Regex("""(?:\s+|\A)text-align\s*:\s*(\S*)\b""")
private val foregroundColorPattern = Regex("""(?:\s+|\A)color\s*:\s*(\S*)\b""")
private val backgroundColorPattern = Regex("""(?:\s+|\A)background(?:-color)?\s*:\s*(\S*)\b""")
private val textDecorationPattern = Regex("""(?:\s+|\A)text-decoration\s*:\s*(\S*)\b""")

/** `android.graphics.Color`'s named colours (`sColorNameMap`), used by `Color.parseColor`. */
private val namedColors: Map<String, Int> =
  mapOf(
    "black" to 0xFF000000.toInt(),
    "darkgray" to 0xFF444444.toInt(),
    "gray" to 0xFF888888.toInt(),
    "lightgray" to 0xFFCCCCCC.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF00FF00.toInt(),
    "blue" to 0xFF0000FF.toInt(),
    "yellow" to 0xFFFFFF00.toInt(),
    "cyan" to 0xFF00FFFF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(),
    "aqua" to 0xFF00FFFF.toInt(),
    "fuchsia" to 0xFFFF00FF.toInt(),
    "darkgrey" to 0xFF444444.toInt(),
    "grey" to 0xFF888888.toInt(),
    "lightgrey" to 0xFFCCCCCC.toInt(),
    "lime" to 0xFF00FF00.toInt(),
    "maroon" to 0xFF800000.toInt(),
    "navy" to 0xFF000080.toInt(),
    "olive" to 0xFF808000.toInt(),
    "purple" to 0xFF800080.toInt(),
    "silver" to 0xFFC0C0C0.toInt(),
    "teal" to 0xFF008080.toInt(),
  )

/**
 * `HtmlToSpannedConverter.getHtmlColor` without `FROM_HTML_OPTION_USE_CSS_COLORS` (COMPACT does not
 * set it): a name goes through `Color.parseColor`, anything else through
 * `XmlUtils.convertValueToInt` ("#", "0x" hex, "0" octal, decimal). -1 means "no colour", so, as on
 * Android, `white` (0xFFFFFFFF) is dropped too.
 */
private fun htmlColor(color: String): Int {
  // Android would throw on an empty value here; no colour is the useful answer.
  if (color.isEmpty()) return -1
  if (color[0].isLetter()) return namedColors[color.lowercase()] ?: -1
  var digits = color
  var sign = 1L
  if (digits.startsWith("-")) {
    sign = -1L
    digits = digits.substring(1)
  }
  val radix: Int
  when {
    digits.startsWith("0x", ignoreCase = true) -> {
      digits = digits.substring(2)
      radix = 16
    }
    digits.startsWith("#") -> {
      digits = digits.substring(1)
      radix = 16
    }
    digits.startsWith("0") && digits.length > 1 -> {
      digits = digits.substring(1)
      radix = 8
    }
    else -> radix = 10
  }
  return digits.toLongOrNull(radix)?.let { (it * sign).toInt() } ?: -1
}

/** `HtmlToSpannedConverter` as a SAX handler, building text and Compose styles. */
private class HtmlConverter(private val colored: Boolean) : DefaultHandler() {
  private val text = StringBuilder()
  private val marks = mutableListOf<Mark>()
  private val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
  private val paragraphStyles = mutableListOf<AnnotatedString.Range<ParagraphStyle>>()
  private val links = mutableListOf<AnnotatedString.Range<LinkAnnotation.Url>>()

  /** `convert()`'s paragraph fix-up, then the spans as one [AnnotatedString]. */
  fun result(): AnnotatedString {
    val paragraphs = paragraphStyles.mapNotNull { range ->
      var end = range.end
      if (end - 2 >= 0 && text[end - 1] == '\n' && text[end - 2] == '\n') end--
      if (end == range.start) null else AnnotatedString.Range(range.item, range.start, end)
    }
    return AnnotatedString.Builder(text.toString())
      .apply {
        spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        paragraphs.forEach { addStyle(it.item, it.start, it.end) }
        links.forEach { addLink(it.item, it.start, it.end) }
      }
      .toAnnotatedString()
  }

  override fun startElement(
    uri: String?,
    localName: String,
    qualifiedName: String?,
    attributes: Attributes,
  ) {
    when (val tag = localName.lowercase()) {
      "br" -> {} // TagSoup closes every <br>; the line break is added at the end tag.
      "p" -> {
        startBlockElement(attributes, margin = 1)
        startCssStyle(attributes)
      }
      "ul" -> startBlockElement(attributes, margin = 1)
      "li" -> {
        startBlockElement(attributes, margin = 1)
        start(Mark.Bullet())
        startCssStyle(attributes)
      }
      "div" -> startBlockElement(attributes, margin = 1)
      "span" -> startCssStyle(attributes)
      "strong",
      "b" -> start(Mark.Bold())
      "em",
      "cite",
      "dfn",
      "i" -> start(Mark.Italic())
      "big" -> start(Mark.Big())
      "small" -> start(Mark.Small())
      "font" -> startFont(attributes)
      "blockquote" -> {
        startBlockElement(attributes, margin = 1)
        start(Mark.Blockquote())
      }
      "tt" -> start(Mark.Monospace())
      "a" -> start(Mark.Href(attributes.getValue("", "href")))
      "u" -> start(Mark.Underline())
      "del",
      "s",
      "strike" -> start(Mark.Strikethrough())
      "sup" -> start(Mark.Super())
      "sub" -> start(Mark.Sub())
      "img" -> text.append('￼') // No ImageGetter: the object replacement character only.
      else ->
        if (isHeading(tag)) {
          startBlockElement(attributes, margin = 1)
          start(Mark.Heading(tag[1] - '1'))
        }
    }
  }

  override fun endElement(uri: String?, localName: String, qualifiedName: String?) {
    when (val tag = localName.lowercase()) {
      "br" -> text.append('\n')
      "p" -> {
        endCssStyle()
        endBlockElement()
      }
      "ul",
      "div" -> endBlockElement()
      "li" -> {
        endCssStyle()
        endBlockElement()
        end<Mark.Bullet>() // BulletSpan: Compose maps only its own list bullets, so no style.
      }
      "span" -> endCssStyle()
      "strong",
      "b" -> end<Mark.Bold>(SpanStyle(fontWeight = FontWeight.Bold))
      "em",
      "cite",
      "dfn",
      "i" -> end<Mark.Italic>(SpanStyle(fontStyle = FontStyle.Italic))
      "big" -> end<Mark.Big>(SpanStyle(fontSize = 1.25f.em))
      "small" -> end<Mark.Small>(SpanStyle(fontSize = 0.8f.em))
      "font" -> endFont()
      "blockquote" -> {
        endBlockElement()
        end<Mark.Blockquote>() // QuoteSpan: Compose has no mapping for it.
      }
      "tt" -> end<Mark.Monospace>(SpanStyle(fontFamily = FontFamily.Monospace))
      "a" -> endLink()
      "u" -> end<Mark.Underline>(SpanStyle(textDecoration = TextDecoration.Underline))
      "del",
      "s",
      "strike" -> end<Mark.Strikethrough>(SpanStyle(textDecoration = TextDecoration.LineThrough))
      "sup" -> end<Mark.Super>(SpanStyle(baselineShift = BaselineShift.Superscript))
      "sub" -> end<Mark.Sub>(SpanStyle(baselineShift = BaselineShift.Subscript))
      else -> if (isHeading(tag)) endHeading()
    }
  }

  /** Whitespace right after other whitespace is dropped; a newline counts as a space. */
  override fun characters(characters: CharArray, start: Int, length: Int) {
    val chunk = StringBuilder()
    for (index in start until start + length) {
      val character = characters[index]
      if (character == ' ' || character == '\n') {
        val previous =
          when {
            chunk.isNotEmpty() -> chunk.last()
            text.isNotEmpty() -> text.last()
            else -> '\n'
          }
        if (previous != ' ' && previous != '\n') chunk.append(' ')
      } else {
        chunk.append(character)
      }
    }
    text.append(chunk)
  }

  private fun isHeading(tag: String): Boolean =
    tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'

  private fun start(mark: Mark) {
    mark.start = text.length
    marks += mark
  }

  /** `getLast`: the most recently opened mark of this kind, removed from the open list. */
  private inline fun <reified T : Mark> takeLast(): T? {
    val index = marks.indexOfLast { it is T }
    return if (index < 0) null else marks.removeAt(index) as T
  }

  /** `setSpanFromMark`: a style from the mark to the end, unless that range is empty. */
  private fun addStyle(mark: Mark, style: SpanStyle?) {
    if (style != null && mark.start != text.length) {
      spanStyles += AnnotatedString.Range(style, mark.start, text.length)
    }
  }

  private inline fun <reified T : Mark> end(style: SpanStyle? = null) {
    takeLast<T>()?.let { addStyle(it, style) }
  }

  private fun appendNewlines(minimum: Int) {
    if (text.isEmpty()) return
    val existing = text.length - text.trimEnd('\n').length
    repeat(minimum - existing) { text.append('\n') }
  }

  /** Margins are all 1: COMPACT sets every `FROM_HTML_SEPARATOR_LINE_BREAK_*` flag. */
  private fun startBlockElement(attributes: Attributes, margin: Int) {
    appendNewlines(margin)
    start(Mark.Newline(margin))
    val style = attributes.getValue("", "style") ?: return
    val alignment = textAlignPattern.find(style)?.groupValues?.get(1) ?: return
    when (alignment.lowercase()) {
      "start" -> start(Mark.Alignment(TextAlign.Start))
      "center" -> start(Mark.Alignment(TextAlign.Center))
      "end" -> start(Mark.Alignment(TextAlign.End))
    }
  }

  private fun endBlockElement() {
    takeLast<Mark.Newline>()?.let { appendNewlines(it.count) }
    takeLast<Mark.Alignment>()?.let {
      if (it.start != text.length) {
        paragraphStyles +=
          AnnotatedString.Range(ParagraphStyle(textAlign = it.align), it.start, text.length)
      }
    }
  }

  private fun endHeading() {
    // The size and weight stop before the newlines the block adds.
    takeLast<Mark.Heading>()?.let {
      addStyle(it, SpanStyle(fontSize = headingSizes[it.level].em, fontWeight = FontWeight.Bold))
    }
    endBlockElement()
  }

  private fun startColor(mark: Mark) {
    if (colored) start(mark)
  }

  private fun startCssStyle(attributes: Attributes) {
    val style = attributes.getValue("", "style") ?: return
    foregroundColorPattern.find(style)?.let {
      val color = htmlColor(it.groupValues[1])
      if (color != -1) startColor(Mark.Foreground(color or 0xFF000000.toInt()))
    }
    backgroundColorPattern.find(style)?.let {
      val color = htmlColor(it.groupValues[1])
      if (color != -1) start(Mark.Background(color or 0xFF000000.toInt()))
    }
    textDecorationPattern.find(style)?.let {
      if (it.groupValues[1].equals("line-through", ignoreCase = true)) start(Mark.Strikethrough())
    }
  }

  private fun endCssStyle() {
    end<Mark.Strikethrough>(SpanStyle(textDecoration = TextDecoration.LineThrough))
    takeLast<Mark.Background>()?.let { addStyle(it, SpanStyle(background = Color(it.color))) }
    takeLast<Mark.Foreground>()?.let { addStyle(it, SpanStyle(color = Color(it.color))) }
  }

  private fun startFont(attributes: Attributes) {
    val color = attributes.getValue("", "color")
    val face = attributes.getValue("", "face")
    if (!color.isNullOrEmpty()) {
      val value = htmlColor(color)
      if (value != -1) startColor(Mark.Foreground(value or 0xFF000000.toInt()))
    }
    if (!face.isNullOrEmpty()) start(Mark.Font(face))
  }

  private fun endFont() {
    takeLast<Mark.Font>()?.let { addStyle(it, fontFamilyStyle(it.face)) }
    takeLast<Mark.Foreground>()?.let { addStyle(it, SpanStyle(color = Color(it.color))) }
  }

  /**
   * Compose's `TypefaceSpan.toSpanStyle`: the generic family names. Other faces would be looked up
   * as Android system fonts, which desktop has no equivalent for, so they get no style.
   */
  private fun fontFamilyStyle(face: String): SpanStyle? =
    when (face) {
      FontFamily.Cursive.name -> FontFamily.Cursive
      FontFamily.Monospace.name -> FontFamily.Monospace
      FontFamily.SansSerif.name -> FontFamily.SansSerif
      FontFamily.Serif.name -> FontFamily.Serif
      else -> null
    }?.let { SpanStyle(fontFamily = it) }

  /** An `<a>` without `href` gets no span (and its style attribute is never read). */
  private fun endLink() {
    val mark = takeLast<Mark.Href>() ?: return
    val href = mark.href ?: return
    if (mark.start != text.length)
      links += AnnotatedString.Range(LinkAnnotation.Url(href), mark.start, text.length)
  }
}
