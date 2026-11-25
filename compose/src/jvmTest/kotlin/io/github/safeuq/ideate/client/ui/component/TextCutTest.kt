package io.github.safeuq.ideate.client.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCutTest {
  @Test
  fun keepsTheTailOfALongPath() {
    // maxLength 24: suffix (24 * 0.7) = 16, prefix 24 - 16 - 3 = 5.
    assertEquals(
      "/User...ects/XFlow/xtool",
      shortenPathWithEllipsis("/Users/safeuq/Files/Projects/XFlow/xtool", 24),
    )
  }

  @Test
  fun shortTextUnchanged() {
    assertEquals("~/xtool", shortenPathWithEllipsis("~/xtool", 24))
  }

  @Test
  fun ellipsisSymbol() {
    // IntelliJ's own example shape: "abcdefghij" at 7 with suffix 3 → "abc…hij".
    assertEquals("abc…hij", shortenTextWithEllipsis("abcdefghij", 7, 3, useEllipsisSymbol = true))
    assertEquals("ab...ij", shortenTextWithEllipsis("abcdefghij", 7, 2))
  }
}
