package io.github.dovecoteescapee.byedpi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dovecoteescapee.byedpi.services.ServiceManager

/**
 * Lets automation apps (Tasker, MacroDroid, ...) connect and disconnect
 * in the background by sending one of the ACTION_* broadcasts.
 */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        private val TAG: String = ActionReceiver::class.java.simpleName
        const val ACTION_CONNECT = "io.github.dovecoteescapee.byedpi.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "io.github.dovecoteescapee.byedpi.ACTION_DISCONNECT"
        const val ACTION_TOGGLE = "io.github.dovecoteescapee.byedpi.ACTION_TOGGLE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_CONNECT -> ServiceManager.connect(context)
            ACTION_DISCONNECT -> ServiceManager.disconnect(context)
            ACTION_TOGGLE -> ServiceManager.toggle(context)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }
}
