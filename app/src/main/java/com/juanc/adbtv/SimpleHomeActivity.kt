package com.juanc.adbtv

import android.app.Activity
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
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale

/**
 * Pantalla de inicio minimalista y sin anuncios: reloj, apps favoritas en mosaicos grandes,
 * "Todas las apps" y "Ajustes". Mantener pulsado OK sobre una app la añade o quita de favoritas.
 */
class SimpleHomeActivity : Activity() {

    private data class Tile(val label: String, val pkg: String?, val action: String? = null)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var title: TextView
    private lateinit var grid: GridView
    private var showAll = false
    private var tiles = listOf<Tile>()

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
        title = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#8B98A5"))
            setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
        }
        grid = GridView(this).apply {
            numColumns = 5
            horizontalSpacing = (16 * dp).toInt()
            verticalSpacing = (16 * dp).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            clipToPadding = false
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            setOnItemClickListener { _, _, pos, _ -> open(tiles[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleFavorite(tiles[pos]); true }
        }
        root.addView(clock)
        root.addView(date)
        root.addView(title)
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.autoStart(this) && !AdbServerService.running) AdbServerService.start(this)
        handler.post(tick)
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Pulsar Inicio estando en "Todas las apps" vuelve a las favoritas
        showAll = false
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showAll) { showAll = false; render() }  // la pantalla de inicio nunca se cierra con Atrás
    }

    private fun render() {
        val apps = AppManager.launchables(this).filter { it.first != packageName }
        tiles = if (showAll) {
            title.text = "Todas las apps  ·  mantén pulsado OK para añadir o quitar de favoritas"
            apps.map { Tile(it.second, it.first) } + Tile("Inicio original", null, "stock") + Tile("Volver", null, "back")
        } else {
            title.text = "Favoritas"
            val byPkg = apps.toMap()
            Prefs.homeFavorites(this).mapNotNull { p -> byPkg[p]?.let { Tile(it, p) } } +
                listOf(Tile("Todas las apps", null, "all"), Tile("Ajustes", null, "settings"), Tile("ADB TV", packageName))
        }
        grid.adapter = TileAdapter()
        grid.requestFocus()
    }

    private fun open(t: Tile) {
        when (t.action) {
            "all" -> { showAll = true; render() }
            "back" -> { showAll = false; render() }
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

    private fun toggleFavorite(t: Tile) {
        val pkg = t.pkg ?: return
        if (pkg == packageName) return
        val favs = Prefs.homeFavorites(this).toMutableList()
        val added = if (pkg in favs) { favs.remove(pkg); false } else { favs.add(pkg); true }
        Prefs.setHomeFavorites(this, favs)
        Toast.makeText(this, if (added) "${t.label} añadida a favoritas" else "${t.label} quitada de favoritas", Toast.LENGTH_SHORT).show()
        if (!showAll) render()
    }

    private fun bannerOrIcon(pkg: String): Pair<Drawable?, Boolean> = try {
        val pm = packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
        val banner = launch?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(ai)
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
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (92 * dp).toInt())
                background = tileBackground(false)
            }
            val (drawable, isBanner) = t.pkg?.let { bannerOrIcon(it) } ?: (null to false)
            if (drawable != null && isBanner) {
                frame.addView(ImageView(ctx).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
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
                    maxLines = 1; setPadding(8, (6 * dp).toInt(), 8, 0)
                })
                frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return frame
        }
    }

    /** Borde de selección: va en el foreground para quedar por encima del banner de la app. */
    private fun focusRing() = GradientDrawable().apply {
        cornerRadius = 14 * resources.displayMetrics.density
        setColor(Color.TRANSPARENT)
        setStroke((4 * resources.displayMetrics.density).toInt(), Color.parseColor("#3DDC84"))
    }

    private fun tileBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = 14 * resources.displayMetrics.density
        setColor(Color.parseColor("#1B2229"))
        if (focused) setStroke((3 * resources.displayMetrics.density).toInt(), Color.parseColor("#3DDC84"))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Resalta el mosaico seleccionado con el mando (GridView no dibuja bien el selector sobre vistas propias)
        grid.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            private var last: View? = null
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                (last as? FrameLayout)?.let { it.foreground = null; it.scaleX = 1f; it.scaleY = 1f }
                (v as? FrameLayout)?.let { it.foreground = focusRing(); it.scaleX = 1.06f; it.scaleY = 1.06f }
                last = v
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }
}
