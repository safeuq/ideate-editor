package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdFileIdNew
import com.jetbrains.rd.ide.model.RdMergeableUsage
import com.jetbrains.rd.ide.model.RdUsageGroup
import com.jetbrains.rd.ide.model.RdUsagePosition
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageRowTest {
  // As the Mac backend sent it for `twice` in `    return twice(new Other().next());` (line 5).
  private fun usage(column: Int) =
    RdMergeableUsage(
      RdUsagePosition(4, column, 63, 68, 63, 68, ""),
      1,
      "    return twice(new Other().next());",
      true,
      false,
      63,
      68,
      RdDocumentIdWithFileId(RdFileIdNew(1, 1, 0)),
      null,
      listOf(
        RdUsageGroup("com.intellij.usages.impl.rules.MethodGroupingRule", "call()", null, 0, false),
        RdUsageGroup(
          "com.intellij.usages.impl.rules.FileGroupingRule",
          "Caller.java",
          null,
          0,
          false,
        ),
      ),
      null,
      false,
    )

  @Test
  fun aRowHasTheTrimmedLineWithTheUsageMarked() {
    assertEquals(
      UsageRow(1, "Caller.java", 5, "return twice(new Other().next());", 7, 12),
      usageRow(usage(11)),
    )
  }

  @Test
  fun aColumnOutsideTheLineMarksNothing() {
    assertEquals(0 to 0, usageRow(usage(80)).let { it.matchStart to it.matchEnd })
  }

  @Test
  fun theTitleLosesItsHtml() {
    assertEquals(
      "Method twice(int) of demo.Caller",
      plainText(
        "<b>Method</b>&nbsp;<b>twice(int)</b>&nbsp;<font color=\"#73767c\">of demo.Caller</font>",
      ),
    )
  }
}
