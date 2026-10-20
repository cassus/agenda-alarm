package io.github.cassus.agendaalarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Full-screen alarm over the lock screen: the ringing alarms, snooze buttons, swipe to dismiss.
 * [AlertService] does the ringing; this screen closes when the service has no alarms left.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val snoozeMinutes = Settings(this).snoozeMinutes

        setContent {
            val alerts by AlertService.alerts.collectAsState()
            // The screen can open before the service has added the alarm, so close only once it was shown.
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(alerts.isEmpty()) { if (alerts.isNotEmpty()) shown = true else if (shown) finish() }
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlarmScreen(
                    alerts = alerts,
                    snoozeMinutes = snoozeMinutes,
                    onSnooze = { m -> AlertService.snooze(this, m) },
                    onDismiss = { AlertService.dismiss(this) },
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    alerts: List<Alert>,
    snoozeMinutes: List<Int>,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = DateFormat.getTimeInstance(DateFormat.SHORT)
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        alerts.forEach { a ->
            if (a.begin > 0) Text(format.format(Date(a.begin)), color = Color.White, fontSize = if (alerts.size > 1) 40.sp else 64.sp)
            Spacer(Modifier.height(12.dp))
            Text(a.title, color = Color.White, fontSize = 28.sp)
            Spacer(Modifier.height(24.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            snoozeMinutes.forEach { m ->
                Button(onClick = { onSnooze(m) }) { Text("${m}m") }
            }
        }
        Text("Snooze", color = Color.Gray)
        Spacer(Modifier.height(48.dp))
        SwipeToDismiss(onDismiss)
    }
}

@Composable
private fun SwipeToDismiss(onDismiss: () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 200.dp.toPx() }
    // The drag sits on the track, not the knob: a moving knob would shrink its own drag deltas.
    Box(
        Modifier.fillMaxWidth().height(64.dp)
            .background(Color.DarkGray, RoundedCornerShape(32.dp))
            .semantics { contentDescription = "Dismiss" }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> offset = (offset + d).coerceAtLeast(0f) },
                onDragStopped = { if (offset > threshold) onDismiss() else offset = 0f },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("swipe to dismiss ▶", color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .padding(4.dp)
                .height(56.dp)
                .fillMaxWidth(0.2f)
                .background(Color.White, RoundedCornerShape(28.dp)),
        )
    }
}
