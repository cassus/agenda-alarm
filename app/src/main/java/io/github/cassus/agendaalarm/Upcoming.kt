package io.github.cassus.agendaalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateFormat

/**
 * Silent "Upcoming alarm" notifications, one per alarm, shown a set time before it rings.
 * Tapping one opens the event in the calendar app; "Skip" cancels that one alarm in this app only.
 */
object Upcoming {
    private const val CHANNEL = "upcoming"
    private const val NOTIF_ID = 2
    private const val REQ_WAKE = -1
    const val EXTRA_KEY = "key"

    /** Which alarms get a notice now, and when the next one comes into the window (null = none). */
    data class Plan(val show: List<ReminderCandidate>, val nextAt: Long?)

    fun plan(upcoming: List<ReminderCandidate>, now: Long, leadMs: Long): Plan {
        if (leadMs <= 0) return Plan(emptyList(), null)
        val (show, later) = upcoming.partition { it.alarmTime - leadMs <= now }
        return Plan(show, later.minOfOrNull { it.alarmTime - leadMs })
    }

    /** Posts notices for the alarms in the window, removes stale ones, and sets the wake-up for the next. */
    fun update(context: Context, upcoming: List<ReminderCandidate>, now: Long = System.currentTimeMillis()) {
        val plan = plan(upcoming, now, Settings(context).upcomingMinutes * 60_000L)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Upcoming alarms", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val keys = plan.show.map { it.key }.toSet()
        nm.activeNotifications.filter { it.id == NOTIF_ID && it.tag !in keys }.forEach { nm.cancel(it.tag, NOTIF_ID) }
        plan.show.forEach { nm.notify(it.key, NOTIF_ID, build(context, it)) }

        val am = context.getSystemService(AlarmManager::class.java)
        val wake = PendingIntent.getBroadcast(
            context, REQ_WAKE, Intent(context, RescheduleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        when {
            plan.nextAt == null -> am.cancel(wake)
            am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.nextAt, wake)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.nextAt, wake)
        }
    }

    private fun build(context: Context, c: ReminderCandidate): Notification {
        val time = DateFormat.getTimeFormat(context).format(c.alarmTime)
        val open = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.begin)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val req = c.key.hashCode()
        val openPi = PendingIntent.getActivity(context, req, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val skipPi = PendingIntent.getBroadcast(
            context, req, Intent(context, SkipReceiver::class.java).putExtra(EXTRA_KEY, c.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Upcoming alarm · $time")
            .setContentText(c.title.ifBlank { "(no title)" })
            .setWhen(c.alarmTime)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Skip", skipPi).build())
            .build()
    }
}

/** "Skip" on an upcoming notice: this alarm won't ring. The calendar event is not changed. */
class SkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Upcoming.EXTRA_KEY) ?: return
        Settings(context).markFired(key, System.currentTimeMillis())
        Scheduler.reschedule(context)
    }
}
