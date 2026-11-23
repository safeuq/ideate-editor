package io.github.safeuq.ideate.client.connect

/**
 * Counts only the time the app is in the foreground, for the reconnect's 2 minutes. Example: 50 s
 * in the foreground, then 10 minutes in the background (or frozen by the phone), then 70 s in the
 * foreground again: [elapsed] is 120 s, so the 2 minutes are up, but never while it was away.
 */
internal class ForegroundClock(private val now: () -> Long = System::currentTimeMillis) {
  private var counted = 0L
  private var since: Long? = null

  fun inForeground(inForeground: Boolean) {
    val time = now()
    if (inForeground) {
      if (since == null) since = time
    } else {
      since?.let { counted += time - it }
      since = null
    }
  }

  fun elapsed(): Long = counted + (since?.let { now() - it } ?: 0L)
}
