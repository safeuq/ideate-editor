package io.github.safeuq.ideate.client.session.buildtree

import io.github.safeuq.ideate.client.kernel.SearchKernel
import io.github.safeuq.ideate.client.session.ProjectTree
import io.github.safeuq.ideate.client.session.TextFragment
import io.github.safeuq.ideate.client.session.TreeNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val EVENT_PACKAGE = "com.intellij.build"

/** `BuildDuration`: a finished step's length, or the start time of a running one. */
sealed interface BuildDuration {
  data class Fixed(val milliseconds: Long) : BuildDuration

  data class InProgress(val startTimestamp: Long) : BuildDuration
}

/**
 * One step of a build, as a `BuildNodesUpdate` carries it (docs/terminal-and-consoles-spec.md 9.3),
 * e.g. `hello` (title) running, or `:app:hello` (name) 3 ms.
 */
data class BuildNode(
  val id: Int,
  /** 0 is the hidden root. */
  val parentId: Int,
  /** The icon's path in the icons jar, e.g. `expui/status/info.svg`; null: none, or [animated]. */
  val iconPath: String?,
  /** The spinner of a running step (`AnimatedIcon.Default`). */
  val animated: Boolean,
  val title: String?,
  val name: String?,
  val hint: String?,
  val duration: BuildDuration?,
  val autoExpand: Boolean,
  val visibleAlways: Boolean,
  val visibleAsSuccessful: Boolean,
  val visibleAsWarning: Boolean,
)

/**
 * The client side of `BuildTreeViewModelProxy` for one build: events in, rows out. Called from one
 * place at a time (the caller holds a lock).
 */
class BuildTreeModel {
  private val nodes = linkedMapOf<Int, BuildNode>()
  private var showSuccessful = true
  private var showWarnings = true
  private val expanded = mutableSetOf<Int>()
  private var selected: Int? = null

  /** The backend's clock minus ours, in ms, from the last `currentTimestamp`. */
  private var clockDifference = 0L

  /** Whether a running step is in the tree: its spinner turns and its duration ticks. */
  val running: Boolean
    get() = nodes.values.any { it.duration is BuildDuration.InProgress }

  /** Applies one `BuildTreeEvent` (JSON from `getTreeEventsFlow`); [now] is our clock in ms. */
  fun apply(event: JsonObject, now: Long) {
    when (val type = event.getValue("type").text()) {
      "$EVENT_PACKAGE.BuildTreeFilteringState" -> {
        showSuccessful = event.getValue("showSuccessful").flag()
        showWarnings = event.getValue("showWarnings").flag()
      }
      "$EVENT_PACKAGE.BuildNodesUpdate" -> {
        event["currentTimestamp"].numberOrNull()?.let { clockDifference = it - now }
        for (element in event.getValue("nodes").array()) {
          val node = nodeOf(element.jsonObject)
          nodes[node.id] = node
          if (node.autoExpand) expanded += node.id
        }
      }
      "$EVENT_PACKAGE.BuildTreeExposeRequest" -> {
        val nodeId = event.getValue("nodeId").int()
        // Open every step above the node so its row shows.
        var parent = nodes[nodeId]?.parentId
        while (parent != null && parent != 0) {
          expanded += parent
          parent = nodes[parent]?.parentId
        }
        if (event["alsoSelect"].flagOrNull() == true) selected = nodeId
      }
      else -> error("Unknown build tree event $type")
    }
  }

  fun select(nodeId: Int) {
    selected = nodeId
  }

  fun toggle(nodeId: Int) {
    if (!expanded.remove(nodeId)) expanded += nodeId
  }

  /**
   * The rows to draw, at our clock [now] (ms): a step shows when it is `visibleAlways`, or the
   * filter shows its kind, or something below it shows (so a hidden parent doesn't hide a problem).
   */
  fun tree(now: Long): ProjectTree {
    val children = nodes.values.groupBy { it.parentId }
    fun shows(node: BuildNode): Boolean =
      node.visibleAlways ||
        (showSuccessful && node.visibleAsSuccessful) ||
        (showWarnings && node.visibleAsWarning) ||
        children[node.id].orEmpty().any(::shows)
    val shown = nodes.values.filter(::shows).associateBy { it.id }
    val order = nodes.keys.withIndex().associate { it.value to it.index }
    val rows = shown.mapValues { (id, node) ->
      val hasChildren = children[id].orEmpty().any { it.id in shown }
      TreeNode(
        id = id,
        parentId = node.parentId.takeIf { it != 0 },
        fragments = fragmentsOf(node),
        iconPaths = listOfNotNull(if (node.animated) spinnerFrame(now) else node.iconPath),
        hasChildren = hasChildren,
        isDirectory = hasChildren,
        path = null,
        sortKey = order.getValue(id),
        trailing = node.duration?.let { durationText(it, now + clockDifference) },
      )
    }
    return ProjectTree(rows, expanded.filterTo(mutableSetOf()) { it in shown }, selected)
  }

  private fun nodeOf(json: JsonObject): BuildNode {
    val icon =
      (json["icon"] as? JsonObject)?.let { decodeIcon(it.getValue("serializedValue").text()) }
        ?: DecodedIcon(null, false)
    return BuildNode(
      id = json.getValue("id").int(),
      parentId = json.getValue("parentId").int(),
      iconPath = icon.path,
      animated = icon.animated,
      title = json["title"].textOrNull(),
      name = json["name"].textOrNull(),
      hint = json["hint"].textOrNull(),
      duration = (json["duration"] as? JsonObject)?.let(::durationOf),
      autoExpand = json["autoExpand"].flagOrNull() ?: false,
      visibleAlways = json["visibleAlways"].flagOrNull() ?: false,
      visibleAsSuccessful = json["visibleAsSuccessful"].flagOrNull() ?: false,
      visibleAsWarning = json["visibleAsWarning"].flagOrNull() ?: false,
    )
  }

  private fun durationOf(json: JsonObject): BuildDuration =
    when (val type = json.getValue("type").text()) {
      "$EVENT_PACKAGE.BuildDuration.Fixed" ->
        BuildDuration.Fixed(json.getValue("durationMs").long())
      "$EVENT_PACKAGE.BuildDuration.InProgress" ->
        BuildDuration.InProgress(json.getValue("startTimestamp").long())
      else -> error("Unknown build duration $type")
    }

  private fun JsonElement.text() = (this as JsonPrimitive).content

  private fun JsonElement?.textOrNull() = (this as? JsonPrimitive)?.contentOrNull

  private fun JsonElement.flag() = requireNotNull((this as JsonPrimitive).booleanOrNull) { "$this" }

  private fun JsonElement?.flagOrNull() = (this as? JsonPrimitive)?.booleanOrNull

  private fun JsonElement?.numberOrNull() = (this as? JsonPrimitive)?.longOrNull

  private fun JsonElement.int() = requireNotNull((this as JsonPrimitive).intOrNull) { "$this" }

  private fun JsonElement.long() = requireNotNull((this as JsonPrimitive).longOrNull) { "$this" }

  private fun JsonElement.array() = this as JsonArray

  private fun fragmentsOf(node: BuildNode): List<TextFragment> = buildList {
    // The row's parts as BuildTreeView's renderer draws them (spec 9.1): bold "title: ", the name,
    // a gray hint after a space.
    node.title?.let { add(TextFragment("$it: ", BOLD, null)) }
    node.name?.let { add(TextFragment(it, 0, null)) }
    node.hint?.let { add(TextFragment(" $it", 0, HINT_COLOR)) }
  }
}

/** `SimpleTextAttributes.STYLE_BOLD`. */
private const val BOLD = 1

/** `IslandsDark.textSecondary`, ARGB. */
private const val HINT_COLOR = 0xFF73767CL

/** [path] is an icon's path in the icons jar; [animated]: the running spinner. */
internal data class DecodedIcon(val path: String?, val animated: Boolean)

/** The kind id (first 8 bytes) of a serialized `IconId` that names an icon by its path. */
private const val PATH_ICON_KIND = "082e30f8db6c3911"

/** The kind id of the spinner (`AnimatedIcon.Default`); the rest of its bytes are zero. */
private const val SPINNER_ICON_KIND = "4deefbdaa64df647"

/**
 * Reads a serialized `IconId` (`icon.serializedValue`, base64; layout in spec 9.3):
 * ```
 * 8 bytes  kind id      082e30f8db6c3911 (a path)    or 4deefbdaa64df647 (the spinner, then 12 zeros)
 * 3 x int  two numbers and 0   (97, 104, 0 for info.svg; not needed)
 * int      path length n in chars
 * n chars  path, UTF-16LE      e.g. expui/status/info.svg
 * ...      1, "com.intellij" (the icon's class loader), 16, 16, zeros
 * ```
 *
 * An unknown kind has no icon rather than failing the whole tree.
 */
internal fun decodeIcon(serializedValue: String): DecodedIcon {
  val bytes = Base64.getDecoder().decode(serializedValue)
  require(bytes.size >= 8) { "Icon id of ${bytes.size} bytes: $serializedValue" }
  return when (bytes.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }) {
    SPINNER_ICON_KIND -> DecodedIcon(null, true)
    PATH_ICON_KIND -> {
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val length = buffer.getInt(20)
      require(length in 1..200 && bytes.size >= 24 + 2 * length) {
        "Icon id with path length $length in ${bytes.size} bytes: $serializedValue"
      }
      DecodedIcon(String(bytes, 24, 2 * length, Charsets.UTF_16LE), false)
    }
    else -> DecodedIcon(null, false)
  }
}

/** `AnimatedIcon.Default`: eight frames, 100 ms each. */
private fun spinnerFrame(now: Long) = "process/step_${now / 100 % 8 + 1}.svg"

/**
 * `NlsMessages.formatDuration`: ICU's short list of the non-zero units, e.g. 1251 ms is `1 sec, 251
 * ms`, 3 ms is `3 ms`, 65 000 ms is `1 min, 5 sec`. A running step counts from its start on the
 * backend's clock [backendNow].
 */
internal fun durationText(duration: BuildDuration, backendNow: Long): String {
  val milliseconds =
    when (duration) {
      is BuildDuration.Fixed -> duration.milliseconds
      is BuildDuration.InProgress -> (backendNow - duration.startTimestamp).coerceAtLeast(0)
    }
  val parts =
    listOf(
        milliseconds / 3_600_000 to "hr",
        milliseconds / 60_000 % 60 to "min",
        milliseconds / 1_000 % 60 to "sec",
        milliseconds % 1_000 to "ms",
      )
      .filter { it.first > 0 }
  return if (parts.isEmpty()) "0 ms" else parts.joinToString(", ") { "${it.first} ${it.second}" }
}

/**
 * The Build trees Ideate shows, by the backend's model id (`BeSplitComponentControl.modelUid`): one
 * [BuildTreeModel] each, fed by `BuildTreeApi.getTreeEventsFlow` over [kernel]. [publish] gets the
 * rows after every change.
 *
 * ponytail: `getNavigationFlow` and `onNavigationContextChange` are not called, so the backend's
 * requests to select a node and the previous/next problem actions do nothing; lift when source
 * navigation from the tree is built.
 */
class BuildTrees(
  private val kernel: SearchKernel,
  private val publish: (uid: Int, ProjectTree) -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val models = ConcurrentHashMap<Int, BuildTreeModel>()
  private val jobs = ConcurrentHashMap<Int, List<Job>>()

  /** Starts following the build [uid]; a second call for the same build does nothing. */
  fun open(uid: Int) {
    if (jobs.containsKey(uid)) return
    val model = BuildTreeModel().also { models[uid] = it }
    val events =
      kernel.buildTreeEvents(uid) { event ->
        synchronized(model) { model.apply(event, clock()) }
        publishTree(uid)
      }
    // A running step's spinner turns and its duration counts up: draw again until none runs.
    val ticker = scope.launch {
      while (isActive) {
        delay(100)
        if (synchronized(model) { model.running }) publishTree(uid)
      }
    }
    jobs[uid] = listOf(events, ticker)
  }

  /** Stops following the build [uid], e.g. when its tab is closed. */
  fun close(uid: Int) {
    jobs.remove(uid)?.forEach(Job::cancel)
    models.remove(uid)
  }

  /** A tap on a row: the backend shows that step's output in the console. */
  fun select(uid: Int, nodeId: Int) {
    val model = models[uid] ?: return
    synchronized(model) { model.select(nodeId) }
    publishTree(uid)
    kernel.selectBuildNode(uid, nodeId)
  }

  fun toggle(uid: Int, nodeId: Int) {
    val model = models[uid] ?: return
    synchronized(model) { model.toggle(nodeId) }
    publishTree(uid)
  }

  private fun publishTree(uid: Int) {
    val model = models[uid] ?: return
    publish(uid, synchronized(model) { model.tree(clock()) })
  }
}
