package com.juanc.adbtv

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
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

        val DIRECTIONS = mapOf(
            "DPAD_UP" to View.FOCUS_UP,
            "DPAD_DOWN" to View.FOCUS_DOWN,
            "DPAD_LEFT" to View.FOCUS_LEFT,
            "DPAD_RIGHT" to View.FOCUS_RIGHT,
        )
    }
}
