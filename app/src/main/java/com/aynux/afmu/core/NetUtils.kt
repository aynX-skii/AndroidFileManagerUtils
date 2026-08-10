package com.aynux.afmu.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtils {

    data class LocalAddress(val iface: String, val ip: String, val isLikelyLan: Boolean)

    /** Every usable IPv4 address of this device, best candidate first. */
    fun localAddresses(): List<LocalAddress> {
        val out = ArrayList<LocalAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (nif in interfaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                out += LocalAddress(nif.name, addr.hostAddress ?: continue, addr.isSiteLocalAddress)
            }
        }
        // Wi-Fi first, then USB/other tethering, then everything else.
        return out.sortedWith(compareBy({ interfaceRank(it.iface) }, { it.ip }))
    }

    private fun interfaceRank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> 2
        name.startsWith("p2p") -> 3
        name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // cellular: last resort
        else -> 5
    }

    /** Broadcast addresses to send discovery probes to, plus the global fallback. */
    fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        if (interfaces != null) {
            for (nif in interfaces) {
                if (!runCatching { nif.isUp }.getOrDefault(false)) continue
                if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { out += it }
                }
            }
        }
        runCatching { out += InetAddress.getByName("255.255.255.255") }
        return out.toList()
    }

    /** Human-readable summary of the active network, used in the UI. */
    fun describeNetwork(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "other"
        }
    }

    fun onLan(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
