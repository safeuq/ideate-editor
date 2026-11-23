package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jetbrains.codeWithMe.model.RdTerminalSessionOrigin
import com.jetbrains.codeWithMe.model.RdTerminalSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A new session carries its size before the backend sees it (spec 3.4), so the shell starts at it.
 */
class TerminalSessionsTest {
  @Test
  fun aNewClientSessionHasItsSizeAndOutputConnectedSet() {
    val session = TerminalSessions.newClientSession(TermSize(100, 30))
    assertEquals(RdTerminalSessionOrigin.Client, session.origin)
    assertEquals(RdTerminalSize(100, 30), session.size.valueOrNull)
    assertEquals(true, session.outputConnected.valueOrNull)
  }

  /** Ids from different app runs never meet, even after `exit` closed a restored session. */
  @Test
  fun idsAreUniquePerRun() {
    assertEquals("SessionId#mhxk2v1c-1", TerminalSessions.sessionId("mhxk2v1c", 1))
    assertNotEquals(
      TerminalSessions.sessionId("mhxk2v1c", 1),
      TerminalSessions.sessionId("mhxk3a9q", 1),
    )
  }
}
