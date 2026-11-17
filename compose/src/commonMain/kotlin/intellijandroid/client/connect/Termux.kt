package intellijandroid.client.connect

/**
 * Runs `intellij-backend` in Termux on this phone (docs/connections.md in
 * safeuq/intellij-backend-android, "Termux setup"). The Android app implements it with Termux's
 * RUN_COMMAND service; the desktop has none.
 */
interface Termux {
  data class Result(val exitCode: Int, val stdout: String, val stderr: String)

  /** A folder in Termux's file system; [isProject] when it holds an `.idea` folder. */
  data class Folder(val name: String, val isProject: Boolean)

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
  }
}

/** One stored string: a shared preference on Android, a file on the desktop. */
interface StoredText {
  fun read(): String?

  fun write(text: String)
}
