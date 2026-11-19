package intellijandroid.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ShortenContainerTextTest {
  /** 10 px per character. */
  private fun width(text: String) = text.length * 10

  @Test
  fun fitsAsItIs() {
    assertEquals("of java.lang.reflect", shortenContainerText("of java.lang.reflect", 300, ::width))
  }

  @Test
  fun noRoomLeftKeepsItWhole() {
    // IntelliJ returns the text unchanged when the room is negative; the row cuts it.
    assertEquals("of a.b.c", shortenContainerText("of a.b.c", -5, ::width))
  }

  @Test
  fun middlePackagesGiveWay() {
    // 33 characters, room for 23: jupiter, then junit go; ".." takes their place.
    assertEquals(
      "of org....api.function", // as IntelliJ shows it
      shortenContainerText("of org.junit.jupiter.api.function", 240, ::width),
    )
  }
}
