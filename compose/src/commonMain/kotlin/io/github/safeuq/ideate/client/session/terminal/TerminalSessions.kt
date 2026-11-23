package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jetbrains.codeWithMe.model.RdTerminalSession
import com.jetbrains.codeWithMe.model.RdTerminalSessionOrigin
import com.jetbrains.codeWithMe.model.RdTerminalSize
import com.jetbrains.codeWithMe.model.terminalModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.onTermination
import com.jetbrains.rd.util.reactive.IScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The terminal tabs of one project, over `solution.terminalModel.sessions` (spec 3.2), as JetBrains
 * Client's `ThinClientTerminalHost` does: a tab opens by putting an `RdTerminalSession(Client)`
 * into the map under `SessionId#<n>`, and the backend starts a shell for it. Sessions the backend
 * puts there itself (`origin = Host`, e.g. Open in Terminal) get a tab too. Both come through the
 * same `view` callback, which makes the [TerminalTab]. [change] edits the tab list the UI reads.
 * Call [bind] on the rd thread; [open] and [close] too.
 */
internal class TerminalSessions(
  private val scheduler: IScheduler,
  private val change: ((List<TerminalTabState>) -> List<TerminalTabState>) -> Unit,
  private val log: (String) -> Unit,
  measured: TermSize?,
) {
  private var model: com.jetbrains.codeWithMe.model.TerminalModel? = null
  private val tabs = ConcurrentHashMap<String, TerminalTab>()
  private val sessions = ConcurrentHashMap<String, RdTerminalSession>()
  private var counter = 0

  /** Makes this run's ids differ from every earlier run's: see [sessionId]. */
  private val run = java.lang.Long.toString(System.currentTimeMillis(), 36)

  // Draws the screens of all tabs, one thread for all of them.
  private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "terminal-publish").apply { isDaemon = true }
  }

  /**
   * The last grid the view measured, kept by the [ClientSession] while no project was bound yet:
   * the view sizes itself before the terminals bind after a reconnect, and that size must not be
   * lost.
   */
  @Volatile private var lastSize: TermSize? = measured

  fun bind(solutionLifetime: Lifetime, solution: Solution) {
    val terminalModel = solution.terminalModel
    model = terminalModel
    solutionLifetime.onTermination { ticker.shutdown() }
    terminalModel.sessions.view(solutionLifetime) { sessionLifetime, id, session ->
      log("Terminal $id added (${session.origin})")
      val tab =
        TerminalTab(
          id,
          session,
          sessionLifetime,
          scheduler,
          ticker,
          publish = { state -> change { tabs -> replace(tabs, state) } },
          // A session from before a reconnect has the size its screen was laid out at: start there,
          // so the restored screen doesn't reflow.
          initialSize =
            session.size.valueOrNull?.let { TermSize(it.width, it.height) }
              ?: lastSize
              ?: TermSize(80, 24),
        )
      tabs[id] = tab
      // Then to the view's grid, which reflows that screen as a resize does (null before the view
      // measured anything).
      lastSize?.let { size -> tab.resize(size.columns, size.rows) }
      sessions[id] = session
      // The backend ends a session by closing it (the shell exited): the tab goes too.
      session.closed.advise(sessionLifetime) { closed ->
        if (closed) terminalModel.sessions.remove(id)
      }
      change { current -> replace(current, tab.snapshot()) }
      sessionLifetime.onTermination {
        tabs.remove(id)
        sessions.remove(id)
        change { current -> current.filterNot { it.id == id } }
        log("Terminal $id removed")
      }
    }
  }

  /** Opens a tab and returns its id. */
  fun open(): String {
    val terminalModel = checkNotNull(model) { "Terminal sessions aren't bound to a project yet" }
    val id = sessionId(run, ++counter)
    terminalModel.sessions[id] = newClientSession(lastSize ?: TermSize(80, 24))
    return id
  }

  /**
   * Closes the tab: tells the backend (`closed = true`), then removes the entry as JetBrains Client
   * does when its widget goes away. docs: what the backend's shell does is spec 3.4.
   */
  fun close(id: String) {
    val terminalModel = checkNotNull(model) { "Terminal sessions aren't bound to a project yet" }
    val session = checkNotNull(sessions[id]) { "No terminal session $id" }
    session.closed.set(true)
    terminalModel.sessions.remove(id)
  }

  fun tab(id: String): TerminalTab? = tabs[id]

  companion object {
    /**
     * `SessionId#<run>-<n>`, e.g. `SessionId#mhxk2v1c-1`: [run] is the app run's start time, so an
     * id is never used twice for this backend. The backend starts no shell for an id it has had
     * before, and the new tab stays blank as "Local" (seen on the S24 2026-11-23): first when a new
     * tab took the id of a session restored from before a restart (`SessionId#1`, counter back at
     * 1), then when a tab took the id of a restored session that `exit` had closed. JetBrains
     * Client does the same with `<its client id><n>` (`ThinClientTerminalHost.getOrCreate`).
     */
    internal fun sessionId(run: String, number: Int) = "SessionId#$run-$number"

    /**
     * A session the client starts (spec 3.4): the size and `outputConnected` are set before the
     * session goes into the map, so the backend starts its shell at that size. `shellCommand` null:
     * the backend's login shell, in the project folder.
     */
    internal fun newClientSession(size: TermSize) =
      RdTerminalSession(RdTerminalSessionOrigin.Client, null).also { session ->
        session.size.set(RdTerminalSize(size.columns, size.rows))
        session.outputConnected.set(true)
      }
  }

  fun resize(id: String, columns: Int, rows: Int) {
    val tab = tabs[id] ?: return
    lastSize = TermSize(columns, rows)
    tab.resize(columns, rows)
  }

  private fun replace(
    tabs: List<TerminalTabState>,
    state: TerminalTabState,
  ): List<TerminalTabState> =
    if (tabs.any { it.id == state.id }) tabs.map { if (it.id == state.id) state else it }
    // A late snapshot of a tab that is already gone must not bring it back.
    else if (this.tabs.containsKey(state.id)) tabs + state else tabs
}
