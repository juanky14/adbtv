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
}
