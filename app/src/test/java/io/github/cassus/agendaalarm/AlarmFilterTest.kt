package io.github.cassus.agendaalarm

import io.github.cassus.agendaalarm.AlarmFilter.STATUS_ACCEPTED
import io.github.cassus.agendaalarm.AlarmFilter.STATUS_DECLINED
import io.github.cassus.agendaalarm.AlarmFilter.STATUS_INVITED
import io.github.cassus.agendaalarm.AlarmFilter.STATUS_TENTATIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmFilterTest {
    private val todoDescription =
        "Changes made to the title, description, or attachments will not be saved. " +
            "To make edits, please go to: https://tasks.google.com/task/abc123"

    private fun candidate(
        calendarId: Long = 7,
        description: String? = null,
        allDay: Boolean = false,
        method: Int = AlarmFilter.METHOD_ALERT,
        status: Int = STATUS_ACCEPTED,
        organizer: Boolean = false,
        begin: Long = 10_000_000,
        minutes: Int = 10,
        eventId: Long = 1,
    ) = ReminderCandidate(eventId, calendarId, "t", description, begin, allDay, minutes, method, status, organizer)

    private val eventsOnly = FilterSettings(calendars = mapOf(7L to CalendarRule(events = true, todos = false)))

    @Test fun todoDetectedByTasksLink() {
        assertEquals(Kind.TODO, AlarmFilter.kindOf(todoDescription))
        assertEquals(Kind.EVENT, AlarmFilter.kindOf("Dentist, bring card"))
        assertEquals(Kind.EVENT, AlarmFilter.kindOf(null))
    }

    @Test fun eventFiresTodoSkipped() {
        assertTrue(AlarmFilter.shouldFire(candidate(), eventsOnly))
        assertFalse(AlarmFilter.shouldFire(candidate(description = todoDescription), eventsOnly))
    }

    @Test fun todoFiresWhenEnabled() {
        val s = FilterSettings(calendars = mapOf(7L to CalendarRule(events = false, todos = true)))
        assertTrue(AlarmFilter.shouldFire(candidate(description = todoDescription), s))
        assertFalse(AlarmFilter.shouldFire(candidate(), s))
    }

    @Test fun unselectedCalendarNeverFires() {
        assertFalse(AlarmFilter.shouldFire(candidate(calendarId = 3), eventsOnly))
    }

    @Test fun emailRemindersIgnored() {
        assertFalse(AlarmFilter.shouldFire(candidate(method = 2), eventsOnly))
    }

    @Test fun allDayOption() {
        assertFalse(AlarmFilter.shouldFire(candidate(allDay = true), eventsOnly))
        assertTrue(AlarmFilter.shouldFire(candidate(allDay = true), eventsOnly.copy(skipAllDay = false)))
    }

    @Test fun attendanceFilter() {
        assertFalse(AlarmFilter.shouldFire(candidate(status = STATUS_DECLINED), eventsOnly))
        assertTrue(AlarmFilter.shouldFire(candidate(status = STATUS_TENTATIVE), eventsOnly))
        assertTrue(AlarmFilter.shouldFire(candidate(status = STATUS_INVITED), eventsOnly))
        assertFalse(AlarmFilter.shouldFire(candidate(status = STATUS_INVITED), eventsOnly.copy(notReplied = false)))
        assertFalse(AlarmFilter.shouldFire(candidate(organizer = true), eventsOnly.copy(owned = false)))
    }

    @Test fun nextPicksEarliestFutureUnfired() {
        val a = candidate(eventId = 1, begin = 5_000_000)
        val b = candidate(eventId = 2, begin = 3_000_000)
        val past = candidate(eventId = 3, begin = 100_000)
        assertEquals(b, AlarmFilter.next(listOf(a, b, past), eventsOnly, now = 1_000_000))
        assertEquals(a, AlarmFilter.next(listOf(a, b, past), eventsOnly, now = 1_000_000, fired = setOf(b.key)))
        assertNull(AlarmFilter.next(listOf(past), eventsOnly, now = 1_000_000))
    }
}
