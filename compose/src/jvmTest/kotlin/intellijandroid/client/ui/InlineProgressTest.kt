package intellijandroid.client.ui

import intellijandroid.client.session.BackgroundTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The status bar's task and counter, as IntelliJ's InlineProgressPanel picks them. */
class InlineProgressTest {
  private val indexing = BackgroundTask(1, "Indexing")
  private val scanning = BackgroundTask(2, "Scanning files…", inStatusBar = false)
  private val sync = BackgroundTask(3, "Gradle sync", suspendable = true, paused = true)

  @Test
  fun oneTaskShowsNoCountButKeepsTheCountersRoom() {
    val progress = inlineProgress(listOf(indexing), popupShown = false)!!
    assertEquals(indexing, progress.indicator)
    assertEquals("", progress.counterText)
    assertEquals(0, progress.counterNumber)
  }

  @Test
  fun statusBarTasksComeFirstAndPausedOnesAreSkipped() {
    val progress = inlineProgress(listOf(scanning, sync, indexing), popupShown = false)!!
    assertEquals(indexing, progress.indicator)
    assertEquals("+2", progress.counterText)
  }

  @Test
  fun aTaskNotForTheStatusBarShowsOnlyTheCount() {
    assertEquals("", inlineProgress(listOf(scanning), popupShown = false)!!.counterText)
    val two = inlineProgress(listOf(scanning, scanning.copy(id = 4)), popupShown = false)!!
    assertEquals(false, two.indicatorVisible)
    assertEquals("2", two.counterText)
  }

  @Test
  fun theOpenPopupHidesTheIndicatorForTheHideLink() {
    val progress = inlineProgress(listOf(indexing, sync), popupShown = true)!!
    assertEquals(false, progress.indicatorVisible)
    assertEquals("", progress.counterText)
    assertEquals("Hide processes (2)", progress.hideLink)
  }

  @Test fun noTasksNoPanel() = assertNull(inlineProgress(emptyList(), popupShown = false))
}
