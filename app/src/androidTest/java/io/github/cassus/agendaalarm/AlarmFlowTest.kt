package io.github.cassus.agendaalarm

import android.app.AlarmManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

/**
 * End-to-end on an emulator: a local test calendar with an event and a to-do,
 * the app schedules only the event, and the alarm shows full-screen over the lock screen.
 */
@RunWith(AndroidJUnit4::class)
class AlarmFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var calendarId = -1L

    @Before fun setUp() {
        val pkg = context.packageName
        listOf(
            "pm grant $pkg android.permission.READ_CALENDAR",
            "pm grant $pkg android.permission.WRITE_CALENDAR",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            "appops set $pkg USE_FULL_SCREEN_INTENT allow",
        ).forEach { device.executeShellCommand(it) }

        calendarId = createLocalCalendar()
        Settings(context).filter = FilterSettings(calendars = mapOf(calendarId to CalendarRule(events = true, todos = false)))
        Settings(context).fadeInSeconds = 1
    }

    @After fun tearDown() {
        context.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI), "${Calendars._ID} = ?", arrayOf("$calendarId"))
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test fun todoIsSkippedEventIsScheduled() {
        val begin = System.currentTimeMillis() + 60 * 60_000L
        insertEvent("Dentist", null, begin, minutes = 10)
        insertEvent("friss kenyer", "To make edits, please go to: https://tasks.google.com/task/xyz", begin, minutes = 10)

        val upcoming = Scheduler.upcoming(context).filter { it.calendarId == calendarId }
        assertEquals(listOf("Dentist"), upcoming.map { it.title })

        Scheduler.reschedule(context)
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull(next)
        assertEquals(begin - 10 * 60_000L, next!!.triggerTime)
    }

    @Test fun emailReminderIsIgnored() {
        insertEvent("Email only", null, System.currentTimeMillis() + 60 * 60_000L, minutes = 10, method = Reminders.METHOD_EMAIL)
        assertTrue(Scheduler.upcoming(context).none { it.calendarId == calendarId })
    }

    @Test fun alarmShowsFullScreenOverLockScreenAndSwipeDismisses() {
        // Reminder 1 minute before an event 80 s away → alarm in ~20 s.
        val begin = System.currentTimeMillis() + 80_000L
        insertEvent("Test alarm event", null, begin, minutes = 1)
        Scheduler.reschedule(context)

        device.sleep() // screen off → lock screen

        val title = device.wait(Until.findObject(By.text("Test alarm event")), 60_000)
        assertNotNull("alarm screen did not appear", title)
        assertTrue(device.isScreenOn)

        val handle = device.findObject(By.desc("Dismiss"))
        assertNotNull(handle)
        val r = handle.visibleBounds
        device.swipe(r.left + 40, r.centerY(), r.right - 10, r.centerY(), 30)

        assertTrue(device.wait(Until.gone(By.text("Test alarm event")), 5_000))
        // It must not fire again for the same reminder.
        assertNull(Scheduler.upcoming(context).firstOrNull { it.title == "Test alarm event" })
    }

    private fun asSyncAdapter(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun createLocalCalendar(): Long {
        context.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI), "${Calendars.ACCOUNT_NAME} = ?", arrayOf(ACCOUNT))
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "AA Test")
            put(Calendars.CALENDAR_DISPLAY_NAME, "AA Test")
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        return ContentUris.parseId(context.contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), v)!!)
    }

    private fun insertEvent(title: String, description: String?, begin: Long, minutes: Int, method: Int = Reminders.METHOD_ALERT) {
        val ev = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
            put(Events.DTSTART, begin)
            put(Events.DTEND, begin + 30 * 60_000L)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(Events.HAS_ALARM, 1)
        }
        val id = ContentUris.parseId(context.contentResolver.insert(Events.CONTENT_URI, ev)!!)
        context.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id)
            put(Reminders.MINUTES, minutes)
            put(Reminders.METHOD, method)
        })
    }

    companion object {
        private const val ACCOUNT = "agenda-alarm-test"
    }
}
