package io.github.safeuq.ideate.client.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HTML labels, read the way android.text.Html (FROM_HTML_MODE_COMPACT) reads them. */
class HtmlTextTest {
  private fun AnnotatedString.styles() = spanStyles.map { Triple(it.item, it.start, it.end) }

  @Test
  fun runConfigurationsCounterHasNoStyle() {
    // RunToolbarPopup's row: an <a> without href adds no span and its style is never read.
    val text =
      htmlText("<html>All Configurations&nbsp;&nbsp;<a style=\"color:#9fa2a8;\">15</a></html>")
    assertEquals("All Configurations  15", text.text)
    assertEquals(emptyList(), text.styles())
    assertEquals(emptyList(), text.getLinkAnnotations(0, text.length))
  }

  @Test
  fun bold() {
    val text = htmlText("<b>Bold</b>")
    assertEquals("Bold", text.text)
    assertEquals(listOf(Triple(SpanStyle(fontWeight = FontWeight.Bold), 0, 4)), text.styles())
  }

  @Test
  fun fontColor() {
    val text = htmlText("<font color=\"#ff0000\">red</font>")
    assertEquals(listOf(Triple(SpanStyle(color = Color(0xFFFF0000)), 0, 3)), text.styles())
  }

  @Test
  fun spanStyleColor() {
    val text = htmlText("a <span style=\"color:#9fa2a8\">grey</span>")
    assertEquals("a grey", text.text)
    assertEquals(listOf(Triple(SpanStyle(color = Color(0xFF9FA2A8)), 2, 6)), text.styles())
  }

  @Test
  fun entities() {
    assertEquals("<>&", htmlText("&lt;&gt;&amp;").text)
  }

  @Test
  fun plainTextIsUnchanged() {
    assertEquals(AnnotatedString("a <b>  b</b>"), swingText("a <b>  b</b>"))
  }

  @Test
  fun uncoloredDropsColorOnly() {
    val html = "<HTML><b><font color=\"red\">x</font></b><span style=\"color:#9fa2a8\">y</span>"
    assertEquals(2, swingText(html).spanStyles.count { it.item.color != Color.Unspecified })
    val plain = swingText(html, colored = false)
    assertEquals("xy", plain.text)
    assertTrue(plain.spanStyles.none { it.item.color != Color.Unspecified })
    assertEquals(listOf(Triple(SpanStyle(fontWeight = FontWeight.Bold), 0, 1)), plain.styles())
  }
}
