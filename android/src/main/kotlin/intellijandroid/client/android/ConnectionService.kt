package intellijandroid.client.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps IDEate's process able to use the network while the screen is off, by running as a
 * foreground service for as long as a session is open (docs/android-app.md, "Screen off").
 *
 * Without it, Doze cuts an app that is only "top sleeping" (screen off, activity stopped) off the
 * network, even from a port on the same phone: the connection then hears nothing for 15 s and ends
 * (`Nothing received for 15 s`, reproduced on the emulator with `dumpsys deviceidle force-idle`). A
 * UID with a foreground service keeps its network in Doze. Termux keeps its own backend alive the
 * same way.
 */
class ConnectionService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val project = intent?.getStringExtra(PROJECT).orEmpty()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while IDEate is connected to a backend"
      },
    )
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle(
          if (project.isEmpty()) "Connected to a backend" else "Connected to $project",
        )
        .setContentText("Keeps the connection while the screen is off")
        .setContentIntent(open)
        .setOngoing(true)
        .build()
    startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    return START_NOT_STICKY
  }

  companion object {
    private const val CHANNEL = "connection"
    private const val NOTIFICATION = 1
    private const val PROJECT = "project"

    /** Starts the service, or updates its notification with [project]. */
    fun start(context: Context, project: String?) {
      context.startForegroundService(
        Intent(context, ConnectionService::class.java).putExtra(PROJECT, project),
      )
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, ConnectionService::class.java))
    }
  }
}
