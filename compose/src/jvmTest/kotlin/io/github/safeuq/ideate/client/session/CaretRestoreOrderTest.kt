package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.DocOffsetAndVirtualModel
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.RdCaret
import com.jetbrains.rd.ide.model.RdCaretId
import com.jetbrains.rd.ide.model.RdCaretState
import com.jetbrains.rd.ide.model.RdCaretStateSnapshot
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdEditorVersion
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileIdOld
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdPatchDocumentVersion
import com.jetbrains.rd.ide.model.RdPatchEditorVersion
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdSelection
import com.jetbrains.rd.ide.model.TextControlLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A reopened editor's first patch carries the text and the caret together, and a listener puts the
 * saved caret back as soon as it hears of it. That move must not count in the patch's own check.
 */
class CaretRestoreOrderTest {
  @Test
  fun aCaretMovedByTheListenerIsNotOutOfStep() {
    val documentId = RdDocumentIdWithFileId(RdFileIdOld("file", "Main.java"))
    val projectId = RdProjectId("project")
    val control =
      KernelAwareTextControlId(
        "editor",
        documentId,
        1,
        TextControlLocation.EditorTab,
        "client",
        projectId,
      )
    val outOfStep = mutableListOf<String>()
    var sentRequests = 0L
    var restored = false
    lateinit var session: EditSession
    session =
      EditSession(
        EditorIdentity(
          documentId,
          control,
          EditorIdentity.textEditorId(
            RdFileEditorCompositeId(documentId.fileId, "client", 1, projectId),
          ),
          projectId,
        ),
        CommandChain(),
        { ++sentRequests },
        changed = {
          if (!restored && session.isReady && session.caretModelVersion >= 0) {
            restored = true
            session.moveCaret(2)
          }
        },
        outOfStep = { outOfStep += it },
      )
    val document = RdDocumentVersion(0, VersionHashes.reset("abc"))
    val editor = RdEditorVersion(0, document.hash, VersionHashes.caret(1, 0, 0, 0))
    val caretId = RdCaretId(true, 1)
    val start = DocOffsetAndVirtualModel(0, 0, false)
    session.backendPatch(
      RdPatch(
        listOf(
          RdResetDocumentChange("abc", documentId, document, RdDocumentVersion(-1, 0), ""),
          RdCaretStateSnapshot(
            false,
            RdCaretState(
              caretId,
              listOf(RdCaret(caretId, start, RdSelection(start, start))),
              document,
            ),
            control,
          ),
        ),
        emptyList(),
        emptyList(),
        listOf(
          RdPatchDocumentVersion(document, "", documentId),
          RdPatchEditorVersion(editor, control),
        ),
      ),
    )
    assertEquals(emptyList(), outOfStep)
    assertEquals(1L, sentRequests, "the saved caret was sent to the backend")
  }
}
