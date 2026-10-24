package io.github.cassus.agendaalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Alert(val title: String, val begin: Long)

/**
 * Rings for the alarms that are due: sound, vibration and the full-screen notification.
 * It runs as a foreground service, so the sound starts even when Android shows only a heads-up
 * notification instead of [AlarmActivity] (phone unlocked and in use).
 * A new alarm while one rings joins the list; dismiss and snooze act on all of them.
 */
class AlertService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var savedVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopAll()
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 5)
                state.value.forEach { Scheduler.snooze(this, it.title, it.begin, minutes) }
                stopAll()
            }
            else -> {
                val alert = Alert(intent?.getStringExtra(Scheduler.EXTRA_TITLE) ?: "", intent?.getLongExtra(Scheduler.EXTRA_BEGIN, 0) ?: 0)
                val first = state.value.isEmpty()
                state.value += alert
                startForeground(NOTIFICATION_ID, notification(alert), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                val settings = Settings(this)
                if (first) startAlert(settings)
                handler.removeCallbacks(autoStop)
                handler.postDelayed(autoStop, settings.autoStopSeconds * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    private val autoStop = Runnable { stopAll() }

    /** Ringer mode decides: silent = screen only, vibrate = + vibration, normal = + sound. */
    private fun startAlert(settings: Settings) {
        val audio = getSystemService(AudioManager::class.java)
        // In DND, getRingerMode() reports silent; the global setting keeps the user's ringer switch.
        val mode = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.MODE_RINGER, audio.ringerMode)
        Log.i(TAG, "Alert start: ringerMode=$mode (audio=${audio.ringerMode})")
        if (mode != AudioManager.RINGER_MODE_SILENT && settings.vibrate) {
            vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            settings.volumePercent?.let { pct ->
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, Settings.volumeIndex(pct, max), 0) }
            }
            val uri = settings.ringtone?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(this@AlertService, uri)
                    isLooping = true
                    setVolume(0f, 0f)
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "Alarm sound failed for $uri", it) }.getOrNull()
            fadeIn(settings.fadeInSeconds)
        }
    }

    private fun fadeIn(seconds: Int) {
        val steps = 30
        val stepMs = (seconds * 1000L / steps).coerceAtLeast(1)
        for (i in 1..steps) {
            handler.postDelayed({
                val v = i.toFloat() / steps
                runCatching { player?.setVolume(v, v) }
            }, i * stepMs)
        }
    }

    private fun stopAll() {
        state.value = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        // Puts the system alarm volume back after a custom-volume alarm.
        savedVolume?.let { v ->
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        }
        state.value = emptyList()
        super.onDestroy()
    }

    private fun notification(alert: Alert): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // The service plays the sound itself.
            },
        )
        val full = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getService(
            this, 1, Intent(this, AlertService::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alert.title)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AgendaAlarm"
        private const val CHANNEL = "alarm"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISMISS = "dismiss"
        private const val ACTION_SNOOZE = "snooze"
        private const val EXTRA_MINUTES = "minutes"

        private val state = MutableStateFlow<List<Alert>>(emptyList())

        /** The alarms ringing now; empty when the service is stopped. */
        val alerts: StateFlow<List<Alert>> = state

        /** [openScreen]: also open [AlarmActivity], for alarms started from the foreground (test button). */
        fun start(context: Context, title: String, begin: Long, openScreen: Boolean = false) {
            val intent = Intent(context, AlertService::class.java)
                .putExtra(Scheduler.EXTRA_TITLE, title).putExtra(Scheduler.EXTRA_BEGIN, begin)
            context.startForegroundService(intent)
            if (openScreen) context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun dismiss(context: Context) =
            context.startService(Intent(context, AlertService::class.java).setAction(ACTION_DISMISS))

        fun snooze(context: Context, minutes: Int) =
            context.startService(Intent(context, AlertService::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_MINUTES, minutes))
    }
}
