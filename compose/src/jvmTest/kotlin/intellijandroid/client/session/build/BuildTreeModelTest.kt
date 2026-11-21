package intellijandroid.client.session.build

import intellijandroid.client.session.ProjectTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The Build tree's events as the Mac backend sent them for the `hello` task of `gradle-smoke`
 * (docs/terminal-and-consoles-spec.md 9.3), and what the client makes of them.
 */
class BuildTreeModelTest {
  private val filterOff =
    """{"type":"com.intellij.build.BuildTreeFilteringState","showSuccessful":false,"showWarnings":true}"""
  private val filterOn =
    """{"type":"com.intellij.build.BuildTreeFilteringState","showSuccessful":true,"showWarnings":true}"""

  private val spinner = """{"serializedValue":"Te772qZN9kcAAAAAAAAAAAAAAAA="}"""
  private val infoId =
    "CC4w+NtsORFhAAAAaAAAAAAAAAAVAAAAZQB4AHAAdQBpAC8AcwB0AGEAdAB1AHMALwBpAG4AZgBvAC4AcwB2AGcAAQwAAABjAG8AbQAuAGkAbgB0AGUAbABsAGkAagAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val passedId =
    "CC4w+NtsORFnAAAA4QEAAAAAAAAYAAAAZQB4AHAAdQBpAC8AcgB1AG4ALwB0AGUAcwB0AFAAYQBzAHMAZQBkAC4AcwB2AGcAAQwAAABjAG8AbQAuAGkAbgB0AGUAbABsAGkAagAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val info = """{"serializedValue":"$infoId"}"""
  private val passed = """{"serializedValue":"$passedId"}"""

  private fun node(
    id: Int,
    parentId: Int,
    icon: String,
    title: String?,
    name: String,
    hint: String?,
    duration: String,
    always: Boolean,
    successful: Boolean,
    warning: Boolean,
    autoExpand: Boolean = false,
  ) =
    """{"id":$id,"parentId":$parentId,"icon":$icon,"title":${title?.let { "\"$it\"" }},"name":"$name","hint":${hint?.let { "\"$it\"" }},"duration":$duration,"navigatables":[],"autoExpand":$autoExpand,"hasProblems":false,"visibleAlways":$always,"visibleAsSuccessful":$successful,"visibleAsWarning":$warning}"""

  private fun update(timestamp: Long, vararg nodes: String) =
    """{"type":"com.intellij.build.BuildNodesUpdate","currentTimestamp":$timestamp,"nodes":[${nodes.joinToString(",")}]}"""

  private fun inProgress(start: Long) =
    """{"type":"com.intellij.build.BuildDuration.InProgress","startTimestamp":$start}"""

  private fun fixed(milliseconds: Long) =
    """{"type":"com.intellij.build.BuildDuration.Fixed","durationMs":$milliseconds}"""

  private val rootRunning =
    node(1, 0, spinner, "hello", "running…", null, inProgress(1000), true, true, false, true)
  private val warning =
    node(2, 1, info, null, "New Minor Gradle Version Available", null, "null", false, true, true)
  private val taskRunning =
    node(3, 1, spinner, null, ":app:hello", null, inProgress(4000), true, true, false)
  private val taskDone = node(3, 1, passed, null, ":app:hello", null, fixed(11), false, true, false)
  private val rootDone =
    node(
      1,
      0,
      passed,
      "hello",
      "successful",
      "At 21/11/26, 1:07 PM",
      fixed(3450),
      true,
      true,
      true,
      true,
    )
  private val exposeWarning =
    """{"type":"com.intellij.build.BuildTreeExposeRequest","nodeId":2,"alsoSelect":false}"""

  private fun model(vararg events: String) =
    BuildTreeModel().apply {
      events.forEach { apply(Json.parseToJsonElement(it).jsonObject, now = 0) }
    }

  private fun ProjectTree.rows() =
    visibleRows().map { (node, depth) ->
      "$depth ${node.fragments.joinToString("") { it.text }}" to node.trailing
    }

  @Test
  fun aRunningBuildShowsItsRunningTask() {
    val tree =
      model(
          filterOff,
          update(5000, rootRunning),
          update(6000, rootRunning, warning),
          exposeWarning,
          update(9000, taskRunning),
        )
        .tree(now = 0)
    assertEquals(
      listOf(
        "1 hello: running…" to "8 sec",
        "2 New Minor Gradle Version Available" to null,
        "2 :app:hello" to "5 sec",
      ),
      tree.rows(),
    )
  }

  @Test
  fun aFinishedSuccessfulTaskIsHiddenUntilSuccessfulStepsShow() {
    val events =
      arrayOf(
        update(5000, rootRunning, warning),
        update(9000, taskRunning),
        update(9100, taskDone),
        update(9200, rootDone),
      )
    assertEquals(
      listOf(
        "1 hello: successful At 21/11/26, 1:07 PM" to "3 sec, 450 ms",
        "2 New Minor Gradle Version Available" to null,
      ),
      model(filterOff, *events).tree(now = 0).rows(),
    )
    assertEquals(
      listOf(
        "1 hello: successful At 21/11/26, 1:07 PM" to "3 sec, 450 ms",
        "2 New Minor Gradle Version Available" to null,
        "2 :app:hello" to "11 ms",
      ),
      model(filterOff, *events, filterOn).tree(now = 0).rows(),
    )
  }

  @Test
  fun titleIsBoldAndHintIsGray() {
    val node = model(filterOff, update(5000, rootDone)).tree(0).nodes.getValue(1)
    assertEquals(
      listOf("hello: " to 1, "successful" to 0, " At 21/11/26, 1:07 PM" to 0),
      node.fragments.map { it.text to it.style },
    )
    assertEquals(0xFF73767CL, node.fragments.last().foreground)
  }

  @Test
  fun iconsAreReadFromTheSerializedId() {
    val tree = model(filterOff, update(5000, rootRunning, warning), update(9100, taskDone)).tree(0)
    assertTrue(tree.nodes.getValue(1).iconPaths.single().startsWith("process/step_"))
    assertEquals(listOf("expui/status/info.svg"), tree.nodes.getValue(2).iconPaths)
    assertEquals(DecodedIcon("expui/run/testPassed.svg", false), decodeIcon(passedId))
    assertEquals(DecodedIcon(null, true), decodeIcon("Te772qZN9kcAAAAAAAAAAAAAAAA="))
    assertEquals(DecodedIcon(null, false), decodeIcon("AAAAAAAAAAAAAAAAAAAAAAAAAAA="))
  }

  @Test
  fun anExposeRequestOpensTheStepsAboveAndMaySelect() {
    val model =
      model(
        filterOff,
        update(
          5000,
          node(1, 0, spinner, "hello", "x", null, "null", true, true, true),
          node(2, 1, info, null, "mid", null, "null", true, true, true),
          node(3, 2, info, null, "leaf", null, "null", true, true, true),
        ),
        """{"type":"com.intellij.build.BuildTreeExposeRequest","nodeId":3,"alsoSelect":true}""",
      )
    val tree = model.tree(0)
    assertEquals(setOf(1, 2), tree.expanded)
    assertEquals(3, tree.selected)
    model.toggle(2)
    assertEquals(setOf(1), model.tree(0).expanded)
  }

  @Test
  fun durationsReadLikeNlsMessages() {
    assertEquals("3 ms", durationText(BuildDuration.Fixed(3), 0))
    assertEquals("1 sec, 251 ms", durationText(BuildDuration.Fixed(1251), 0))
    assertEquals("2 sec", durationText(BuildDuration.Fixed(2000), 0))
    assertEquals("1 min, 5 sec", durationText(BuildDuration.Fixed(65_000), 0))
    assertEquals("0 ms", durationText(BuildDuration.Fixed(0), 0))
    // A running step counts from its start on the backend's clock.
    assertEquals("2 sec, 500 ms", durationText(BuildDuration.InProgress(10_000), 12_500))
  }
}
