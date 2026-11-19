package intellijandroid.client.session

import com.jetbrains.rd.ide.model.DocOffsetAndVirtualModel
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.RdCaret
import com.jetbrains.rd.ide.model.RdCaretId
import com.jetbrains.rd.ide.model.RdCaretState
import com.jetbrains.rd.ide.model.RdCaretStateChange
import com.jetbrains.rd.ide.model.RdCaretStateSnapshot
import com.jetbrains.rd.ide.model.RdDocumentChange
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdEditorVersion
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileIdOld
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdPatchDocumentVersion
import com.jetbrains.rd.ide.model.RdPatchEditorVersion
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdSelection
import com.jetbrains.rd.ide.model.RdTextModificationKind
import com.jetbrains.rd.ide.model.RdTypeKeyRequest
import com.jetbrains.rd.ide.model.TextControlLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The edit session against a small model of the backend that builds its answers the way the backend
 * does (docs/edit-protocol-backend.md 4 and 7): a caret snapshot before and after each document
 * change, one editor version step per caret item.
 */
class EditRetryTest {
  private val documentId = RdDocumentIdWithFileId(RdFileIdOld("file", "Main.java"))
  private val projectId = RdProjectId("project")
  private val control =
    KernelAwareTextControlId(
      "editor",
      documentId,
      1,
      TextControlLocation.EditorTab,
      "client",
      projectId,
    )
  private val sent = mutableListOf<Pair<Long, RdRequest>>()
  private val outOfStep = mutableListOf<String>()
  private val session =
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
      { request -> (sent.size + 1L).also { sent += it to request } },
      changed = {},
      outOfStep = { outOfStep += it },
    )

  /** The backend's copy of the editor. */
  private var text = ""
  private var document = RdDocumentVersion(0, VersionHashes.reset(""))
  private var editor = RdEditorVersion(0, document.hash, VersionHashes.caret(1, 0, 0, 0))

  init {
    // Opening the file: the text, then the caret.
    session.backendPatch(
      RdPatch(
        listOf(RdResetDocumentChange("", documentId, document, RdDocumentVersion(-1, 0), "")),
        emptyList(),
        emptyList(),
        listOf(RdPatchDocumentVersion(document, "", documentId)),
      ),
    )
    session.backendPatch(
      RdPatch(
        listOf(RdCaretStateSnapshot(false, caretState(0, document), control)),
        emptyList(),
        emptyList(),
        listOf(RdPatchEditorVersion(editor, control)),
      ),
    )
  }

  private fun caretState(offset: Int, version: RdDocumentVersion): RdCaretState {
    val id = RdCaretId(true, 1)
    fun position(at: Int) = DocOffsetAndVirtualModel(at, 0, false)
    return RdCaretState(
      id,
      listOf(RdCaret(id, position(offset), RdSelection(position(offset), position(offset)))),
      version,
    )
  }

  /** The backend inserts [inserted] at [offset] and leaves the caret at [caretAfter]. */
  private fun insert(offset: Int, inserted: String, caretBefore: Int, caretAfter: Int): RdPatch {
    val documentBefore = document
    val editorBefore = editor
    val documentAfter =
      RdDocumentVersion(
        document.value + 1,
        VersionHashes.change(document.hash, offset, offset, text.length, "", inserted),
      )
    val change =
      RdDocumentChange(
        offset,
        offset,
        inserted,
        "",
        RdTextModificationKind.NotSpecified,
        text.length,
        null,
        false,
        documentId,
        documentAfter,
        documentBefore,
        "",
      )
    text = text.substring(0, offset) + inserted + text.substring(offset)
    document = documentAfter
    editor =
      RdEditorVersion(
        editorBefore.caretModelVersion + 2,
        documentAfter.hash,
        VersionHashes.caret(1, caretAfter, caretAfter, caretAfter),
      )
    return RdPatch(
      listOf(
        RdCaretStateSnapshot(true, caretState(caretBefore, documentBefore), control),
        change,
        RdCaretStateSnapshot(true, caretState(caretAfter, documentAfter), control),
      ),
      emptyList(),
      listOf(
        RdPatchEditorVersion(editorBefore, control),
        RdPatchDocumentVersion(documentBefore, "", documentId),
      ),
      listOf(
        RdPatchEditorVersion(editor, control),
        RdPatchDocumentVersion(documentAfter, "", documentId),
      ),
    )
  }

  /** The backend's answer to a transparent marker: no change. */
  private val marker = RdPatch(emptyList(), emptyList(), emptyList(), emptyList())

  private fun typed(id: Long) = (sent.first { it.first == id }.second as RdTypeKeyRequest).char

  @Test
  fun onlyCaretMovesTheBackendMakesByItselfAreCounted() {
    // The caret snapshot that comes with the opened file is not a move.
    assertEquals(0, session.backendCaretMoves)
    // Navigation: the backend moves the caret, e.g. to a symbol chosen in Search Everywhere.
    session.backendPatch(
      RdPatch(
        listOf(RdCaretStateChange(caretState(0, document), caretState(0, document), control)),
        emptyList(),
        emptyList(),
        listOf(RdPatchEditorVersion(editor, control)),
      ),
    )
    assertEquals(1, session.backendCaretMoves)
    // The caret in an answer to typing is not.
    session.type('a')
    session.response(1, insert(0, "a", 0, 1))
    assertEquals(1, session.backendCaretMoves)
  }

  @Test
  fun answersLikeTheGuessOnlyConfirmIt() {
    session.type('a')
    session.response(1, insert(0, "a", 0, 1))
    session.response(2, marker)
    assertEquals("a", session.text)
    assertTrue(outOfStep.isEmpty())
  }

  @Test
  fun aDifferentAnswerIsTakenAndLaterTypingRunsAgainOnIt() {
    session.type('(')
    session.type('a')
    // The backend closed the parenthesis.
    session.response(1, insert(0, "()", 0, 1))
    assertEquals("(a)", session.text)
    assertEquals(2, session.caret)
    session.response(2, marker)
    session.response(3, insert(1, "a", 1, 2))
    session.response(4, marker)
    assertEquals("(a)", session.text)
    assertTrue(outOfStep.isEmpty())
  }

  @Test
  fun anEarlierAnswerDoesntTakeOurVersionsBack() {
    session.type('x')
    session.type('y')
    session.response(1, insert(0, "x", 0, 1))
    session.response(2, marker)
    // Typed after that answer: must build on the versions after 'y', not after 'x'.
    session.type('(')
    session.response(3, insert(1, "y", 1, 2))
    session.response(4, marker)
    // The backend closed the parenthesis: applied with checks against our versions after 'y'.
    session.response(5, insert(2, "()", 2, 3))
    assertEquals("xy()", session.text)
    assertTrue(outOfStep.isEmpty())
  }

  @Test
  fun typingGoesOnWhileAnswersArePending() {
    session.type('(')
    session.response(1, insert(0, "()", 0, 1))
    session.type('b')
    assertEquals("(b)", session.text)
  }

  @Test
  fun aRetrySendsEveryPendingRequestAgain() {
    session.type('a')
    session.type('b')
    val first = sent.toList()
    session.retry(1)
    assertEquals(first.map { it.second }, sent.drop(first.size).map { it.second })
    assertEquals("ab", session.text)
  }

  @Test
  fun aSecondRetryDropsThatEditAndSendsTheRestAgain() {
    session.type('a')
    session.type('b')
    session.retry(1)
    sent.clear()
    session.retry(5)
    assertEquals("b", session.text)
    // The marker after 'a', then 'b' typed again at 0 with its marker.
    assertEquals(3, sent.size)
    assertEquals('b', typed(sent[1].first))
  }

  @Test
  fun aRefusedEditIsUndoneAndTheRestKept() {
    session.type('a')
    session.type('b')
    session.failure(1)
    assertEquals("b", session.text)
  }

  @Test
  fun versionsThatDontMatchTheBackendsCallForItsState() {
    session.type('a')
    document = RdDocumentVersion(0, 42)
    // An answer unlike the guess is applied with checks (one like it only gives its versions).
    session.response(1, insert(0, "ab", 0, 1))
    assertEquals(1, outOfStep.size)
  }

  @Test
  fun whileTakingTheBackendsStateTypingWaits() {
    session.type('a')
    session.dropPending()
    assertEquals("", session.text)
    session.type('x')
    assertEquals("", session.text)
    session.recovered(null)
    session.type('c')
    assertEquals("c", session.text)
  }
}
