package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CompletionTest {
  @Test
  fun camelHumpsMatchLikeTheRecordedPopup() {
    // Recorded for the prefix "gre": greeting, getRegistry and getReceiver were all offered.
    assertEquals(setOf(0, 1, 2), matchedIndices("gre", "greeting"))
    assertEquals(setOf(0, 3, 4), matchedIndices("gre", "getRegistry"))
    assertEquals(setOf(0, 3, 4), matchedIndices("gre", "getReceiver"))
    assertNull(matchedIndices("gx", "greeting"))
    assertNull(matchedIndices("re", "greeting"))
  }

  @Test
  fun iconIdBlobHoldsTheIconPath() {
    // `presentation.icon.serializedValue` of the recorded item "greeting".
    val blob =
      "Pj1d3rHk9q2MAAAAPQAAAAAAAAABKV1/a6Mo6e13AAAAAQAAAAguMPjbbDkRZwAAABUBAAAAAAAAGAAAAGUAeABwAHUAaQAvAGYAaQBsAGUAVAB5AHAAZQBzAC8AagBhAHYAYQAuAHMAdgBnAAEMAAAAYwBvAG0ALgBpAG4AdABlAGwAbABpAGoAABAAAAAQAAAAAAAAAAAAAAAAAAAAAA=="
    assertEquals(
      "expui/fileTypes/java.svg",
      iconPath(buildJsonObject { put("serializedValue", blob) }),
    )
  }

  @Test
  fun colorizedIconBlobKeepsItsTint() {
    // `problemDto.icon.serializedValue` of a build error in Project Errors: IntelliJ's
    // `HighlightDisplayLevel.ERROR` icon, a white shape tinted 214, 77, 91.
    val blob =
      "cCnOMArcPva7AAAAvAOFVCcLAgAQAAAA1gAAAE0AAABbAAAA/wAAAAguMPjbbDkRkwAAAOYBAAAAAAAALgAAAGUAeABwAHUAaQAvAGcAZQBuAGUAcgBhAGwALwBpAG4AcwBwAGUAYwB0AGkAbwBuAHMALwBpAG4AcwBwAGUAYwB0AGkAbwBuAHMARQByAHIAbwByAC4AcwB2AGcAAQwAAABjAG8AbQAuAGkAbgB0AGUAbABsAGkAagAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    assertEquals(
      "colorize/D64D5B/expui/general/inspections/inspectionsError.svg",
      iconPath(buildJsonObject { put("serializedValue", blob) }),
    )
  }

  @Test
  fun backspaceFollowsIntellijsLookup() {
    // Popup opened after "p", then "svm" typed into it.
    assertEquals(
      BackspaceInLookup.TRUNCATE,
      backspaceInLookup(prefixLength = 4, typedSinceOpen = 3),
    )
    assertEquals(
      BackspaceInLookup.TRUNCATE,
      backspaceInLookup(prefixLength = 2, typedSinceOpen = 1),
    )
    assertEquals(BackspaceInLookup.HIDE, backspaceInLookup(prefixLength = 1, typedSinceOpen = 0))
    // Ctrl+Space after "psvm".
    assertEquals(BackspaceInLookup.RESTART, backspaceInLookup(prefixLength = 4, typedSinceOpen = 0))
    // Popup opened on an empty prefix (after a '.').
    assertEquals(BackspaceInLookup.HIDE, backspaceInLookup(prefixLength = 0, typedSinceOpen = 0))
  }
}
