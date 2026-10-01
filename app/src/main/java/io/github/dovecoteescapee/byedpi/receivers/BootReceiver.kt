package io.github.dovecoteescapee.byedpi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dovecoteescapee.byedpi.services.ServiceManager

/** Brings the auto-connect monitor back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private val TAG: String = BootReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action}")
                ServiceManager.startMonitorIfEnabled(context)
            }
        }
    }
}
