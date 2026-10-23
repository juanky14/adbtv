package com.juanc.adbtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Servicio en primer plano: mantiene el servidor web vivo e intenta activar/conectar ADB. */
class AdbServerService : Service() {

    private var server: WebServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (server == null) {
            try {
                server = WebServer(applicationContext, Prefs.WEB_PORT).also { it.start(30_000, false) }
                AppLog.i("Terminal web en http://${NetUtils.localIp() ?: "IP"}:${Prefs.WEB_PORT}")
                DeviceInfo.advertise(applicationContext, Prefs.WEB_PORT)
            } catch (e: Exception) {
                AppLog.i("No se pudo iniciar el servidor web: ${e.message}")
            }
            Thread { setupAdb(applicationContext) }.start()
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        DeviceInfo.stopAdvertising(applicationContext)
        AdbBridge.disconnect()
        running = false
        AppLog.i("Servicio detenido")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Servidor ADB", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ADB TV Server activo")
            .setContentText("${NetUtils.localIp() ?: "sin red"}  ·  ADB :${AdbBridge.ADB_PORT}  ·  Web :${Prefs.WEB_PORT}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "adbtv"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, AdbServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AdbServerService::class.java))

        /** Activa ADB por red si hace falta (root) y conecta el cliente local. */
        fun setupAdb(ctx: Context) {
            if (!AdbBridge.isAdbPortOpen()) {
                AppLog.i("ADB por red inactivo; comprobando root…")
                if (!AdbBridge.enableWithRoot()) {
                    AppLog.i("Sin root: activa 'Depuración USB/ADB' en Opciones de desarrollador " +
                        "o ejecuta una vez desde un PC: adb tcpip ${AdbBridge.ADB_PORT}")
                    AppLog.i("Mientras tanto la terminal web usa los permisos de la app (limitados)")
                    return
                }
            }
            AdbBridge.connect(ctx)
        }
    }
}
