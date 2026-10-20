package io.github.cassus.agendaalarm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

/** The alert settings screen and the custom alarm volume, on an emulator. */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val settings = Settings(context)

    @Before fun setUp() {
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        settings.snoozeMinutes = listOf(5, 10, 15)
        settings.volumePercent = null
        settings.fadeInSeconds = 1
        settings.autoStopSeconds = 180
    }

    @After fun tearDown() {
        settings.volumePercent = null
        settings.snoozeMinutes = listOf(5, 10, 15)
        device.pressHome()
    }

    @Test fun editedSnoozeLengthsShowOnTheAlarm() {
        launchMain()
        scrollTo("Test alarm now")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").text("5, 10, 15")), 5_000)
        assertNotNull(field)
        field.click()
        field.text = "3, 7"
        assertTrue(device.wait(Until.hasObject(By.text("3, 7")), 2_000))
        assertEquals(listOf(3, 7), settings.snoozeMinutes)

        // Invalid text is flagged and not saved.
        device.findObject(By.clazz("android.widget.EditText").text("3, 7")).text = "3, x"
        device.pressBack() // the keyboard hides the error line
        assertTrue(device.wait(Until.hasObject(By.textContains("Invalid")), 2_000))
        assertEquals(listOf(3, 7), settings.snoozeMinutes)
        device.findObject(By.clazz("android.widget.EditText").text("3, x")).text = "3, 7"

        launchMain() // fresh screen: no keyboard in the way, values read back from storage
        device.waitForIdle()
        // The old screen can still be on show for a moment; find the button again if it went stale.
        for (i in 1..3) if (runCatching { scrollTo("Test alarm now").click() }.isSuccess) break
        assertNotNull(device.wait(Until.findObject(By.text("3m")), 5_000))
        assertNotNull(device.findObject(By.text("7m")))
        assertNull(device.findObject(By.text("5m")))
        dismissAlarm()
    }

    @Test fun customVolumeIsSetDuringAlarmAndRestoredAfter() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        settings.volumePercent = 100

        AlertService.start(context, "Volume test", System.currentTimeMillis(), openScreen = true)
        assertNotNull(device.wait(Until.findObject(By.text("Volume test")), 5_000))
        assertEquals(max, audio.getStreamVolume(AudioManager.STREAM_ALARM))

        dismissAlarm()
        assertTrue(device.wait(Until.gone(By.text("Volume test")), 5_000))
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test fun systemVolumeIsLeftAlone() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        AlertService.start(context, "System volume test", System.currentTimeMillis(), openScreen = true)
        assertNotNull(device.wait(Until.findObject(By.text("System volume test")), 5_000))
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        dismissAlarm()
    }

    @Test fun autoStopClosesTheAlarm() {
        settings.autoStopSeconds = 10
        AlertService.start(context, "Auto-stop test", System.currentTimeMillis(), openScreen = true)
        assertNotNull(device.wait(Until.findObject(By.text("Auto-stop test")), 5_000))
        assertTrue(device.wait(Until.gone(By.text("Auto-stop test")), 20_000))
    }

    @Test fun alarmRingsWithoutTheScreen() {
        // Phone in use: Android shows only a heads-up notification, but the alarm still rings.
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        settings.volumePercent = 100
        AlertService.start(context, "Heads-up test", System.currentTimeMillis())
        waitFor { AlertService.alerts.value.isNotEmpty() && audio.getStreamVolume(AudioManager.STREAM_ALARM) == max }
        AlertService.dismiss(context)
        waitFor { AlertService.alerts.value.isEmpty() && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 1 }
    }

    @Test fun secondAlarmJoinsTheFirstAndDismissClosesBoth() {
        AlertService.start(context, "First alarm", System.currentTimeMillis(), openScreen = true)
        assertNotNull(device.wait(Until.findObject(By.text("First alarm")), 5_000))
        AlertService.start(context, "Second alarm", System.currentTimeMillis())
        assertNotNull(device.wait(Until.findObject(By.text("Second alarm")), 5_000))
        assertNotNull(device.findObject(By.text("First alarm")))
        dismissAlarm()
        assertTrue(device.wait(Until.gone(By.desc("Dismiss")), 5_000))
        assertTrue(AlertService.alerts.value.isEmpty())
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(100)
        }
    }

    private fun launchMain() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun scrollTo(text: String) = run {
        repeat(5) {
            device.findObject(By.text(text))?.let { return@run it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        device.findObject(By.text(text))!!
    }

    private fun dismissAlarm() {
        val r = device.findObject(By.desc("Dismiss")).visibleBounds
        device.swipe(r.left + 40, r.centerY(), r.right - 10, r.centerY(), 30)
    }
}
