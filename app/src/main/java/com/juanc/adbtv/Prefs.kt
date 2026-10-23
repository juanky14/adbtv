package com.juanc.adbtv

import android.content.Context
import java.security.SecureRandom

object Prefs {
    const val WEB_PORT = 8080
    private const val FILE = "adbtv"

    fun pin(ctx: Context): String {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString("pin", null) ?: newPin(ctx)
    }

    fun newPin(ctx: Context): String {
        val pin = "%06d".format(SecureRandom().nextInt(1_000_000))
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("pin", pin).apply()
        return pin
    }

    fun autoStart(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("autostart", true)

    fun setAutoStart(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("autostart", value).apply()

    /** ¿Sustituir la pantalla de inicio del sistema por la de ADB TV? */
    fun homeOverride(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("home_override", false)

    fun setHomeOverride(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("home_override", value).apply()

    /** Apps de servicios de vídeo y música habituales que se ponen en favoritas la primera vez. */
    private val DEFAULT_FAVORITES = listOf(
        "com.netflix.ninja", "com.amazon.firebat", "com.amazon.amazonvideo.livingroom",
        "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.disney.disneyplus",
        "com.dazn", "com.spotify.tv.android", "tv.twitch.android.viewer", "tv.twitch.android.app",
        "org.jellyfin.androidtv", "com.rtve.androidtv", "com.amazon.venezia",
    )

    fun homeFavorites(ctx: Context): List<String> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.getString("home_favorites", null)?.let { s -> return s.split(',').filter { it.isNotBlank() } }
        // Primera vez: servicios conocidos instalados + apps instaladas por el usuario
        val installed = AppManager.launchables(ctx).map { it.first }
        val userApps = installed.filter { pkg ->
            try {
                ctx.packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
            } catch (_: Exception) { false }
        }
        return (DEFAULT_FAVORITES.filter { it in installed } + userApps).distinct().filter { it != ctx.packageName }
    }

    fun setHomeFavorites(ctx: Context, pkgs: List<String>) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("home_favorites", pkgs.joinToString(",")).apply()
}
