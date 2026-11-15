package com.example.ultrahdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.ultrahdr.ui.ConversionSession
import com.example.ultrahdr.ui.Phase
import com.example.ultrahdr.ui.UiState
import com.example.ultrahdr.ui.summarize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a batch running while the app is in the background. It does no work itself: it shows the
 * batch's progress as a notification, which tells Android the app is busy, and goes away when the
 * batch ends. A batch that finishes while no screen of the app is open leaves a short note behind.
 */
class ConversionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConversionSession.convertJob?.cancel()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Converting", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            startForeground(PROGRESS_ID, progress(ConversionSession.state.value), type)
        } catch (_: Exception) {
            // Android refused (started too late, or not allowed now). The batch still runs while
            // the app is on screen.
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                ConversionSession.state.collect { state ->
                    when (state.phase) {
                        is Phase.Working -> manager.notify(PROGRESS_ID, progress(state))
                        Phase.Preparing -> Unit
                        else -> {
                            if (state.phase is Phase.Finished && ConversionSession.screensShown == 0) {
                                manager.notify(DONE_ID, done(state))
                            }
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 gives media work six hours a day. A batch that long is stopped between photos. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ConversionSession.convertJob?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progress(state: UiState): Notification {
        val total = (state.phase as? Phase.Working)?.total ?: state.photos.size
        val finished = state.results.size
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ConversionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Converting photos")
            .setContentText("${minOf(finished + 1, total.coerceAtLeast(1))} of ${total.coerceAtLeast(1)}")
            .setProgress(total.coerceAtLeast(1), finished, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun done(state: UiState): Notification {
        val stopped = (state.phase as? Phase.Finished)?.stopped == true
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(summarize(state.results.values, state.photos.size, stopped).title)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val CHANNEL = "converting"
        private const val PROGRESS_ID = 1
        const val DONE_ID = 2
        private const val ACTION_STOP = "com.example.ultrahdr.STOP"

        /** Clears the "finished" note once the app is open again. */
        fun clearDoneNote(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(DONE_ID)
        }
    }
}
