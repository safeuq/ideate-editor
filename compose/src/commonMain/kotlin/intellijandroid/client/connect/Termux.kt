package intellijandroid.client.connect

/**
 * Runs `intellij-backend` in Termux on this phone (docs/connections.md in
 * safeuq/intellij-backend-android, "Termux setup"). The Android app implements it with Termux's
 * RUN_COMMAND service; the desktop has none.
 */
interface Termux {
  data class Result(val exitCode: Int, val stdout: String, val stderr: String)

  /**
   * A folder in Termux's file system; [isProject] when it holds an `.idea` folder. [hasFolders] and
   * [hasHiddenFolders]: whether it holds sub-folders whose names don't or do start with `.`; both
   * true when the shell can't read it, so the chooser keeps its arrow.
   */
  data class Folder(
    val name: String,
    val isProject: Boolean,
    val hasFolders: Boolean,
    val hasHiddenFolders: Boolean,
  )

  val isInstalled: Boolean
  val hasPermission: Boolean

  /**
   * Asks the user for the RUN_COMMAND permission; the app calls [ConnectController.refresh] with
   * the answer.
   */
  fun requestPermission()

  /**
   * Runs `intellij-backend <arguments>`; [onResult] gets its exit code and output on the main
   * thread.
   */
  fun run(arguments: List<String>, onResult: (kotlin.Result<Result>) -> Unit)

  /** The folders directly inside [path], sorted like IntelliJ's file chooser (ignoring case). */
  fun listFolders(path: String, onResult: (kotlin.Result<List<Folder>>) -> Unit)

  companion object {
    /**
     * Termux's home folder; RUN_COMMAND passes arguments without a shell, so `~` isn't expanded.
     */
    const val HOME = "/data/data/com.termux/files/home"

    /** `intellij-backend link` / `status`: exit code when no backend is running. */
    const val EXIT_NOT_RUNNING = 3

    /** `intellij-backend start`: another IntelliJ is using the same settings. */
    const val EXIT_SETTINGS_IN_USE = 4

    /**
     * Shell script for [listFolders]: prints `<kind>/<visible><hidden>/<name>` per sub-folder of
     * `$1`, e.g. `project/11/xtool` or `folder/00/empty`. `<kind>` is `project` (has `.idea`) or
     * `folder`; `<visible>` and `<hidden>` are 1 when the sub-folder itself holds folders whose
     * names don't or do start with `.`. Names can't hold `/`, so the name is everything after the
     * second one.
     */
    const val LIST_FOLDERS =
      """cd "${'$'}1" || exit 1
for entry in * .[!.]* ..?*; do
  [ -d "${'$'}entry" ] || continue
  kind=folder
  [ -d "${'$'}entry/.idea" ] && kind=project
  if [ -r "${'$'}entry" ] && [ -x "${'$'}entry" ]; then
    visible=0
    hidden=0
    for sub in "${'$'}entry"/*; do [ -d "${'$'}sub" ] && visible=1 && break; done
    for sub in "${'$'}entry"/.[!.]* "${'$'}entry"/..?*; do [ -d "${'$'}sub" ] && hidden=1 && break; done
  else
    visible=1
    hidden=1
  fi
  echo "${'$'}kind/${'$'}visible${'$'}hidden/${'$'}entry"
done"""

    /** Parses [LIST_FOLDERS]' output, sorted like IntelliJ's file chooser (ignoring case). */
    fun parseFolders(output: String): List<Folder> =
      output
        .lines()
        .filter { it.isNotEmpty() }
        .map { line ->
          val parts = line.split('/', limit = 3)
          require(parts.size == 3 && parts[1].length == 2) { "Unexpected folder line: $line" }
          val (kind, flags, name) = parts
          Folder(
            name,
            isProject = kind == "project",
            hasFolders = flags[0] == '1',
            hasHiddenFolders = flags[1] == '1',
          )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
  }
}

/** One stored string: a shared preference on Android, a file on the desktop. */
interface StoredText {
  fun read(): String?

  fun write(text: String)
}
