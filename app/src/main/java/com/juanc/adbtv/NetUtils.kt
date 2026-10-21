package com.juanc.adbtv

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object NetUtils {
    /** IPv4 de la red local; prefiere Ethernet y Wi-Fi sobre otras interfaces. */
    fun localIp(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("eth") || name.startsWith("wlan") }
        return (preferred ?: candidates.firstOrNull())?.second?.hostAddress
    }

    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }
}
