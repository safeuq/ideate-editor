package io.github.safeuq.ideate.client.ssh

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HostData's reading of the desktop IDEs' `recentProjects.xml` and the merge with the backends'.
 */
class HostDataTest {
  private val idea =
    InstalledIde("/apps/IDEA.app/Contents", null, "IU", "263.1.1", "2026.3", "IntelliJ IDEA")
  private val newerIdea =
    idea.copy(idePath = "/apps/IDEA-new.app/Contents", buildNumber = "263.10.1")

  /** One entry as IntelliJ IDEA 2026.3 writes it (trimmed), on this Mac. */
  private fun recentProjectsXml(key: String, activation: String?, productCode: String = "IU") =
    """
    <application>
      <component name="RecentProjectsManager">
        <option name="additionalInfo">
          <map>
            <entry key="$key">
              <value>
                <RecentProjectMetaInfo frameTitle="IDLReasonerChoco – OASSolver.java">
                  ${activation?.let { """<option name="activationTimestamp" value="$it" />""" }.orEmpty()}
                  <option name="build" value="$productCode-243.26053.27" />
                  <option name="productionCode" value="$productCode" />
                  <option name="projectOpenTimestamp" value="1700000000000" />
                </RecentProjectMetaInfo>
              </value>
            </entry>
          </map>
        </option>
      </component>
    </application>
    """
      .trimIndent()

  private val choco = "\$USER_HOME\$/Files/Projects/Java/OpenSource/IDLReasonerChoco"

  @Test
  fun readsAnEntryWithTheNewestIdeOfItsProduct() {
    assertEquals(
      listOf(
        RecentProject(
          "/Users/dev/Files/Projects/Java/OpenSource/IDLReasonerChoco",
          newerIdea.idePath,
          1745454457133,
          running = false,
        ),
      ),
      HostData.ideRecentProjects(
        mapOf("IntelliJIdea2026.3" to recentProjectsXml(choco, "1745454457133")),
        "/Users/dev",
        listOf(idea, newerIdea),
        emptySet(),
      ),
    )
  }

  @Test
  fun withoutActivationTheOpenTimeCounts() {
    assertEquals(
      listOf(1700000000000),
      HostData.ideRecentProjects(
          mapOf("IntelliJIdea2026.3" to recentProjectsXml("/work/a", null)),
          "/Users/dev",
          listOf(idea),
          emptySet(),
        )
        .map { it.dateLastOpened },
    )
  }

  @Test
  fun onlyTheNewestSettingsFolderPerProduct() {
    val projects =
      HostData.ideRecentProjects(
        mapOf(
          "IntelliJIdea2026.2" to recentProjectsXml("/work/old", "1"),
          "IntelliJIdea2026.3" to recentProjectsXml("/work/new", "2"),
          "IntelliJIdea2026.10" to recentProjectsXml("/work/newest", "3"),
          "GoLand2025.1" to recentProjectsXml("/work/go", "4", productCode = "GO"),
        ),
        "/Users/dev",
        listOf(idea),
        emptySet(),
      )
    // GoLand isn't installed: its project is dropped.
    assertEquals(listOf("/work/newest"), projects.map { it.projectPath })
  }

  @Test
  fun aMissingFolderIsDropped() {
    assertEquals(
      emptyList(),
      HostData.ideRecentProjects(
        mapOf("IntelliJIdea2026.3" to recentProjectsXml(choco, "1")),
        "/Users/dev",
        listOf(idea),
        setOf(choco),
      ),
    )
  }

  @Test
  fun mergesByPathRunningFirstElseLatest() {
    val backend =
      listOf(
        RecentProject("/work/smoke", "/ide/backend", 1000, running = true),
        RecentProject("/work/xtool", "/ide/backend", 1000, running = false),
      )
    val ide =
      listOf(
        RecentProject("/work/smoke", idea.idePath, 5000, running = false),
        RecentProject("/work/xtool", idea.idePath, 3000, running = false),
        RecentProject("/work/only-ide", idea.idePath, 2000, running = false),
      )
    assertEquals(
      listOf(ide[1], ide[2], backend[0]),
      HostData.mergedRecent(backend, ide),
    )
  }

  @Test
  fun aCorruptFileIsSkippedAndTheOthersStillCount() {
    assertEquals(
      listOf("/work/go"),
      HostData.ideRecentProjects(
          mapOf(
            "IntelliJIdea2026.3" to "<application><component",
            "GoLand2025.1" to recentProjectsXml("/work/go", "4", productCode = "GO"),
          ),
          "/Users/dev",
          listOf(idea, idea.copy(idePath = "/apps/GoLand.app/Contents", productCode = "GO")),
          emptySet(),
        )
        .map { it.projectPath },
    )
  }

  @Test
  fun aGoneKeyWithAnEscapedAmpersandMatches() {
    assertEquals(
      emptyList(),
      HostData.ideRecentProjects(
        mapOf("IntelliJIdea2026.3" to recentProjectsXml("/work/R&amp;D", "1")),
        "/Users/dev",
        listOf(idea),
        setOf("/work/R&amp;D"),
      ),
    )
  }
}
