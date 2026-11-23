package io.github.safeuq.ideate.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The project avatar's letters, as IntelliJ's `AvatarUtils.initials` gives them. */
class InitialsTest {
  @Test
  fun followsIntellij() {
    val expected =
      mapOf(
        "xtool" to "X", // one lower-case word: one letter
        "smoke" to "S",
        "MyProject" to "MP", // two capitals from camel case
        "intellij-community" to "IC",
        "xtool-web-app" to "XA", // first and last word
        "my project, second" to "MS", // space splits before comma
        "1st_try" to "1T", // camel case gives "T" only, so the words win
      )
    assertEquals(expected, expected.mapValues { (name, _) -> initials(name) })
  }
}
