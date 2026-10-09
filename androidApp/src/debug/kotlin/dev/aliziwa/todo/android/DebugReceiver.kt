package dev.aliziwa.todo.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY (lives in `src/debug`, so it cannot ship).
 *
 * Lets adb exercise the platform surfaces without depending on the shared DueEngine, which is
 * being written in parallel. Examples:
 *
 *   adb shell am broadcast -a dev.aliziwa.todo.debug.ALERT \
 *     -n dev.aliziwa.todo/dev.aliziwa.todo.android.DebugReceiver --es title "Take the bins out"
 *
 *   adb shell am broadcast -a dev.aliziwa.todo.debug.SCHEDULE \
 *     -n dev.aliziwa.todo/dev.aliziwa.todo.android.DebugReceiver --ei seconds 60
 *
 *   adb shell am broadcast -a dev.aliziwa.todo.debug.STICKY \
 *     -n dev.aliziwa.todo/dev.aliziwa.todo.android.DebugReceiver --es title "Stand-up" --ei inMinutes 12
 *
 *   adb shell am broadcast -a dev.aliziwa.todo.debug.PROMPT_AT \
 *     -n dev.aliziwa.todo/dev.aliziwa.todo.android.DebugReceiver --ei hour 8 --ei minute 0
 *
 *   adb shell am broadcast -a dev.aliziwa.todo.debug.LLM \
 *     -n dev.aliziwa.todo/dev.aliziwa.todo.android.DebugReceiver --es path /data/local/tmp/model.litertlm
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId") ?: "debug-task"
        val title = intent.getStringExtra("title") ?: "Debug task"
        Log.i(TAG, "debug action ${intent.action} taskId=$taskId")

        when (intent.action) {
            "dev.aliziwa.todo.debug.ALERT" -> AndroidServices.alerts?.showDueAlert(
                DueAlert(
                    taskId = taskId,
                    title = title,
                    dueAtEpochMillis = System.currentTimeMillis(),
                    priority = intent.getIntExtra("priority", 2),
                    isRecurring = intent.getBooleanExtra("recurring", false),
                    extensionCount = intent.getIntExtra("extensions", 0),
                ),
            )

            "dev.aliziwa.todo.debug.DISMISS" -> AndroidServices.alerts?.dismissDueAlert(taskId)

            "dev.aliziwa.todo.debug.STICKY" -> AndroidServices.alerts?.updateSticky(
                StickySummary(
                    nextTaskTitle = title,
                    nextDueAtEpochMillis = System.currentTimeMillis() +
                        intent.getIntExtra("inMinutes", 12) * 60_000L,
                    dueNowCount = intent.getIntExtra("dueNow", 0),
                    remainingTodayCount = intent.getIntExtra("remaining", 3),
                    completedTodayCount = intent.getIntExtra("done", 1),
                ),
            )

            "dev.aliziwa.todo.debug.PROMPT" -> AndroidServices.alerts?.showDailyPrompt()

            "dev.aliziwa.todo.debug.SCHEDULE" -> {
                val seconds = intent.getIntExtra("seconds", 60)
                AndroidServices.alarms?.schedule(
                    taskId,
                    System.currentTimeMillis() + seconds * 1000L,
                )
            }

            "dev.aliziwa.todo.debug.CANCEL" -> AndroidServices.alarms?.cancel(taskId)

            "dev.aliziwa.todo.debug.PROMPT_AT" -> AndroidServices.alarms?.scheduleDailyPrompt(
                intent.getIntExtra("hour", 8),
                intent.getIntExtra("minute", 0),
            )

            "dev.aliziwa.todo.debug.LLM" -> {
                val path = intent.getStringExtra("path") ?: "/data/local/tmp/model.litertlm"
                val engine = AndroidServices.llm ?: return
                val pending = goAsync()
                ReceiverScope.launch {
                    try {
                        val result = engine.load(path)
                        Log.i(TAG, "llm load($path) -> $result state=${engine.state.value}")
                        if (engine.state.value is EngineState.Ready) {
                            engine.generate(
                                listOf(
                                    dev.aliziwa.todo.platform.LlmMessage(
                                        dev.aliziwa.todo.platform.LlmRole.USER,
                                        intent.getStringExtra("prompt") ?: "Say hello in five words.",
                                    ),
                                ),
                                dev.aliziwa.todo.platform.GenerationOptions(maxTokens = 64),
                            ).collect { Log.i(TAG, "llm chunk: $it") }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "llm debug failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }

            // Drops the foreground service so `adb shell am kill dev.aliziwa.todo` can actually
            // kill the process - the only way to prove an alarm cold-starts the app (force-stop
            // is no good: Android cancels a stopped package's alarms by design).
            "dev.aliziwa.todo.debug.STOPFG" -> StickyForegroundService.stop(context)

            else -> Log.w(TAG, "unknown debug action ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "TodoDebug"
    }
}
