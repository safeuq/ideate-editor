package io.github.safeuq.ideate.client.session

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class JetBrainsWorkspaceTest {
  private val projectId = "6d9b208e-6ee1-4ef7-90f7-ad353e610b57:3M6vsUcRFtWmIj7kcJysZPs4ZkY"
  private val projectFolder = "projects/90838c858145b5a10d1aa545ebcbdb58dc32ba48"
  private val xtool = "/data/data/com.termux/files/home/Files/Projects/Xflow/xtool"

  private fun newRoot() = Files.createTempDirectory("ideate-jetbrains").toFile()

  private fun xml(file: File): Element =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

  private fun Element.all(name: String) =
    (0 until getElementsByTagName(name).length).map {
      getElementsByTagName(name).item(it) as Element
    }

  /** A root holding the real sample, as JetBrains Client left it for [projectId]. */
  private fun rootWithSample(): File {
    val root = newRoot()
    File(root, "$projectFolder/.idea").mkdirs()
    File(root, "$projectFolder/.idea/workspace.xml")
      .writeText(
        """<project version="4"><component name="ProjectId" id="3M6vsZ6tIYOZU24M22V6piMwnxX" /></project>""",
      )
    File(root, "workspace").mkdirs()
    File(root, "workspace/3M6vsZ6tIYOZU24M22V6piMwnxX.xml")
      .writeText(javaClass.getResource("/jetbrains-workspace-sample.xml")!!.readText())
    return root
  }

  @Test
  fun readsTheTabsJetBrainsClientSaved() {
    val workspace = JetBrainsWorkspace(emptyList(), rootWithSample())
    val tabs = checkNotNull(workspace.read(projectId))
    assertEquals(5, tabs.size)
    assertEquals("$xtool/README.md", tabs[0].path)
    assertEquals(listOf(3), tabs.indices.filter { tabs[it].current })
    assertEquals(
      WorkspaceCaret(TextPosition(15, 0), TextPosition(15, 0), TextPosition(15, 0)),
      tabs[4].caret,
    )
    assertEquals(273, tabs[3].relativeCaretPosition)
  }

  @Test
  fun writingKeepsEverythingButTheChangedCaret() {
    val root = rootWithSample()
    val workspace = JetBrainsWorkspace(emptyList(), root)
    val tabs = checkNotNull(workspace.read(projectId))
    val moved = WorkspaceCaret(TextPosition(16, 4), TextPosition(16, 4), TextPosition(18, 0))
    workspace.save(
      projectId,
      tabs.mapIndexed { index, tab -> if (index == 4) tab.copy(caret = moved) else tab },
    )
    val project = xml(File(root, "workspace/3M6vsZ6tIYOZU24M22V6piMwnxX.xml"))
    // Other components stay.
    assertEquals(
      listOf("BookmarksManager", "FileEditorManager", "IdeDocumentHistory"),
      project.all("component").map { it.getAttribute("name") },
    )
    assertEquals("{}", project.all("component").last().textContent)
    // The leaf's cached tab info is left for JetBrains to work out again.
    assertFalse(project.all("leaf").single().hasAttribute("ideFingerprint"))
    assertEquals("-1", project.all("leaf").single().getAttribute("SIDE_TABS_SIZE_LIMIT_KEY"))
    // What we don't understand stays on its tab.
    val entries = project.all("entry")
    assertEquals("48451", entries[0].getAttribute("file-id"))
    assertEquals("1794673134749", entries[0].getAttribute("managing-fs"))
    assertEquals("file", entries[0].getAttribute("protocol"))
    assertEquals(
      "split-provider[text-editor;markdown-preview-editor]",
      entries[0].all("provider").single().getAttribute("editor-type-id"),
    )
    assertEquals("SHOW_PREVIEW", entries[0].all("state").single().getAttribute("split_layout"))
    assertEquals("true", entries[3].all("caret").single().getAttribute("lean-forward"))
    assertTrue(
      project.all("tab")[2].textContent.contains(""""tabTitle":"ContentionResolution.java""""),
    )
    // The changed caret is written as IntelliJ does: attributes that are 0 are left out.
    val caret = entries[4].all("caret").single()
    assertEquals(
      mapOf(
        "line" to "16",
        "column" to "4",
        "selection-start-line" to "16",
        "selection-start-column" to "4",
        "selection-end-line" to "18",
      ),
      (0 until caret.attributes.length).associate {
        caret.attributes.item(it).let { a -> a.nodeName to a.nodeValue }
      },
    )
    assertEquals("210", entries[4].all("state").single().getAttribute("relative-caret-position"))
    assertEquals(tabs.map { it.path }, checkNotNull(workspace.read(projectId)).map { it.path })
  }

  @Test
  fun keepsTheScrollUnlessGivenOne() {
    val workspace = JetBrainsWorkspace(emptyList(), rootWithSample())
    val tabs = checkNotNull(workspace.read(projectId))
    // Unknown (null) keeps the stored 273; a reported value replaces it.
    workspace.save(projectId, tabs.map { it.copy(relativeCaretPosition = null) })
    assertEquals(273, checkNotNull(workspace.read(projectId))[3].relativeCaretPosition)
    workspace.save(
      projectId,
      tabs.mapIndexed { index, tab ->
        if (index == 3) tab.copy(relativeCaretPosition = 306) else tab
      },
    )
    assertEquals(306, checkNotNull(workspace.read(projectId))[3].relativeCaretPosition)
  }

  @Test
  fun anchorIsTheSelectionEndThatIsNotTheCaret() {
    val start = TextPosition(2, 1)
    val end = TextPosition(4, 0)
    // The caret at the end of the selection: the anchor is its start, and the other way round.
    assertEquals(start, WorkspaceCaret(end, start, end).anchorPosition())
    assertEquals(end, WorkspaceCaret(start, start, end).anchorPosition())
    // No selection: the anchor is the caret.
    assertEquals(start, WorkspaceCaret(start, start, start).anchorPosition())
  }

  @Test
  fun createsTheProjectFolderAndItsId() {
    val root = newRoot()
    val workspace = JetBrainsWorkspace(emptyList(), root)
    assertEquals(null, workspace.read(projectId))
    workspace.save(projectId, listOf(WorkspaceTab("/p/Main.java", current = true)))
    val id =
      xml(File(root, "$projectFolder/.idea/workspace.xml"))
        .all("component")
        .single { it.getAttribute("name") == "ProjectId" }
        .getAttribute("id")
    assertEquals(27, id.length)
    assertTrue(File(root, "workspace/$id.xml").isFile)
    // The next save finds the same id.
    workspace.save(projectId, emptyList())
    assertEquals(listOf("$id.xml"), File(root, "workspace").list()!!.toList())
    assertEquals(emptyList(), workspace.read(projectId))
  }

  @Test
  fun writesATabItHasNoEntryForPlainly() {
    val root = rootWithSample()
    val workspace = JetBrainsWorkspace(emptyList(), root)
    val backendOpened =
      WorkspaceTab(
        "/p/search/Hello.java",
        current = true,
        caret = WorkspaceCaret(TextPosition(2, 5), TextPosition(2, 5), TextPosition(2, 5)),
      )
    workspace.save(
      projectId,
      checkNotNull(workspace.read(projectId)).map { it.copy(current = false) } + backendOpened,
    )
    val project = xml(File(root, "workspace/3M6vsZ6tIYOZU24M22V6piMwnxX.xml"))
    val file = project.all("file").last()
    assertEquals("true", file.getAttribute("current-in-tab"))
    // The earlier current tab is no longer current.
    assertFalse(project.all("file")[3].hasAttribute("current-in-tab"))
    val entry = file.all("entry").single()
    assertEquals("cwm:///p/search/Hello.java", entry.getAttribute("file"))
    assertEquals(
      listOf("file"),
      (0 until entry.attributes.length).map { entry.attributes.item(it).nodeName },
    )
    assertEquals("""{"tabTitle":"Hello.java"}""", file.all("tab").single().textContent)
    assertEquals(backendOpened, checkNotNull(workspace.read(projectId)).last())
  }

  @Test
  fun picksTheProductFolderThatHasTheProject() {
    val without = newRoot().also { File(it, "projects/other").mkdirs() }
    val with = newRoot().also { File(it, projectFolder).mkdirs() }
    val own = newRoot()
    JetBrainsWorkspace(listOf(without, with), own).save(projectId, emptyList())
    assertTrue(File(with, "$projectFolder/.idea/workspace.xml").isFile)
    assertFalse(File(without, projectFolder).exists())
    // No product folder has the project: the first one with any `projects` folder.
    val another = "11111111-1111-1111-1111-111111111111:abc"
    JetBrainsWorkspace(listOf(newRoot(), without, with), own).save(another, emptyList())
    assertEquals(2, File(without, "projects").list()!!.size)
    assertNotNull(own)
  }

  @Test
  fun convertsBetweenOffsetsAndPositions() {
    val text = "ab\ncd\n\nef"
    for (offset in 0..text.length) assertEquals(offset, offsetOf(text, positionOf(text, offset)))
    assertEquals(TextPosition(1, 1), positionOf(text, 4))
    assertEquals(TextPosition(3, 0), positionOf(text, 7))
    // Past the end of a line or of the text.
    assertEquals(5, offsetOf(text, TextPosition(1, 40)))
    assertEquals(text.length, offsetOf(text, TextPosition(40, 0)))
  }
}
