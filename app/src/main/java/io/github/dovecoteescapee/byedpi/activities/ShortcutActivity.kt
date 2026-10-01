package io.github.dovecoteescapee.byedpi.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.receivers.ActionReceiver
import io.github.dovecoteescapee.byedpi.services.ServiceManager

/** Invisible activity behind the launcher shortcuts. */
class ShortcutActivity : Activity() {

    companion object {
        private val TAG: String = ShortcutActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            ActionReceiver.ACTION_CONNECT -> connect()
            ActionReceiver.ACTION_DISCONNECT -> ServiceManager.disconnect(this)
            ActionReceiver.ACTION_TOGGLE -> ServiceManager.toggle(this)
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        finish()
    }

    private fun connect() {
        if (ServiceManager.connect(this) == ServiceManager.ConnectResult.VpnPermissionRequired) {
            // The VPN consent dialog needs a visible activity, so let the main screen ask for it
            Toast.makeText(this, R.string.vpn_permission_required_open_app, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
