package io.github.safeuq.ideate.client.desktop

import com.jetbrains.codeWithMe.model.TerminalModel
import com.jetbrains.rd.util.reactive.IScheduler
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.session.ClientSession
import io.github.safeuq.ideate.client.session.terminal.TerminalTabState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The Terminal against a live backend (docs/terminal-and-consoles-plan.md 6.2). Skipped unless
 * `DRIVER_LINK=<join link>` is set; one join link works once, so run one test per backend start
 * (`--tests '*TerminalLiveTest.<name>'`).
 */
class TerminalLiveTest {
  private lateinit var current: ClientSession

  private fun withSession(block: (ClientSession) -> Any?) {
    val link = System.getenv("DRIVER_LINK")
    assumeTrue(link != null, "DRIVER_LINK is not set")
    val session = ClientSession(JoinLink.parse(requireNotNull(link)), initialFile = null)
    current = session
    session.start()
    try {
      block(session)
    } finally {
      session.stop()
    }
  }

  private fun <T : Any> waitFor(description: String, seconds: Long = 40, find: () -> T?): T {
    val deadline = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < deadline) {
      find()?.let {
        return it
      }
      Thread.sleep(50)
    }
    throw AssertionError(
      "Timed out waiting for $description; the screen was:\n" +
        tabs().joinToString("\n----\n") { tab ->
          "${tab.title}:\n" + tab.lines().joinToString("\n")
        },
    )
  }

  private fun tabs() = current.state.value.terminals

  private fun TerminalTabState.lines() = screen.lines.map { it.text }

  /**
   * The shell is ready when its screen has text and stops changing for 2 s (a login shell prints a
   * banner and a prompt over a second or two, and drops what is typed before).
   */
  private fun settle(id: String) {
    var last = emptyList<String>()
    var since = System.currentTimeMillis()
    waitFor("the shell to settle", seconds = 60) {
      val lines = tabs().first { it.id == id }.lines()
      if (lines != last || lines.none { it.isNotBlank() }) {
        last = lines
        since = System.currentTimeMillis()
      }
      Unit.takeIf { System.currentTimeMillis() - since > 2_000 }
    }
  }

  private fun ClientSession.openFirstTerminal(): String {
    // The project must be bound before a tab can open; its tool windows arrive with it.
    waitFor("the project") { state.value.toolWindows.takeIf { it.isNotEmpty() } }
    openTerminal()
    val tab = waitFor("a terminal tab") { tabs().firstOrNull() }
    settle(tab.id)
    return tab.id
  }

  /** Both checks in one test: a join link works once, so one client run serves both. */
  @Test
  fun terminalAgainstALiveBackend() = withSession { session ->
    typingResizeTitleAndFullScreenProgram(session)
    whatClosedDoes(session)
  }

  private fun typingResizeTitleAndFullScreenProgram(session: ClientSession) {
    val id = session.openFirstTerminal()

    session.terminalText(id, "echo hi\r")
    waitFor("the line hi") { tabs().first { it.id == id }.lines().firstOrNull { it == "hi" } }

    // The shell keeps working after a resize, and the PTY knows the new size.
    session.resizeTerminal(id, 60, 20)
    waitFor("the 60 × 20 screen") {
      tabs().first { it.id == id }.screen.takeIf { it.columns == 60 && it.rows == 20 }
    }
    session.terminalText(id, "stty size\r")
    waitFor("the line 20 60") { tabs().first { it.id == id }.lines().firstOrNull { it == "20 60" } }
    session.terminalText(id, "echo still-works\r")
    waitFor("the line still-works") {
      tabs().first { it.id == id }.lines().firstOrNull { it == "still-works" }
    }

    // The sleep keeps the shell's prompt (which sets the title to the folder) from replacing it at
    // once.
    session.terminalText(id, "printf '\\033]0;probe-title\\007'; sleep 3\r")
    waitFor("the title probe-title") { tabs().firstOrNull { it.title == "probe-title" } }

    session.terminalText(id, "vi\r")
    waitFor("the alternate buffer") { tabs().firstOrNull { it.screen.alternate } }
    session.terminalText(id, ":q\r")
    waitFor("the alternate buffer to end") { tabs().firstOrNull { !it.screen.alternate } }
  }

  /**
   * Spec 3.4 / 12 row 4: does `closed = true` end the shell, or only removing the map entry? Reads
   * the shell's own pid (`echo $$`), then sets `closed` alone, then removes the entry, and checks
   * after each whether that process is alive (the backend runs on this machine).
   */
  private fun whatClosedDoes(session: ClientSession) {
    session.openTerminal()
    val id = waitFor("a second terminal tab") { tabs().firstOrNull { it.id != "SessionId#1" }?.id }
    settle(id)
    session.terminalText(id, "echo pid=\$\$\r")
    val pid =
      waitFor("the shell's pid") {
          tabs()
            .first { it.id == id }
            .lines()
            .firstNotNullOfOrNull { Regex("^pid=(\\d+)$").find(it)?.groupValues?.get(1) }
        }
        .toLong()
    val shell = ProcessHandle.of(pid).orElseThrow { AssertionError("No process $pid") }
    assertTrue(shell.isAlive, "shell $pid should be alive before closing")

    // Reaches into the session for the raw model: `closed` alone, which the session's own close()
    // never does without removing the entry too.
    val sessions = field(session, "terminalSessions")
    val scheduler = field(sessions, "scheduler") as IScheduler
    val model = field(sessions, "model") as TerminalModel
    scheduler.queue { model.sessions.getValue(id).closed.set(true) }
    Thread.sleep(5_000)
    println("RESULT: after closed = true alone, shell alive = ${shell.isAlive}")
    println("RESULT: tab still listed by the client = ${tabs().any { it.id == id }}")
    val aliveAfterClosed = shell.isAlive

    scheduler.queue { model.sessions.remove(id) }
    Thread.sleep(5_000)
    println("RESULT: after removing the entry too, shell alive = ${shell.isAlive}")
    assertEquals(false, shell.isAlive, "the shell should end once its entry is removed")
    println("RESULT: closed alone ended the shell = ${!aliveAfterClosed}")
  }

  private fun field(target: Any, name: String): Any {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return requireNotNull(field.get(target)) { "$name is null" }
  }
}
