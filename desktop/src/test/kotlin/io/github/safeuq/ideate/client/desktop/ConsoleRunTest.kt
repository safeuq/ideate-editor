package io.github.safeuq.ideate.client.desktop

import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs `Main` of the `smoke` project (plan appendix B) and waits for the program's whole output in
 * the console's text; then runs `Slow` (prints `tick 1` to `tick 6` 0.7 s apart, then a stderr
 * line) and checks the text grows as the program prints, with the stderr line colored. Skipped
 * unless `DRIVER_LINK=<join link>` is set; the session log goes to
 * `DRIVER_OUT/console-run-log.txt`.
 */
class ConsoleRunTest {
  @Test
  fun programOutputArrives() {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val out = File(System.getenv("DRIVER_OUT") ?: "build/driver").apply { mkdirs() }
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    session.start()
    try {
      waitFor("the project") { session.state.value.projectName != null }
      val finished = "Process finished with exit code 0"
      fun consoleWith(marker: String) =
        session.state.value.consoles.values.firstOrNull { it.text.contains(marker) }

      session.runConfiguration("Application.Main")
      waitFor("the whole output of Main") { consoleWith(finished) != null }
      val main = requireNotNull(consoleWith("just a simple greeting")).text
      println("[console] $main")
      assertTrue(main.contains("just a simple greeting\n22\n"), main)
      assertTrue(main.trimEnd().endsWith(finished), main)

      // Slow: every tick must show up on its own, before the program is done.
      session.runConfiguration("Application.Slow")
      val seen = mutableSetOf<Int>()
      waitFor("the whole output of Slow") {
        consoleWith("tick 1")?.text?.let { text ->
          seen += Regex("tick \\d").findAll(text).count()
        }
        consoleWith("done with an error line")?.text?.contains(finished) == true
      }
      println("[console] tick counts seen while printing: ${seen.sorted()}")
      assertTrue(seen.any { it in 2..5 }, "the text only came all at once: $seen")
      val slow = requireNotNull(consoleWith("done with an error line"))
      val errorStart = slow.text.indexOf("done with an error line")
      val errorColor =
        slow.highlights
          .filter { it.start <= errorStart && it.end > errorStart }
          .mapNotNull { it.style.foreground }
      val normalColor =
        slow.highlights
          .filter {
            it.start <= slow.text.indexOf("tick 2") && it.end > slow.text.indexOf("tick 2")
          }
          .mapNotNull { it.style.foreground }
      println("[console] stderr foreground $errorColor, stdout $normalColor")
      assertTrue(errorColor.isNotEmpty(), "the stderr line has no color")
      assertTrue(errorColor != normalColor, "stderr is colored like stdout: $errorColor")
      assertEquals(
        emptyList(),
        session.state.value.log.filter { it.contains("Cannot edit") || it.contains("out of step") },
      )
    } finally {
      out.resolve("console-run-log.txt").writeText(session.state.value.log.joinToString("\n"))
      session.stop()
    }
  }

  private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 50_000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
      Thread.sleep(100)
    }
  }
}
