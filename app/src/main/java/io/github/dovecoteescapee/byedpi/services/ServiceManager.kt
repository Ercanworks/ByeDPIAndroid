package io.github.dovecoteescapee.byedpi.services

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.START_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    enum class ConnectResult {
        Started,
        AlreadyRunning,
        VpnPermissionRequired,
    }

    fun start(context: Context, mode: Mode) {
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Connects in the configured mode without any UI, as used by shortcuts,
     * broadcasts and auto-connect. The VPN consent dialog can only be shown
     * from the app itself, so without consent this reports it instead.
     */
    fun connect(context: Context): ConnectResult {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            Log.i(TAG, "Already connected")
            return ConnectResult.AlreadyRunning
        }

        val mode = context.getPreferences().mode()
        if (mode == Mode.VPN && VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted, cannot connect without UI")
            return ConnectResult.VpnPermissionRequired
        }

        start(context, mode)
        return ConnectResult.Started
    }

    /** Returns false if there was nothing to disconnect. */
    fun disconnect(context: Context): Boolean {
        val (status, _) = appStatus
        if (status == AppStatus.Halted) {
            Log.i(TAG, "Already disconnected")
            return false
        }

        stop(context)
        return true
    }

    fun toggle(context: Context) {
        val (status, _) = appStatus
        when (status) {
            AppStatus.Halted -> connect(context)
            AppStatus.Running -> disconnect(context)
        }
    }

    fun startMonitor(context: Context) {
        Log.i(TAG, "Starting app monitor")
        val intent = Intent(context, AppMonitorService::class.java)
        intent.action = AppMonitorService.ACTION_START_MONITOR
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Starts the auto-connect monitor if it is enabled and fully set up.
     * Safe to call repeatedly, e.g. on boot and whenever the app is opened,
     * so that a monitor killed by the system comes back.
     */
    fun startMonitorIfEnabled(context: Context) {
        val prefs = context.getPreferences()
        if (!prefs.getBoolean("auto_connect_enabled", false)) return
        if (prefs.getString("auto_connect_package", null) == null) return
        if (!hasUsageStatsPermission(context)) {
            Log.w(TAG, "Auto-connect enabled, but usage access is missing")
            return
        }
        startMonitor(context)
    }

    fun stopMonitor(context: Context) {
        Log.i(TAG, "Stopping app monitor")
        val intent = Intent(context, AppMonitorService::class.java)
        intent.action = AppMonitorService.ACTION_STOP_MONITOR
        context.startService(intent)
    }

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun stop(context: Context) {
        val (_, mode) = appStatus
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Stopping VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Stopping proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
