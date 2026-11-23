package io.github.safeuq.ideate.client.connect

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderListingTest {
  private fun folder(
    name: String,
    hasFolders: Boolean = false,
    hasHiddenFolders: Boolean = false,
  ) = Termux.Folder(name, isProject = false, hasFolders, hasHiddenFolders)

  @Test
  fun emptyFolderHasNoArrowBeforeItIsExpanded() {
    assertEquals(false, hasChildFolders(null, folder("empty"), showHidden = false))
    assertEquals(false, hasChildFolders(null, folder("empty"), showHidden = true))
  }

  @Test
  fun folderWithSubFoldersHasAnArrowBeforeItIsExpanded() {
    assertEquals(true, hasChildFolders(null, folder("src", hasFolders = true), showHidden = false))
  }

  @Test
  fun hiddenSubFoldersCountOnlyWhenHiddenFilesAreShown() {
    val project = folder("xtool", hasHiddenFolders = true) // only .idea inside
    assertEquals(false, hasChildFolders(null, project, showHidden = false))
    assertEquals(true, hasChildFolders(null, project, showHidden = true))
  }

  @Test
  fun loadedListingWinsOverTheParentsListing() {
    // The folder changed on disk after its parent was listed.
    val listed = folder("build", hasFolders = true)
    assertEquals(false, hasChildFolders(emptyList(), listed, showHidden = false))
    assertEquals(true, hasChildFolders(listOf(folder("out")), folder("build"), showHidden = false))
  }

  @Test
  fun rootKeepsItsArrowUntilLoaded() {
    assertEquals(true, hasChildFolders(null, null, showHidden = false))
  }

  @Test
  fun parsesTheListingAndSortsIgnoringCase() {
    val folders = Termux.parseFolders("folder/00/b\nproject/11/A b\nfolder/10/.config\n")
    assertEquals(
      listOf(
        Termux.Folder(".config", isProject = false, hasFolders = true, hasHiddenFolders = false),
        Termux.Folder("A b", isProject = true, hasFolders = true, hasHiddenFolders = true),
        Termux.Folder("b", isProject = false, hasFolders = false, hasHiddenFolders = false),
      ),
      folders,
    )
  }

  @Test
  fun rejectsAListingInTheOldFormat() {
    assertFailsWith<IllegalArgumentException> { Termux.parseFolders("folder/xtool\n") }
  }

  /** Runs the real script with the system `sh`, as Termux runs it with its own. */
  @Test
  fun scriptReportsSubFoldersOfEachFolder() {
    val home = Files.createTempDirectory("folder-listing")
    try {
      home.resolve("empty").createDirectories()
      home.resolve("files-only").createDirectories().resolve("a.txt").createFile()
      home.resolve("dirs/inner").createDirectories()
      home.resolve("hidden-only/.cache").createDirectories()
      home.resolve("xtool/.idea").createDirectories()
      home.resolve("xtool/src").createDirectories()
      home.resolve("with space/sub").createDirectories()
      home.resolve("plain-file").createFile()

      assertEquals(
        listOf(
          "folder/00/empty",
          "folder/00/files-only",
          "folder/01/hidden-only",
          "folder/10/dirs",
          "folder/10/with space",
          "project/11/xtool",
        ),
        runScript(home).lines().filter { it.isNotEmpty() }.sorted(),
      )
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun runScript(folder: Path): String {
    val process =
      ProcessBuilder("/bin/sh", "-c", Termux.LIST_FOLDERS, "sh", folder.toString()).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
    return output
  }
}
