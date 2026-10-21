package com.juanc.adbtv

import android.content.Context
import okio.Pipe
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Gestor de archivos del TV. Todo pasa por ADB (usuario shell), así que se ve lo mismo que con
 * "adb shell": /sdcard, USB en /storage, /data/local/tmp, y lectura de /system.
 */
object FileManager {

    /** Entrecomilla para sh: 'a'\''b' */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(it)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun requireAdb(ctx: Context): dadb.Dadb =
        AdbBridge.client(ctx) ?: throw IllegalStateException("El gestor de archivos necesita ADB conectado")

    fun list(ctx: Context, rawPath: String): JSONObject {
        requireAdb(ctx)
        val path = normalize(rawPath)
        val dir = if (path == "/") "/" else "$path/"
        val r = AdbBridge.exec(
            ctx,
            "cd ${q(path)} || exit 1; find ${q(dir)} -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%A|%n' {} + 2>/dev/null; " +
                "echo '==='; find -L ${q(dir)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null",
            60,
        )
        if (r.exitCode != 0 && !r.output.contains("===")) {
            throw IllegalStateException(r.output.trim().ifEmpty { "No se puede abrir $path" })
        }
        val (statPart, dirPart) = r.output.split("===\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val dirs = dirPart.lineSequence().filter { it.isNotBlank() }.toSet()
        val entries = JSONArray()
        statPart.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('|', limit = 5)
            if (f.size < 5) return@mapNotNull null
            val full = f[4]
            val isLink = f[0] == "symbolic link"
            JSONObject()
                .put("name", full.substringAfterLast('/'))
                .put("dir", full in dirs)
                .put("link", isLink)
                .put("size", f[1].toLongOrNull() ?: 0)
                .put("mtime", f[2].toLongOrNull() ?: 0)
                .put("perms", f[3])
        }.sortedWith(compareBy({ !it.getBoolean("dir") }, { it.getString("name").lowercase() }))
            .forEach { entries.put(it) }
        return JSONObject().put("path", path).put("entries", entries)
    }

    /** Lugares de inicio: almacenamiento interno, USB conectados y carpetas útiles. */
    fun places(ctx: Context): JSONArray {
        val out = JSONArray()
            .put(JSONObject().put("name", "Almacenamiento").put("path", "/sdcard"))
            .put(JSONObject().put("name", "Descargas").put("path", "/sdcard/Download"))
        val r = AdbBridge.exec(ctx, "ls /storage", 10)
        r.output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" && !it.contains(' ') }
            .forEach { out.put(JSONObject().put("name", "USB $it").put("path", "/storage/$it")) }
        return out
            .put(JSONObject().put("name", "Temporal (tmp)").put("path", "/data/local/tmp"))
            .put(JSONObject().put("name", "Raíz /").put("path", "/"))
    }

    /** Descarga en streaming: dadb escribe en un pipe y NanoHTTPD lee del otro extremo. */
    fun open(ctx: Context, rawPath: String): InputStream {
        val d = requireAdb(ctx)
        val path = normalize(rawPath)
        val pipe = Pipe(256 * 1024)
        Thread {
            try {
                d.pull(pipe.sink, path)
            } catch (e: Exception) {
                AppLog.i("Error descargando $path: ${e.message}")
            } finally {
                try { pipe.sink.close() } catch (_: Exception) {}
            }
        }.start()
        return pipe.source.buffer().inputStream()
    }

    fun upload(ctx: Context, dir: String, name: String, local: File) {
        val d = requireAdb(ctx)
        require(name.isNotBlank() && !name.contains('/')) { "Nombre de archivo no válido" }
        d.push(local, normalize("$dir/$name"), 438 /* 0666 */, System.currentTimeMillis())
    }

    fun op(ctx: Context, op: String, p: Map<String, String>): ExecResult {
        requireAdb(ctx)
        fun path(key: String) = normalize(p[key] ?: throw IllegalArgumentException("Falta $key"))
        fun paths() = (p["paths"] ?: "").split('\n').filter { it.isNotBlank() }.map { normalize(it) }
            .also { require(it.isNotEmpty()) { "No hay archivos seleccionados" } }

        val cmd = when (op) {
            "mkdir" -> "mkdir -p ${q(path("path"))}"
            "rename" -> {
                val src = path("path")
                val name = p["name"].orEmpty()
                require(name.isNotBlank() && !name.contains('/')) { "Nombre no válido" }
                "mv -n ${q(src)} ${q(src.substringBeforeLast('/') + "/" + name)}"
            }
            "delete" -> {
                val targets = paths()
                require(targets.none { it in PROTECTED }) { "No se puede borrar una carpeta del sistema" }
                "rm -rf ${targets.joinToString(" ") { q(it) }}"
            }
            "copy" -> "cp -R ${paths().joinToString(" ") { q(it) }} ${q(path("dest"))}/"
            "move" -> "mv ${paths().joinToString(" ") { q(it) }} ${q(path("dest"))}/"
            "install" -> "pm install -r ${q(path("path"))}"
            "read" -> "head -c 262144 ${q(path("path"))}"
            else -> throw IllegalArgumentException("Operación desconocida: $op")
        }
        if (op != "read") AppLog.i("Archivos: $cmd")
        return AdbBridge.exec(ctx, cmd, if (op == "copy" || op == "move" || op == "install") 600 else 60)
    }

    private val PROTECTED = setOf("/", "/sdcard", "/storage", "/system", "/data", "/vendor", "/proc", "/dev", "/mnt")
}
