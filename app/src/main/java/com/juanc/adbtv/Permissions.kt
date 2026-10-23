package com.juanc.adbtv

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import org.json.JSONObject

/**
 * "Modo permisos": cuando el sistema no deja a la app usar ADB sobre sí misma (Fire OS 8 lo bloquea),
 * la app funciona con permisos especiales que se conceden UNA vez desde un PC con ADB y no caducan.
 */
object Permissions {

    /** Comando único para conceder todo desde un PC: adb shell "<esto>" */
    fun setupCommand(ctx: Context): String {
        val p = ctx.packageName
        return listOf(
            "pm grant $p android.permission.WRITE_SECURE_SETTINGS",
            "appops set $p SYSTEM_ALERT_WINDOW allow",
            "appops set $p GET_USAGE_STATS allow",
            "appops set $p REQUEST_INSTALL_PACKAGES allow",
            "appops set $p MANAGE_EXTERNAL_STORAGE allow",
            "pm grant $p android.permission.READ_EXTERNAL_STORAGE",
            "pm grant $p android.permission.WRITE_EXTERNAL_STORAGE",
        ).joinToString("; ")
    }

    // checkSelfPermission es de Android 6; checkPermission funciona en todas las versiones
    private fun granted(ctx: Context, perm: String) =
        ctx.checkPermission(perm, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    fun secureSettings(ctx: Context) =
        granted(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)

    fun overlay(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun usageStats(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun install(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun storage(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 ->
            granted(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> true
    }

    private fun accessibilityComponent(ctx: Context) =
        ComponentName(ctx, RemoteAccessibilityService::class.java).flattenToString()

    fun accessibilityEnabled(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(accessibilityComponent(ctx), ignoreCase = true) } == true

    /** Con WRITE_SECURE_SETTINGS la app activa su propio servicio de accesibilidad (y lo reactiva si se apaga). */
    fun ensureAccessibility(ctx: Context) {
        if (accessibilityEnabled(ctx) || !secureSettings(ctx)) return
        try {
            val cr = ctx.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = current.split(':').filter { it.isNotBlank() } + accessibilityComponent(ctx)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            AppLog.i("Servicio de accesibilidad activado")
        } catch (e: Exception) {
            AppLog.i("No se pudo activar la accesibilidad: ${e.message}")
        }
    }

    fun json(ctx: Context): JSONObject = JSONObject()
        .put("secureSettings", secureSettings(ctx))
        .put("accessibility", accessibilityEnabled(ctx))
        .put("overlay", overlay(ctx))
        .put("usageStats", usageStats(ctx))
        .put("install", install(ctx))
        .put("storage", storage(ctx))

    /** Lo esencial para el modo permisos: abrir apps desde segundo plano y controlar con el mando. */
    fun ready(ctx: Context) = overlay(ctx) && accessibilityEnabled(ctx)

    fun missing(ctx: Context): List<String> = buildList {
        if (!secureSettings(ctx)) add("ajustes seguros")
        if (!accessibilityEnabled(ctx)) add("accesibilidad")
        if (!overlay(ctx)) add("superposición")
        if (!usageStats(ctx)) add("estadísticas de uso")
        if (!install(ctx)) add("instalar apps")
        if (!storage(ctx)) add("archivos")
    }
}
