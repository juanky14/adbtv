package com.juanc.adbtv

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ExecResult(val output: String, val exitCode: Int, val via: String)

/**
 * Puente hacia el adbd del propio dispositivo.
 *
 * Android 9 no tiene "depuración inalámbrica" nativa, así que una app sin privilegios no puede
 * abrir el puerto 5555 por sí sola. Esta clase:
 *  1. detecta si adbd ya escucha en TCP 5555 (muchos Android TV lo hacen al activar la depuración);
 *  2. si hay root, lo activa con setprop service.adb.tcp.port;
 *  3. se conecta a 127.0.0.1:5555 como cliente ADB para ejecutar comandos con el usuario "shell".
 * Si nada de eso es posible, los comandos se ejecutan con los permisos de la propia app.
 */
object AdbBridge {
    const val ADB_PORT = 5555
    private const val HOST = "127.0.0.1"

    @Volatile private var dadb: Dadb? = null
    private val pool = Executors.newCachedThreadPool()

    val isConnected: Boolean get() = dadb != null
    fun isAdbPortOpen(): Boolean = NetUtils.isPortOpen(HOST, ADB_PORT)

    fun hasRoot(): Boolean = runLocal(listOf("su", "-c", "id"), 8).output.contains("uid=0")

    /** Activa adbd por TCP usando root. No persiste tras reiniciar (el servicio lo repite al arrancar). */
    fun enableWithRoot(): Boolean {
        AppLog.i("Intentando activar ADB por red con root…")
        val script = "settings put global adb_enabled 1; setprop service.adb.tcp.port $ADB_PORT; " +
            "stop adbd; start adbd"
        val r = runLocal(listOf("su", "-c", script), 15)
        if (r.exitCode != 0) {
            AppLog.i("Root no disponible o denegado: ${r.output.trim().ifEmpty { "código ${r.exitCode}" }}")
            return false
        }
        disconnect()
        repeat(10) {
            Thread.sleep(500)
            if (isAdbPortOpen()) {
                AppLog.i("adbd escuchando en el puerto $ADB_PORT")
                return true
            }
        }
        AppLog.i("adbd no abrió el puerto $ADB_PORT tras reiniciarlo")
        return false
    }

    /**
     * Conecta al adbd local. La primera vez el TV mostrará "¿Permitir depuración USB?":
     * hay que aceptarlo con el mando (marcar "Permitir siempre desde este ordenador").
     */
    @Synchronized
    fun connect(ctx: Context): Boolean {
        if (dadb != null) return true
        if (!isAdbPortOpen()) {
            AppLog.i("El puerto $ADB_PORT no está abierto: ADB por red no está activo")
            return false
        }
        return try {
            AppLog.i("Conectando a adbd local… (acepta el diálogo de depuración en el TV si aparece)")
            val d = Dadb.create(HOST, ADB_PORT, keyPair(ctx))
            val r = d.shell("id")
            dadb = d
            AppLog.i("ADB local conectado: ${r.allOutput.trim()}")
            true
        } catch (e: Exception) {
            AppLog.i("No se pudo conectar a adbd: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Cliente ADB conectado, o null si ADB por red no está disponible. */
    fun client(ctx: Context): Dadb? = dadb ?: if (connect(ctx)) dadb else null

    @Synchronized
    fun disconnect() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    fun exec(ctx: Context, cmd: String, timeoutSec: Long = 30, retry: Boolean = true): ExecResult {
        val d = client(ctx)
        if (d == null) return runLocal(listOf("sh", "-c", cmd), timeoutSec)
        val stream = try {
            d.openShell(cmd)
        } catch (e: Exception) {
            // La conexión se cayó (p. ej. adbd reiniciado): reintenta una vez
            AppLog.i("Conexión ADB perdida, reconectando…")
            disconnect()
            return if (retry) exec(ctx, cmd, timeoutSec, retry = false)
            else runLocal(listOf("sh", "-c", cmd), timeoutSec)
        }
        val future = pool.submit<AdbShellResponse> { stream.readAll() }
        return try {
            val r = future.get(timeoutSec, TimeUnit.SECONDS)
            ExecResult(r.output + r.errorOutput, r.exitCode, "adb")
        } catch (e: TimeoutException) {
            try { stream.close() } catch (_: Exception) {}
            ExecResult("[tiempo agotado tras ${timeoutSec}s; usa comandos que terminen, p. ej. 'logcat -d']\n", 124, "adb")
        } catch (e: Exception) {
            ExecResult("[error: ${e.cause?.message ?: e.message}]\n", 1, "adb")
        }
    }

    /** Instala uno o varios APK. Varios archivos se tratan como splits de una misma app. */
    fun install(ctx: Context, apks: List<File>, downgrade: Boolean = false): ExecResult {
        val opts = if (downgrade) arrayOf("-r", "-d") else arrayOf("-r")
        val d = client(ctx)
        if (d != null) {
            // No usamos dadb.install(): emplea "cmd package install -S" por streaming, que en
            // Android 9 de este TV se queda colgado. Copiamos a /data/local/tmp y usamos pm.
            val remote = apks.indices.map { "/data/local/tmp/adbtv_$it.apk" }
            return try {
                apks.zip(remote).forEach { (f, r) -> d.push(f, r, 420, System.currentTimeMillis()) }
                val o = opts.joinToString(" ")
                val cmd = if (apks.size == 1) {
                    "pm install $o ${remote[0]}"
                } else {
                    val total = apks.sumOf { it.length() }
                    "S=\$(pm install-create $o -S $total | sed 's/.*\\[\\([0-9]*\\)\\].*/\\1/') && " +
                        apks.zip(remote).mapIndexed { i, (f, r) -> "pm install-write -S ${f.length()} \$S $i $r" }
                            .joinToString(" && ") + " && pm install-commit \$S"
                }
                val r = exec(ctx, "$cmd; rm -f ${remote.joinToString(" ")}", 300)
                if (r.exitCode == 0 && r.output.contains("Success")) ExecResult("Instalado correctamente (vía ADB)\n", 0, "adb")
                else ExecResult("Error al instalar:\n${r.output}", 1, "adb")
            } catch (e: Exception) {
                exec(ctx, "rm -f ${remote.joinToString(" ")}", 10)
                ExecResult("Error al instalar: ${e.message}\n", 1, "adb")
            }
        }
        if (apks.size > 1) return ExecResult("Los APK divididos requieren ADB conectado\n", 1, "app")
        val apk = apks[0]
        apk.setReadable(true, false)
        val r = runLocal(listOf("su", "-c", "pm install ${opts.joinToString(" ")} '${apk.absolutePath}'"), 180)
        return if (r.exitCode == 0) ExecResult("Instalado correctamente (vía root)\n${r.output}", 0, "root")
        else ExecResult("No se pudo instalar: se necesita ADB conectado o root.\n${r.output}", 1, "app")
    }

    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    fun runLocal(command: List<String>, timeoutSec: Long): ExecResult {
        return try {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            p.outputStream.close()
            val out = StringBuffer()
            val reader = Thread {
                try { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } catch (_: Exception) {}
            }.apply { start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy()
                reader.join(500)
                return ExecResult("$out[tiempo agotado tras ${timeoutSec}s]\n", 124, "app")
            }
            reader.join(2000)
            ExecResult(out.toString(), p.exitValue(), "app")
        } catch (e: Exception) {
            ExecResult("[error: ${e.message}]\n", 127, "app")
        }
    }
}
