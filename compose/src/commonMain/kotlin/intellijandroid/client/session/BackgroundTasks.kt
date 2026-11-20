package intellijandroid.client.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A task running on the backend (docs/status-bar.md), e.g. title "Indexing…", text "Scanning files
 * to index", details "…/guava-33.4.8-jre.jar", fraction 0.4 (null: indeterminate).
 */
data class BackgroundTask(
  val id: Long,
  val title: String,
  val text: String? = null,
  val details: String? = null,
  val fraction: Double? = null,
  val cancellable: Boolean = false,
  val suspendable: Boolean = false,
  val paused: Boolean = false,
  /** IntelliJ shows only these in the status bar; all of them in the Processes popup. */
  val inStatusBar: Boolean = true,
)

/**
 * The backend's running tasks through Fleet RPC `TaskInfoApi` (IntelliJ's
 * platform/progress/shared/src/rpc/TaskInfoApi.kt): `activeTasks()` streams `TaskAdded`,
 * `ProgressChanged`, `StatusChanged`, `SuspensionChanged` and `TaskRemoved`; `cancelTask`,
 * `pauseTask` and `resumeTask` take the task's id. Called on the protocol thread.
 */
internal class BackgroundTasks(
  private val rpc: FleetRpc,
  private val publish: (List<BackgroundTask>) -> Unit,
  private val log: (String) -> Unit,
) {
  private val tasks = LinkedHashMap<Long, BackgroundTask>()

  fun start() {
    rpc.subscribe(SERVICE, "activeTasks", JsonObject(emptyMap())) { element ->
      runCatching { apply(element.jsonObject) }
        .onFailure { failure -> log("Task event not read (${failure.message}): $element") }
    }
  }

  fun cancel(id: Long) = rpc.call(SERVICE, "cancelTask", buildJsonObject { put("taskId", id) })

  fun pause(id: Long) =
    rpc.call(
      SERVICE,
      "pauseTask",
      buildJsonObject {
        put("taskId", id)
        put("reason", null as String?)
      },
    )

  fun resume(id: Long) = rpc.call(SERVICE, "resumeTask", buildJsonObject { put("taskId", id) })

  private fun apply(event: JsonObject) {
    if (applyTaskEvent(tasks, event)) publish(tasks.values.toList())
  }

  private companion object {
    const val SERVICE = "com.intellij.platform.ide.progress.rpc.TaskInfoApi"
  }
}

/**
 * Applies one `TaskInfoEvent` to [tasks]; false when it changed nothing (an event for a task not
 * seen, or a kind this client doesn't know). Example `TaskAdded`:
 * `{"type":"…rpc.TaskInfoEvent.TaskAdded","taskId":1,"title":"Scanning files…",
 * "cancellation":{"type":"…NonCancellableTaskCancellation"},
 * "suspension":{"type":"…TaskSuspension.NonSuspendable"},
 * "status":{"type":"…TaskStatus.Running","source":"SYSTEM"},"visibleInStatusBar":false,…}`.
 */
internal fun applyTaskEvent(tasks: MutableMap<Long, BackgroundTask>, event: JsonObject): Boolean {
  val id = requireNotNull(event["taskId"]?.jsonPrimitive?.longOrNull) { "no taskId" }
  when (kindOf(event)) {
    "TaskAdded" ->
      tasks[id] =
        BackgroundTask(
          id = id,
          title = event.text("title").orEmpty(),
          cancellable = kindOf(event["cancellation"])?.startsWith("Cancellable") == true,
          suspendable = kindOf(event["suspension"]) == "Suspendable",
          paused = kindOf(event["status"]) == "Paused",
          inStatusBar = event["visibleInStatusBar"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    "TaskRemoved" -> tasks.remove(id) ?: return false
    "ProgressChanged" -> {
      val state = event["state"] as? JsonObject
      tasks[id] =
        tasks[id]?.copy(
          text = state?.text("text"),
          details = state?.text("details"),
          fraction = state?.get("fraction")?.jsonPrimitive?.doubleOrNull,
        ) ?: return false
    }
    "StatusChanged" ->
      tasks[id] = tasks[id]?.copy(paused = kindOf(event["status"]) == "Paused") ?: return false
    "SuspensionChanged" ->
      tasks[id] =
        tasks[id]?.copy(suspendable = kindOf(event["suspension"]) == "Suspendable") ?: return false
    else -> return false
  }
  return true
}

/**
 * A sealed class's case from kotlinx.serialization's `type` field, e.g.
 * `com.intellij.platform.ide.progress.rpc.TaskInfoEvent.TaskAdded` → `TaskAdded`,
 * `…suspender.TaskSuspension.NonSuspendable` → `NonSuspendable`.
 */
private fun kindOf(element: JsonElement?): String? =
  ((element as? JsonObject)?.get("type") as? JsonPrimitive)
    ?.contentOrNull
    ?.substringAfterLast('.')
    ?.substringAfterLast('$')

private fun JsonObject.text(key: String): String? =
  (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
