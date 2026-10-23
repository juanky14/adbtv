package com.juanc.adbtv

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Control del dispositivo con APIs de Android, para cuando no hay ADB (modo permisos). */
object NativeControl {

    private val main = Handler(Looper.getMainLooper())

    /** Las APIs de accesibilidad y de actividades se llaman desde el hilo principal. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching(block); latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return (result ?: Result.failure(IllegalStateException("Tiempo agotado"))).getOrThrow()
    }

    fun launch(ctx: Context, pkg: String): ExecResult {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return ExecResult("Esta app no tiene pantalla de inicio\n", 1, "app")
        return try {
            onMain { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ExecResult("Abierta $pkg\n", 0, "app")
        } catch (e: Exception) {
            ExecResult("No se pudo abrir $pkg: ${e.message}\n", 1, "app")
        }
    }

    fun requestUninstall(ctx: Context, pkg: String): ExecResult = try {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { ctx.startActivity(intent) }
        ExecResult("Confirma la desinstalación en la pantalla del televisor\n", 0, "app")
    } catch (e: Exception) {
        ExecResult("No se pudo pedir la desinstalación: ${e.message}\n", 1, "app")
    }

    private fun home(ctx: Context) = onMain {
        val intent = if (Prefs.homeOverride(ctx)) Intent(ctx, SimpleHomeActivity::class.java)
        else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }

    private fun media(ctx: Context, code: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    private fun volume(ctx: Context, direction: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (direction == 0) {
            if (Build.VERSION.SDK_INT >= 23) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
            }
        } else {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        return true
    }

    /** Enciende la pantalla: un wake lock con ACQUIRE_CAUSES_WAKEUP solo necesita WAKE_LOCK. */
    private fun wakeUp(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "adbtv:wake")
            .acquire(3000)
        return true
    }

    private val MEDIA_KEYS = mapOf(
        "MEDIA_PLAY_PAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "MEDIA_PLAY" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "MEDIA_PAUSE" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "MEDIA_STOP" to KeyEvent.KEYCODE_MEDIA_STOP,
        "MEDIA_NEXT" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "MEDIA_PREVIOUS" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "MEDIA_FAST_FORWARD" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "MEDIA_REWIND" to KeyEvent.KEYCODE_MEDIA_REWIND,
    )

    /** Teclas del mando que se pueden simular sin ADB. */
    val SUPPORTED_KEYS = setOf(
        "HOME", "BACK", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER",
        "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "WAKEUP", "SLEEP", "POWER", "SETTINGS",
    ) + MEDIA_KEYS.keys

    fun key(ctx: Context, key: String, times: Int): ExecResult {
        if (key !in SUPPORTED_KEYS) {
            return ExecResult("La tecla $key necesita ADB; sin ADB solo: ${SUPPORTED_KEYS.sorted().joinToString()}\n", 1, "app")
        }
        val acc = RemoteAccessibilityService.instance
        val needsAccessibility = key in setOf("BACK", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER", "SLEEP", "POWER")
        if (needsAccessibility && acc == null) {
            return ExecResult("La tecla $key necesita el servicio de accesibilidad de ADB TV activado\n", 1, "app")
        }
        var ok = true
        repeat(times) {
            ok = ok && try {
                when (key) {
                    "HOME" -> if (Prefs.homeOverride(ctx)) home(ctx)
                        else acc?.let { onMain { it.global(AccessibilityService.GLOBAL_ACTION_HOME) } } ?: home(ctx)
                    "BACK" -> onMain { acc!!.global(AccessibilityService.GLOBAL_ACTION_BACK) }
                    "DPAD_CENTER", "ENTER" -> onMain { acc!!.clickFocused() }
                    in RemoteAccessibilityService.DIRECTIONS -> onMain { acc!!.moveFocus(RemoteAccessibilityService.DIRECTIONS.getValue(key)) }
                    "VOLUME_UP" -> volume(ctx, 1)
                    "VOLUME_DOWN" -> volume(ctx, -1)
                    "VOLUME_MUTE" -> volume(ctx, 0)
                    "WAKEUP" -> wakeUp(ctx)
                    "SLEEP", "POWER" -> onMain { acc!!.lockScreen() }
                    "SETTINGS" -> onMain {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                    }
                    else -> media(ctx, MEDIA_KEYS.getValue(key))
                }
            } catch (e: Exception) {
                AppLog.i("Tecla $key: ${e.message}")
                false
            }
            if (times > 1) Thread.sleep(80)
        }
        return if (ok) ExecResult("", 0, "app") else ExecResult("No se pudo ejecutar la tecla $key\n", 1, "app")
    }

    fun foreground(ctx: Context): String? {
        RemoteAccessibilityService.foreground?.let { return it }
        if (!Permissions.usageStats(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 6 * 3600_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        return last
    }

    fun screenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }
}
