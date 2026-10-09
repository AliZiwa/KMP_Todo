package dev.aliziwa.todo.android

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.platform.DueAlert
import kotlinx.coroutines.delay

/**
 * The sticky due alert. Launched by the full-screen intent on the alert notification, so it
 * appears over the lock screen with the screen turned on, exactly like a clock alarm.
 *
 * It is a thin shell: every button broadcasts to [TaskActionReceiver], which is the same code
 * path the notification buttons use. That way the action still completes if this Activity is
 * killed the instant after the tap.
 */
class DueAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        Log.i(TAG, "alert activity for $taskId")

        setContent {
            MaterialTheme {
                val alerts by AlertBus.alerts.collectAsState()
                val alert = alerts.firstOrNull { it.taskId == taskId } ?: alerts.firstOrNull()

                // Acted on elsewhere (notification button, another device, the main UI): go away.
                LaunchedEffect(alert?.taskId) { if (alert == null) finishAndRemoveTask() }

                if (alert != null) AlertCard(alert) { action, minutes ->
                    sendBroadcast(TaskActionReceiver.intent(this, action, alert.taskId, minutes))
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask to drop the keyguard so the buttons are tappable without unlocking first.
        runCatching {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
    }

    companion object {
        private const val TAG = "TodoAlertUi"
        const val EXTRA_TASK_ID = "taskId"

        fun pendingIntent(context: Context, taskId: String): PendingIntent {
            val intent = Intent(context, DueAlertActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .setData(Uri.parse("todo://alert/$taskId"))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
            return PendingIntent.getActivity(
                context,
                NotificationIds.alert(taskId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

@Composable
private fun AlertCard(alert: DueAlert, onAction: (action: String, minutes: Int) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = when (alert.priority) {
                        2 -> "HIGH PRIORITY"
                        1 -> "MEDIUM PRIORITY"
                        else -> "LOW PRIORITY"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = alert.title.ifBlank { "Task due" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append(formatCountdown(alert.dueAtEpochMillis, now))
                        if (alert.isRecurring) append(" · daily")
                        if (alert.extensionCount > 0) append(" · extended ${alert.extensionCount}x")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { onAction(TaskActionReceiver.ACTION_DONE, 0) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done") }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 15, 30).forEach { minutes ->
                        OutlinedButton(
                            onClick = { onAction(TaskActionReceiver.ACTION_EXTEND, minutes) },
                            modifier = Modifier.weight(1f),
                        ) { Text("+${minutes}m") }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { onAction(TaskActionReceiver.ACTION_DISMISS, 0) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Dismiss") }
                    TextButton(
                        onClick = { onAction(TaskActionReceiver.ACTION_DELETE, 0) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
