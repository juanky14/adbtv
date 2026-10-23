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
        btnStop = findViewById(R.id.btnStop)

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
        val status = buildString {
            append(if (running) "● Servicio activo" else "○ Servicio detenido")
            append("\n")
            val missing = Permissions.missing(this@MainActivity)
            append(
                when {
                    connected -> "● ADB por red activo y conectado (comandos como usuario shell)"
                    AdbBridge.blockedBySystem && missing.isEmpty() ->
                        "● Modo permisos: el sistema no permite ADB local, pero la app tiene todos los permisos"
                    AdbBridge.blockedBySystem ->
                        "◐ El sistema no permite ADB local. Para el modo permisos ejecuta una vez desde un PC:\n" +
                            "adb shell \"${Permissions.setupCommand(this@MainActivity)}\""
                    portOpen -> "◐ ADB por red activo; acepta el diálogo de depuración y pulsa Conectar"
                    else -> "○ ADB por red inactivo: actívalo en Opciones de desarrollador, con root, o con 'adb tcpip 5555'"
                }
            )
            if (AdbBridge.blockedBySystem && missing.isNotEmpty()) append("\nFaltan: ${missing.joinToString()}")
        }
        handler.post {
            txtStatus.text = status
            txtAdbCmd.text = if (portOpen) "adb connect $ip:${AdbBridge.ADB_PORT}" else "(ADB por red no disponible)"
            txtWebUrl.text = if (running) "http://$ip:${Prefs.WEB_PORT}" else "(servidor detenido)"
            txtPin.text = "PIN: $pin"
            btnStop.text = if (running) "Detener" else "Iniciar"
        }
    }

    private fun renderLog() {
        txtLog.text = AppLog.text()
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
