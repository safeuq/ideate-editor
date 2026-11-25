package io.github.safeuq.ideate.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * Event shapes follow the serializers of `ProblemEventDto`, `FileProblemDto`, `GenericProblemDto`.
 */
class ProjectErrorsTest {
  private val prefix = "com.intellij.analysis.problemsView.toolWindow.splitApi"

  private fun apply(problems: MutableMap<String, ProjectProblem>, json: String) =
    applyProblemEvents(problems, Json.parseToJsonElement(json).jsonArray)

  @Test
  fun appearedUpdatedAndDisappearedKeepTheListById() {
    val problems = LinkedHashMap<String, ProjectProblem>()
    apply(
      problems,
      """[{"type":"$prefix.ProblemEventDto.Appeared","problemDto":{"type":"$prefix.FileProblemDto",
        "id":"a","text":"Cannot resolve symbol 'Strin'","description":null,"icon":null,
        "filePath":"/work/demo/src/Main.java","fileId":{},"line":5,"column":4}},
        {"type":"$prefix.ProblemEventDto.Appeared","problemDto":{"type":"$prefix.GenericProblemDto",
        "id":"b","text":"Build file is broken","description":"details","icon":null}}]""",
    )
    assertEquals(
      listOf(
        ProjectProblem(
          "a",
          "Cannot resolve symbol 'Strin'",
          null,
          null,
          "/work/demo/src/Main.java",
          5,
          4,
        ),
        ProjectProblem("b", "Build file is broken", "details", null, null, 0, 0),
      ),
      problems.values.toList(),
    )
    apply(
      problems,
      """[{"type":"$prefix.ProblemEventDto.Updated","problemDto":{"type":"$prefix.GenericProblemDto",
        "id":"b","text":"Build file is fine","description":null,"icon":null}},
        {"type":"$prefix.ProblemEventDto.Disappeared","problemId":"a"}]""",
    )
    assertEquals(listOf("Build file is fine"), problems.values.map { it.text })
  }
}
