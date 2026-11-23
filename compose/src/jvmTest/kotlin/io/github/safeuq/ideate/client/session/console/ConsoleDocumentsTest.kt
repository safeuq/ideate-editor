package io.github.safeuq.ideate.client.session.console

import com.jetbrains.rd.ide.model.RdAbstractDocumentId
import com.jetbrains.rd.ide.model.RdDocumentChange
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdTextModificationKind
import io.github.safeuq.ideate.client.session.VersionHashes
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleDocumentsTest {
  private val documentId = RdAbstractDocumentId("ed73181b-f882-4ed2-bd73-83e4c4f74437")
  private val outOfStep = mutableListOf<String>()
  private val consoles = ConsoleDocuments { outOfStep += it }
  private var version = RdDocumentVersion(0, VersionHashes.reset(""))
  private var text = ""

  init {
    consoles.register(documentId, "g9ojv29tmj8vgr2tv7gf")
    consoles.apply(
      patch(RdResetDocumentChange("", documentId, version, RdDocumentVersion(-1, 0), "")),
    )
  }

  private fun patch(change: com.jetbrains.rd.ide.model.RdPatchItem) =
    RdPatch(listOf(change), emptyList(), emptyList(), emptyList())

  /** What the backend sends when the program prints [inserted] at the end of the console. */
  private fun append(inserted: String, oldVersion: RdDocumentVersion = version): RdPatch {
    val newVersion =
      RdDocumentVersion(
        oldVersion.value + 1,
        VersionHashes.change(oldVersion.hash, text.length, text.length, text.length, "", inserted),
      )
    text += inserted
    version = newVersion
    return patch(
      RdDocumentChange(
        text.length - inserted.length,
        text.length - inserted.length,
        inserted,
        "",
        RdTextModificationKind.NotSpecified,
        text.length - inserted.length,
        null,
        false,
        documentId,
        newVersion,
        oldVersion,
        "",
      ),
    )
  }

  @Test
  fun everyChangeAfterTheResetIsApplied() {
    consoles.apply(append("java -classpath … demo.Main\n"))
    consoles.apply(append("just a simple greeting\n"))
    assertEquals("java -classpath … demo.Main\njust a simple greeting\n", consoles.text(documentId))
    assertEquals(emptyList(), outOfStep)
  }

  @Test
  fun aChangeWithTheWrongOldVersionIsReportedAndNotApplied() {
    consoles.apply(append("first\n"))
    // A change that never reaches us: the next one then builds on a version we don't have.
    append("second\n")
    consoles.apply(append("third\n"))
    assertEquals("first\n", consoles.text(documentId))
    assertEquals(1, outOfStep.size)
  }
}
