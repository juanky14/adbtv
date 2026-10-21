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

    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    fun json(ctx: Context): JSONObject = JSONObject()
        .put("id", id(ctx))
        .put("name", name(ctx))
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("appVersion", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)

    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(ctx: Context, port: Int) {
        if (listener != null) return
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
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
        try { ctx.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) {}
        listener = null
    }
}
