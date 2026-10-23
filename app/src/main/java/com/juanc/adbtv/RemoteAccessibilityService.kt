package com.juanc.adbtv

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Mando a distancia sin ADB: acciones globales (Inicio, Atrás, apagar pantalla) y navegación con
 * flechas moviendo el foco de la app en pantalla. También sabe qué app está en primer plano.
 */
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        AppLog.i("Control remoto (accesibilidad) listo")
        // Fire OS 8 no entrega BOOT_COMPLETED a apps instaladas a mano, pero sí arranca este servicio al
        // encender: lo aprovechamos para levantar el servidor
        if (Prefs.autoStart(this) && !AdbServerService.running) AdbServerService.start(this)
        // Al encender, el inicio del sistema puede aparecer antes de que este servicio arranque
        rootInActiveWindow?.packageName?.toString()?.let { pkg ->
            val cls = stockHomes(this).firstOrNull { it.first == pkg }?.second
            if (cls != null) {
                lastWindow = pkg to cls
                maybeReplaceStockHome(pkg, cls)
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignora capas del sistema y teclados, que no son "la app abierta"
        if (pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod")) return
        foreground = pkg
        lastWindow = pkg to event.className?.toString()
        maybeReplaceStockHome(pkg, event.className?.toString())
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastWindow: Pair<String, String?>? = null

    private fun isStockHome(pkg: String, cls: String?): Boolean {
        if (cls == null) return false
        val homes = stockHomeCache ?: stockHomes(this).also { stockHomeCache = it }
        return homes.any { (p, c) -> p == pkg && (cls == c || cls.endsWith("." + c.substringAfterLast('.'))) }
    }

    /**
     * Botón Inicio del mando con el inicio de ADB TV activado: abre el nuestro directamente, sin pasar
     * por el del sistema. Mantenerlo pulsado abre el inicio original (escape por si hiciera falta).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !Prefs.homeOverride(this)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 1) {
                homeLongPressed = true
                openStockHome(this)
            }
            KeyEvent.ACTION_UP -> {
                if (!homeLongPressed && !event.isCanceled) openSimpleHome()
                homeLongPressed = false
            }
        }
        return true
    }

    private var homeLongPressed = false

    private fun openSimpleHome() {
        stockHomeAllowedUntil = 0L
        handler.removeCallbacksAndMessages(null)
        startActivity(
            android.content.Intent(this, SimpleHomeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    /**
     * Si está activado el inicio de ADB TV y aparece la pantalla de inicio del sistema (p. ej. la de
     * Amazon, que Fire OS no deja desactivar), la sustituimos por la nuestra. Solo la actividad de
     * inicio: los Ajustes de Fire TV viven en el mismo paquete y deben seguir funcionando.
     */
    private fun maybeReplaceStockHome(pkg: String, cls: String?) {
        if (!Prefs.homeOverride(this) || System.currentTimeMillis() < stockHomeAllowedUntil) return
        if (!isStockHome(pkg, cls)) return
        // El inicio de Amazon también se usa de paso (p. ej. para abrir Ajustes): solo lo sustituimos si
        // sigue en pantalla un momento después
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val (p, c) = lastWindow ?: return@postDelayed
            if (!isStockHome(p, c) || System.currentTimeMillis() < stockHomeAllowedUntil) return@postDelayed
            openSimpleHome()
        }, 800)
    }

    override fun onInterrupt() {}

    fun global(action: Int) = performGlobalAction(action)

    /** Mueve el foco en la dirección indicada (View.FOCUS_UP/DOWN/LEFT/RIGHT). */
    fun moveFocus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        // 1) Búsqueda estándar de Android (apps con vistas clásicas)
        focused.focusSearch(direction)?.let { next ->
            if (next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
        }
        // 2) Búsqueda geométrica: interfaces propias (launcher de Fire TV, Compose…) no implementan focusSearch
        val from = Rect().also { focused.getBoundsInScreen(it) }
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectFocusable(root, candidates)
        val best = candidates
            .filter { (n, r) -> n != focused && r != from && isInDirection(from, r, direction) }
            .minByOrNull { (_, r) -> score(from, r, direction) }
            ?: return false
        return best.first.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun collectFocusable(node: AccessibilityNodeInfo, out: MutableList<Pair<AccessibilityNodeInfo, Rect>>, depth: Int = 0) {
        if (depth > 40 || !node.isVisibleToUser) return
        if (node.isFocusable && node.isEnabled) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) out += node to r
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectFocusable(it, out, depth + 1) }
    }

    private fun isInDirection(from: Rect, to: Rect, direction: Int) = when (direction) {
        View.FOCUS_RIGHT -> to.centerX() > from.centerX() && to.left >= from.centerX()
        View.FOCUS_LEFT -> to.centerX() < from.centerX() && to.right <= from.centerX()
        View.FOCUS_DOWN -> to.centerY() > from.centerY() && to.top >= from.centerY()
        View.FOCUS_UP -> to.centerY() < from.centerY() && to.bottom <= from.centerY()
        else -> false
    }

    /** Distancia en el eje del movimiento + desvío lateral penalizado (lo que haría el foco de Android). */
    private fun score(from: Rect, to: Rect, direction: Int): Long {
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        val major = if (horizontal) abs(to.centerX() - from.centerX()) else abs(to.centerY() - from.centerY())
        val minor = if (horizontal) abs(to.centerY() - from.centerY()) else abs(to.centerX() - from.centerX())
        return major.toLong() + 3L * minor
    }

    /** Pulsa el elemento con el foco (o el primer padre que admita clic). */
    fun clickFocused(): Boolean {
        var node: AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        var depth = 0
        while (node != null && depth < 6) {
            val canClick = node.isClickable ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (canClick && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        return false
    }

    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
        @Volatile var foreground: String? = null
            private set

        @Volatile private var stockHomeCache: List<Pair<String, String>>? = null
        @Volatile private var stockHomeAllowedUntil = 0L

        private fun homeIntent() =
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)

        /**
         * Todas las actividades de inicio del sistema (las que se abrirían sin ADB TV). Incluye la clase
         * real de los alias: la ventana que se ve en pantalla lleva el nombre de la actividad de destino.
         */
        fun stockHomes(ctx: android.content.Context): List<Pair<String, String>> =
            ctx.packageManager.queryIntentActivities(homeIntent(), 0)
                .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "android" }
                .flatMap { r ->
                    val a = r.activityInfo
                    listOfNotNull(a.packageName to a.name, a.targetActivity?.let { a.packageName to it })
                }

        fun stockHomePackage(ctx: android.content.Context): String? {
            // El inicio predeterminado del sistema (si no somos nosotros), si no el de más prioridad
            ctx.packageManager.resolveActivity(homeIntent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.takeIf { it != ctx.packageName && it != "android" }
                ?.let { return it }
            return ctx.packageManager.queryIntentActivities(homeIntent(), 0)
                .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "android" }
                .maxByOrNull { it.priority }?.activityInfo?.packageName
        }

        /** Abre el inicio original del sistema durante 2 minutos sin que lo sustituyamos. */
        fun openStockHome(ctx: android.content.Context) {
            allowStockHome(120_000)
            ctx.startActivity(
                homeIntent().setPackage(stockHomePackage(ctx) ?: return)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /** Permite ver el inicio del sistema un rato (p. ej. al abrir los Ajustes de Fire TV desde el nuestro). */
        fun allowStockHome(ms: Long) {
            stockHomeAllowedUntil = System.currentTimeMillis() + ms
        }

        val DIRECTIONS = mapOf(
            "DPAD_UP" to View.FOCUS_UP,
            "DPAD_DOWN" to View.FOCUS_DOWN,
            "DPAD_LEFT" to View.FOCUS_LEFT,
            "DPAD_RIGHT" to View.FOCUS_RIGHT,
        )
    }
}
