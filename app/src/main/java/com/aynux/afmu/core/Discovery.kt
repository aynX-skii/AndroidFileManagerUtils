package com.aynux.afmu.core

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * Zero-configuration peer finding over UDP broadcast — deliberately simpler and more
 * reliable across Android versions and desktop firewalls than mDNS.
 *
 * A probe is the literal string `AFMU-DISCOVER/1`; every listening device answers with a
 * one-line JSON description of itself. The token is never part of a discovery reply.
 */
class Discovery(
    private val prefs: Prefs,
    private val log: (String) -> Unit = {},
) {

    data class Peer(
        val name: String,
        val os: String,
        val host: String,
        val port: Int,
    ) {
        val url: String get() = "http://$host:$port"
    }

    @Volatile private var socket: DatagramSocket? = null

    val isRunning: Boolean get() = socket != null

    /** Starts answering probes so the PC can find this phone without anyone typing an IP. */
    fun start(serverPort: () -> Int) {
        stop()
        val sock = DatagramSocket(null as SocketAddress?).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(Prefs.DISCOVERY_PORT))
        }
        socket = sock
        Thread({ listenLoop(sock, serverPort) }, "afmu-discovery").apply { isDaemon = true }.start()
        log("Discovery responder on UDP ${Prefs.DISCOVERY_PORT}")
    }

    fun stop() {
        val sock = socket ?: return
        socket = null
        runCatching { sock.close() }
    }

    private fun listenLoop(sock: DatagramSocket, serverPort: () -> Int) {
        val buffer = ByteArray(2048)
        while (socket === sock) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                if (socket === sock) log("Discovery stopped: ${e.message}")
                return
            }
            val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
            if (!payload.startsWith(PROBE_PREFIX)) continue
            if (!prefs.discoverable) continue

            val reply = describe(prefs, serverPort()).toString().toByteArray(Charsets.UTF_8)
            runCatching {
                sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }.onFailure { log("Discovery reply failed: ${it.message}") }
        }
    }

    /**
     * Broadcasts a probe and collects everything that answers within [timeoutMs].
     * Used to find a PC running `afmu serve`.
     */
    fun probe(timeoutMs: Int = 1200): List<Peer> {
        val found = LinkedHashMap<String, Peer>()
        val mine = NetUtils.localAddresses().map { it.ip }.toSet()

        DatagramSocket(null as SocketAddress?).use { sock ->
            sock.reuseAddress = true
            sock.broadcast = true
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = 250

            val probe = "$PROBE_PREFIX\n".toByteArray(Charsets.UTF_8)
            for (target in NetUtils.broadcastTargets()) {
                runCatching {
                    sock.send(DatagramPacket(probe, probe.size, target, Prefs.DISCOVERY_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val host = packet.address?.hostAddress ?: continue
                if (host in mine) continue // our own responder answering the broadcast
                val peer = parse(host, String(packet.data, packet.offset, packet.length)) ?: continue
                found["${peer.host}:${peer.port}"] = peer
            }
        }
        return found.values.toList()
    }

    /** Direct probe of a known host, for when broadcast is blocked by the network. */
    fun probeHost(host: String, timeoutMs: Int = 800): Peer? {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return DatagramSocket(null as SocketAddress?).use { sock ->
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = timeoutMs
            val probe = "$PROBE_PREFIX\n".toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.send(DatagramPacket(probe, probe.size, address, Prefs.DISCOVERY_PORT))
                sock.receive(packet)
                parse(
                    packet.address?.hostAddress ?: host,
                    String(packet.data, packet.offset, packet.length)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        const val PROBE_PREFIX = "AFMU-DISCOVER"

        fun describe(prefs: Prefs, serverPort: Int): JSONObject = JSONObject()
            .put("afmu", HttpServer.PROTOCOL_VERSION)
            .put("name", prefs.deviceName)
            .put("os", "android")
            .put("port", serverPort)

        private fun parse(host: String, raw: String): Peer? {
            val json = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
            // Missing field: not one of ours. A major version we do not know: it may mean
            // anything at all, so refuse rather than guess (PROTOCOL.md §7).
            if (json.optInt("afmu", 0) != HttpServer.PROTOCOL_VERSION) return null
            val port = json.optInt("port", 0)
            if (port <= 0) return null
            return Peer(
                name = json.optString("name", host),
                os = json.optString("os", "unknown"),
                host = host,
                port = port,
            )
        }
    }
}
