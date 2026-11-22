package intellijandroid.client.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import intellijandroid.client.connect.Termux
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs `intellij-backend` in Termux through Termux's RUN_COMMAND service (docs/connections.md,
 * "Termux setup"). Needs `allow-external-apps = true` in `~/.termux/termux.properties` and the
 * runtime permission [PERMISSION], which [requestPermission] asks for (the activity owns the
 * permission launcher).
 */
class AndroidTermux(private val context: Context, private val askPermission: () -> Unit) : Termux {
  override val isInstalled: Boolean
    get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

  override val hasPermission: Boolean
    get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

  override fun requestPermission() = askPermission()

  /**
   * `start` and `stop` go through Termux's `login` as a login shell, so the backend gets the same
   * environment as when started over SSH: `SHELL` from `~/.termux/shell` (the Terminal's shell;
   * without it IntelliJ falls back to Android's `/bin/sh`) and the user's profile. `status` and
   * `link` run directly, because anything a profile prints would end up in the output parsed here.
   */
  override fun run(arguments: List<String>, onResult: (kotlin.Result<Termux.Result>) -> Unit) =
    if (arguments.firstOrNull() == "start" || arguments.firstOrNull() == "stop") {
      run(listOf("-l", "-c", "exec $COMMAND \"$@\"", COMMAND) + arguments, LOGIN, onResult)
    } else {
      run(arguments, COMMAND, onResult)
    }

  /**
   * Runs `<executable> <arguments>` in the background; [onResult] gets its exit code and output on
   * the main thread, or a failure when Termux couldn't run it (e.g. `allow-external-apps` is off).
   */
  private fun run(
    arguments: List<String>,
    executable: String,
    onResult: (kotlin.Result<Termux.Result>) -> Unit,
  ) {
    check(hasPermission) { "The app doesn't have Termux's RUN_COMMAND permission yet" }
    val action = "${context.packageName}.TERMUX_RESULT.${requestCodes.incrementAndGet()}"
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
          context.unregisterReceiver(this)
          val bundle = intent.getBundleExtra("result")
          onResult(
            when {
              bundle == null ->
                kotlin.Result.failure(IllegalStateException("Termux answered without a result"))
              bundle.getInt("err", Activity.RESULT_OK) != Activity.RESULT_OK ->
                kotlin.Result.failure(
                  IllegalStateException(
                    "Termux couldn't run intellij-backend: ${bundle.getString("errmsg")}",
                  ),
                )
              else ->
                kotlin.Result.success(
                  Termux.Result(
                    bundle.getInt("exitCode"),
                    bundle.getString("stdout").orEmpty(),
                    bundle.getString("stderr").orEmpty(),
                  ),
                )
            },
          )
        }
      }
    // Termux sends the PendingIntent as this app, so a receiver that isn't exported still gets it.
    context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
    val resultIntent =
      PendingIntent.getBroadcast(
        context,
        0,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
      )
    val command =
      Intent("com.termux.RUN_COMMAND")
        .setClassName(PACKAGE, "com.termux.app.RunCommandService")
        .putExtra("com.termux.RUN_COMMAND_PATH", executable)
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments.toTypedArray())
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
    runCatching { context.startService(command) }
      .onFailure { failure ->
        context.unregisterReceiver(receiver)
        onResult(kotlin.Result.failure(failure))
      }
  }

  /**
   * The folders directly inside [path], hidden ones included, sorted like IntelliJ's file chooser
   * (ignoring case). This app can't read Termux's private files itself, so Termux's shell lists
   * them.
   */
  override fun listFolders(path: String, onResult: (kotlin.Result<List<Termux.Folder>>) -> Unit) =
    run(listOf("-c", Termux.LIST_FOLDERS, "sh", path), SHELL) { result ->
      onResult(
        result.mapCatching { output ->
          check(output.exitCode == 0) {
            "Can't list $path: ${output.stderr.ifBlank { "exit ${output.exitCode}" }}"
          }
          Termux.parseFolders(output.stdout)
        },
      )
    }

  companion object {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val COMMAND = "/data/data/com.termux/files/usr/bin/intellij-backend"

    private const val SHELL = "/data/data/com.termux/files/usr/bin/sh"
    private const val LOGIN = "/data/data/com.termux/files/usr/bin/login"

    private val requestCodes = AtomicInteger()
  }
}
