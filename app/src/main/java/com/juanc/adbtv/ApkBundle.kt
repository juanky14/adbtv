package com.juanc.adbtv

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Soporte para apps divididas (base + splits): paquetes .apks / .xapk / .zip. */
object ApkBundle {

    /** Archivos APK de una app instalada: base.apk y sus splits. */
    fun installedApks(ctx: Context, pkg: String): List<File> {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        return (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).map { File(it) }
    }

    /** Zip con todas las partes, generado al vuelo. */
    fun zipStream(apks: List<File>): InputStream {
        val input = PipedInputStream(256 * 1024)
        val output = PipedOutputStream(input)
        Thread {
            try {
                ZipOutputStream(output).use { zip ->
                    zip.setLevel(0) // los APK ya están comprimidos
                    apks.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                AppLog.i("Error generando .apks: ${e.message}")
            }
        }.start()
        return input
    }

    /** ¿Es un paquete de APKs (y no un APK normal, que también es un zip)? */
    fun isBundle(file: File): Boolean = try {
        ZipFile(file).use { z ->
            z.getEntry("AndroidManifest.xml") == null &&
                z.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }

    class Extracted(val apks: List<File>, val obbs: List<Pair<File, String>>)

    /** Extrae los .apk (y los .obb de un .xapk, con su ruta destino en /sdcard). */
    fun extract(bundle: File, outDir: File): Extracted {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val apks = mutableListOf<File>()
        val obbs = mutableListOf<Pair<File, String>>()
        ZipFile(bundle).use { z ->
            z.entries().asSequence().filter { !it.isDirectory }.forEachIndexed { i, e ->
                val name = e.name.substringAfterLast('/')
                val isApk = name.endsWith(".apk", ignoreCase = true)
                val isObb = name.endsWith(".obb", ignoreCase = true)
                if (!isApk && !isObb) return@forEachIndexed
                val out = File(outDir, "${i}_$name")
                z.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                if (isApk) apks += out
                else {
                    // En los .xapk los OBB vienen como Android/obb/<paquete>/<archivo>.obb
                    val rel = e.name.substringAfter("Android/obb/", "")
                    if (rel.isNotEmpty() && !rel.contains("..")) obbs += out to "/sdcard/Android/obb/$rel"
                }
            }
        }
        // base.apk primero: pm lo necesita para crear la sesión
        apks.sortBy { if (it.name.endsWith("_base.apk")) 0 else 1 }
        return Extracted(apks, obbs)
    }
}
