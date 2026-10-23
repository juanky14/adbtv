package com.juanc.adbtv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var txtStatus: TextView
    private lateinit var txtAdbCmd: TextView
    private lateinit var txtWebUrl: TextView
    private lateinit var txtPin: TextView
    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStop: Button
    private lateinit var btnHome: Button

    private val logListener: () -> Unit = { handler.post { renderLog() } }
    private val ticker = object : Runnable {
        override fun run() {
            Thread { renderStatus() }.start()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtStatus = findViewById(R.id.txtStatus)
        txtAdbCmd = findViewById(R.id.txtAdbCmd)
        txtWebUrl = findViewById(R.id.txtWebUrl)
        txtPin = findViewById(R.id.txtPin)
        txtLog = findViewById(R.id.txtLog)
        scrollLog = findViewById(R.id.scrollLog)
        // ScrollView se vuelve enfocable al crearse (ignora el XML): el registro no debe quitar el foco a los botones
        scrollLog.isFocusable = false
        scrollLog.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        btnStop = findViewById(R.id.btnStop)
        btnHome = findViewById(R.id.btnHome)
        btnHome.setOnClickListener {
            val enable = !Prefs.homeOverride(this)
            if (enable && !Permissions.accessibilityEnabled(this)) {
                AppLog.i("El inicio sencillo necesita el servicio de accesibilidad (modo permisos)")
                return@setOnClickListener
            }
            Prefs.setHomeOverride(this, enable)
            AppLog.i(if (enable) "Inicio sencillo activado: el botón Inicio abre la pantalla de ADB TV" else "Inicio sencillo desactivado")
            if (enable) startActivity(android.content.Intent(this, SimpleHomeActivity::class.java))
            Thread { renderStatus() }.start()
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            ensureRunning()
            Thread { AdbServerService.setupAdb(applicationContext); renderStatus() }.start()
        }
        findViewById<Button>(R.id.btnRoot).setOnClickListener {
            Thread {
                if (AdbBridge.enableWithRoot()) AdbBridge.connect(applicationContext, force = true)
                renderStatus()
            }.start()
        }
        findViewById<Button>(R.id.btnNewPin).setOnClickListener {
            Prefs.newPin(this)
            AppLog.i("PIN de la terminal web cambiado")
            Thread { renderStatus() }.start()
        }
        btnStop.setOnClickListener {
            if (AdbServerService.running) {
                AdbServerService.stop(this)
                Prefs.setAutoStart(this, false)
            } else {
                Prefs.setAutoStart(this, true)
                ensureRunning()
            }
            handler.postDelayed({ Thread { renderStatus() }.start() }, 300)
        }

        ensureRunning()
        findViewById<View>(R.id.btnConnect).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus !is Button) findViewById<View>(R.id.btnConnect).requestFocus()
        AppLog.addListener(logListener)
        renderLog()
        handler.post(ticker)
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun ensureRunning() {
        if (Prefs.autoStart(this) && !AdbServerService.running) AdbServerService.start(this)
    }

    /** Se llama desde un hilo de fondo (hace comprobaciones de red) y publica en la UI. */
    private fun renderStatus() {
        val ip = NetUtils.localIp() ?: "sin red"
        val portOpen = AdbBridge.isAdbPortOpen()
        val connected = AdbBridge.isConnected
        val running = AdbServerService.running
        val pin = Prefs.pin(this)
        val missing = Permissions.missing(this)
        // Cada línea: un punto de color discreto (bien / atención / apagado) y un texto corto
        val lines = listOf(
            (if (running) OK else OFF) to (if (running) "Servicio activo" else "Servicio detenido"),
            when {
                connected -> OK to "Conectado por ADB"
                AdbBridge.blockedBySystem && missing.isEmpty() -> OK to "Modo permisos: control completo sin ADB"
                AdbBridge.blockedBySystem ->
                    WARN to "Faltan permisos (${missing.joinToString()}). Ejecuta una vez desde un PC:\n" +
                        "adb shell \"${Permissions.setupCommand(this)}\""
                portOpen -> WARN to "ADB disponible: acepta el aviso de depuración y pulsa Conectar"
                else -> OFF to "ADB por red desactivado (Opciones de desarrollador, root o 'adb tcpip 5555')"
            },
        )
        val status = android.text.SpannableStringBuilder()
        lines.forEachIndexed { i, (color, text) ->
            if (i > 0) status.append("\n")
            val start = status.length
            status.append("●  ")
            status.setSpan(android.text.style.ForegroundColorSpan(color), start, start + 1, 0)
            status.append(text)
        }
        handler.post {
            txtStatus.text = status
            txtAdbCmd.text = if (portOpen) "adb connect $ip:${AdbBridge.ADB_PORT}" else "No disponible"
            txtWebUrl.text = if (running) "http://$ip:${Prefs.WEB_PORT}" else "Servidor detenido"
            txtPin.text = pin
            btnStop.text = if (running) "Detener" else "Iniciar"
            btnHome.text = if (Prefs.homeOverride(this)) "Inicio: sí" else "Inicio: no"
        }
    }

    private companion object {
        val OK = android.graphics.Color.parseColor("#8FBF94")
        val WARN = android.graphics.Color.parseColor("#D9B26A")
        val OFF = android.graphics.Color.parseColor("#5C646E")
    }

    private fun renderLog() {
        txtLog.text = AppLog.text()
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
