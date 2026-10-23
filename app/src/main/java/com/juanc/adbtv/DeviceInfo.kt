package com.juanc.adbtv

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** Identidad del TV y anuncio en la red (mDNS) para que Home Assistant lo descubra solo. */
object DeviceInfo {
    const val SERVICE_TYPE = "_adbtv._tcp."

    @SuppressLint("HardwareIds")
    fun id(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** Los Fire TV solo exponen códigos de modelo (AFTMM…): los traducimos a su nombre comercial. */
    private val FIRE_TV_MODELS = mapOf(
        "AFTB" to "Fire TV (1.ª gen)",
        "AFTS" to "Fire TV (2.ª gen)",
        "AFTN" to "Fire TV (3.ª gen)",
        "AFTM" to "Fire TV Stick (1.ª gen)",
        "AFTT" to "Fire TV Stick (2.ª gen)",
        "AFTSSS" to "Fire TV Stick (3.ª gen)",
        "AFTSS" to "Fire TV Stick Lite",
        "AFTMM" to "Fire TV Stick 4K",
        "AFTKM" to "Fire TV Stick 4K (2.ª gen)",
        "AFTKA" to "Fire TV Stick 4K Max",
        "AFTKRT" to "Fire TV Stick 4K Max (2.ª gen)",
        "AFTA" to "Fire TV Cube (1.ª gen)",
        "AFTR" to "Fire TV Cube (2.ª gen)",
        "AFTGAZL" to "Fire TV Cube (3.ª gen)",
    )

    val isFireTv: Boolean get() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) && Build.MODEL.startsWith("AFT")

    fun modelName(): String = FIRE_TV_MODELS[Build.MODEL]
        ?: if (isFireTv) "Fire TV (${Build.MODEL})" else "${Build.MANUFACTURER} ${Build.MODEL}"

    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: modelName()

    fun json(ctx: Context): JSONObject = JSONObject()
        .put("id", id(ctx))
        .put("name", name(ctx))
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("modelName", modelName())
        .put("fireTv", isFireTv)
        .put("android", Build.VERSION.RELEASE)
        .put("appVersion", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)

    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(ctx: Context, port: Int) {
        if (listener != null) return
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name(ctx)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", id(ctx))
            setAttribute("model", Build.MODEL)
            setAttribute("manufacturer", Build.MANUFACTURER)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) = AppLog.i("Anunciado en la red como '${si.serviceName}'")
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) = AppLog.i("No se pudo anunciar en la red ($code)")
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            AppLog.i("Error anunciando mDNS: ${e.message}")
        }
    }

    fun stopAdvertising(ctx: Context) {
        val l = listener ?: return
        try { (ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(l) } catch (_: Exception) {}
        listener = null
    }
}
