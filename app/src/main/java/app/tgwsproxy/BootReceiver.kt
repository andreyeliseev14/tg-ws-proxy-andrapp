package app.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.isEnabled(context) && Prefs.isAutostart(context)) {
            ProxyService.start(context)
        }
    }
}
