package io.github.cassus.agendaalarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Keeps exactly one exact alarm set: the next reminder that passes the filter.
 * Snoozes are separate alarms with their own request code.
 */
object Scheduler {
    private const val TAG = "AgendaAlarm"
    private const val LOOKAHEAD = 8 * 24 * 60 * 60 * 1000L
    private const val GRACE = 5 * 60 * 1000L
    private const val REQ_NEXT = 1
    private const val REQ_SNOOZE = 2

    const val EXTRA_TITLE = "title"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_KEY = "key"

    fun upcoming(context: Context, now: Long = System.currentTimeMillis()): List<ReminderCandidate> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val settings = Settings(context)
        val filter = settings.filter
        val fired = settings.fired(now)
        // A reminder that came due in the last few minutes and hasn't fired still counts: two reminders at
        // the same time, or an alarm delivered late. Its alarm is then set in the past and fires at once.
        return CalendarReader(context).reminders(now - GRACE, now + LOOKAHEAD)
            .filter { it.alarmTime > now - GRACE && it.key !in fired && AlarmFilter.shouldFire(it, filter) }
            .sortedBy { it.alarmTime }
    }

    fun reschedule(context: Context) {
        CalendarChangeJob.schedule(context)
        val next = upcoming(context).firstOrNull()
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, REQ_NEXT, next)
        if (next == null) {
            am.cancel(pi)
            Log.i(TAG, "no upcoming alarm")
            return
        }
        setExact(context, next.alarmTime, pi)
        Log.i(TAG, "next alarm '${next.title}' at ${next.alarmTime}")
    }

    fun snooze(context: Context, title: String, begin: Long, minutes: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BEGIN, begin)
        // One slot per alarm, so snoozing a second alarm doesn't replace the first snooze.
        val req = REQ_SNOOZE + "$title@$begin".hashCode().mod(1_000_000)
        val pi = PendingIntent.getBroadcast(context, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setExact(context, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    private fun pending(context: Context, req: Int, c: ReminderCandidate?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        if (c != null) intent.putExtra(EXTRA_TITLE, c.title).putExtra(EXTRA_BEGIN, c.begin).putExtra(EXTRA_KEY, c.key)
        return PendingIntent.getBroadcast(context, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setExact(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        // setAlarmClock: exact, survives Doze, and shows the alarm icon in the status bar.
        if (am.canScheduleExactAlarms()) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            Log.w(TAG, "exact alarms not allowed; using inexact")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
