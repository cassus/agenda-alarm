package io.github.cassus.agendaalarm

/** One reminder of one event instance, as read from the calendar provider. */
data class ReminderCandidate(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val begin: Long,
    val allDay: Boolean,
    val minutes: Int,
    val method: Int,
    val selfAttendeeStatus: Int,
    val isOrganizer: Boolean,
) {
    val alarmTime: Long get() = begin - minutes * 60_000L
    val key: String get() = "$eventId@$begin-$minutes"
}

enum class Kind { EVENT, TODO }

data class CalendarRule(val events: Boolean = true, val todos: Boolean = false)

data class FilterSettings(
    /** Calendars allowed to fire, with what kind of item. Missing = not selected. */
    val calendars: Map<Long, CalendarRule> = emptyMap(),
    val skipAllDay: Boolean = true,
    val accepted: Boolean = true,
    val tentative: Boolean = true,
    val declined: Boolean = false,
    val notReplied: Boolean = true,
    val owned: Boolean = true,
)

object AlarmFilter {
    // Values of CalendarContract.Reminders.METHOD_ALERT and CalendarContract.Attendees.ATTENDEE_STATUS_*.
    const val METHOD_ALERT = 1
    const val STATUS_NONE = 0
    const val STATUS_ACCEPTED = 1
    const val STATUS_DECLINED = 2
    const val STATUS_INVITED = 3
    const val STATUS_TENTATIVE = 4

    /** Google Tasks synced into the calendar carry this link in the description. */
    const val TASKS_MARKER = "https://tasks.google.com/task/"

    fun kindOf(description: String?): Kind =
        if (description?.contains(TASKS_MARKER) == true) Kind.TODO else Kind.EVENT

    fun shouldFire(c: ReminderCandidate, s: FilterSettings): Boolean {
        val rule = s.calendars[c.calendarId] ?: return false
        if (c.method != METHOD_ALERT) return false
        if (c.allDay && s.skipAllDay) return false
        val kindOk = when (kindOf(c.description)) {
            Kind.EVENT -> rule.events
            Kind.TODO -> rule.todos
        }
        if (!kindOk) return false
        return attendanceOk(c, s)
    }

    private fun attendanceOk(c: ReminderCandidate, s: FilterSettings): Boolean {
        if (c.isOrganizer) return s.owned
        return when (c.selfAttendeeStatus) {
            STATUS_ACCEPTED -> s.accepted
            STATUS_TENTATIVE -> s.tentative
            STATUS_DECLINED -> s.declined
            STATUS_INVITED, STATUS_NONE -> s.notReplied
            else -> s.notReplied
        }
    }

    /** The earliest reminder that should fire strictly after [now] and isn't already fired. */
    fun next(all: List<ReminderCandidate>, s: FilterSettings, now: Long, fired: Set<String> = emptySet()) =
        all.filter { it.alarmTime > now && it.key !in fired && shouldFire(it, s) }
            .minByOrNull { it.alarmTime }
}
