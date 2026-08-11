package com.aynux.afmu.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single owner of the server and the discovery responder, shared by the UI and the
 * foreground service so both see the same live state.
 */
object Bridge {

    data class State(
        val running: Boolean = false,
        val port: Int = 0,
        val addresses: List<NetUtils.LocalAddress> = emptyList(),
        val network: String = "",
        val onLan: Boolean = false,
        val log: List<String> = emptyList(),
        /** The last PC that paired with us, and a counter so repeats still register. */
        val pairedWith: Discovery.Peer? = null,
        val pairEvents: Int = 0,
    ) {
        /** URLs to open in a desktop browser, best candidate first. */
        val urls: List<String> get() = addresses.map { "http://${it.ip}:$port" }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: HttpServer? = null
    private var discovery: Discovery? = null
    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context, prefs: Prefs) {
        val app = context.applicationContext
        appContext = app
        if (server?.isRunning == true) {
            refresh(app)
            return
        }
        // The pairing table is what makes v2 possible at all: it is the list of fingerprints
        // allowed through the handshake. Without it the server stays v1-only.
        val peers = PeerStore(app)
        val httpServer = HttpServer(app, prefs, peers) { log(it) }
        val port = httpServer.start()
        server = httpServer

        // The responder needs our own fingerprint to advertise a rolling `rid` (§6.1), and
        // the table to recognise the replies it hears. Minting touches the keystore, so this
        // may be null on a device where that failed — discovery then behaves exactly as in v1
        // rather than refusing to start.
        val identityFp = runCatching { Identity.ensure()?.fingerprint }.getOrNull()
        val responder = Discovery(prefs, peers, identityFp) { log(it) }
        runCatching { responder.start { server?.port ?: 0 } }
            .onFailure { log("Discovery unavailable: ${it.message}") }
        discovery = responder

        _state.update { it.copy(running = true, port = port) }
        refresh(app)
    }

    @Synchronized
    fun stop() {
        // Nobody can poll for a verdict once the server is down, and leaving a prompt on
        // screen for a connection that can no longer happen is just confusing.
        AuthRequests.clear()
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        _state.update { it.copy(running = false, port = 0) }
    }

    /** Re-reads network interfaces; call after the Wi-Fi state changes. */
    fun refresh(context: Context) {
        _state.update {
            it.copy(
                addresses = NetUtils.localAddresses(),
                network = NetUtils.describeNetwork(context),
                onLan = NetUtils.onLan(context),
                port = server?.port ?: 0,
                running = server?.isRunning == true,
            )
        }
    }

    /** [peers] is optional; without it a paired PC that changed IP shows up as a stranger. */
    fun probePeers(prefs: Prefs, peers: PeerStore? = null): List<Discovery.Peer> =
        runCatching { Discovery(prefs, peers) { log(it) }.probe() }.getOrElse {
            log("Peer scan failed: ${it.message}")
            emptyList()
        }

    /**
     * A peer wrote its details to `/api/pair`. Published as state rather than handled here
     * because the server thread has no business touching the UI's peer selection.
     */
    fun notePaired(peer: Discovery.Peer) =
        _state.update { it.copy(pairedWith = peer, pairEvents = it.pairEvents + 1) }

    fun log(message: String) {
        val stamped = "${timeFormat.format(Date())}  $message"
        _state.update { it.copy(log = (it.log + stamped).takeLast(MAX_LOG_LINES)) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    private const val MAX_LOG_LINES = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
}
