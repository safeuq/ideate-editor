package io.github.safeuq.ideate.client.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One row of the Problems tool window's File tab: IntelliJ's `HighlightingProblem`, built from a
 * highlighter that carries a [HighlightInfo]. [line] and [column] count from 0.
 */
data class HighlightingProblem(
  val highlightId: Long,
  val offset: Int,
  val line: Int,
  val column: Int,
  val text: String,
  val description: String,
  val severity: Int,
  val severityId: String,
  val hasQuickFixes: Boolean,
)

/**
 * One row of the Project Errors tab: the backend's `FileProblemDto` ([filePath] set, [line] and
 * [column] from 0) or `GenericProblemDto` ([filePath] null).
 */
@Serializable
data class ProjectProblem(
  val id: String,
  val text: String,
  val description: String?,
  val iconPath: String?,
  val filePath: String?,
  val line: Int,
  val column: Int,
)

/**
 * The Project Errors tab's rows, from `ProblemsViewApi.getProjectErrorsFlow(projectId)`: a stream
 * of lists of `ProblemEventDto`. Example event: `{"type":"….ProblemEventDto.Appeared",
 * "problemDto":{"type":"….FileProblemDto","id":"…","text":"Cannot resolve symbol 'Strin'",
 * "description":null,"icon":{…},"filePath":"/work/demo/src/Main.java","fileId":{…},"line":5,
 * "column":4}}`; `Disappeared` carries `problemId`. Called on the protocol thread.
 */
internal class ProjectErrors(
  private val rpc: FleetRpc,
  private val kernelProjectId: String,
  private val publish: (List<ProjectProblem>) -> Unit,
  private val log: (String) -> Unit,
) {
  private val problems = LinkedHashMap<String, ProjectProblem>()

  fun start() {
    rpc.subscribe(
      SERVICE,
      "getProjectErrorsFlow",
      buildJsonObject { put("projectId", buildJsonObject { put("id", kernelProjectId) }) },
    ) { element ->
      runCatching { applyProblemEvents(problems, element.jsonArray) }
        .onSuccess {
          log("Project errors: ${problems.size}")
          publish(problems.values.toList())
        }
        .onFailure { failure -> log("Problem events not read (${failure.message}): $element") }
    }
  }

  private companion object {
    const val SERVICE = "com.intellij.platform.problemsView.shared.ProblemsViewApi"
  }
}

/** Applies a list of `ProblemEventDto` (Appeared, Updated, Disappeared) to [problems], by id. */
internal fun applyProblemEvents(problems: MutableMap<String, ProjectProblem>, events: JsonArray) {
  for (event in events) {
    val fields = event.jsonObject
    val kind = requireNotNull(fields["type"]?.jsonPrimitive?.contentOrNull) { "no type" }
    if (kind.endsWith(".Disappeared")) {
      problems.remove(
        requireNotNull(fields["problemId"]?.jsonPrimitive?.contentOrNull) { "no problemId" },
      )
      continue
    }
    val problem = requireNotNull(fields["problemDto"] as? JsonObject) { "no problemDto in $kind" }
    val id = requireNotNull(problem["id"]?.jsonPrimitive?.contentOrNull) { "no id" }
    problems[id] =
      ProjectProblem(
        id = id,
        text = requireNotNull(problem["text"]?.jsonPrimitive?.contentOrNull) { "no text" },
        description =
          (problem["description"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        iconPath = iconPath(problem["icon"]),
        filePath =
          (problem["filePath"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        line = (problem["line"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0,
        column = (problem["column"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0,
      )
  }
}

object ProblemsView {
  /**
   * The lowest severity the File tab lists: `HighlightSeverity.TEXT_ATTRIBUTES.myVal + 1`, as
   * `ProblemsViewHighlightingFileRoot` passes to `ProblemsViewHighlightingWatcher` (INFORMATION is
   * 10 and TEXT ATTRIBUTES 11, so neither is listed).
   */
  const val FILE_LEVEL = 12

  /**
   * The counter's entries (`TrafficLightRenderer.getStatus`): one per severity that has
   * highlighters above `TEXT_ATTRIBUTES` (`incErrorCount`), most severe first, with the count and
   * the severity's count message, e.g. "2 errors" from `{0, choice, 1#{0} error|2#{0} errors}`.
   */
  // ponytail: no StatusItemMerger (IntelliJ can merge e.g. grammar and typo items into one);
  // add it when two items show where IntelliJ shows one. The tooltip is the display name, not the
  // filled-in count message: filling ICU choice templates needs a formatter the client lacks.
  fun statusItems(
    highlights: List<Highlight>,
    severities: List<HighlightSeverity>,
  ): List<StatusItem> {
    val counts =
      highlights
        .mapNotNull { it.info }
        .filter { it.severity >= FILE_LEVEL }
        .groupingBy { it.severityId to it.severity }
        .eachCount()
    return counts.entries
      .sortedByDescending { it.key.second }
      .map { (severity, count) ->
        val known = severities.firstOrNull { it.id == severity.first }
        StatusItem(severity.first, known?.iconPaths.orEmpty(), count, known?.displayName)
      }
  }

  /**
   * The offset of [line] and [column] (both from 0) in [text], e.g. line 5, column 4 of a build
   * error. A line past the end gives the text's end, a column past the line's end that line's end.
   */
  // ponytail: a column counts characters, so a tab is one column; IntelliJ's logical column counts
  // a tab as its width. Add the tab width when a tab-indented file lands on the wrong column.
  fun offsetAt(text: String, line: Int, column: Int): Int {
    var start = 0
    repeat(line) {
      val lineEnd = text.indexOf('\n', start)
      if (lineEnd < 0) return text.length
      start = lineEnd + 1
    }
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return (start + column).coerceIn(start, end)
  }

  /**
   * The problems of [text]'s highlighters, in the File tab's order. A highlighter counts when
   * `ProblemsViewHighlightingWatcher.isValid` holds: it has a description and its severity is at
   * least [level].
   */
  fun highlightingProblems(
    text: String,
    highlights: List<Highlight>,
    level: Int = FILE_LEVEL,
  ): List<HighlightingProblem> =
    highlights
      .mapNotNull { highlight ->
        val info = highlight.info ?: return@mapNotNull null
        val description = info.description ?: return@mapNotNull null
        if (info.severity < level || highlight.start !in 0..text.length) return@mapNotNull null
        val lineStart = text.lastIndexOf('\n', highlight.start - 1) + 1
        HighlightingProblem(
          highlightId = highlight.id,
          offset = highlight.start,
          line = text.count(0, lineStart),
          column = highlight.start - lineStart,
          text = problemText(description),
          description = description,
          severity = info.severity,
          severityId = info.severityId,
          hasQuickFixes = info.hasQuickFixes,
        )
      }
      .sortedWith(ProblemsViewNodeComparator)

  /**
   * The problem under [offset] whose text shows when the pointer rests there: the one with the
   * highest severity among the highlighters that cover it and have a tooltip or description.
   */
  // ponytail: one problem; IntelliJ's hover lists every problem at the offset, most severe first.
  fun problemAt(highlights: List<Highlight>, offset: Int): Highlight? =
    highlights
      .filter { highlight ->
        offset >= highlight.start &&
          offset < highlight.end &&
          highlight.info.let { it != null && (it.toolTip ?: it.description) != null }
      }
      .maxByOrNull { requireNotNull(it.info).severity }

  /**
   * `HighlightingProblem.getText`: the description, cut at its first line break with "…" unless it
   * is HTML.
   */
  fun problemText(description: String): String {
    val lineBreak = description.indexOfFirst { it == '\n' || it == '\r' }
    return if (lineBreak < 0 || description.startsWith("<html>", ignoreCase = true)) description
    else description.substring(0, lineBreak) + "…"
  }

  /**
   * `ProblemsViewNodeComparator` with "sort by severity" on (the default): severity high to low,
   * then line, column, text.
   */
  // ponytail: text compared without case, not IntelliJ's natural compare (digits by value); only
  // decides between two problems of one severity at the same position.
  val ProblemsViewNodeComparator: Comparator<HighlightingProblem> =
    compareByDescending<HighlightingProblem> { it.severity }
      .thenBy { it.line }
      .thenBy { it.column }
      .thenBy(String.CASE_INSENSITIVE_ORDER) { it.text }

  private fun String.count(from: Int, until: Int): Int {
    var lines = 0
    for (index in from until until) if (this[index] == '\n') lines++
    return lines
  }
}
