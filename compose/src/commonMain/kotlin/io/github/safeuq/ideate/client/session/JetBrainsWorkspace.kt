package io.github.safeuq.ideate.client.session

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A place in a document, both counted from 0: offset 4 of `"ab\ncd\nef"` is line 1, column 1. */
data class TextPosition(val line: Int, val column: Int)

/** A text editor's caret and selection, as IntelliJ keeps them in `<caret>`. */
data class WorkspaceCaret(
  val position: TextPosition,
  val selectionStart: TextPosition,
  val selectionEnd: TextPosition,
)

/**
 * One editor tab, as IntelliJ's `FileEntry` has it. [path] is the backend's path, e.g.
 * `/Users/me/smoke/src/demo/Main.java`, kept as `cwm:///Users/me/smoke/src/demo/Main.java`. [caret]
 * null: unknown, so a stored tab keeps its stored caret.
 */
data class WorkspaceTab(
  val path: String,
  val current: Boolean = false,
  val caret: WorkspaceCaret? = null,
  val pinned: Boolean = false,
  val preview: Boolean = false,
  /** The caret's pixels below the editor's top; only written for a tab with no stored entry. */
  val relativeCaretPosition: Int = 0,
)

/**
 * The editor tabs in JetBrains Client's own files, so Ideate and an installed JetBrains Client
 * share them for the same backend project. A project's folder is `<root>/projects/<sha1 of the
 * project id>` (`ThinClientProjectUtil.createProjectDir`), e.g. `projects/90838c85…ba48`; its
 * `.idea/workspace.xml` names the project (`<component name="ProjectId" id="3M6vsZ6t…">`), and the
 * tabs are the `FileEditorManager` component of `<root>/workspace/<that id>.xml`. Only the first
 * `<leaf>` of that component is read and rewritten; every other component and leaf stays as it is.
 *
 * The root is the first of [productRoots] (e.g. `~/Library/Application Support/JetBrains/
 * IntelliJIdea2026.3`, newest first) that has the project's folder, else the first that has any
 * `projects` folder, else [ownRoot].
 */
class JetBrainsWorkspace(private val productRoots: List<File>, private val ownRoot: File) {
  /** [projectId]'s tabs in order; null when no tabs were ever saved for it. */
  fun read(projectId: String): List<WorkspaceTab>? {
    val root = rootFor(projectId)
    val workspaceFile =
      workspaceFile(root, workspaceId(root, projectId, create = false) ?: return null)
    if (!workspaceFile.isFile) return null
    val component = parse(workspaceFile).documentElement.component(EDITOR_MANAGER) ?: return null
    val leaf = firstLeaf(component) ?: return null
    return leaf.children("file").mapNotNull(::readTab)
  }

  /** Makes [tabs] the project's tabs, creating its folder and workspace file when missing. */
  fun save(projectId: String, tabs: List<WorkspaceTab>) {
    val root = rootFor(projectId)
    val workspaceFile =
      workspaceFile(root, checkNotNull(workspaceId(root, projectId, create = true)))
    val document = if (workspaceFile.isFile) parse(workspaceFile) else newDocument()
    val component =
      document.documentElement.component(EDITOR_MANAGER)
        ?: document.documentElement.append(document.createElement("component")).apply {
          setAttribute("name", EDITOR_MANAGER)
        }
    val leaf = firstLeaf(component) ?: component.append(document.createElement("leaf"))
    val files = leaf.children("file")
    val stored = files.associateBy { it.children("entry").firstOrNull()?.getAttribute("file") }
    // IntelliJ works out the fingerprint again for a leaf it finds without one.
    leaf.removeAttribute("ideFingerprint")
    files.forEach(leaf::removeChild)
    for (tab in tabs) leaf.append(updated(document, stored[URL_PREFIX + tab.path], tab))
    write(workspaceFile, document)
  }

  private fun rootFor(projectId: String): File {
    val hash = sha1(projectId)
    return productRoots.firstOrNull { File(it, "projects/$hash").isDirectory }
      ?: productRoots.firstOrNull { File(it, "projects").isDirectory }
      ?: ownRoot
  }

  /** The project's id in `.idea/workspace.xml`; [create] writes it when there is none. */
  private fun workspaceId(root: File, projectId: String, create: Boolean): String? {
    val file = File(root, "projects/${sha1(projectId)}/.idea/workspace.xml")
    val document = if (file.isFile) parse(file) else newDocument()
    document.documentElement
      .component(PROJECT_ID)
      ?.getAttribute("id")
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return it
      }
    if (!create) return null
    val id = "3" + String(CharArray(26) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })
    document.documentElement.append(document.createElement("component")).apply {
      setAttribute("name", PROJECT_ID)
      setAttribute("id", id)
    }
    write(file, document)
    return id
  }

  private fun workspaceFile(root: File, id: String) = File(root, "workspace/$id.xml")

  private fun readTab(file: Element): WorkspaceTab? {
    val entry = file.children("entry").firstOrNull() ?: return null
    val url = entry.getAttribute("file")
    if (!url.startsWith(URL_PREFIX)) return null
    val state = textProvider(entry)?.children("state")?.firstOrNull()
    val caret = state?.children("caret")?.firstOrNull()
    return WorkspaceTab(
      url.removePrefix(URL_PREFIX),
      current = file.getAttribute("current-in-tab") == "true",
      caret =
        caret?.let {
          WorkspaceCaret(
            TextPosition(it.int("line"), it.int("column")),
            TextPosition(it.int("selection-start-line"), it.int("selection-start-column")),
            TextPosition(it.int("selection-end-line"), it.int("selection-end-column")),
          )
        },
      pinned = file.getAttribute("pinned") == "true",
      preview = entry.getAttribute("preview") == "true",
      relativeCaretPosition = state?.int("relative-caret-position") ?: 0,
    )
  }

  /** [stored] with [tab]'s current flag and caret put in; a new `<file>` when there is none. */
  private fun updated(document: Document, stored: Element?, tab: WorkspaceTab): Element {
    val file = stored ?: newFile(document, tab)
    if (tab.current) file.setAttribute("current-in-tab", "true")
    else file.removeAttribute("current-in-tab")
    val provider = textProvider(file.children("entry").first())
    val caret = tab.caret
    if (provider != null && caret != null) {
      val state =
        provider.children("state").firstOrNull()
          ?: provider.append(document.createElement("state")).also { state ->
            if (tab.relativeCaretPosition != 0) {
              state.setAttribute("relative-caret-position", "${tab.relativeCaretPosition}")
            }
          }
      val element =
        state.children("caret").firstOrNull() ?: state.append(document.createElement("caret"))
      // IntelliJ leaves out an attribute that is 0.
      element.putInt("line", caret.position.line)
      element.putInt("column", caret.position.column)
      element.putInt("selection-start-line", caret.selectionStart.line)
      element.putInt("selection-start-column", caret.selectionStart.column)
      element.putInt("selection-end-line", caret.selectionEnd.line)
      element.putInt("selection-end-column", caret.selectionEnd.column)
    }
    return file
  }

  private fun newFile(document: Document, tab: WorkspaceTab): Element {
    val file = document.createElement("file")
    if (tab.pinned) file.setAttribute("pinned", "true")
    val entry = file.append(document.createElement("entry"))
    entry.setAttribute("file", URL_PREFIX + tab.path)
    if (tab.preview) entry.setAttribute("preview", "true")
    entry.append(document.createElement("provider")).apply {
      setAttribute("editor-type-id", TEXT_EDITOR)
      setAttribute("selected", "true")
    }
    val title = buildJsonObject { put("tabTitle", tab.path.substringAfterLast('/')) }
    file.append(document.createElement("tab")).append(document.createCDATASection("$title"))
    return file
  }

  /** The entry's selected editor provider, when it is a plain text editor. */
  private fun textProvider(entry: Element): Element? =
    entry
      .children("provider")
      .let { providers ->
        providers.firstOrNull { it.getAttribute("selected") == "true" } ?: providers.firstOrNull()
      }
      ?.takeIf { it.getAttribute("editor-type-id") == TEXT_EDITOR }

  private fun firstLeaf(component: Element) =
    component.getElementsByTagName("leaf").item(0) as? Element

  private fun parse(file: File): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).also {
      // The indenting output would double the old line breaks.
      withoutBlankText(it.documentElement)
    }

  private fun withoutBlankText(element: Element) {
    for (child in element.childNodes.let { nodes -> List(nodes.length) { nodes.item(it) } }) {
      when {
        child is Element -> withoutBlankText(child)
        child.nodeType == Node.TEXT_NODE && child.textContent.isBlank() ->
          element.removeChild(child)
      }
    }
  }

  private fun newDocument(): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().also {
      it.appendChild(it.createElement("project")).let { project ->
        (project as Element).setAttribute("version", "4")
      }
    }

  /** Writes a temporary file beside [file] and moves it over, so a reader never sees half of it. */
  private fun write(file: File, document: Document) {
    file.parentFile.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.outputStream().use { output ->
      TransformerFactory.newInstance()
        .newTransformer()
        .apply {
          setOutputProperty(OutputKeys.INDENT, "yes")
          setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
          setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        .transform(DOMSource(document), StreamResult(output))
    }
    Files.move(
      temporary.toPath(),
      file.toPath(),
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
  }

  private fun Element.children(name: String): List<Element> =
    List(childNodes.length) { childNodes.item(it) }
      .filterIsInstance<Element>()
      .filter { it.tagName == name }

  private fun Element.component(name: String) =
    children("component").firstOrNull { it.getAttribute("name") == name }

  private fun <T : Node> Node.append(child: T): T = appendChild(child) as T

  private fun Element.int(name: String) = getAttribute(name).toIntOrNull() ?: 0

  private fun Element.putInt(name: String, value: Int) =
    if (value == 0) removeAttribute(name) else setAttribute(name, "$value")

  private companion object {
    const val EDITOR_MANAGER = "FileEditorManager"
    const val PROJECT_ID = "ProjectId"
    const val TEXT_EDITOR = "text-editor"
    const val URL_PREFIX = "cwm://"
    const val ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val random = SecureRandom()

    fun sha1(text: String) =
      MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }
}

/** [offset] of [text] as a line and column; a tab counts as one column. */
fun positionOf(text: String, offset: Int): TextPosition {
  val clamped = offset.coerceIn(0, text.length)
  val lineStart = text.lastIndexOf('\n', clamped - 1) + 1
  return TextPosition(text.take(clamped).count { it == '\n' }, clamped - lineStart)
}

/** The offset of [position] in [text]; a position past the end of its line or text is clamped. */
fun offsetOf(text: String, position: TextPosition): Int {
  var lineStart = 0
  repeat(position.line) {
    val newline = text.indexOf('\n', lineStart)
    if (newline < 0) return text.length
    lineStart = newline + 1
  }
  val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
  return minOf(lineStart + position.column, lineEnd)
}
