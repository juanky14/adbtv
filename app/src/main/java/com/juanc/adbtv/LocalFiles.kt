package com.juanc.adbtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Gestor de archivos sin ADB (modo permisos): acceso directo con el permiso "gestionar todos los
 * archivos". Ve el almacenamiento interno y los USB; las carpetas del sistema, solo en lectura.
 */
object LocalFiles {

    private fun file(path: String) = File(FileManager.normalize(path))

    private fun perms(f: File) = buildString {
        append(if (f.isDirectory) 'd' else '-')
        append(if (f.canRead()) 'r' else '-')
        append(if (f.canWrite()) 'w' else '-')
        append(if (f.canExecute()) 'x' else '-')
    }

    fun list(rawPath: String): JSONObject {
        val dir = file(rawPath)
        val children = dir.listFiles()
            ?: throw IllegalStateException(
                if (dir.exists()) "Sin permiso para leer ${dir.path} (falta el permiso de archivos)" else "No existe ${dir.path}"
            )
        val entries = JSONArray()
        children.map { f ->
            // Comparamos con la ruta canónica de la carpeta: /sdcard es un enlace y todo lo de dentro lo parecería
            val isLink = try { f.canonicalPath != File(dir.canonicalFile, f.name).path } catch (_: Exception) { false }
            JSONObject()
                .put("name", f.name)
                .put("dir", f.isDirectory)
                .put("link", isLink)
                .put("size", if (f.isFile) f.length() else 0)
                .put("mtime", f.lastModified() / 1000)
                .put("perms", perms(f))
        }.sortedWith(compareBy({ !it.getBoolean("dir") }, { it.getString("name").lowercase() }))
            .forEach { entries.put(it) }
        return JSONObject().put("path", FileManager.normalize(rawPath)).put("entries", entries)
    }

    fun places(): JSONArray {
        val out = JSONArray()
            .put(JSONObject().put("name", "Almacenamiento").put("path", "/sdcard"))
            .put(JSONObject().put("name", "Descargas").put("path", "/sdcard/Download"))
        File("/storage").listFiles()?.map { it.name }
            ?.filter { it != "emulated" && it != "self" }
            ?.forEach { out.put(JSONObject().put("name", "USB $it").put("path", "/storage/$it")) }
        return out.put(JSONObject().put("name", "Raíz /").put("path", "/"))
    }

    fun open(rawPath: String): InputStream = file(rawPath).inputStream()

    fun upload(dir: String, name: String, local: File) {
        require(name.isNotBlank() && !name.contains('/')) { "Nombre de archivo no válido" }
        local.copyTo(File(file(dir), name), overwrite = true)
    }

    fun op(ctx: Context, op: String, p: Map<String, String>, protected: Set<String>): ExecResult {
        fun path(key: String) = file(p[key] ?: throw IllegalArgumentException("Falta $key"))
        fun paths() = (p["paths"] ?: "").split('\n').filter { it.isNotBlank() }.map { file(it) }
            .also { require(it.isNotEmpty()) { "No hay archivos seleccionados" } }
        fun ok(msg: String = "") = ExecResult(msg, 0, "app")
        fun fail(msg: String) = ExecResult("$msg\n", 1, "app")

        return when (op) {
            "mkdir" -> if (path("path").mkdirs() || path("path").isDirectory) ok() else fail("No se pudo crear la carpeta")
            "rename" -> {
                val src = path("path")
                val name = p["name"].orEmpty()
                require(name.isNotBlank() && !name.contains('/')) { "Nombre no válido" }
                val dest = File(src.parentFile, name)
                when {
                    dest.exists() -> fail("Ya existe ${dest.name}")
                    src.renameTo(dest) -> ok()
                    else -> fail("No se pudo renombrar")
                }
            }
            "delete" -> {
                val targets = paths()
                require(targets.none { it.path in protected }) { "No se puede borrar una carpeta del sistema" }
                val failed = targets.filterNot { it.deleteRecursively() }
                if (failed.isEmpty()) ok() else fail("No se pudo borrar: ${failed.joinToString { it.name }}")
            }
            "copy", "move" -> {
                val dest = path("dest")
                val failed = paths().filterNot { src ->
                    val target = File(dest, src.name)
                    try {
                        if (op == "move" && src.renameTo(target)) true
                        else src.copyRecursively(target, overwrite = false).also { copied ->
                            if (copied && op == "move") src.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
                if (failed.isEmpty()) ok() else fail("No se pudo ${if (op == "copy") "copiar" else "mover"}: ${failed.joinToString { it.name }}")
            }
            "install" -> NativeInstaller.install(ctx, listOf(path("path")))
            "read" -> {
                val f = path("path")
                val buf = ByteArray(minOf(f.length(), 262144L).toInt())
                var n = 0
                f.inputStream().use { inp ->
                    while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                }
                ok(String(buf, 0, n, Charsets.UTF_8))
            }
            else -> throw IllegalArgumentException("Operación desconocida: $op")
        }
    }
}
