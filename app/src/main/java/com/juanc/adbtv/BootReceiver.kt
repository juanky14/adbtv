package com.juanc.adbtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca el servicio al encender el TV o al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.autoStart(context)) {
            AppLog.i("Arranque automático (${intent.action?.substringAfterLast('.')})")
            AdbServerService.start(context)
        }
    }
}
