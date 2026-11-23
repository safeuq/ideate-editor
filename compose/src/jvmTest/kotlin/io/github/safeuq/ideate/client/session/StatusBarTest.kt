package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The status bar's widget order and its background tasks (docs/status-bar.md). */
class StatusBarTest {
  @Test
  fun widgetsFollowTheirAnchorsAsIntelliJsStatusBar() {
    // As the Mac backend sent them, in this arrival order.
    val widgets =
      listOf(
        "GridAggregator" to "before Position",
        "Position" to "ANY",
        "LineSeparator" to "after Position",
        "Encoding" to "after LineSeparator",
        "InsertOverwrite" to "after PowerSaveMode",
        "CodeStyleStatusBarWidget" to "after InsertOverwrite",
        "ReadOnlyAttribute" to "after InsertOverwrite",
        "FatalError" to "after Notifications",
      )
    assertEquals(
      listOf(
        "GridAggregator",
        "Position",
        "LineSeparator",
        "Encoding",
        "InsertOverwrite",
        "CodeStyleStatusBarWidget",
        "ReadOnlyAttribute",
        "FatalError",
      ),
      orderByAnchors(widgets),
    )
    // A widget that names a later one comes before it, whatever the arrival order.
    assertEquals(listOf("A", "B"), orderByAnchors(listOf("B" to "after A", "A" to null)))
    assertEquals(listOf("B", "A"), orderByAnchors(listOf("A" to null, "B" to "before A")))
  }

  @Test
  fun taskEventsFromTheBackendBuildTheTaskList() {
    val tasks = linkedMapOf<Long, BackgroundTask>()
    fun event(json: String) = Json.parseToJsonElement(json).jsonObject
    // Recorded from the Mac backend's TaskInfoApi.activeTasks() stream.
    assertTrue(
      applyTaskEvent(
        tasks,
        event(
          """{"type":"com.intellij.platform.ide.progress.rpc.TaskInfoEvent.TaskAdded","taskId":1,"projectId":{"id":"jiebkhombgkqbk38rv03"},"title":"Scanning files…","cancellation":{"type":"com.intellij.platform.ide.progress.NonCancellableTaskCancellation"},"suspension":{"type":"com.intellij.platform.ide.progress.suspender.TaskSuspension.NonSuspendable"},"status":{"type":"com.intellij.platform.ide.progress.TaskStatus.Running","source":"SYSTEM"},"visibleInStatusBar":false,"ownerKind":"PROJECT"}""",
        ),
      ),
    )
    applyTaskEvent(
      tasks,
      event(
        """{"type":"com.intellij.platform.ide.progress.rpc.TaskInfoEvent.ProgressChanged","taskId":1,"state":{"type":"com.intellij.platform.util.progress.StepState","fraction":0.9941860465116279,"text":"Scanning files…","details":"Scanning dependencies"}}""",
      ),
    )
    assertEquals(
      BackgroundTask(
        id = 1,
        title = "Scanning files…",
        text = "Scanning files…",
        details = "Scanning dependencies",
        fraction = 0.9941860465116279,
        inStatusBar = false,
      ),
      tasks.getValue(1),
    )
    // A pausable, cancellable task, paused by the user.
    applyTaskEvent(
      tasks,
      event(
        """{"type":"com.intellij.platform.ide.progress.rpc.TaskInfoEvent.TaskAdded","taskId":2,"title":"Analyzing project","cancellation":{"type":"com.intellij.platform.ide.progress.CancellableTaskCancellation"},"suspension":{"type":"com.intellij.platform.ide.progress.suspender.TaskSuspension.Suspendable","suspendText":"Pause"},"status":{"type":"com.intellij.platform.ide.progress.TaskStatus.Paused","source":"USER"},"visibleInStatusBar":true}""",
      ),
    )
    val second = tasks.getValue(2)
    assertTrue(second.cancellable && second.suspendable && second.paused)
    assertTrue(
      applyTaskEvent(
        tasks,
        event(
          """{"type":"com.intellij.platform.ide.progress.rpc.TaskInfoEvent.TaskRemoved","taskId":1}""",
        ),
      ),
    )
    assertEquals(listOf(2L), tasks.keys.toList())
    // An update for a task that isn't there changes nothing.
    assertFalse(
      applyTaskEvent(
        tasks,
        event(
          """{"type":"com.intellij.platform.ide.progress.rpc.TaskInfoEvent.StatusChanged","taskId":9,"status":{"type":"com.intellij.platform.ide.progress.TaskStatus.Running","source":"SYSTEM"}}""",
        ),
      ),
    )
  }
}
