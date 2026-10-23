package com.juanc.adbtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.Locale

/**
 * Pantalla de inicio minimalista y sin anuncios: reloj, apps favoritas en mosaicos grandes,
 * "Todas las apps" y "Ajustes".
 *
 * - Sin scroll: las apps se reparten en páginas que caben enteras; bajar desde la última fila pasa
 *   a la página siguiente y subir desde la primera, a la anterior.
 * - Mantener pulsado OK abre un menú: mover (reordenar con las flechas) o quitar/añadir a favoritas.
 */
class SimpleHomeActivity : Activity() {

    private companion object {
        const val COLUMNS = 5
        const val TILE_DP = 92
        const val SPACING_DP = 16
        const val GRID_PADDING_DP = 12
        const val CORNER_DP = 12f
        const val UNFOCUSED_ALPHA = 0.78f
    }

    private data class Tile(val label: String, val pkg: String?, val action: String? = null) {
        val isApp get() = action == null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var title: TextView
    private lateinit var pager: TextView
    private lateinit var grid: GridView

    private var showAll = false
    private var page = 0
    private var rows = 3                       // filas que caben en pantalla (se mide al dibujar)
    private var allTiles = listOf<Tile>()      // todas las del modo actual
    private var tiles = listOf<Tile>()         // las de la página visible
    private var moving: String? = null         // paquete que se está reordenando
    private var highlighted: View? = null

    private val pageSize get() = rows * COLUMNS
    private val pageCount get() = maxOf(1, (allTiles.size + pageSize - 1) / pageSize)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.getTimeFormat(this@SimpleHomeActivity).format(now)
            date.text = DateFormat.format("EEEE, d 'de' MMMM", now).toString().replaceFirstChar { it.uppercase(Locale.getDefault()) }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding((48 * dp).toInt(), (20 * dp).toInt(), (48 * dp).toInt(), (12 * dp).toInt())
        }
        clock = TextView(this).apply { textSize = 44f; setTextColor(Color.WHITE) }
        date = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#8B98A5")) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
        }
        title = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#8B98A5")) }
        pager = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#8B98A5")); gravity = Gravity.END }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pad = (GRID_PADDING_DP * dp).toInt()
        grid = GridView(this).apply {
            numColumns = COLUMNS
            horizontalSpacing = (SPACING_DP * dp).toInt()
            verticalSpacing = (SPACING_DP * dp).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            clipToPadding = false
            // Margen para que el mosaico ampliado y su sombra no se corten en los bordes
            setPadding(pad, pad, pad, pad)
            // Nada de barras, brillos ni sombreados de desplazamiento: se pagina
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnItemClickListener { _, _, pos, _ -> if (moving == null) open(tiles[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> if (moving == null && !swallowCenterUp) showMenu(tiles[pos]); true }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    highlighted?.let { highlight(it, false, false) }
                    v?.let { highlight(it, true, tiles.getOrNull(pos)?.pkg == moving && moving != null) }
                    highlighted = v
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        // Cuántas filas caben enteras: así cada página llena la pantalla sin desplazarse
        grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val usable = bottom - top - 2 * pad + (SPACING_DP * dp).toInt()
            val fit = maxOf(1, usable / ((TILE_DP + SPACING_DP) * dp).toInt())
            if (fit != rows) {
                val selected = page * pageSize + maxOf(0, grid.selectedItemPosition)
                rows = fit
                grid.post { render(selected) }
            }
        }
        root.addView(clock)
        root.addView(date)
        root.addView(header)
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.autoStart(this) && !AdbServerService.running) AdbServerService.start(this)
        handler.post(tick)
        render(page * pageSize + maxOf(0, grid.selectedItemPosition))
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        moving = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Pulsar Inicio vuelve siempre a la primera página de favoritas
        showAll = false
        moving = null
        render(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            moving != null -> stopMoving()
            showAll -> { showAll = false; render(0) }
            page > 0 -> render(0)
            // la pantalla de inicio nunca se cierra con Atrás
        }
    }

    // ---------- Contenido y paginación ----------

    private fun buildTiles(): List<Tile> {
        val apps = AppManager.launchables(this).filter { it.first != packageName }
        return if (showAll) {
            // Las apps de sistema sin lanzador de TV (ajustes internos de Android, perfiles…) no son para la tele
            val pm = packageManager
            val keep = Prefs.homeFavorites(this).toSet() + Prefs.DEFAULT_FAVORITES
            val tvApps = apps.filter { (pkg, _) ->
                if (pkg in keep) return@filter true
                val system = try {
                    pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                } catch (_: Exception) { false }
                !system || pm.getLeanbackLaunchIntentForPackage(pkg) != null
            }
            tvApps.map { Tile(it.second, it.first) } + Tile("Inicio original", null, "stock") + Tile("Volver", null, "back")
        } else {
            val byPkg = apps.toMap()
            Prefs.homeFavorites(this).mapNotNull { p -> byPkg[p]?.let { Tile(it, p) } } +
                listOf(Tile("Todas las apps", null, "all"), Tile("Ajustes", null, "settings"), Tile("ADB TV", packageName))
        }
    }

    /** Dibuja la página que contiene [selectGlobal] (índice en la lista completa) y lo selecciona. */
    private fun render(selectGlobal: Int = 0) {
        allTiles = buildTiles()
        val index = selectGlobal.coerceIn(0, maxOf(0, allTiles.size - 1))
        page = index / pageSize
        val from = page * pageSize
        tiles = allTiles.subList(from, minOf(allTiles.size, from + pageSize))

        title.text = when {
            moving != null -> "Moviendo ${allTiles.firstOrNull { it.pkg == moving }?.label ?: ""}  ·  flechas para mover, OK para terminar"
            showAll -> "Todas las apps"
            else -> "Favoritas"
        }
        pager.text = if (pageCount > 1) "${page + 1} / $pageCount" else ""
        highlighted = null
        grid.adapter = TileAdapter()
        grid.requestFocus()
        grid.setSelection(index - from)
        // onItemSelected no salta si la posición no cambia: resaltamos la selección inicial a mano
        grid.post {
            val v = grid.selectedView ?: return@post
            if (v === highlighted) return@post
            highlighted?.let { highlight(it, false, false) }
            highlight(v, true, moving != null && tiles.getOrNull(grid.selectedItemPosition)?.pkg == moving)
            highlighted = v
        }
    }

    private var swallowCenterUp = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        // Pulsación larga de OK: la detectamos nosotros (primera repetición de la tecla, ~0,5 s) porque
        // no todos los mandos la entregan como "long press" a la cuadrícula. Se descarta el clic al soltar.
        if (isCenter && moving == null) {
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount >= 1 || event.isLongPress)) {
                if (!swallowCenterUp) {
                    swallowCenterUp = true
                    tiles.getOrNull(grid.selectedItemPosition)?.let { showMenu(it) }
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && swallowCenterUp) {
                swallowCenterUp = false
                return true
            }
        }
        if (moving != null) {
            // En modo mover, las flechas mueven la app y OK/Atrás terminan; nada llega a la cuadrícula
            if (event.action == KeyEvent.ACTION_DOWN) when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1)
                KeyEvent.KEYCODE_DPAD_UP -> moveBy(-COLUMNS)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(COLUMNS)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> stopMoving()
                else -> return super.dispatchKeyEvent(event)
            }
            return code in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            ) || super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && pageCount > 1) {
            val sel = grid.selectedItemPosition.takeIf { it >= 0 } ?: return super.dispatchKeyEvent(event)
            val row = sel / COLUMNS
            val col = sel % COLUMNS
            val lastRow = (tiles.size - 1) / COLUMNS
            val from = page * pageSize
            when {
                code == KeyEvent.KEYCODE_DPAD_DOWN && row == lastRow && page < pageCount - 1 -> {
                    render(minOf(from + pageSize + col, allTiles.size - 1)); return true
                }
                code == KeyEvent.KEYCODE_DPAD_UP && row == 0 && page > 0 -> {
                    render(from - pageSize + (rows - 1) * COLUMNS + col); return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT && sel == tiles.size - 1 && page < pageCount - 1 -> {
                    render(from + pageSize); return true
                }
                code == KeyEvent.KEYCODE_DPAD_LEFT && sel == 0 && page > 0 -> {
                    render(from - 1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Menú, favoritas y reordenación ----------

    private fun showMenu(t: Tile) {
        val pkg = t.pkg ?: return
        if (!t.isApp || pkg == packageName) return
        val isFavorite = pkg in Prefs.homeFavorites(this)
        val options = if (!showAll) listOf("Mover", "Quitar de favoritas")
        else listOf(if (isFavorite) "Quitar de favoritas" else "Añadir a favoritas")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Mover" -> startMoving(pkg)
                    "Quitar de favoritas" -> setFavorite(pkg, false)
                    "Añadir a favoritas" -> setFavorite(pkg, true)
                }
            }
            .show()
    }

    private fun setFavorite(pkg: String, favorite: Boolean) {
        val favs = Prefs.homeFavorites(this).toMutableList()
        favs.remove(pkg)
        if (favorite) favs.add(pkg)
        Prefs.setHomeFavorites(this, favs)
        val keep = page * pageSize + maxOf(0, grid.selectedItemPosition)
        render(if (showAll) keep else minOf(keep, maxOf(0, favs.size - 1)))
    }

    private fun startMoving(pkg: String) {
        moving = pkg
        render(Prefs.homeFavorites(this).indexOf(pkg))
    }

    private fun stopMoving() {
        val pkg = moving ?: return
        moving = null
        render(Prefs.homeFavorites(this).indexOf(pkg))
    }

    /** Mueve la favorita [delta] posiciones (±1 en horizontal, ±columnas en vertical) y guarda el orden. */
    private fun moveBy(delta: Int) {
        val pkg = moving ?: return
        val favs = Prefs.homeFavorites(this).toMutableList()
        val from = favs.indexOf(pkg)
        if (from < 0) { moving = null; render(0); return }
        val to = (from + delta).coerceIn(0, favs.size - 1)
        if (to == from) return
        favs.removeAt(from)
        favs.add(to, pkg)
        Prefs.setHomeFavorites(this, favs)
        render(to)
    }

    // ---------- Abrir ----------

    private fun open(t: Tile) {
        when (t.action) {
            "all" -> { showAll = true; render(0) }
            "back" -> { showAll = false; render(0) }
            "settings" -> {
                // En Fire TV los Ajustes se abren a través del inicio de Amazon: vamos directos a su pantalla
                RemoteAccessibilityService.allowStockHome(5_000)
                val fireSettings = Intent("amazon.intent.action.MAINSETTINGS").addCategory("android.intent.category.MAINSETTINGS")
                val intent = if (fireSettings.resolveActivity(packageManager) != null) fireSettings else Intent(Settings.ACTION_SETTINGS)
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "stock" -> {
                // El inicio original (perfiles, contenidos de Amazon…) durante 2 minutos
                RemoteAccessibilityService.allowStockHome(120_000)
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(RemoteAccessibilityService.stockHomePackage(this) ?: return)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> {
                val pm = packageManager
                val intent = if (t.pkg == packageName) Intent(this, MainActivity::class.java)
                else pm.getLeanbackLaunchIntentForPackage(t.pkg!!) ?: pm.getLaunchIntentForPackage(t.pkg)
                intent?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    // ---------- Mosaicos ----------

    private fun bannerOrIcon(pkg: String): Pair<Drawable?, Boolean> = try {
        val pm = packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        // Nuestra propia app, con el mismo estilo discreto que "Ajustes" (icono y nombre)
        val banner = if (pkg == packageName) null
        else pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(ai)
        if (banner != null) banner to true else pm.getApplicationIcon(ai) to false
    } catch (_: PackageManager.NameNotFoundException) {
        null to false
    }

    private inner class TileAdapter : BaseAdapter() {
        override fun getCount() = tiles.size
        override fun getItem(position: Int) = tiles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx: Context = this@SimpleHomeActivity
            val dp = resources.displayMetrics.density
            val t = tiles[position]
            val frame = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (TILE_DP * dp).toInt())
                background = tileBackground()
                // Recorta el banner con las esquinas redondeadas del fondo (si no, asoman sus esquinas cuadradas)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                alpha = UNFOCUSED_ALPHA
            }
            val (drawable, isBanner) = t.pkg?.let { bannerOrIcon(it) } ?: (null to false)
            if (drawable != null && isBanner) {
                frame.addView(ImageView(ctx).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                if (drawable != null) {
                    col.addView(ImageView(ctx).apply { setImageDrawable(drawable) }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
                } else {
                    col.addView(TextView(ctx).apply {
                        text = when (t.action) { "all" -> "▦"; "settings" -> "⚙"; "stock" -> "⌂"; else -> "←" }
                        textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    })
                }
                col.addView(TextView(ctx).apply {
                    text = t.label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), 0)
                })
                frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return frame
        }
    }

    /** Línea blanca fina por dentro del contorno; más marcada mientras se mueve la app. */
    private fun hairline(strong: Boolean) = GradientDrawable().apply {
        val dp = resources.displayMetrics.density
        cornerRadius = CORNER_DP * dp
        setColor(Color.TRANSPARENT)
        setStroke(((if (strong) 2.5f else 1.5f) * dp).toInt().coerceAtLeast(2), Color.argb(if (strong) 235 else 110, 255, 255, 255))
    }

    private fun tileBackground() = GradientDrawable().apply {
        cornerRadius = CORNER_DP * resources.displayMetrics.density
        setColor(Color.parseColor("#1B2229"))
    }

    /** Seleccionado: se eleva un poco (escala y sombra suaves); el resto se atenúa ligeramente. */
    // FrameLayout.setForeground existe desde Android 1 (en API 23 solo se movió a View)
    @android.annotation.SuppressLint("NewApi")
    private fun highlight(v: View, focused: Boolean, isMoving: Boolean) {
        val dp = resources.displayMetrics.density
        val scale = when { isMoving -> 1.1f; focused -> 1.07f; else -> 1f }
        (v as? FrameLayout)?.foreground = if (focused) hairline(isMoving) else null
        v.animate()
            .scaleX(scale).scaleY(scale)
            .alpha(if (focused) 1f else UNFOCUSED_ALPHA)
            .translationZ(if (focused) (if (isMoving) 16 else 10) * dp else 0f)
            .setDuration(140)
            .start()
    }
}
