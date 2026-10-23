package com.neoai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps NEO alive for hands-free use and shows an honest, user-dismissable
 * indicator. This service does NOT record in the background by itself; the
 * microphone is only opened while the user has an active listening session.
 */
class NeoForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NeoForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEO AI")
            .setContentText("Hands-free mode on. Mic sirf bolne par khulta hai.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL, "NEO background", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "NEO hands-free indicator" }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL = "neo_background"
        const val NOTIF_ID = 41
        const val ACTION_STOP = "com.neoai.STOP_BACKGROUND"

        fun start(context: Context) {
            val intent = Intent(context, NeoForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NeoForegroundService::class.java))
        }
    }
}
