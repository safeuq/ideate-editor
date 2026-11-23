package io.github.safeuq.ideate.client.connect

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IntelliJ's formatPrettyDateTime, as Gateway shows it ("Last active: 52 minutes ago", "Yesterday
 * 1:02 pm").
 */
class LastActiveTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private val locale = Locale.forLanguageTag("en-IN")
  private val now = LocalDateTime.of(2026, 11, 17, 13, 0).atZone(zone).toInstant().toEpochMilli()

  private fun at(day: Int, hour: Int, minute: Int) =
    LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  private fun text(then: Long) = lastActive(then, now, zone, locale)

  @Test
  fun recentTimesInMinutes() {
    assertEquals("Moments ago", text(now - 10_000))
    assertEquals("A minute ago", text(now - 60_000))
    assertEquals("52 minutes ago", text(now - 52 * 60_000))
    assertEquals("1 hour ago", text(now - 61 * 60_000))
  }

  @Test
  fun todayYesterdayThenTheDate() {
    // The JDK's locale data puts a narrow no-break space (U+202F) before am/pm; IntelliJ formats
    // with the same JDK, so Gateway shows the same text.
    assertEquals("Today 9:15\u202Fam", text(at(17, 9, 15)))
    assertEquals("Yesterday 1:02\u202Fpm", text(at(16, 13, 2)))
    assertEquals("15/11/26, 10:21\u202Fam", text(at(15, 10, 21)))
  }
}
