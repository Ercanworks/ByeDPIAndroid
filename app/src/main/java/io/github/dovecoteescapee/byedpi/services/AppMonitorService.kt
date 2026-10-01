package io.github.dovecoteescapee.byedpi.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects when the selected app comes to the foreground and, depending on
 * the settings, disconnects again some time after the user has left it.
 *
 * The foreground app is followed through usage events: every time an app's
 * activity is resumed, that app becomes the foreground app. Pausing alone
 * (e.g. turning the screen off during a voice call) doesn't count as leaving.
 */
class AppMonitorService : LifecycleService() {

    companion object {
        private val TAG: String = AppMonitorService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI AppMonitor"
        private const val POLL_INTERVAL_MS = 2000L

        // Events are re-read with some overlap, since they can be stored with a small delay
        private const val EVENT_OVERLAP_MS = 5000L

        // How far back to look for the current foreground app when monitoring starts
        private const val INITIAL_LOOKBACK_MS = 60 * 60 * 1000L

        // A status broadcast arriving this soon after our own start/stop was caused by us
        private const val OWN_ACTION_WINDOW_MS = 15_000L

        // With "never disconnect", leaving the app for this long still ends the session
        private const val SESSION_END_GRACE_MS = 15_000L

        // UsageEvents.Event.ACTIVITY_RESUMED, called MOVE_TO_FOREGROUND before API 29
        private const val EVENT_ACTIVITY_RESUMED = 1

        // Windows of these packages appear on top of the current app without the user leaving it
        private val IGNORED_PACKAGES = setOf("com.android.systemui")

        const val ACTION_START_MONITOR = "start_monitor"
        const val ACTION_STOP_MONITOR = "stop_monitor"
    }

    private var monitorJob: Job? = null
    private var sessionEndJob: Job? = null
    private var targetPackage: String? = null

    private var foregroundPackage: String? = null
    private var lastQueryTime = 0L
    private var targetInForeground = false

    // The monitor started the current connection, so it may also stop it.
    // A connection the user started by hand is never stopped by the monitor.
    private var connectedByMonitor = false

    // The user connected or disconnected by hand while the target app was in use.
    // The monitor then leaves the connection alone until the app is opened anew.
    private var userOverride = false

    private var expectedStatus: AppStatus? = null
    private var expectedStatusTime = 0L

    private var shownProblem: Int? = null

    private val sessionActive: Boolean
        get() = targetInForeground || sessionEndJob?.isActive == true

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                STARTED_BROADCAST -> onStatusChanged(AppStatus.Running)
                STOPPED_BROADCAST -> onStatusChanged(AppStatus.Halted)
                FAILED_BROADCAST -> {
                    Log.w(TAG, "Connection failed")
                    connectedByMonitor = false
                    // A failed start is followed by a stop broadcast, which isn't the user's doing
                    if (expectedStatus == AppStatus.Running) {
                        expect(AppStatus.Halted)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        monitorJob?.cancel()
        sessionEndJob?.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return when (intent?.action) {
            ACTION_STOP_MONITOR -> {
                stopMonitoring()
                stopSelf()
                START_NOT_STICKY
            }

            // ACTION_START_MONITOR, or a restart by the system after being killed
            else -> {
                val prefs = getPreferences()
                targetPackage = prefs.getString("auto_connect_package", null)

                if (!prefs.getBoolean("auto_connect_enabled", false) || targetPackage == null) {
                    Log.w(TAG, "Auto-connect is disabled or no app is selected")
                    // A service started with startForegroundService must go foreground even when quitting
                    startForegroundNotification()
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundNotification()
                startMonitoring()
                START_STICKY
            }
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        sessionEndJob?.cancel()
        foregroundPackage = null
        lastQueryTime = 0L
        targetInForeground = false
        userOverride = false

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        monitorJob = lifecycleScope.launch {
            while (isActive) {
                val hasUsageAccess = ServiceManager.hasUsageStatsPermission(this@AppMonitorService)
                if (!hasUsageAccess) {
                    showProblem(R.string.auto_connect_problem_usage_access)
                } else if (shownProblem == R.string.auto_connect_problem_usage_access) {
                    showProblem(null)
                }

                if (hasUsageAccess && powerManager.isInteractive) {
                    // Nothing can be opened while the screen is off, so skip polling then
                    val foreground = withContext(Dispatchers.IO) { queryForegroundPackage() }
                    val isTargetForeground = foreground == targetPackage

                    if (isTargetForeground && !targetInForeground) {
                        targetInForeground = true
                        onTargetAppOpened()
                    } else if (!isTargetForeground && targetInForeground) {
                        targetInForeground = false
                        onTargetAppLeft()
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Started monitoring for $targetPackage")
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        sessionEndJob?.cancel()
        sessionEndJob = null
        Log.i(TAG, "Stopped monitoring")
    }

    /**
     * Applies the usage events since the last query and returns the current foreground app.
     * Only ever called from the monitor loop, one call at a time.
     */
    private fun queryForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return foregroundPackage

        val now = System.currentTimeMillis()
        val begin = if (lastQueryTime == 0L) {
            now - INITIAL_LOOKBACK_MS
        } else {
            lastQueryTime - EVENT_OVERLAP_MS
        }
        lastQueryTime = now

        val events = usageStatsManager.queryEvents(begin, now) ?: return foregroundPackage
        val event = UsageEvents.Event()
        // Events come in chronological order, so the last resumed app wins.
        // Re-reading the overlap is harmless: it replays the same order.
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == EVENT_ACTIVITY_RESUMED && !isIgnoredPackage(event.packageName)) {
                foregroundPackage = event.packageName
            }
        }

        return foregroundPackage
    }

    private fun isIgnoredPackage(packageName: String): Boolean =
        packageName == this.packageName || packageName in IGNORED_PACKAGES

    private fun onTargetAppOpened() {
        Log.i(TAG, "Target app opened: $targetPackage")

        val returnedWithinGrace = sessionEndJob?.isActive == true
        sessionEndJob?.cancel()

        if (!returnedWithinGrace) {
            userOverride = false
        }

        if (userOverride) {
            Log.i(TAG, "User took over the connection in this session, not connecting")
            return
        }

        connectIfAllowed()
    }

    private fun onTargetAppLeft() {
        Log.i(TAG, "Target app left: $targetPackage")

        val disconnectDelayMs = getDisconnectDelayMs()
        sessionEndJob = lifecycleScope.launch {
            delay(disconnectDelayMs ?: SESSION_END_GRACE_MS)

            if (disconnectDelayMs != null && connectedByMonitor && !userOverride) {
                disconnectByMonitor()
            }
            userOverride = false
            Log.i(TAG, "Session ended")
        }
    }

    private fun connectIfAllowed() {
        if (!isAllowedOnCurrentNetwork()) {
            Log.i(TAG, "Blocked: mobile data only, currently on WiFi")
            return
        }

        expect(AppStatus.Running)
        when (ServiceManager.connect(this)) {
            ServiceManager.ConnectResult.Started -> {
                connectedByMonitor = true
                showProblem(null)
            }

            ServiceManager.ConnectResult.AlreadyRunning -> {
                expectedStatus = null
            }

            ServiceManager.ConnectResult.VpnPermissionRequired -> {
                expectedStatus = null
                showProblem(R.string.auto_connect_problem_vpn_permission)
            }
        }
    }

    private fun disconnectByMonitor() {
        Log.i(TAG, "Disconnecting after leaving $targetPackage")
        expect(AppStatus.Halted)
        if (!ServiceManager.disconnect(this)) {
            expectedStatus = null
        }
        connectedByMonitor = false
    }

    private fun expect(status: AppStatus) {
        expectedStatus = status
        expectedStatusTime = System.currentTimeMillis()
    }

    private fun onStatusChanged(status: AppStatus) {
        val expected = expectedStatus == status &&
            System.currentTimeMillis() - expectedStatusTime < OWN_ACTION_WINDOW_MS
        if (expected) {
            expectedStatus = null
            return
        }

        // Not caused by the monitor: the user (or another app) changed the connection
        Log.i(TAG, "Connection changed to $status by the user")
        connectedByMonitor = false
        if (sessionActive) {
            userOverride = true
        }
    }

    /** Null means never disconnect. */
    private fun getDisconnectDelayMs(): Long? {
        val seconds = getPreferences()
            .getString("auto_connect_disconnect_delay", "15")
            ?.toLongOrNull() ?: 15L
        return if (seconds < 0) null else seconds * 1000L
    }

    private fun isAllowedOnCurrentNetwork(): Boolean {
        val mobileOnly = getPreferences().getBoolean("auto_connect_mobile_only", false)
        return !mobileOnly || !isOnWifi()
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun registerNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.auto_connect_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.enableLights(false)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    /** Shows a problem that keeps auto-connect from working in the notification, or clears it. */
    private fun showProblem(@StringRes problem: Int?) {
        if (problem == shownProblem) return
        shownProblem = problem
        problem?.let { Log.w(TAG, getString(it)) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(FOREGROUND_SERVICE_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val appName = targetPackage?.let {
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it, 0)
                ).toString()
            } catch (_: Exception) { it }
        } ?: "..."

        val problem = shownProblem
        val contentIntent = when (problem) {
            R.string.auto_connect_problem_usage_access ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            else ->
                Intent(this, MainActivity::class.java)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setContentTitle(getString(R.string.auto_connect_notification_title))
            .setContentText(
                if (problem != null) getString(problem)
                else getString(R.string.auto_connect_notification_content, appName)
            )
            .setContentIntent(
                PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
    }

    private fun startForegroundNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }
}
