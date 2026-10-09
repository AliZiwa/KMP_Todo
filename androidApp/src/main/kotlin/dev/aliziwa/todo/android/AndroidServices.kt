package dev.aliziwa.todo.android

/**
 * Handles to the platform implementations, for the places Android hands us only a Context:
 * BroadcastReceivers, the foreground service, and the alert Activity. Set once from
 * [TodoApplication.onCreate], which always runs before any other app component.
 */
object AndroidServices {
    @Volatile var alarms: AndroidAlarmScheduler? = null
    @Volatile var alerts: AndroidAlertPresenter? = null
    @Volatile var doorbell: DoorbellPlayer? = null
    @Volatile var llm: LiteRtLlmEngine? = null
}
