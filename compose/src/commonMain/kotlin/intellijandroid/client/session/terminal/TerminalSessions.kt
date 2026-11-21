package intellijandroid.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jetbrains.codeWithMe.model.RdTerminalSession
import com.jetbrains.codeWithMe.model.RdTerminalSessionOrigin
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
) {
  private var model: com.jetbrains.codeWithMe.model.TerminalModel? = null
  private val tabs = ConcurrentHashMap<String, TerminalTab>()
  private val sessions = ConcurrentHashMap<String, RdTerminalSession>()
  private var counter = 0

  // Draws the screens of all tabs, one thread for all of them.
  private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "terminal-publish").apply { isDaemon = true }
  }

  /** The last grid the view measured, for a new tab to start at (80 × 24 until then). */
  @Volatile private var lastSize = TermSize(80, 24)

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
          initialSize = lastSize,
        )
      tabs[id] = tab
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
    val id = "SessionId#${++counter}"
    // null: the backend's login shell, in the project folder (spec 3.4).
    terminalModel.sessions[id] = RdTerminalSession(RdTerminalSessionOrigin.Client, null)
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
