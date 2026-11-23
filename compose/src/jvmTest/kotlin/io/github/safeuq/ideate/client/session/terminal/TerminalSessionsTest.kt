package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jetbrains.codeWithMe.model.RdTerminalSessionOrigin
import com.jetbrains.codeWithMe.model.RdTerminalSize
import kotlin.test.Test
import kotlin.test.assertEquals

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

  /** After a restart, sessions from before keep their ids: a new tab must not reuse one. */
  @Test
  fun aNewTabSkipsIdsStillInTheMap() {
    val restored = setOf("SessionId#1", "SessionId#3")
    assertEquals("SessionId#2", TerminalSessions.freeSessionId(1) { it in restored })
    assertEquals("SessionId#4", TerminalSessions.freeSessionId(3) { it in restored })
    assertEquals("SessionId#1", TerminalSessions.freeSessionId(1) { false })
  }
}
