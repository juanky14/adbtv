package com.juanc.adbtv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Listado y gestión de apps instaladas. Las acciones se ejecutan por ADB (usuario shell). */
object AppManager {

    private val PKG_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** Paquetes cuya eliminación puede dejar el TV sin arrancar o sin imagen. */
    val CRITICAL = setOf(
        "android", "com.android.systemui", "com.android.tv.settings", "com.android.shell",
        "com.android.providers.settings", "com.android.providers.media", "com.android.providers.tv",
        "com.android.inputdevices", "com.android.bluetooth", "com.android.externalstorage",
        "com.android.defcontainer", "com.google.android.packageinstaller", "com.google.android.gms",
        "com.google.android.gsf", "com.google.android.webview", "com.google.android.tvlauncher",
        "com.google.android.tungsten.setupwraith", "com.google.android.ext.services",
        "com.google.android.ext.shared", "com.mediatek.tvinput", "com.mediatek.wwtv.tvcenter",
        "com.mediatek.tvinputservice.arbitratorservice", "com.mediatek.tv.agent", "mitv.service",
        "com.xiaomi.mitv.tvmanager", "com.xiaomi.mitv.res", "com.google.android.inputmethod.latin",
    )

    private val iconCache = ConcurrentHashMap<String, ByteArray>()

    fun isValidPackage(pkg: String) = PKG_RE.matches(pkg)

    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val out = JSONArray()
        pm.getInstalledPackages(flags)
            .mapNotNull { pi -> pi.applicationInfo?.let { pi to it } }
            .sortedBy { (_, ai) -> ai.loadLabel(pm).toString().lowercase() }
            .forEach { (pi, ai) ->
                val installed = ai.flags and ApplicationInfo.FLAG_INSTALLED != 0
                out.put(
                    JSONObject()
                        .put("pkg", pi.packageName)
                        .put("label", ai.loadLabel(pm).toString())
                        .put("version", pi.versionName ?: "")
                        .put("system", ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                        .put("updated", ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                        .put("installed", installed)
                        .put("enabled", ai.enabled)
                        .put("launchable", installed && launchComponent(ctx, pi.packageName) != null)
                        .put("size", File(ai.sourceDir ?: "").length())
                        .put("critical", pi.packageName in CRITICAL)
                        .put("self", pi.packageName == ctx.packageName)
                )
            }
        return out
    }

    fun icon(ctx: Context, pkg: String): ByteArray? = iconCache[pkg] ?: try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = if (d is BitmapDrawable && d.bitmap != null) {
            Bitmap.createScaledBitmap(d.bitmap, 64, 64, true)
        } else {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                d.setBounds(0, 0, 64, 64)
                d.draw(Canvas(it))
            }
        }
        ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            .also { iconCache[pkg] = it }
    } catch (_: Exception) {
        null
    }

    /** Apps que se pueden abrir (las del launcher de TV y las de móvil), ordenadas por nombre. */
    fun launchables(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg != ctx.packageName && pkg !in found) found[pkg] = ri.loadLabel(pm).toString()
            }
        }
        return found.toList().sortedBy { it.second.lowercase() }
    }

    private fun simplify(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Busca una app por paquete exacto o por nombre ("netflix", "Disney+", "prime video").
     * Prioridad: paquete exacto, nombre exacto, nombre que empieza por, nombre que contiene.
     */
    fun find(ctx: Context, query: String): Pair<String, String>? {
        val apps = launchables(ctx)
        apps.firstOrNull { it.first == query }?.let { return it }
        val q = simplify(query)
        if (q.isEmpty()) return null
        return apps.firstOrNull { simplify(it.second) == q }
            ?: apps.firstOrNull { simplify(it.second).startsWith(q) }
            ?: apps.firstOrNull { simplify(it.second).contains(q) }
            ?: apps.firstOrNull { simplify(it.first).contains(q) }
    }

    /** Paquete de la app en primer plano (requiere ADB). */
    fun foreground(ctx: Context): String? {
        if (!AdbBridge.isConnected) return null
        val out = AdbBridge.exec(ctx, "dumpsys activity activities | grep -m1 mResumedActivity", 10).output
        return Regex("u0 ([A-Za-z0-9_.]+)/").find(out)?.groupValues?.get(1)
    }

    private fun launchComponent(ctx: Context, pkg: String): String? {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return intent?.component?.flattenToShortString()
    }

    /** Ejecuta una acción sobre un paquete. Devuelve el comando ejecutado y su salida. */
    fun action(ctx: Context, pkg: String, action: String, force: Boolean): ExecResult {
        if (!isValidPackage(pkg)) return ExecResult("Nombre de paquete no válido\n", 2, "-")
        if (pkg == ctx.packageName && action in setOf("uninstall", "disable", "clear", "stop")) {
            return ExecResult("No puedo hacer eso sobre mí misma: perderías el acceso remoto\n", 2, "-")
        }
        if (pkg in CRITICAL && action in setOf("uninstall", "disable", "clear") && !force) {
            return ExecResult("$pkg es crítico para el sistema; confirma para continuar\n", 3, "-")
        }
        val isSystem = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (_: Exception) {
            false
        }
        val cmd = when (action) {
            // Apps de sistema: solo se ocultan para el usuario 0 (reversible con "restore")
            "uninstall" -> if (isSystem) "pm uninstall -k --user 0 $pkg" else "pm uninstall $pkg"
            "uninstall-keep" -> "pm uninstall -k --user 0 $pkg"
            "restore" -> "cmd package install-existing $pkg"
            "disable" -> "pm disable-user --user 0 $pkg"
            "enable" -> "pm enable $pkg"
            "stop" -> "am force-stop $pkg"
            "clear" -> "pm clear $pkg"
            "launch" -> launchComponent(ctx, pkg)?.let { "am start -n $it" }
                ?: return ExecResult("Esta app no tiene pantalla de inicio\n", 1, "-")
            else -> return ExecResult("Acción desconocida: $action\n", 2, "-")
        }
        AppLog.i("Apps: $cmd")
        val r = AdbBridge.exec(ctx, cmd, 60)
        iconCache.remove(pkg)
        return r.copy(output = "$ $cmd\n${r.output}")
    }
}
