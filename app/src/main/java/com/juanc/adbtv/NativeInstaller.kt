package com.juanc.adbtv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Instalación sin ADB con PackageInstaller: el sistema pide confirmación en la pantalla del TV. */
object NativeInstaller {

    private class Pending {
        val done = CountDownLatch(1)
        @Volatile var status = PackageInstaller.STATUS_FAILURE
        @Volatile var message: String? = null
    }

    private val pending = ConcurrentHashMap<Int, Pending>()

    fun install(ctx: Context, apks: List<File>): ExecResult {
        if (!Permissions.install(ctx)) {
            return ExecResult("Falta el permiso para instalar apps (ver la configuración del modo permisos)\n", 1, "app")
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        val wait = Pending().also { pending[id] = it }
        try {
            installer.openSession(id).use { session ->
                apks.forEachIndexed { i, f ->
                    session.openWrite("part$i.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, InstallResultReceiver::class.java).putExtra("id", id), flags
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            pending.remove(id)
            return ExecResult("Error preparando la instalación: ${e.message}\n", 1, "app")
        }
        AppLog.i("Instalación pendiente de confirmar en el TV")
        // Damos tiempo a que alguien acepte el diálogo con el mando
        val finished = wait.done.await(180, TimeUnit.SECONDS)
        pending.remove(id)
        return when {
            !finished -> ExecResult("Nadie confirmó la instalación en el TV (3 min)\n", 1, "app")
            wait.status == PackageInstaller.STATUS_SUCCESS -> ExecResult("Instalado correctamente (confirmado en el TV)\n", 0, "app")
            else -> ExecResult("No se instaló: ${wait.message ?: "cancelado"}\n", 1, "app")
        }
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra("id", -1)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            pending[id]?.let {
                it.status = status
                it.message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                it.done.countDown()
            }
        }
    }
}
