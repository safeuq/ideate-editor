package intellijandroid.client.session

/**
 * IntelliJ's indent guides, computed in the client: in split mode (263.7307.200) the backend sends
 * no `IndentGuideHighlighterModel`, so JetBrains Client's guides come from the frontend's own pass
 * (docs/split-protocol.md, "Indent guides"). A port of `IndentGuideCalculator.calculate` and
 * `IndentGuides.buildDescriptors` (platform/lang-impl, `codeInsight/daemon/impl/indentGuide`).
 *
 * Differences, all because the client has no lexer or PSI: comments are recognised from the
 * backend's syntax highlighting ([isComment]); a closing brace is `}`, `)` or `]` as the first
 * character of a line (IntelliJ asks the language's brace matcher, and `CodeBlockSupportHandler`
 * for languages like Ruby's `end`); `codeConstructStartLine`, which only feeds the "show the
 * block's start" hint, is not computed.
 */
object IndentGuides {
  /** IntelliJ's default; the client doesn't get the file's code style. */
  const val TAB_SIZE = 4

  fun compute(
    text: String,
    isComment: (Int) -> Boolean,
    tabSize: Int = TAB_SIZE,
  ): List<IndentGuide> {
    if (text.isEmpty()) return emptyList()
    val starts = buildList {
      add(0)
      text.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun lineEnd(line: Int) = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
    fun firstNonWhitespace(line: Int): Int {
      var offset = starts[line]
      while (offset < lineEnd(line) && (text[offset] == ' ' || text[offset] == '\t')) offset++
      return offset
    }

    // Negative: the line is blank or a comment, and its indent (the absolute value) was taken from
    // the lines around it.
    val lineIndents = IntArray(starts.size)
    for (line in lineIndents.indices) {
      var column = 0
      var offset = starts[line]
      while (offset < lineEnd(line)) {
        when (text[offset]) {
          ' ' -> column++
          '\t' -> column = (column / tabSize + 1) * tabSize
          else -> break
        }
        offset++
      }
      lineIndents[line] = if (offset == lineEnd(line) || isComment(offset)) -1 else column
    }

    var topIndent = 0
    var line = 0
    while (line < lineIndents.size) {
      if (lineIndents[line] >= 0) {
        topIndent = lineIndents[line]
        line++
        continue
      }
      val startLine = line
      while (line < lineIndents.size && lineIndents[line] < 0) line++
      val bottomIndent = if (line < lineIndents.size) lineIndents[line] else topIndent
      var indent = minOf(topIndent, bottomIndent)
      // A blank run before a closing brace belongs to the block it closes.
      if (bottomIndent < topIndent && text.getOrNull(firstNonWhitespace(line)) in CLOSING_BRACES) {
        indent = topIndent
      }
      for (blankLine in startLine until line) lineIndents[blankLine] = -minOf(topIndent, indent)
    }

    val guides = mutableListOf<IndentGuide>()
    fun add(level: Int, start: Int, end: Int) {
      var startLine = start
      while (startLine > 0 && lineIndents[startLine] < 0) startLine--
      guides += IndentGuide(level, startLine, end)
    }
    val openLines = ArrayDeque(listOf(0))
    val openIndents = ArrayDeque(listOf(0))
    for (current in 1 until lineIndents.size) {
      val currentIndent = kotlin.math.abs(lineIndents[current])
      while (openIndents.isNotEmpty() && currentIndent <= openIndents.last()) {
        val level = openIndents.removeLast()
        val startLine = openLines.removeLast()
        // Only a block whose lines aren't all at the opening line's indent gets a guide.
        if (
          level > 0 && (startLine until current).any { level != kotlin.math.abs(lineIndents[it]) }
        ) {
          add(level, startLine, current)
        }
      }
      val previousIndent = kotlin.math.abs(lineIndents[current - 1])
      if (currentIndent - previousIndent > 1) {
        openLines.addLast(current - 1)
        openIndents.addLast(previousIndent)
      }
    }
    while (openIndents.isNotEmpty()) {
      val level = openIndents.removeLast()
      val startLine = openLines.removeLast()
      if (level > 0) add(level, startLine, lineIndents.size)
    }
    return guides
  }

  private val CLOSING_BRACES = setOf('}', ')', ']')
}
