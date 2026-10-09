package dev.aliziwa.todo.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import dev.aliziwa.todo.App
import dev.aliziwa.todo.TodoApp

/**
 * The one visible Activity. `singleTask` so notification deep links re-use the existing instance
 * (see [onNewIntent]) instead of stacking copies.
 *
 * Permission UX is deliberately non-blocking: the app is fully usable with every permission
 * denied (alarms use `setAlarmClock`, which needs none), so each prompt is asked at most once and
 * nothing gates the UI.
 */
class MainActivity : ComponentActivity() {

    private val route = mutableStateOf<String?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
            if (granted) StickyForegroundService.ensureRunning(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent { App(TodoApp.graph(), route.value) }
        askForWhatWeNeed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { route.value = it }
    }

    override fun onResume() {
        super.onResume()
        TodoBridge.onAppForegrounded()
        StickyForegroundService.ensureRunning(this)
    }

    // --------------------------------------------------------- permissions

    private fun askForWhatWeNeed() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 1. POST_NOTIFICATIONS (API 33+). Without it there is no sticky summary and no alert
        //    notification; the full-screen alert Activity still works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            runCatching { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            return // one dialog at a time; the rest is offered on the next launch
        }

        // 2. Full-screen intent (API 34+). Apps that are not calendar/alarm apps are denied by
        //    default, which downgrades the due alert to a heads-up notification. There is no
        //    runtime permission for it - only a settings screen.
        if (Build.VERSION.SDK_INT >= 34 && !prefs.getBoolean(KEY_ASKED_FSI, false)) {
            val nm = getSystemService(NotificationManager::class.java)
            val allowed = runCatching { nm?.canUseFullScreenIntent() == true }.getOrDefault(true)
            Log.i(TAG, "canUseFullScreenIntent=$allowed")
            if (!allowed) {
                prefs.edit().putBoolean(KEY_ASKED_FSI, true).apply()
                val opened = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:$packageName")),
                    )
                    true
                }.getOrDefault(false)
                Log.i(TAG, "full-screen-intent settings opened=$opened")
                if (opened) return
            } else {
                prefs.edit().putBoolean(KEY_ASKED_FSI, true).apply()
            }
        }

        // 3. Exact alarms. USE_EXACT_ALARM is granted at install, so this should always pass;
        //    it only matters on a build/ROM where that permission is withheld, in which case the
        //    user can grant SCHEDULE_EXACT_ALARM from settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !prefs.getBoolean(KEY_ASKED_EXACT, false)) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            val canSchedule = runCatching { am?.canScheduleExactAlarms() == true }.getOrDefault(true)
            Log.i(TAG, "canScheduleExactAlarms=$canSchedule")
            prefs.edit().putBoolean(KEY_ASKED_EXACT, true).apply()
            if (!canSchedule) {
                val opened = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName")),
                    )
                    true
                }.getOrDefault(false)
                Log.i(TAG, "exact-alarm settings opened=$opened")
                if (opened) return
            }
        }

        // 4. Battery optimisation exemption, asked exactly once. setAlarmClock already fires in
        //    Doze, so this only buys reliability for the foreground service after long idle.
        if (!prefs.getBoolean(KEY_ASKED_BATTERY, false)) {
            val pm = getSystemService(PowerManager::class.java)
            val exempt = runCatching { pm?.isIgnoringBatteryOptimizations(packageName) == true }
                .getOrDefault(true)
            if (!exempt) {
                prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
                runCatching {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                    startActivity(i)
                }.onFailure { Log.w(TAG, "battery optimisation dialog unavailable", it) }
            } else {
                prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
            }
        }
    }

    companion object {
        private const val TAG = "TodoMain"
        private const val PREFS = "permissions"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        private const val KEY_ASKED_FSI = "asked_full_screen_intent"
        private const val KEY_ASKED_BATTERY = "asked_battery_optimisation"
        private const val KEY_ASKED_EXACT = "asked_exact_alarm"

        /** Deep-link route handed straight to `App(graph, initialRoute)`. */
        const val EXTRA_ROUTE = "initialRoute"

        fun intent(context: Context, route: String?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .apply {
                    if (route != null) {
                        putExtra(EXTRA_ROUTE, route)
                        // Distinct identity per route so the PendingIntents do not collapse.
                        data = Uri.parse("todo://route/$route")
                    }
                }

        fun pendingIntent(context: Context, route: String?, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                intent(context, route).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
