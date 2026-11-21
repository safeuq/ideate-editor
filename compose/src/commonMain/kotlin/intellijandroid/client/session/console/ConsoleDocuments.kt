package intellijandroid.client.session.console

import com.jetbrains.rd.ide.model.RdDocumentChange
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdResetDocumentChange

/**
 * The text of the backend's consoles (Run, Build Output, …), the client side of IntelliJ's
 * `FrontendConsoleViewImpl`. A console's document has no file (`RdAbstractDocumentId`), so it gets
 * no edit session; its text is only ever changed by the backend, through patches:
 * `RdResetDocumentChange(text = "", newVersion = (0, 127))`, then one `RdDocumentChange(offset =
 * 120, newText = "just a simple greeting\n")` for each piece the program prints.
 */
class ConsoleDocuments(
  /** Called with a reason when a patch does not follow the version we have, e.g. "v3 after v5". */
  private val outOfStep: (String) -> Unit,
) {
  private class Console(val kernelEditorId: String) {
    var text = ""
    var version: RdDocumentVersion? = null
  }

  private val consoles = mutableMapOf<RdDocumentId, Console>()

  fun register(documentId: RdDocumentId, kernelEditorId: String) {
    consoles[documentId] = Console(kernelEditorId)
  }

  fun remove(documentId: RdDocumentId) {
    consoles.remove(documentId)
  }

  operator fun contains(documentId: RdDocumentId) = documentId in consoles

  fun text(documentId: RdDocumentId): String? = consoles[documentId]?.text

  /** The console's id in the backend, e.g. `g9ojv29tmj8vgr2tv7gf`. */
  fun kernelEditorId(documentId: RdDocumentId): String? = consoles[documentId]?.kernelEditorId

  /** Applies the changes of [patch] that belong to a registered console; returns those consoles. */
  fun apply(patch: RdPatch): Set<RdDocumentId> {
    val changed = mutableSetOf<RdDocumentId>()
    for (item in patch.changes) {
      when (item) {
        is RdResetDocumentChange -> {
          val console = consoles[item.documentId] ?: continue
          console.text = item.text
          console.version = item.newVersion
          changed += item.documentId
        }
        is RdDocumentChange -> {
          val console = consoles[item.documentId] ?: continue
          val version = console.version
          if (version != item.oldVersion) {
            outOfStep("v${item.newVersion.value} came after v${version?.value}")
            // ponytail: after this, every later change of this console is dropped too (each one
            // builds on a version we don't have) until the next RdResetDocumentChange, so the text
            // stays stale. Lift by asking the backend for the console's text again.
            continue
          }
          val end = (item.offset + item.oldText.length).coerceAtMost(console.text.length)
          console.text = console.text.replaceRange(item.offset.coerceAtMost(end), end, item.newText)
          console.version = item.newVersion
          changed += item.documentId
        }
        else -> Unit
      }
    }
    return changed
  }
}
