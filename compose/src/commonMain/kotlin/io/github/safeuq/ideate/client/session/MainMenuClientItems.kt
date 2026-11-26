package io.github.safeuq.ideate.client.session

import io.github.safeuq.ideate.client.ui.MenuEntry
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.SubMenu

/**
 * The main menu items JetBrains Client adds from its own actions, which the backend's `MainMenu`
 * doesn't send (docs/plans/main-menu-client-items.tsv, 129 rows, read from client 263.7307.200).
 * Ideate shows them greyed, where the client has them.
 */
internal object MainMenuClientItems {
  enum class Kind {
    /** A greyed item. */
    ITEM,
    /** A greyed submenu row with no entries. */
    MENU,
  }

  /**
   * One row of the table. [menu]: the top-level menu, e.g. `Edit`; empty for a whole top-level menu
   * (`Window`). [parent]: the submenu inside it, e.g. `Find`, or empty. [after]: the label it
   * follows under the same parent; empty: first.
   */
  data class Row(
    val menu: String,
    val parent: String,
    val label: String,
    val kind: Kind,
    val after: String,
  )

  val ROWS =
    listOf(
      Row("File", "New", "Package", Kind.ITEM, "Scratch File"),
      Row("File", "New", "Data Source", Kind.MENU, "Markdown File"),
      Row("File", "New", "Data Source from Cloud Provider", Kind.MENU, "Data Source"),
      Row("File", "New", "Data Source Templates", Kind.ITEM, "Data Source from Cloud Provider"),
      Row("File", "New", "Workspace Data Source", Kind.ITEM, "Data Source Templates"),
      Row("File", "New", "Data Source from File/Folder", Kind.ITEM, "Workspace Data Source"),
      Row("File", "New", "Data Source from URL", Kind.ITEM, "Data Source from File/Folder"),
      Row("File", "New", "Data Source in Path", Kind.ITEM, "Data Source from URL"),
      Row("File", "New", "DDL Data Source", Kind.ITEM, "Data Source in Path"),
      Row("File", "New", "Driver", Kind.ITEM, "DDL Data Source"),
      Row("File", "", "Close Project", Kind.ITEM, "Recent Projects"),
      Row("File", "", "Plugins…", Kind.ITEM, "Project Structure…"),
      Row("File", "File Properties", "Make file read-only", Kind.ITEM, "Associate with File Type…"),
      Row("File", "", "Cache Recovery", Kind.MENU, "Reload All from Disk"),
      Row("File", "Manage IDE Settings", "Import Settings…", Kind.ITEM, ""),
      Row("File", "Manage IDE Settings", "Export Settings…", Kind.ITEM, "Import Settings…"),
      Row(
        "File",
        "Manage IDE Settings",
        "Restore Default Settings…",
        Kind.ITEM,
        "Backup and Sync…",
      ),
      Row("File", "Export", "Project to Eclipse…", Kind.ITEM, "Files or Selection to HTML…"),
      Row("File", "", "Power Save Mode", Kind.ITEM, "Print…"),
      Row("File", "", "Essential Highlighting", Kind.ITEM, "Power Save Mode"),
      Row("Edit", "", "Search In Selection", Kind.ITEM, "Delete"),
      Row("Edit", "Find", "Find…", Kind.ITEM, ""),
      Row("Edit", "Find", "Replace…", Kind.ITEM, "Find…"),
      Row("Edit", "Find", "Find Next Occurrence", Kind.ITEM, "Replace…"),
      Row("Edit", "Find", "Find Previous Occurrence", Kind.ITEM, "Find Next Occurrence"),
      Row("Edit", "Find", "Select All Occurrences", Kind.ITEM, "Find Previous Occurrence"),
      Row("Edit", "Find", "Add Selection for Next Occurrence", Kind.ITEM, "Select All Occurrences"),
      Row("Edit", "Find", "Unselect Occurrence", Kind.ITEM, "Add Selection for Next Occurrence"),
      Row("Edit", "Find", "Search In Selection", Kind.ITEM, "Unselect Occurrence"),
      Row(
        "Edit",
        "Find",
        "Next Occurrence of the Word at Caret",
        Kind.ITEM,
        "Scroll to Results During Typing",
      ),
      Row(
        "Edit",
        "Find",
        "Previous Occurrence of the Word at Caret",
        Kind.ITEM,
        "Next Occurrence of the Word at Caret",
      ),
      Row("Edit", "Find", "Find in Files…", Kind.ITEM, "Previous Occurrence of the Word at Caret"),
      Row("Edit", "Find", "Replace in Files…", Kind.ITEM, "Find in Files…"),
      Row("Edit", "", "Column Selection Mode", Kind.ITEM, "Find Usages"),
      Row("Edit", "", "Select All", Kind.ITEM, "Column Selection Mode"),
      Row("Edit", "", "Add Carets to Ends of Selected Lines", Kind.ITEM, "Select All"),
      Row("Edit", "Macros", "Play Back Last Macro", Kind.ITEM, ""),
      Row("Edit", "Macros", "Start Macro Recording", Kind.ITEM, "Play Back Last Macro"),
      Row("Edit", "Macros", "Edit Macros", Kind.ITEM, "Start Macro Recording"),
      Row("Edit", "Macros", "Play Saved Macros…", Kind.ITEM, "Edit Macros"),
      Row("Edit", "Bookmarks", "Add Mnemonic Bookmark…", Kind.ITEM, "Toggle Bookmark"),
      Row("Edit", "Bookmarks", "Show Line Bookmarks…", Kind.ITEM, "Next Line Bookmark"),
      Row("View", "", "Tool Windows", Kind.MENU, ""),
      Row("View", "Appearance", "Enter Presentation Mode", Kind.ITEM, ""),
      Row(
        "View",
        "Appearance",
        "Enter Distraction Free Mode",
        Kind.ITEM,
        "Enter Presentation Mode",
      ),
      Row("View", "Appearance", "Enter Full Screen", Kind.ITEM, "Enter Distraction Free Mode"),
      Row("View", "Appearance", "Enter Zen Mode", Kind.ITEM, "Enter Full Screen"),
      Row("View", "Appearance", "Compact Mode", Kind.ITEM, "Enter Zen Mode"),
      Row("View", "Appearance", "Zoom IDE (Current: 100%)…", Kind.ITEM, "Compact Mode"),
      Row("View", "Appearance", "Toolbar", Kind.ITEM, "Presentation Assistant"),
      Row("View", "Appearance", "Tool Window Bars", Kind.ITEM, "Navigation Bar"),
      Row("View", "Appearance", "Status Bar", Kind.ITEM, "Tool Window Bars"),
      Row("View", "", "Quick Documentation", Kind.ITEM, "Quick Type Definition"),
      Row("View", "", "Recent Locations", Kind.ITEM, "Context Info"),
      Row("View", "", "Recent Files", Kind.ITEM, "Recent Changes"),
      Row("View", "", "Recently Changed Files", Kind.ITEM, "Recent Files"),
      Row("View", "", "Quick Switch Scheme…", Kind.ITEM, "Compare with Clipboard"),
      Row("View", "Active Editor", "Show Whitespaces", Kind.ITEM, "Soft-Wrap"),
      Row("View", "Active Editor", "Show Line Numbers", Kind.ITEM, "Show Whitespaces"),
      Row("View", "Active Editor", "Show Gutter Icons", Kind.ITEM, "Show Line Numbers"),
      Row("View", "Active Editor", "Show Indent Guides", Kind.ITEM, "Show Gutter Icons"),
      Row("View", "Active Editor", "Increase Font Size", Kind.ITEM, "Show Breadcrumbs"),
      Row("View", "Active Editor", "Decrease Font Size", Kind.ITEM, "Increase Font Size"),
      Row("View", "", "Increase Font Size in All Editors", Kind.ITEM, "Active Editor"),
      Row(
        "View",
        "",
        "Decrease Font Size in All Editors",
        Kind.ITEM,
        "Increase Font Size in All Editors",
      ),
      Row(
        "View",
        "",
        "Reset Font Size in All Editors",
        Kind.ITEM,
        "Decrease Font Size in All Editors",
      ),
      Row(
        "View",
        "",
        "Highlight Only Current Declaration",
        Kind.ITEM,
        "Reset Font Size in All Editors",
      ),
      Row("View", "Bidi Text Base Direction", "Content-Based", Kind.ITEM, ""),
      Row("View", "Bidi Text Base Direction", "Left-to-Right", Kind.ITEM, "Content-Based"),
      Row("View", "Bidi Text Base Direction", "Right-to-Left", Kind.ITEM, "Left-to-Right"),
      Row("Navigate", "", "Text…", Kind.ITEM, "Symbol…"),
      Row("Navigate", "", "Line:Column…", Kind.ITEM, "Text…"),
      Row("Navigate", "", "Next Highlighted Error", Kind.ITEM, "Line:Column…"),
      Row("Navigate", "", "Previous Highlighted Error", Kind.ITEM, "Next Highlighted Error"),
      Row("Navigate", "Navigate in File", "Next Change", Kind.ITEM, "Custom Folding…"),
      Row("Navigate", "Navigate in File", "Previous Change", Kind.ITEM, "Next Change"),
      Row("Navigate", "", "Select In…", Kind.ITEM, "Navigate in File"),
      Row("Navigate", "", "Jump to Navigation Bar", Kind.ITEM, "Select In…"),
      Row("Navigate", "", "File Structure", Kind.ITEM, "Related Symbol…"),
      Row("Code", "Folding", "Expand", Kind.ITEM, ""),
      Row("Code", "Folding", "Expand Recursively", Kind.ITEM, "Expand"),
      Row("Code", "Folding", "Expand All", Kind.ITEM, "Expand Recursively"),
      Row("Code", "Folding", "Collapse", Kind.ITEM, "Expand All"),
      Row("Code", "Folding", "Collapse Recursively", Kind.ITEM, "Collapse"),
      Row("Code", "Folding", "Collapse All", Kind.ITEM, "Collapse Recursively"),
      Row("Code", "Folding", "Toggle Folding", Kind.ITEM, "Collapse Doc Comments"),
      Row(
        "Run",
        "",
        "Profile 'Main.java' with 'IntelliJ Profiler'",
        Kind.MENU,
        "Run 'Main.java' with Coverage",
      ),
      Row("Run", "", "Stop Background Processes…", Kind.ITEM, "Stop"),
      Row("Run", "Debugging Actions", "Compile and Reload Modified Files", Kind.ITEM, ""),
      Row("Run", "Debugging Actions", "Step Over", Kind.ITEM, "Compile and Reload Modified Files"),
      Row("Run", "Debugging Actions", "Force Step Over", Kind.ITEM, "Throw Exception"),
      Row("Run", "Debugging Actions", "Step Into", Kind.ITEM, "Force Step Over"),
      Row("Run", "Debugging Actions", "Force Step Into", Kind.ITEM, "Step Into"),
      Row("Run", "Debugging Actions", "Smart Step Into", Kind.ITEM, "Force Step Into"),
      Row("Run", "Debugging Actions", "Step Out", Kind.ITEM, "Smart Step Into"),
      Row("Run", "Debugging Actions", "Run to Cursor", Kind.ITEM, "Step Out"),
      Row("Run", "Debugging Actions", "Force Run to Cursor", Kind.ITEM, "Run to Cursor"),
      Row("Run", "Debugging Actions", "Reset Frame", Kind.ITEM, "Force Run to Cursor"),
      Row("Run", "Debugging Actions", "Resume Program", Kind.ITEM, "Reset Frame"),
      Row("Run", "Debugging Actions", "Evaluate Expression…", Kind.ITEM, "Resume Program"),
      Row("Run", "Debugging Actions", "Show Execution Point", Kind.ITEM, "Evaluate Expression…"),
      Row("Run", "Debugging Actions", "Get Thread Dump", Kind.ITEM, "Show Execution Point"),
      Row("Run", "Toggle Breakpoint", "Line Breakpoint", Kind.ITEM, ""),
      Row("Run", "Toggle Breakpoint", "Temporary Line Breakpoint", Kind.ITEM, "Line Breakpoint"),
      Row(
        "Run",
        "Toggle Breakpoint",
        "Toggle Breakpoint Enabled",
        Kind.ITEM,
        "Temporary Line Breakpoint",
      ),
      Row("Run", "", "View Breakpoints…", Kind.ITEM, "Toggle Breakpoint"),
      Row("Run", "", "Import Tests from File…", Kind.ITEM, "Test History"),
      Row("Tools", "", "Create Command Line Launcher…", Kind.ITEM, "Generate Javadoc…"),
      Row("Tools", "", "Internal Actions", Kind.MENU, "Services"),
      Row("Tools", "", "Show Plugin Loading State", Kind.ITEM, "XML Actions"),
      Row("Tools", "", "Ask Agent with Context", Kind.ITEM, "Show Plugin Loading State"),
      Row("Tools", "", "Ask Agent Depending on Context", Kind.ITEM, "Ask Agent with Context"),
      Row("Tools", "Qodana", "Log in to Qodana…", Kind.ITEM, "Add Qodana to CI Pipeline"),
      Row("", "", "Window", Kind.MENU, "VCS"),
      Row("Help", "", "Find Action…", Kind.ITEM, ""),
      Row("Help", "", "Help", Kind.ITEM, "Find Action…"),
      Row("Help", "", "What's New in IntelliJ IDEA", Kind.ITEM, "Learn IDE Features"),
      Row("Help", "", "Getting Started", Kind.ITEM, "What's New in IntelliJ IDEA"),
      Row("Help", "", "IntelliJ IDEA on YouTube", Kind.ITEM, "Getting Started"),
      Row("Help", "", "Keyboard Shortcuts PDF", Kind.ITEM, "IntelliJ IDEA on YouTube"),
      Row("Help", "", "Submit a Bug Report…", Kind.ITEM, "Keyboard Shortcuts PDF"),
      Row("Help", "", "Submit Feedback…", Kind.ITEM, "Submit a Bug Report…"),
      Row("Help", "", "Show Log in Finder", Kind.ITEM, "Open Log in Editor"),
      Row("Help", "", "Collect Host and Client Logs", Kind.ITEM, "Show Log in Finder"),
      Row(
        "Help",
        "Diagnostic Tools",
        "Collect Troubleshooting Information",
        Kind.ITEM,
        "Special Files and Folders…",
      ),
      Row(
        "Help",
        "Diagnostic Tools",
        "Choose Trace Scenarios",
        Kind.ITEM,
        "Kotlin CRI Diagnostics",
      ),
      Row(
        "Help",
        "Diagnostic Tools",
        "Show Patch Engine Diagnostics",
        Kind.ITEM,
        "Choose Trace Scenarios",
      ),
      Row("Help", "", "Delete Leftover IDE Directories…", Kind.ITEM, "Edit Custom VM Options…"),
      Row("Help", "", "Register…", Kind.ITEM, "Delete Leftover IDE Directories…"),
    )

  /**
   * [menus] with each of [rows] put in place, disabled: after its [Row.after] label under its
   * parent; when that label isn't there, after the previous row of the table (when it went under
   * the same parent), else at the end. A row whose parent isn't there, or whose label is there
   * already (e.g. Ideate's own Search Everywhere items), is skipped.
   */
  fun merge(menus: List<SubMenu>, rows: List<Row> = ROWS): List<SubMenu> {
    var result = menus
    var previous: Row? = null
    for (row in rows) {
      val previousLabel = previous?.takeIf { it.menu == row.menu && it.parent == row.parent }?.label
      previous = row
      result =
        if (row.menu.isEmpty()) insert(result, row, previousLabel).filterIsInstance<SubMenu>()
        else
          result.map { menu ->
            when {
              menu.label != row.menu -> menu
              row.parent.isEmpty() -> menu.copy(entries = insert(menu.entries, row, previousLabel))
              else ->
                menu.copy(
                  entries =
                    menu.entries.map { entry ->
                      if (entry is SubMenu && entry.label == row.parent)
                        entry.copy(entries = insert(entry.entries, row, previousLabel))
                      else entry
                    },
                )
            }
          }
    }
    return result
  }

  private fun insert(entries: List<MenuEntry>, row: Row, previousLabel: String?): List<MenuEntry> {
    fun indexAfter(label: String?): Int? =
      label
        ?.let { wanted -> entries.indexOfFirst { labelOf(it) == wanted } }
        ?.takeIf { it >= 0 }
        ?.plus(1)
    if (entries.any { labelOf(it) == row.label }) return entries
    val entry =
      when (row.kind) {
        Kind.ITEM -> MenuItem(row.label, enabled = false)
        Kind.MENU -> SubMenu(row.label, emptyList(), enabled = false)
      }
    val at =
      if (row.after.isEmpty()) 0
      else indexAfter(row.after) ?: indexAfter(previousLabel) ?: entries.size
    return entries.take(at) + entry + entries.drop(at)
  }

  private fun labelOf(entry: MenuEntry): String? =
    when (entry) {
      is MenuItem -> entry.label
      is SubMenu -> entry.label
      else -> null
    }
}
