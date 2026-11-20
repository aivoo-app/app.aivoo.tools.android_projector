package app.aivoo.tools.androidprojector.util

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private val PREFERRED = listOf("wlan0", "eth0", "en0")
    private val IGNORED_PREFIXES = listOf("tun", "p2p", "rmnet", "docker", "veth", "br-", "dummy")

    fun getIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase()
                if (IGNORED_PREFIXES.any { name.startsWith(it) }) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val host = addr.hostAddress?.substringBefore('%') ?: continue
                    if (name in PREFERRED) return host
                    if (addr.isSiteLocalAddress && fallback == null) {
                        fallback = host
                    }
                }
            }
            return fallback ?: "0.0.0.0"
        } catch (_: Exception) {
            return "0.0.0.0"
        }
    }
}