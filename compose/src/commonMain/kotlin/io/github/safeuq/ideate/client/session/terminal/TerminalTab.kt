package io.github.safeuq.ideate.client.session.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jetbrains.codeWithMe.model.BeTerminalColor
import com.jetbrains.codeWithMe.model.BeTerminalEntryStyle
import com.jetbrains.codeWithMe.model.BeTerminalLine
import com.jetbrains.codeWithMe.model.BeTerminalScreenState
import com.jetbrains.codeWithMe.model.RdTerminalSession
import com.jetbrains.codeWithMe.model.RdTerminalSize
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.onTermination
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.reactive.adviseNotNull
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

/** What the UI gets of one terminal tab: [title] is the tab's text (see [terminalTabTitle]). */
@Serializable
data class TerminalTabState(val id: String, val title: String, val screen: TerminalScreen)

/**
 * One terminal: the backend's shell over [session], emulated by JediTerm here (spec 3.3, 4). A
 * thread of its own runs the read-and-emulate loop; every change to the screen publishes a new
 * [TerminalTabState] through [publish], at most every [PUBLISH_DELAY_MS] ms, from the [ticker]
 * thread. Keys, text and pastes can be sent from any thread.
 */
internal class TerminalTab(
  val id: String,
  session: RdTerminalSession,
  sessionLifetime: Lifetime,
  scheduler: IScheduler,
  private val ticker: ScheduledExecutorService,
  private val publish: (TerminalTabState) -> Unit,
  initialSize: TermSize,
) {
  @Volatile private var title = terminalTabTitle(null, null, null)
  @Volatile private var bracketedPaste = false
  @Volatile private var cursorVisible = true
  @Volatile private var cursorShape = CursorShape.BLINK_BLOCK
  private val publishQueued = AtomicBoolean()

  private val styleState = StyleState()
  private val buffer =
    TerminalTextBuffer(
      initialSize.columns,
      initialSize.rows,
      styleState,
      TERMINAL_HISTORY_LINES,
    )
  private val reader = TerminalScreenReader(buffer)

  private val display =
    object : TerminalDisplay {
      override fun setCursor(x: Int, y: Int) = queuePublish()

      override fun setCursorShape(shape: CursorShape?) {
        cursorShape = shape ?: CursorShape.BLINK_BLOCK
        queuePublish()
      }

      override fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
        queuePublish()
      }

      override fun scrollArea(top: Int, height: Int, dy: Int) = queuePublish()

      override fun useAlternateScreenBuffer(enabled: Boolean) = queuePublish()

      override fun setBracketedPasteMode(enabled: Boolean) {
        bracketedPaste = enabled
      }

      // The tab's title is the backend's `title` property, which reads the same escape codes
      // itself.
      override fun getWindowTitle(): String = title

      override fun setWindowTitle(windowTitle: String) = Unit

      override fun beep() = Unit

      override fun getSelection(): TerminalSelection? = null

      // ponytail: mouse reports to programs (vim's mouse, htop) aren't sent yet; the view selects
      // text with the mouse always.
      override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit

      override fun setMouseFormat(mouseFormat: MouseFormat) = Unit

      override fun ambiguousCharsAreDoubleWidth() = false
    }

  private val terminal = JediTerminal(display, buffer, styleState)
  private val connector =
    RdTtyConnector(
      sendInput = { bytes -> session.input.fire(bytes.toList()) },
      // The size property belongs to the rd thread; the emulator calls from its own.
      sendSize = { size ->
        scheduler.queue { session.size.set(RdTerminalSize(size.columns, size.rows)) }
      },
    )
  private val executors = TerminalExecutors()
  // No type-ahead manager (JetBrains Client passes none either): the predicted-typing feature
  // needs the shell's prompt text. JediTerm's parameter is annotated non-null, hence the
  // suppression; every send below says `userInput = false`, the one place the manager is used.
  @Suppress("NULL_FOR_NONNULL_TYPE")
  private val starter =
    TerminalStarter(
      terminal,
      connector,
      TtyBasedArrayDataStream(connector),
      null,
      executors,
    )

  init {
    // A session the client opened has both set already, before it went into the map (spec 3.4,
    // TerminalSessions.newClientSession). Only a session the backend opened (`origin = Host`) gets
    // them here.
    if (session.size.valueOrNull == null)
      session.size.set(RdTerminalSize(initialSize.columns, initialSize.rows))
    if (session.outputConnected.valueOrNull != true) session.outputConnected.set(true)
    // A session that was open before this connection (a reconnect, or the app started again) comes
    // with the screen so far; the client takes it and clears it, as JetBrains Client's
    // `setInitialState` does. Advised before the output, which continues from that screen.
    session.initialState.adviseNotNull(sessionLifetime) { state ->
      restore(state)
      session.initialState.set(null)
    }
    session.output.adviseOn(sessionLifetime, scheduler) { bytes ->
      connector.onOutput(bytes.toByteArray())
    }
    session.title.advise(sessionLifetime) { backendTitle ->
      title =
        terminalTabTitle(
          backendTitle?.userDefinedTitle,
          backendTitle?.applicationTitle,
          backendTitle?.defaultTitle,
        )
      queuePublish()
    }
    buffer.addModelListener { queuePublish() }
    Thread(starter::start, "terminal-$id").apply { isDaemon = true }.start()
    sessionLifetime.onTermination {
      starter.requestEmulatorStop()
      connector.close()
      executors.shutdownWhenAllExecuted()
    }
  }

  /**
   * The backend sends whole lines, not cut at any width (a 168-character prompt is one entry list),
   * so each wraps at this grid's width here; the cursor moves down by the rows the lines above it
   * gained, and rows beyond the screen's height go to the history.
   */
  private fun restore(state: BeTerminalScreenState) {
    val columns = terminal.size.columns
    val history = state.historyLines.orEmpty().flatMap { line -> terminalLines(line, columns) }
    var cursorY = state.cursorY
    val screen =
      state.screenLines.flatMapIndexed { index, line ->
        terminalLines(line, columns).also { rows ->
          if (index < state.cursorY - 1) cursorY += rows.size - 1
        }
      }
    val overflow = (screen.size - terminal.size.rows).coerceAtLeast(0)
    buffer.lock()
    try {
      buffer.clearScreenAndHistoryBuffers()
      buffer.historyBuffer.addLines(history + screen.take(overflow))
      buffer.screenBuffer.addLines(screen.drop(overflow))
    } finally {
      buffer.unlock()
    }
    terminal.cursorPosition(state.cursorX, cursorY - overflow)
    queuePublish()
  }

  fun resize(columns: Int, rows: Int) {
    if (columns == terminal.size.columns && rows == terminal.size.rows) return
    starter.postResize(TermSize(columns, rows), RequestOrigin.User)
  }

  /**
   * A key as AWT numbers it (`KeyEvent.VK_UP` = 38) with AWT's modifier bits; see [TerminalKeys].
   */
  fun sendKey(keyCode: Int, modifiers: Int) {
    starter.getCode(keyCode, modifiers)?.let { starter.sendBytes(it, false) }
  }

  fun sendText(text: String) = starter.sendString(text, false)

  /** Line breaks go as Enter (`\r`); wrapped in `\e[200~` … `\e[201~` when the program asked. */
  fun paste(text: String) {
    val lines = text.replace("\r\n", "\n").replace('\n', '\r')
    sendText(if (bracketedPaste) "\u001b[200~$lines\u001b[201~" else lines)
  }

  /**
   * IntelliJ's Clear Buffer. ponytail: drops the history only; IntelliJ also clears the screen
   * above the prompt.
   */
  fun clearBuffer() {
    buffer.lock()
    try {
      buffer.clearHistory()
    } finally {
      buffer.unlock()
    }
    queuePublish()
  }

  fun snapshot(): TerminalTabState {
    val alternate = buffer.isUsingAlternateBuffer
    val cursor =
      TerminalCursor(
        column = terminal.cursorX - 1,
        row = terminal.cursorY - 1,
        visible = cursorVisible,
        shape = cursorShape.toShape(),
        blinking = cursorShape.isBlinking,
      )
    return TerminalTabState(id, title, reader.read(cursor, alternate))
  }

  private fun queuePublish() {
    if (!publishQueued.compareAndSet(false, true)) return
    try {
      ticker.schedule(
        {
          publishQueued.set(false)
          publish(snapshot())
        },
        PUBLISH_DELAY_MS,
        TimeUnit.MILLISECONDS,
      )
    } catch (stopped: RejectedExecutionException) {
      // The project closed and the ticker stopped; the emulator's last callbacks land here.
    }
  }

  private companion object {
    /** About 30 screens a second while a program floods the output. */
    const val PUBLISH_DELAY_MS = 33L
  }
}

/** JediTerm's threads for one terminal; they stop with the tab. */
private class TerminalExecutors : TerminalExecutorServiceManager {
  private val single = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "terminal-emulator").apply { isDaemon = true }
  }
  private val unbounded = Executors.newCachedThreadPool { task ->
    Thread(task, "terminal-io").apply { isDaemon = true }
  }

  override fun getSingleThreadScheduledExecutor() = single

  override fun getUnboundedExecutorService() = unbounded

  override fun shutdownWhenAllExecuted() {
    single.shutdown()
    unbounded.shutdown()
  }
}

/** One line of a backend screen state (spec 3.3), as JetBrains Client's `fromModel` builds it. */
/**
 * One backend line as rows of [columns] cells, all but the last marked wrapped as JediTerm marks a
 * line the shell's output ran past the edge of.
 */
// ponytail: counts characters, not cells; a wide (CJK) character takes two cells and would run one
// past the edge. Count cells with JediTerm's CharUtils if that shows up.
internal fun terminalLines(line: BeTerminalLine, columns: Int): List<TerminalLine> {
  val rows = mutableListOf(TerminalLine())
  var column = 0
  line.entries.forEach { entry ->
    val style = textStyle(entry.style)
    var start = 0
    while (start < entry.text.length) {
      if (column == columns) {
        rows.last().isWrapped = true
        rows += TerminalLine()
        column = 0
      }
      val end = minOf(entry.text.length, start + columns - column)
      rows.last().insertString(column, CharBuffer(entry.text.substring(start, end)), style)
      column += end - start
      start = end
    }
  }
  return rows
}

/** Bit `n` of `optionsMask` is JediTerm's `TextStyle.Option` number `n` (bold, italic, …). */
private fun textStyle(style: BeTerminalEntryStyle): TextStyle {
  val options = EnumSet.noneOf(TextStyle.Option::class.java)
  TextStyle.Option.entries
    .filter { option -> style.optionsMask and (1 shl option.ordinal) != 0 }
    .forEach(options::add)
  return TextStyle(
    style.foreground?.let(::terminalColor),
    style.background?.let(::terminalColor),
    options,
  )
}

/** A palette index (0 to 255), or an RGB color when the index is negative. */
private fun terminalColor(color: BeTerminalColor): TerminalColor =
  if (color.index >= 0) TerminalColor(color.index) else TerminalColor(color.r, color.g, color.b)
