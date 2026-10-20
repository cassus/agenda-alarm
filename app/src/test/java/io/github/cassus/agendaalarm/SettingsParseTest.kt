package io.github.cassus.agendaalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsParseTest {
    @Test fun snoozeParsesAndSorts() {
        assertEquals(listOf(5, 10, 15), Settings.parseSnooze("5, 10,15"))
        assertEquals(listOf(3, 30), Settings.parseSnooze(" 30 , 3 "))
        assertEquals(listOf(1), Settings.parseSnooze("1,"))
    }

    @Test fun snoozeRejectsBadInput() {
        assertNull(Settings.parseSnooze(""))
        assertNull(Settings.parseSnooze("5, x"))
        assertNull(Settings.parseSnooze("0"))
        assertNull(Settings.parseSnooze("1441"))
        assertNull(Settings.parseSnooze("5,5"))
        assertNull(Settings.parseSnooze("1,2,3,4,5"))
    }

    @Test fun secondsInRange() {
        assertEquals(90, Settings.parseSeconds(" 90 ", 0..600))
        assertNull(Settings.parseSeconds("601", 0..600))
        assertNull(Settings.parseSeconds("1.5", 0..600))
    }

    @Test fun volumeIndexScalesAndNeverSilent() {
        assertEquals(7, Settings.volumeIndex(100, 7))
        assertEquals(4, Settings.volumeIndex(50, 7))
        assertEquals(1, Settings.volumeIndex(0, 7))
        assertEquals(7, Settings.volumeIndex(150, 7))
    }
}
