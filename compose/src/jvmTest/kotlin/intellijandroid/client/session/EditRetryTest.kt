package intellijandroid.client.session

import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileIdOld
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.TextControlLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The backend ignores requests sent with a retry timestamp older than its own, so after an
 * `RdRetryResponse` the refused request and every later one must be sent again.
 */
class EditRetryTest {
  private val sent = mutableListOf<Pair<Long, RdRequest>>()
  private val session = run {
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
    val identity =
      EditorIdentity(
        documentId,
        control,
        EditorIdentity.textEditorId(
          RdFileEditorCompositeId(documentId.fileId, "client", 1, projectId),
        ),
        projectId,
      )
    EditSession(
        identity,
        CommandChain(),
        { request -> (sent.size + 1L).also { sent += it to request } },
      ) {}
      .also {
        it.backendPatch(
          RdPatch(
            listOf(
              RdResetDocumentChange(
                text = "",
                documentId = documentId,
                oldVersion = RdDocumentVersion(0, 0),
                newVersion = RdDocumentVersion(1, 0),
                uniqueId = "",
              ),
            ),
            emptyList(),
            emptyList(),
            emptyList(),
          ),
        )
      }
  }

  @Test
  fun aRetrySendsTheRefusedRequestAndEveryLaterOneAgain() {
    session.type('a')
    session.type('b')
    val first = sent.toList()
    session.retry(first.first().first)
    assertEquals(first.map { it.second }, sent.drop(first.size).map { it.second })
    assertEquals("ab", session.text)
  }

  @Test
  fun aSecondRetryRollsBackAndLetsTypingContinue() {
    session.type('a')
    session.retry(1)
    session.retry(sent.size.toLong() - 1)
    assertEquals("", session.text)
    session.type('c')
    assertEquals("c", session.text)
  }
}
