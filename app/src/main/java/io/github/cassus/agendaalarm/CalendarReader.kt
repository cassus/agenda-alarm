package io.github.cassus.agendaalarm

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String)

/** Reads calendars and upcoming reminders from the Android calendar provider. */
class CalendarReader(private val context: Context) {

    fun calendars(): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.VISIBLE} = 1", null, Calendars._ID,
        )?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "")
        }
        return out
    }

    /** All reminders of event instances that begin within [from, to]. */
    fun reminders(from: Long, to: Long): List<ReminderCandidate> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        data class Inst(
            val eventId: Long, val calendarId: Long, val title: String, val description: String?,
            val begin: Long, val allDay: Boolean, val status: Int, val organizer: Boolean,
        )
        val instances = mutableListOf<Inst>()
        context.contentResolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.DESCRIPTION,
                Instances.BEGIN, Instances.ALL_DAY, Instances.SELF_ATTENDEE_STATUS, Instances.IS_ORGANIZER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                var begin = c.getLong(4)
                // All-day instances are stored at UTC midnight; alarms count from local midnight.
                if (allDay) begin -= TimeZone.getDefault().getOffset(begin)
                instances += Inst(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3), begin, allDay,
                    c.getInt(6), c.getString(7) == "1",
                )
            }
        }
        if (instances.isEmpty()) return emptyList()

        val remindersByEvent = mutableMapOf<Long, MutableList<Pair<Int, Int>>>()
        val ids = instances.map { it.eventId }.distinct().joinToString(",")
        context.contentResolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} IN ($ids)", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                remindersByEvent.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1) to c.getInt(2)
            }
        }

        return instances.flatMap { i ->
            remindersByEvent[i.eventId].orEmpty().map { (minutes, method) ->
                // Default reminder (-1) means the calendar's default; treat as 10 minutes like the stock app.
                val m = if (minutes < 0) 10 else minutes
                ReminderCandidate(i.eventId, i.calendarId, i.title, i.description, i.begin, i.allDay, m, method, i.status, i.organizer)
            }
        }
    }
}
