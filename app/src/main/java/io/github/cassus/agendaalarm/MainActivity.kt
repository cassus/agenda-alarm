package io.github.cassus.agendaalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Settings: calendars, filters, permissions, and the list of upcoming alarms. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Scheduler.reschedule(this)
        refresh++
    }

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // The "default" entry comes back as the settings URI; store null so it follows the system default.
            Settings(this).ringtone = uri?.takeIf { it != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }?.toString()
            refresh++
        }
    }

    private fun launchRingtonePicker(current: String?) {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        pickRingtone.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: default),
        )
    }

    private fun ringtoneName(uri: String?): String =
        if (uri == null) "Default alarm sound"
        else runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri)).getTitle(this) }.getOrNull() ?: uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(refresh)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.reschedule(this)
        refresh++
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Composable
    // Takes tick (= refresh) so Compose re-runs it (and the permission checks) after onResume.
    private fun SettingsScreen(tick: Int) {
        val settings = remember { Settings(this) }
        var filter by remember(tick) { mutableStateOf(settings.filter) }
        fun save(f: FilterSettings) {
            filter = f
            settings.filter = f
            Scheduler.reschedule(this)
            refresh++
        }

        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Permissions", fontWeight = FontWeight.Bold)
            key(tick) { PermissionRows() }
            Spacer(Modifier.height(16.dp))

            Text("Calendars  (E = events, T = to-dos)", fontWeight = FontWeight.Bold)
            if (has(Manifest.permission.READ_CALENDAR)) {
                CalendarReader(this@MainActivity).calendars().forEach { cal ->
                    val rule = filter.calendars[cal.id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cal.name, Modifier.weight(1f))
                        Text("E")
                        Checkbox(rule?.events == true, { on -> save(filter.withRule(cal.id, (rule ?: CalendarRule(false, false)).copy(events = on))) })
                        Text("T")
                        Checkbox(rule?.todos == true, { on -> save(filter.withRule(cal.id, (rule ?: CalendarRule(false, false)).copy(todos = on))) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Toggle("Skip all-day events", filter.skipAllDay) { save(filter.copy(skipAllDay = it)) }
            Text("Attendance", fontWeight = FontWeight.Bold)
            Toggle("Accepted", filter.accepted) { save(filter.copy(accepted = it)) }
            Toggle("Tentative", filter.tentative) { save(filter.copy(tentative = it)) }
            Toggle("Declined", filter.declined) { save(filter.copy(declined = it)) }
            Toggle("Not replied", filter.notReplied) { save(filter.copy(notReplied = it)) }
            Toggle("Events I own", filter.owned) { save(filter.copy(owned = it)) }
            Spacer(Modifier.height(16.dp))

            AlertSection(settings)
            Spacer(Modifier.height(16.dp))

            Text("Upcoming alarms", fontWeight = FontWeight.Bold)
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            Scheduler.upcoming(this@MainActivity).take(20).forEach {
                Text("${fmt.format(Date(it.alarmTime))}  ${it.title}")
            }
        }
    }

    /** Ringtone, volume, fade-in, auto-stop, snooze lengths, and a test button. */
    @Composable
    private fun AlertSection(settings: Settings) {
        Text("Alert", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ringtone: ${ringtoneName(settings.ringtone)}", Modifier.weight(1f))
            Button(onClick = { launchRingtonePicker(settings.ringtone) }) { Text("Change") }
        }

        var vibrate by remember(refresh) { mutableStateOf(settings.vibrate) }
        Toggle("Vibrate", vibrate) { vibrate = it; settings.vibrate = it }

        var volume by remember(refresh) { mutableStateOf(settings.volumePercent) }
        Toggle("Use system alarm volume", volume == null) { system ->
            volume = if (system) null else 70
            settings.volumePercent = volume
        }
        volume?.let { pct ->
            Text("Custom volume: $pct%")
            Slider(
                value = pct.toFloat(),
                onValueChange = { volume = it.roundToInt() },
                onValueChangeFinished = { settings.volumePercent = volume },
                valueRange = 0f..100f,
                modifier = Modifier.semantics { contentDescription = "Custom volume" },
            )
        }

        NumberField("Fade-in (seconds)", "${settings.fadeInSeconds}") { t ->
            Settings.parseSeconds(t, 0..600)?.also { settings.fadeInSeconds = it }
        }
        NumberField("Auto-stop (seconds)", "${settings.autoStopSeconds}") { t ->
            Settings.parseSeconds(t, 10..3600)?.also { settings.autoStopSeconds = it }
        }
        NumberField("Snooze minutes (comma-separated)", settings.snoozeMinutes.joinToString(", ")) { t ->
            Settings.parseSnooze(t)?.also { settings.snoozeMinutes = it }
        }
        Button(onClick = {
            AlertService.start(this@MainActivity, "Test alarm", System.currentTimeMillis(), openScreen = true)
        }) { Text("Test alarm now") }
    }

    /** Text field that saves on every valid edit and shows an error while the text is invalid. */
    @Composable
    private fun NumberField(label: String, initial: String, save: (String) -> Any?) {
        var text by remember { mutableStateOf(initial) }
        var valid by remember { mutableStateOf(true) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; valid = save(it) != null },
            label = { Text(label) },
            isError = !valid,
            supportingText = if (valid) null else ({ Text("Invalid, not saved") }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun PermissionRows() {
        val missing = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS).filterNot(::has)
        Status("Calendar + notifications", missing.isEmpty()) { askPermissions.launch(missing.toTypedArray()) }
        Status("Full-screen alerts", getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        Status("Exact alarms", getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        Status("No battery optimization", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun Status(label: String, ok: Boolean, fix: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (ok) "✓ " else "⚠ ") + label, Modifier.weight(1f))
            if (!ok) Button(onClick = fix) { Text("Fix") }
        }
    }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(value, onChange)
        }
    }
}

private fun FilterSettings.withRule(id: Long, rule: CalendarRule) =
    copy(calendars = if (!rule.events && !rule.todos) calendars - id else calendars + (id to rule))
