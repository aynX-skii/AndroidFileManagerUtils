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

        /** Must match `afmu::kPairingModeSec` on the Linux side. */
        const val PAIRING_MODE_SEC = 60L
        private val PAIRING_MODE_MS = PAIRING_MODE_SEC * 1000

        /**
         * Pairing mode (PROTOCOL.md §1.5).
         *
         * Normally the reply carries **no device name and no OS**: one UDP packet, and
         * anyone on the LAN learns "this phone is called Pixel 8 and runs Android" — an
         * information leak that needs no credential at all and happens without the owner
         * ever knowing. Only after the user explicitly taps "make discoverable" do we
         * answer in full, and only for [PAIRING_MODE_SEC].
         *
         * That shrinks the window in which a stranger can read the device name from
         * *forever* down to *the minute the user asked for*.
         */
        @Volatile
        private var pairingUntil = 0L

        fun startPairingMode(now: Long = System.currentTimeMillis()) {
            pairingUntil = now + PAIRING_MODE_MS
        }

        fun stopPairingMode() {
            pairingUntil = 0
        }

        fun pairingMode(now: Long = System.currentTimeMillis()): Boolean = pairingUntil > now

        /** Seconds left, for the countdown; 0 when off. */
        fun pairingSecondsLeft(now: Long = System.currentTimeMillis()): Int {
            val left = pairingUntil - now
            return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
        }

        /** Names we have seen before, so a device we already know still shows a name (§1.5). */
        private val knownNames = HashMap<String, String>()
        private val knownOs = HashMap<String, String>()

        fun describe(prefs: Prefs, serverPort: Int): JSONObject = JSONObject()
            .put("afmu", HttpServer.PROTOCOL_VERSION)
            .put("port", serverPort)
            .also {
                if (pairingMode()) {
                    it.put("name", prefs.deviceName)
                    it.put("os", "android")
                }
            }

        private fun parse(host: String, raw: String): Peer? {
            val json = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
            // Missing field: not one of ours. A major version we do not know: it may mean
            // anything at all, so refuse rather than guess (PROTOCOL.md §7).
            if (json.optInt("afmu", 0) != HttpServer.PROTOCOL_VERSION) return null
            val port = json.optInt("port", 0)
            if (port <= 0) return null

            // name/os are optional as of §1.5 — a peer outside pairing mode sends neither.
            // Remember them once seen, so the everyday list still shows names for devices we
            // already know and only strangers appear as a bare address.
            val key = "$host:$port"
            val name = json.optString("name").takeIf { it.isNotEmpty() }
                ?.also { knownNames[key] = it }
                ?: knownNames[key]
            val os = json.optString("os").takeIf { it.isNotEmpty() }
                ?.also { knownOs[key] = it }
                ?: knownOs[key]
            return Peer(
                name = name ?: host,
                os = os ?: "unknown",
                host = host,
                port = port,
            )
        }
    }
}
