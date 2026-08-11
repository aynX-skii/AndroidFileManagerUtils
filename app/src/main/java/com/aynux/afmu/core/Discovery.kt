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
    /**
     * The pairing table, so a reply carrying only a rolling `rid` can still be resolved to a
     * device we know (§6.1). Null just means no reply is ever recognised — every peer shows
     * up as a bare address, which is also what happens with v1 peers.
     */
    private val peers: PeerStore? = null,
    /**
     * Our own SPKI fingerprint, used to compute the `rid` we advertise. Null means we
     * advertise none, and paired peers will not recognise us.
     */
    private val identityFp: ByteArray? = null,
    private val log: (String) -> Unit = {},
) {

    data class Peer(
        val name: String,
        val os: String,
        val host: String,
        val port: Int,
        /**
         * Non-empty when this peer is in our pairing table, recognised via the rolling `rid`
         * (or the `fp` a peer in pairing mode published). Empty is the common case: strangers,
         * v1 devices, and anything not yet paired.
         */
        val fingerprint: String = "",
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

            val reply = describe(prefs, serverPort(), identityFp).toString()
                .toByteArray(Charsets.UTF_8)
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
                val peer = parse(host, String(packet.data, packet.offset, packet.length), peers)
                    ?: continue
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
                    String(packet.data, packet.offset, packet.length),
                    peers,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        const val PROBE_PREFIX = ProtocolConstants.PROBE_PREFIX

        const val PAIRING_MODE_SEC = ProtocolConstants.PAIRING_MODE_SEC.toLong()
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

        fun describe(prefs: Prefs, serverPort: Int, identityFp: ByteArray? = null): JSONObject =
            JSONObject()
                .put("afmu", HttpServer.PROTOCOL_VERSION)
                .put("port", serverPort)
                .also {
                    // The rolling id goes out in normal mode too (§6.1): a stranger sees only
                    // random hex, while a device we already paired with computes the same value.
                    // That is how "no name leak" and "my own devices stay recognisable" coexist.
                    RollingId.current(identityFp)?.let { rid -> it.put("rid", rid) }
                    if (pairingMode()) {
                        it.put("name", prefs.deviceName)
                        it.put("os", "android")
                        // The fingerprint goes out only in pairing mode — the minute the user
                        // asked for. Note this is **one leak = trackable forever**: whoever
                        // catches the fp can compute our rid in every future window (§6.2).
                        identityFp?.let { fp -> it.put("fp", Base32.encode(fp)) }
                    }
                }

        private fun parse(host: String, raw: String, peers: PeerStore? = null): Peer? {
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
            var name = json.optString("name").takeIf { it.isNotEmpty() }
                ?.also { knownNames[key] = it }
                ?: knownNames[key]
            var os = json.optString("os").takeIf { it.isNotEmpty() }
                ?.also { knownOs[key] = it }
                ?: knownOs[key]

            // The pairing table beats knownNames: the latter is keyed by host:port and is
            // lost the moment DHCP hands out a different address, while the table is keyed by
            // fingerprint and survives it (§13 question 3).
            val fp = peers?.let { identify(json, host, port, it) }
            if (fp != null) {
                val record = peers.find(fp)
                if (name == null) name = record?.name
                if (os == null) os = record?.os
            }

            return Peer(
                name = name ?: host,
                os = os ?: "unknown",
                host = host,
                port = port,
                fingerprint = fp.orEmpty(),
            )
        }

        /**
         * Which paired device sent this reply, or null when we do not know it — which is the
         * common case and not an error.
         */
        private fun identify(json: JSONObject, host: String, port: Int, peers: PeerStore): String? {
            // A peer in pairing mode publishes its `fp` outright (§6.2). It is public
            // information and leaking it costs no access. But **publishing it is not proof**:
            // only a fingerprint already in the table counts as recognition, or anyone could
            // claim to be a device we know.
            val claimed = PeerCodec.normalize(json.optString("fp"))
            if (claimed != null && peers.isPaired(claimed)) return noteIdentified(claimed, host, port, peers)

            val rid = json.optString("rid").takeIf { it.isNotEmpty() } ?: return null
            val now = System.currentTimeMillis() / 1000
            val hit = peers.all().firstOrNull { RollingId.matches(Base32.decode(it.fp), rid, now) }
            return hit?.let { noteIdentified(it.fp, host, port, peers) }
        }

        private fun noteIdentified(fp: String, host: String, port: Int, peers: PeerStore): String {
            // Recognising a device that moved to a new address is half the point of the rid
            // (§13 question 3). Refreshing the hint means the next connection already knows
            // which fingerprint to pin.
            peers.noteSeen(fp, host, port)
            return fp
        }
    }
}
