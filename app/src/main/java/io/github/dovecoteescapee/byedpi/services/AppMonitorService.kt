package io.github.dovecoteescapee.byedpi.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppMonitorService : LifecycleService() {

    companion object {
        private val TAG: String = AppMonitorService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI AppMonitor"
        private const val POLL_INTERVAL_MS = 2500L

        const val ACTION_START_MONITOR = "start_monitor"
        const val ACTION_STOP_MONITOR = "stop_monitor"
    }

    private var monitorJob: Job? = null
    private var disconnectJob: Job? = null
    private var targetPackage: String? = null
    private var wasTargetInForeground = false
    private var manualOverride = false
    private var autoInitiatedAction = false

    // How long to wait after Discord leaves foreground before disconnecting.
    // This prevents disconnection when opening notification panel, switching
    // briefly to another app, or using the recent apps screen.
    private val DISCONNECT_GRACE_MS = 15_000L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                STOPPED_BROADCAST -> {
                    if (!autoInitiatedAction && wasTargetInForeground) {
                        Log.i(TAG, "Manual disconnect detected, setting override")
                        manualOverride = true
                    }
                }
                STARTED_BROADCAST -> {
                    if (!autoInitiatedAction && wasTargetInForeground) {
                        Log.i(TAG, "Manual connect detected, clearing override")
                        manualOverride = false
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel()

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                statusReceiver,
                IntentFilter().apply {
                    addAction(STARTED_BROADCAST)
                    addAction(STOPPED_BROADCAST)
                },
                RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                statusReceiver,
                IntentFilter().apply {
                    addAction(STARTED_BROADCAST)
                    addAction(STOPPED_BROADCAST)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        monitorJob?.cancel()
        disconnectJob?.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_MONITOR -> {
                startForegroundNotification()
                startMonitoring()
                return START_STICKY
            }
            ACTION_STOP_MONITOR -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Restarted by system after being killed
                if (getPreferences().getBoolean("auto_connect_enabled", false)) {
                    startForegroundNotification()
                    startMonitoring()
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startMonitoring() {
        val prefs = getPreferences()
        targetPackage = prefs.getString("auto_connect_package", null)

        if (targetPackage == null) {
            Log.w(TAG, "No target package configured")
            stopSelf()
            return
        }

        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            while (isActive) {
                val foregroundPkg = getForegroundPackage()
                val isTargetForeground = foregroundPkg == targetPackage

                if (isTargetForeground && !wasTargetInForeground) {
                    onTargetAppOpened()
                } else if (!isTargetForeground && wasTargetInForeground) {
                    onTargetAppClosed()
                }

                wasTargetInForeground = isTargetForeground
                delay(POLL_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Started monitoring for $targetPackage")
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Stopped monitoring")
    }

    private fun onTargetAppOpened() {
        Log.i(TAG, "Target app opened: $targetPackage")
        manualOverride = false

        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            Log.i(TAG, "Already connected")
            return
        }

        // Network check
        if (!isAutoConnectAllowedOnCurrentNetwork()) {
            Log.i(TAG, "Auto-connect blocked by network settings")
            return
        }

        val mode = getPreferences().mode()
        if (mode == Mode.VPN && VpnService.prepare(this) != null) {
            Log.w(TAG, "VPN permission not granted, cannot auto-connect")
            return
        }

        autoInitiatedAction = true
        ServiceManager.start(this, mode)
        autoInitiatedAction = false
    }

    private fun onTargetAppClosed() {
        Log.i(TAG, "Target app closed: $targetPackage")

        if (manualOverride) {
            Log.i(TAG, "Manual override active, resetting")
            manualOverride = false
            return
        }

        val (status, _) = appStatus
        if (status == AppStatus.Halted) {
            Log.i(TAG, "Already disconnected")
            return
        }

        autoInitiatedAction = true
        ServiceManager.stop(this)
        autoInitiatedAction = false
    }

    /**
     * Returns true if auto-connect should proceed on the current network.
     * Checks:
     * 1. "Mobile only" switch → block if on WiFi
     * 2. WiFi exceptions list → block if current SSID is in the list
     */
    @SuppressLint("MissingPermission")
    private fun isAutoConnectAllowedOnCurrentNetwork(): Boolean {
        val prefs = getPreferences()
        val mobileOnly = prefs.getBoolean("auto_connect_mobile_only", false)
        val exceptionsRaw = prefs.getString("auto_connect_wifi_exceptions", "") ?: ""

        val onWifi = isOnWifi()

        // If mobile only → block when on WiFi
        if (mobileOnly && onWifi) {
            Log.i(TAG, "Blocked: mobile-only mode, currently on WiFi")
            return false
        }

        // If there are WiFi exceptions → check current SSID
        if (onWifi && exceptionsRaw.isNotBlank()) {
            val currentSsid = getCurrentSsid()
            if (currentSsid != null) {
                val exceptions = exceptionsRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (exceptions.any { it.equals(currentSsid, ignoreCase = true) }) {
                    Log.i(TAG, "Blocked: WiFi '$currentSsid' is in exceptions list")
                    return false
                }
            }
        }

        return true
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

    @SuppressLint("MissingPermission")
    private fun getCurrentSsid(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: get SSID from NetworkCapabilities
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = cm?.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                // TransportInfo contains WifiInfo on Android 10+
                val wifiInfo = caps.transportInfo
                if (wifiInfo is android.net.wifi.WifiInfo) {
                    wifiInfo.ssid?.removeSurrounding("\"")
                } else null
            } else {
                @Suppress("DEPRECATION")
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.connectionInfo?.ssid?.removeSurrounding("\"")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SSID", e)
            null
        }
    }

    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 5000

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            beginTime,
            endTime
        )

        if (usageStats.isNullOrEmpty()) return null

        return usageStats
            .filter { it.lastTimeUsed > 0 && !isSystemUiPackage(it.packageName) }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    /**
     * Returns true for system overlay packages that should not affect
     * foreground detection: notification panel, launchers, recents, etc.
     * We check if the package has a launcher activity — if not, it's a
     * system overlay (like SystemUI) and we ignore it.
     */
    private fun isSystemUiPackage(packageName: String): Boolean {
        // Always ignore own package
        if (packageName == this.packageName) return true

        // Always ignore known system UI packages across OEMs
        val knownSystemUi = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.samsung.android.app.spage",
            "com.samsung.android.app.cocktailbarservice",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher"
        )
        if (packageName in knownSystemUi) return true

        // For any other package: check if it has a launcher activity.
        // System overlays (notification shade, recents, etc.) typically don't.
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            packageManager.queryIntentActivities(intent, 0).isEmpty()
        } catch (_: Exception) {
            false
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

    private fun startForegroundNotification() {
        val appName = targetPackage?.let {
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it, 0)
                ).toString()
            } catch (_: Exception) { it }
        } ?: "..."

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setContentTitle(getString(R.string.auto_connect_notification_title))
            .setContentText(getString(R.string.auto_connect_notification_content, appName))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }
}
