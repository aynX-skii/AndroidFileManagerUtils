package com.aynux.afmu

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aynux.afmu.core.AuthRequests
import com.aynux.afmu.core.Bridge
import com.aynux.afmu.core.Discovery
import com.aynux.afmu.core.Identity
import com.aynux.afmu.core.LocaleHelper
import com.aynux.afmu.core.Base32
import com.aynux.afmu.core.PairPayload
import com.aynux.afmu.core.PairSas
import com.aynux.afmu.core.PeerClient
import com.aynux.afmu.core.PeerRecord
import com.aynux.afmu.core.PeerStore
import com.aynux.afmu.core.Prefs
import com.aynux.afmu.core.Storage
import com.aynux.afmu.service.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Status { RUNNING, DONE, FAILED }

    /** Which way the bytes are going — the transfer list mixes both. */
    enum class Direction { SEND, RECEIVE }

    data class Transfer(
        val id: Long,
        val name: String,
        val direction: Direction = Direction.SEND,
        val moved: Long = 0,
        val total: Long = -1,
        val status: Status = Status.RUNNING,
        val detail: String = "",
    ) {
        val fraction: Float get() = if (total > 0) (moved.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** One row of a `GET /api/list` reply from the PC. */
    data class RemoteEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
    )

    /** State of the remote file browser; null when the browser is closed. */
    data class RemoteBrowse(
        val peer: Discovery.Peer,
        val path: String = "/",
        val parent: String? = null,
        val entries: List<RemoteEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    ) {
        val files: List<RemoteEntry> get() = entries.filterNot { it.isDir }
    }

    /**
     * A request this phone has sent to another device, waiting for someone to tap Allow over
     * there. Mirrors the Linux client's side of PROTOCOL.md §3.8 — that is how one phone
     * connects to another without either of them typing a token.
     */
    data class OutgoingAuth(
        val peer: Discovery.Peer,
        /** Shown on both screens; the user compares them before allowing. */
        val code: String,
        val remaining: Int = AuthRequests.TIMEOUT_SEC,
        val sending: Boolean = true,
        /**
         * v2 pairing: the 8-character compare code, formatted `XXXX-XXXX`.
         *
         * **Computed here, not taken from the reply.** The server echoes its own value back
         * only so we can catch an implementation mismatch; showing that one would mean an
         * attacker just has to send the string you were hoping for (draft §4.2.3).
         */
        val sas: String = "",
    ) {
        val isPairing: Boolean get() = sas.isNotEmpty() || code.isEmpty()
    }

    data class UiState(
        val serverRunning: Boolean = false,
        val port: Int = 0,
        val urls: List<String> = emptyList(),
        val network: String = "",
        val onLan: Boolean = false,
        val log: List<String> = emptyList(),
        val token: String = "",
        val deviceName: String = "",
        val inbox: String = "",
        val writable: Boolean = true,
        val discoverable: Boolean = true,
        /** Seconds left of pairing mode; 0 when off (PROTOCOL.md §1.5). */
        val pairingSecondsLeft: Int = 0,
        val fullStorageAccess: Boolean = false,
        /** Devices seen on the network right now. Unrelated to [pairedPeers] — being visible
         *  is not being trusted. */
        val peers: List<Discovery.Peer> = emptyList(),
        /**
         * The v2 pairing table (PROTOCOL.md v2 §4.3). Empty until the mTLS handshake
         * lands; the list and its delete action exist first on purpose, so nothing can be
         * written here that the user cannot then remove.
         */
        val pairedPeers: List<PeerRecord> = emptyList(),
        /** Rows the last load discarded as unusable — said out loud rather than swallowed. */
        val pairedDropped: Int = 0,
        /**
         * This device's own fingerprint, grouped for reading. Empty when the identity could
         * not be minted (no AndroidKeyStore), which is also what turns encryption off.
         */
        val localFingerprint: String = "",
        /** Where the private key lives: StrongBox / TEE / software. Shown, not enforced. */
        val identityBacking: String = "",
        /** Serve encrypted connections only — the phone cannot serve both at once, see
         *  [com.aynux.afmu.core.HttpServer.handle]. */
        val encryptedOnly: Boolean = false,
        /** Zero-trust mode: paired devices only, encrypted only (draft §9). */
        val zeroTrustMode: Boolean = false,
        /** Guest mode: the browser interface and password auth. See [Prefs.guestMode]. */
        val guestMode: Boolean = false,
        val selectedPeer: Discovery.Peer? = null,
        val peerToken: String = "",
        val scanning: Boolean = false,
        val browse: RemoteBrowse? = null,
        val transfers: List<Transfer> = emptyList(),
        val message: String? = null,
        val language: String = Prefs.LANG_SYSTEM,
        /** Another device is asking to connect; non-null puts the approval dialog on screen. */
        val pendingAuth: AuthRequests.Request? = null,
        /** We are asking another device; non-null puts the waiting dialog on screen. */
        val outgoingAuth: OutgoingAuth? = null,
        val allowAuthRequests: Boolean = true,
        val scannerOpen: Boolean = false,
    )

    /** Toast/snackbar text has to follow the chosen language like the rest of the UI. */
    private fun str(resId: Int): String =
        LocaleHelper.wrap(getApplication()).getString(resId)

    private val prefs = Prefs(app)
    private val peerStore = PeerStore(app)
    private val client = PeerClient(app)
    private val ids = AtomicLong(0)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Last pairing already reflected in the UI; guards against re-applying on every emission. */
    private var seenPairEvent = 0

    /** The poll loop of an authorization we started; cancelled when the user backs out. */
    private var authJob: Job? = null
    private var pairingTicker: Job? = null

    init {
        reloadPrefs()
        viewModelScope.launch {
            Bridge.state.collect { bridge ->
                _state.update {
                    it.copy(
                        serverRunning = bridge.running,
                        port = bridge.port,
                        urls = bridge.urls,
                        network = bridge.network,
                        onLan = bridge.onLan,
                        log = bridge.log,
                    )
                }
                // A peer wrote its details to /api/pair while the app was open; adopt them
                // instead of waiting for the next onResume to re-read prefs.
                if (bridge.pairEvents != seenPairEvent) {
                    seenPairEvent = bridge.pairEvents
                    bridge.pairedWith?.let(::adoptPeer)
                }
            }
        }
        viewModelScope.launch {
            AuthRequests.pending.collect { request ->
                _state.update { it.copy(pendingAuth = request) }
            }
        }
        // Minting the identity touches the keystore, which is slow enough to matter and must
        // not happen on the main thread.
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Identity.ensure() }.getOrNull() }
            _state.update {
                it.copy(
                    localFingerprint = info?.fingerprintDisplay.orEmpty(),
                    identityBacking = info?.backing?.name.orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            peerStore.peers.collect { paired ->
                _state.update {
                    it.copy(pairedPeers = paired, pairedDropped = peerStore.droppedOnLoad)
                }
            }
        }
    }

    /**
     * Forgets a pairing. There is no confirmation here — the UI asks first, because this
     * closes a door the user may have to walk through again with the other device in hand.
     */
    fun unpair(fp: String) {
        if (peerStore.remove(fp)) {
            _state.update { it.copy(message = str(R.string.unpaired)) }
        }
    }

    fun reloadPrefs() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                token = prefs.token,
                deviceName = prefs.deviceName,
                writable = prefs.writable,
                discoverable = prefs.discoverable,
                encryptedOnly = !prefs.allowLegacyPlaintext,
                zeroTrustMode = prefs.zeroTrustMode,
                guestMode = prefs.guestMode,
                peerToken = prefs.peerToken,
                allowAuthRequests = prefs.allowAuthRequests,
                language = prefs.language,
                inbox = Storage.inboxDir(app, prefs).absolutePath,
                fullStorageAccess = Storage.hasFullAccess(app),
            )
        }
        Bridge.refresh(app)
    }

    // ------------------------------------------------------------------ server controls

    /**
     * Switches the receiving server between plaintext v1 and encrypted v2.
     *
     * Restarts the server when it is running: unlike the other toggles this one changes what
     * the listening socket *is*, so it cannot take effect on the next connection alone.
     */
    fun setEncryptedOnly(value: Boolean) {
        prefs.allowLegacyPlaintext = !value
        _state.update { it.copy(encryptedOnly = value) }
        if (_state.value.serverRunning) {
            val app = getApplication<Application>()
            TransferService.stop(app)
            TransferService.start(app)
        }
    }

    /**
     * Zero-trust mode: paired devices only, encrypted only (draft §9).
     *
     * Forces encrypted-only on as well, so the setting the server reads and the switch the
     * user sees never disagree. Restarts the server for the same reason [setEncryptedOnly]
     * does — this changes what the listening socket is.
     */
    fun setZeroTrustMode(value: Boolean) {
        prefs.zeroTrustMode = value
        if (value) prefs.allowLegacyPlaintext = false
        _state.update {
            it.copy(zeroTrustMode = value, encryptedOnly = value || it.encryptedOnly)
        }
        if (_state.value.serverRunning) {
            val app = getApplication<Application>()
            TransferService.stop(app)
            TransferService.start(app)
        }
    }

    /**
     * Guest mode: the browser interface and password authentication (draft §9).
     *
     * No restart — the server reads it per connection, so the next one already sees the
     * change. Restarting would cut off transfers in flight for nothing.
     */
    fun setGuestMode(value: Boolean) {
        prefs.guestMode = value
        _state.update { it.copy(guestMode = value) }
    }

    fun setServerRunning(running: Boolean) {
        val app = getApplication<Application>()
        // Remember the choice: the next launch must not silently start serving again.
        prefs.serverEnabled = running
        if (running) TransferService.start(app) else TransferService.stop(app)
    }

    fun refreshNetwork() = Bridge.refresh(getApplication())

    fun regenerateToken() {
        prefs.regenerateToken()
        _state.update { it.copy(token = prefs.token, message = str(R.string.msg_new_token)) }
        Bridge.log("Access token regenerated — reconnect your PC")
    }

    fun setDeviceName(name: String) {
        prefs.deviceName = name.trim().ifBlank { android.os.Build.MODEL }
        _state.update { it.copy(deviceName = prefs.deviceName) }
    }

    fun setWritable(value: Boolean) {
        prefs.writable = value
        _state.update { it.copy(writable = value) }
    }

    /**
     * Persists the language. Publishing it on the state is all it takes: the UI resolves its
     * strings against whatever this says (see ProvideAppLocale), so the switch is just the next
     * recomposition.
     */
    fun setLanguage(tag: String) {
        if (prefs.language == tag) return
        prefs.language = tag
        LocaleHelper.applyDefault(tag)
        _state.update { it.copy(language = tag) }
    }

    fun setDiscoverable(value: Boolean) {
        prefs.discoverable = value
        _state.update { it.copy(discoverable = value) }
    }

    /**
     * Publishes this phone's name in discovery replies for one minute (PROTOCOL.md §1.5).
     *
     * The countdown is driven here rather than read on demand, because the button has to
     * tick down on screen and nothing else would wake the UI.
     */
    fun startPairingMode() {
        Discovery.startPairingMode()
        pairingTicker?.cancel()
        pairingTicker = viewModelScope.launch {
            while (Discovery.pairingMode()) {
                _state.update { it.copy(pairingSecondsLeft = Discovery.pairingSecondsLeft()) }
                delay(1000)
            }
            _state.update { it.copy(pairingSecondsLeft = 0) }
        }
    }

    fun stopPairingMode() {
        Discovery.stopPairingMode()
        pairingTicker?.cancel()
        pairingTicker = null
        _state.update { it.copy(pairingSecondsLeft = 0) }
    }

    fun setAllowAuthRequests(value: Boolean) {
        prefs.allowAuthRequests = value
        if (!value) AuthRequests.clear()
        _state.update { it.copy(allowAuthRequests = value) }
    }

    // ------------------------------------------------------------ approving a PC

    /**
     * Grants the asking PC this phone's token. Nothing leaves the device until this runs —
     * the endpoint only ever parks a request until the user decides.
     */
    fun approveAuth() {
        val request = _state.value.pendingAuth ?: return
        AuthRequests.decide(request.id, granted = true)
        Bridge.log("Allowed ${request.name} (${request.host}) to connect")
        _state.update { it.copy(message = str(R.string.msg_auth_allowed)) }
    }

    fun denyAuth() {
        val request = _state.value.pendingAuth ?: return
        AuthRequests.decide(request.id, granted = false)
        Bridge.log("Denied ${request.name} (${request.host})")
    }

    // ------------------------------------------------------------------ QR pairing

    fun openScanner() = _state.update { it.copy(scannerOpen = true) }
    fun closeScanner() = _state.update { it.copy(scannerOpen = false) }

    fun reportCameraDenied() =
        _state.update { it.copy(message = str(R.string.msg_camera_needed)) }

    /**
     * A decoded QR code. Anything that is not one of ours is reported and ignored — a camera
     * pointed at the world decodes plenty of barcodes nobody asked about.
     */
    fun onCodeScanned(raw: String) {
        val payload = PairPayload.parse(raw)
        if (payload == null) {
            _state.update { it.copy(scannerOpen = false, message = str(R.string.msg_not_afmu_code)) }
            return
        }
        _state.update { it.copy(scannerOpen = false) }
        viewModelScope.launch { applyPairing(payload) }
    }

    /**
     * Stores what the code carried, then finds which of the PC's addresses actually answers —
     * a multi-homed PC advertises several, and only the one on our subnet will work.
     */
    private suspend fun applyPairing(payload: PairPayload) {
        if (payload.isV2) {
            applyPairingV2(payload)
            return
        }
        prefs.peerToken = payload.token
        _state.update { it.copy(peerToken = payload.token, scanning = true) }

        val reachable = withContext(Dispatchers.IO) {
            payload.hosts.firstNotNullOfOrNull { host ->
                val candidate = Discovery.Peer(
                    name = payload.name.ifBlank { host },
                    os = payload.os,
                    host = host,
                    port = payload.port,
                )
                runCatching { client.info(candidate, payload.token) }.getOrNull()?.let { info ->
                    candidate.copy(name = info.optString("name", candidate.name))
                }
            }
        }
        _state.update { it.copy(scanning = false) }

        if (reachable == null) {
            _state.update { it.copy(message = str(R.string.msg_scan_unreachable)) }
            Bridge.log("Scanned ${payload.hosts.firstOrNull()} but nothing answered")
            return
        }

        adoptPeer(reachable)
        _state.update { it.copy(message = str(R.string.msg_paired_with) + " " + reachable.name) }
        Bridge.log("Paired with ${reachable.name} by QR code")

        // Hand our own token back so the PC can pull from this phone without typing it.
        withContext(Dispatchers.IO) {
            runCatching {
                client.pair(
                    peer = reachable,
                    token = payload.token,
                    selfName = prefs.deviceName,
                    selfPort = Bridge.state.value.port.takeIf { it > 0 } ?: prefs.port,
                    selfToken = prefs.token,
                )
            }
        }.onFailure {
            // Not fatal: the PC simply has to be given this phone's token by hand.
            Bridge.log("Peer did not accept our token: ${it.message}")
        }
    }


    /**
     * The v2 path: the code carried a fingerprint instead of a token (draft §4.1).
     *
     * Scanning already authenticated the other end out of band, so the TLS connection is
     * pinned to that fingerprint from the very first packet — a relay cannot get into this
     * conversation. What is still needed is the *other* user's consent, because their device
     * has no way to know a QR was involved. That is what the compare code is for.
     */
    private suspend fun applyPairingV2(payload: PairPayload) {
        val fp = payload.fingerprint
        val nonceA = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val commit = MessageDigest.getInstance("SHA-256").digest(nonceA)

        val identity = withContext(Dispatchers.IO) { Identity.ensure() }
        if (identity == null) {
            _state.update { it.copy(message = str(R.string.msg_pair_no_identity)) }
            return
        }

        _state.update { it.copy(scanning = true) }

        // A multi-homed PC advertises several addresses; only the one on our subnet answers.
        val outcome = withContext(Dispatchers.IO) {
            payload.hosts.firstNotNullOfOrNull { host ->
                runCatching {
                    val started = client.pairCommit(
                        host, payload.port, fp, commit.toHex(), prefs.deviceName,
                    )
                    val session = started.optString("session")
                    val nonceB = started.optString("nb").fromHex()
                    if (session.isEmpty() || nonceB.size != 32) return@runCatching null

                    val revealed = client.pairReveal(host, payload.port, fp, session, nonceA.toHex())

                    // Ours, computed here. Theirs is only a cross-check for implementation bugs.
                    val mine = PairSas.compute(identity.fingerprint, fp.toFingerprintBytes(), nonceA, nonceB)
                    if (mine == null || mine != revealed.optString("sas")) return@runCatching null
                    Triple(host, session, mine)
                }.getOrNull()
            }
        }
        _state.update { it.copy(scanning = false) }

        if (outcome == null) {
            _state.update { it.copy(message = str(R.string.msg_pair_failed)) }
            Bridge.log("v2 pairing did not get past the compare-code step")
            return
        }
        val (host, session, sas) = outcome

        val peer = Discovery.Peer(payload.name.ifBlank { host }, payload.os, host, payload.port)
        _state.update {
            it.copy(outgoingAuth = OutgoingAuth(peer, code = "", sending = false,
                                                sas = PairSas.format(sas)))
        }
        Bridge.log("Pairing compare code $sas — check it against their screen")

        authJob?.cancel()
        authJob = viewModelScope.launch { pollPairingV2(peer, fp, session) }
    }

    /** Waits for the other user to tap Allow, then records the pairing. */
    private suspend fun pollPairingV2(peer: Discovery.Peer, fp: String, session: String) {
        for (second in AuthRequests.TIMEOUT_SEC downTo 1) {
            _state.update { s ->
                s.outgoingAuth?.let { s.copy(outgoingAuth = it.copy(remaining = second)) } ?: s
            }
            delay(1000)
            val reply = withContext(Dispatchers.IO) {
                runCatching { client.pairPoll(peer.host, peer.port, fp, session) }.getOrNull()
            } ?: continue

            when (reply.optString("status")) {
                "pending" -> continue
                "granted" -> {
                    peerStore.upsert(
                        PeerRecord(
                            fp = fp,
                            name = reply.optString("name").ifBlank { peer.name },
                            os = reply.optString("os").ifBlank { peer.os },
                            lastHost = peer.host,
                            lastPort = reply.optInt("port", peer.port),
                        )
                    )
                    _state.update {
                        it.copy(outgoingAuth = null, message = str(R.string.msg_paired_with) + " " + peer.name)
                    }
                    Bridge.log("Paired with ${peer.name} over an encrypted link")
                    return
                }
                else -> {
                    _state.update { it.copy(outgoingAuth = null, message = str(R.string.msg_pair_declined)) }
                    return
                }
            }
        }
        _state.update { it.copy(outgoingAuth = null, message = str(R.string.msg_pair_timeout)) }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        if (length % 2 != 0) ByteArray(0)
        else runCatching {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrDefault(ByteArray(0))

    private fun String.toFingerprintBytes(): ByteArray = Base32.decode(this) ?: ByteArray(0)

    // ------------------------------------------------------ asking another device to let us in

    /**
     * Asks [peer] to put an approval prompt on its own screen, and waits for the verdict.
     * This is the phone-to-phone (and phone-to-PC) path for when nobody wants to copy a
     * token by hand: allow it over there and this phone gets the token, then hands its own
     * back so both directions work at once.
     */
    fun requestAuthorization(peer: Discovery.Peer) {
        if (_state.value.outgoingAuth != null) return
        val code = "%04d".format(SecureRandom().nextInt(10_000))
        _state.update { it.copy(outgoingAuth = OutgoingAuth(peer = peer, code = code)) }
        authJob = viewModelScope.launch { runAuthorization(peer, code) }
    }

    fun cancelAuthorization() {
        authJob?.cancel()
        authJob = null
        _state.update { it.copy(outgoingAuth = null) }
    }

    private suspend fun runAuthorization(peer: Discovery.Peer, code: String) {
        val selfPort = Bridge.state.value.port.takeIf { it > 0 } ?: prefs.port

        val ticket = withContext(Dispatchers.IO) {
            runCatching { client.requestAuth(peer, prefs.deviceName, code, selfPort) }
        }.getOrElse {
            finishAuthorization(str(R.string.msg_auth_failed))
            return
        }

        if (ticket.outcome != PeerClient.AuthOutcome.OK) {
            finishAuthorization(
                when (ticket.outcome) {
                    PeerClient.AuthOutcome.UNSUPPORTED -> str(R.string.msg_auth_unsupported)
                    PeerClient.AuthOutcome.DISABLED -> str(R.string.msg_auth_disabled)
                    PeerClient.AuthOutcome.BUSY -> str(R.string.msg_auth_busy)
                    else -> str(R.string.msg_auth_failed)
                }
            )
            return
        }

        _state.update { it.copy(outgoingAuth = it.outgoingAuth?.copy(sending = false)) }
        Bridge.log("Asked ${peer.name} to approve — code $code")

        // One poll a second, and the deadline is ours as much as theirs: a peer that stops
        // answering must not leave this dialog up forever.
        //
        // The deadline is wall clock rather than a count of rounds. Each round also waits on
        // a request that may itself take seconds, so counting iterations would drift well
        // past the timeout the peer is enforcing on the very same request.
        val deadline = System.currentTimeMillis() + AuthRequests.TIMEOUT_SEC * 1000L
        while (true) {
            delay(1000)
            val remaining = ((deadline - System.currentTimeMillis() + 999) / 1000).toInt()
            if (remaining <= 0) break
            _state.update { it.copy(outgoingAuth = it.outgoingAuth?.copy(remaining = remaining)) }

            val reply = withContext(Dispatchers.IO) {
                runCatching { client.pollAuth(peer, ticket.id) }.getOrNull()
            } ?: continue // a dropped packet is not a verdict; ask again next second

            when (reply.optString("status")) {
                "pending" -> continue
                "granted" -> {
                    val token = reply.optString("token")
                    if (token.isEmpty()) {
                        finishAuthorization(str(R.string.msg_auth_failed))
                        return
                    }
                    adoptGrantedPeer(peer, reply, token)
                    return
                }
                "denied" -> {
                    finishAuthorization(str(R.string.msg_auth_denied))
                    return
                }
                else -> {
                    finishAuthorization(str(R.string.msg_auth_expired))
                    return
                }
            }
        }
        finishAuthorization(str(R.string.msg_auth_expired))
    }

    private suspend fun adoptGrantedPeer(peer: Discovery.Peer, reply: JSONObject, token: String) {
        prefs.peerToken = token
        val granted = peer.copy(
            name = reply.optString("name").ifBlank { peer.name },
            port = reply.optInt("port", peer.port).takeIf { it in 1..65535 } ?: peer.port,
        )
        adoptPeer(granted)
        finishAuthorization(str(R.string.msg_paired_with) + " " + granted.name)
        Bridge.log("Approved by ${granted.name} — token received")

        // Hand our own token back, so the other side can reach this phone too (§3.9).
        withContext(Dispatchers.IO) {
            runCatching {
                client.pair(
                    peer = granted,
                    token = token,
                    selfName = prefs.deviceName,
                    selfPort = Bridge.state.value.port.takeIf { it > 0 } ?: prefs.port,
                    selfToken = prefs.token,
                )
            }
        }.onFailure { Bridge.log("Peer did not accept our token: ${it.message}") }
    }

    private fun finishAuthorization(message: String) {
        authJob = null
        _state.update { it.copy(outgoingAuth = null, message = message) }
    }

    /** Puts a peer in the list and selects it, wherever it came from. */
    private fun adoptPeer(peer: Discovery.Peer) {
        prefs.lastPeer = "${peer.host}:${peer.port}"
        _state.update { current ->
            current.copy(
                peers = (current.peers + peer).distinctBy { "${it.host}:${it.port}" },
                selectedPeer = peer,
                peerToken = prefs.peerToken,
            )
        }
    }

    fun clearLog() = Bridge.clearLog()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // -------------------------------------------------------------------- peer controls

    fun scanForPeers() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Bridge.probePeers(prefs, peerStore) }
            _state.update { current ->
                val selected = current.selectedPeer
                    ?: found.firstOrNull { "${it.host}:${it.port}" == prefs.lastPeer }
                    ?: found.firstOrNull()
                current.copy(scanning = false, peers = found, selectedPeer = selected)
            }
            Bridge.log(
                if (found.isEmpty()) "No PC answered — is `afmu serve` running?"
                else "Found ${found.size} peer(s)"
            )
        }
    }

    fun selectPeer(peer: Discovery.Peer) {
        prefs.lastPeer = "${peer.host}:${peer.port}"
        _state.update { it.copy(selectedPeer = peer) }
    }

    /** Adds a PC by hand, for networks where UDP broadcast is filtered. */
    fun addPeerManually(hostPort: String) {
        val host = hostPort.substringBefore(':').trim()
        if (host.isEmpty()) return
        val typedPort = hostPort.substringAfter(':', "").toIntOrNull()
        val port = typedPort ?: Prefs.DEFAULT_PORT
        viewModelScope.launch {
            val peer = withContext(Dispatchers.IO) {
                val probed = Discovery(prefs, peerStore).probeHost(host)
                when {
                    probed == null -> Discovery.Peer(host, "linux", host, port)
                    // A typed port wins over the discovery reply. The probe is a convenience —
                    // it fills in the name and the port when the user gave neither. Letting it
                    // overwrite a port the user typed produces "I entered :9765 and it connected
                    // to :8765", with nothing on screen admitting the substitution.
                    typedPort != null -> probed.copy(port = typedPort)
                    else -> probed
                }
            }
            _state.update {
                it.copy(peers = (it.peers + peer).distinctBy { p -> "${p.host}:${p.port}" })
            }
            selectPeer(peer)
            Bridge.log("Added peer ${peer.host}:${peer.port}")
        }
    }

    fun setPeerToken(token: String) {
        prefs.peerToken = token.trim()
        _state.update { it.copy(peerToken = prefs.peerToken) }
    }

    // ------------------------------------------------------------------------ transfers

    /**
     * Pushes files picked by the user (or shared into the app) to the selected PC,
     * scanning for one first when nothing is selected yet — a share from another app
     * should not dead-end on "pick a peer".
     */
    fun sendToPeer(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val target = resolvePeer() ?: return@launch
            if (!peerAuthReady(target)) return@launch
            // One at a time, for the same reason downloads are serial: sharing a few
            // hundred photos would otherwise open that many simultaneous connections,
            // and the peer spawns a thread per connection.
            uris.forEach { uri -> sendOne(target, uri) }
        }
    }

    /**
     * The peer to talk to: whatever is selected, otherwise whatever a fresh scan turns up.
     * Reports the reason and returns null when nothing usable is found.
     */
    private suspend fun resolvePeer(): Discovery.Peer? {
        _state.value.selectedPeer?.let { return it }

        _state.update { it.copy(scanning = true) }
        val found = withContext(Dispatchers.IO) { Bridge.probePeers(prefs, peerStore) }
        val peer = found.firstOrNull { "${it.host}:${it.port}" == prefs.lastPeer }
            ?: found.firstOrNull()
        _state.update { it.copy(scanning = false, peers = found, selectedPeer = peer) }

        if (peer == null) {
            _state.update {
                it.copy(message = str(R.string.msg_no_pc_found))
            }
        } else {
            prefs.lastPeer = "${peer.host}:${peer.port}"
        }
        return peer
    }

    /**
     * Do we have any way to authenticate to [peer]?
     *
     * A paired peer needs **no token at all** — the handshake against a pinned key is the
     * authentication (PROTOCOL.md v2 §5.2), and asking for one here would make v2 strictly
     * harder to use than v1: the user would be told to type a password that the other end
     * has stopped accepting.
     */
    private fun peerAuthReady(peer: Discovery.Peer): Boolean {
        if (peerStore.findByAddressHint(peer.host, peer.port) != null) return true
        if (_state.value.peerToken.isNotBlank()) return true
        _state.update { it.copy(message = str(R.string.msg_enter_pc_token)) }
        return false
    }

    private suspend fun sendOne(peer: Discovery.Peer, uri: Uri) {
        val file = withContext(Dispatchers.IO) { client.describe(uri) }
        val id = ids.incrementAndGet()
        addTransfer(Transfer(id = id, name = file.name, total = file.size))

        withContext(Dispatchers.IO) {
            runCatching {
                client.upload(peer, prefs.peerToken, file) { sent, total ->
                    updateTransfer(id) { it.copy(moved = sent, total = total) }
                }
            }
        }.onSuccess { savedAs ->
            updateTransfer(id) {
                it.copy(status = Status.DONE, moved = it.total.coerceAtLeast(it.moved), detail = savedAs)
            }
            Bridge.log("Sent ${file.name} → ${peer.name}")
        }.onFailure { error ->
            updateTransfer(id) {
                it.copy(status = Status.FAILED, detail = error.message ?: "failed")
            }
            Bridge.log("Send failed (${file.name}): ${error.message}")
        }
    }

    // ------------------------------------------------------------------- remote browsing

    /** Opens the PC's file tree, scanning for a peer first if none is selected yet. */
    fun openRemoteBrowser() {
        if (_state.value.browse != null) return
        viewModelScope.launch {
            val peer = resolvePeer() ?: return@launch
            if (!peerAuthReady(peer)) return@launch
            _state.update { it.copy(browse = RemoteBrowse(peer = peer)) }
            loadRemote("/")
        }
    }

    fun closeRemoteBrowser() = _state.update { it.copy(browse = null) }

    fun browseRemote(path: String) {
        viewModelScope.launch { loadRemote(path) }
    }

    fun reloadRemote() {
        _state.value.browse?.let { browseRemote(it.path) }
    }

    private suspend fun loadRemote(path: String) {
        val peer = _state.value.browse?.peer ?: return
        _state.update { it.copy(browse = it.browse?.copy(loading = true, error = null)) }

        withContext(Dispatchers.IO) {
            runCatching { client.list(peer, prefs.peerToken, path) }
        }.onSuccess { json ->
            val entries = json.optJSONArray("entries")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { row ->
                        RemoteEntry(
                            name = row.optString("name"),
                            path = row.optString("path"),
                            isDir = row.optBoolean("dir", false),
                            size = row.optLong("size", 0),
                            mtime = row.optLong("mtime", 0),
                        )
                    }
                }
            }.orEmpty()
            _state.update {
                it.copy(
                    browse = it.browse?.copy(
                        path = json.optString("path", path).ifEmpty { "/" },
                        parent = if (json.isNull("parent")) null
                        else json.optString("parent").takeIf { p -> p.isNotBlank() },
                        entries = entries,
                        loading = false,
                        error = null,
                    )
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    browse = it.browse?.copy(
                        loading = false,
                        error = error.message ?: "cannot list $path",
                    )
                )
            }
        }
    }

    /** Pulls one remote file into this phone's inbox. */
    fun receiveFromPeer(entry: RemoteEntry) {
        if (entry.isDir) return
        val peer = _state.value.browse?.peer ?: return
        viewModelScope.launch { receiveOne(peer, entry) }
    }

    /**
     * Pulls every file in the folder being shown — subfolders are left alone. Downloads run
     * one after another: a folder of a few hundred photos would otherwise open that many
     * simultaneous connections, and the peer spawns a thread per connection.
     */
    fun receiveAllInFolder() {
        val browse = _state.value.browse ?: return
        val files = browse.files
        if (files.isEmpty()) return
        viewModelScope.launch {
            files.forEach { receiveOne(browse.peer, it) }
        }
    }

    private suspend fun receiveOne(peer: Discovery.Peer, entry: RemoteEntry) {
        val id = ids.incrementAndGet()
        addTransfer(
            Transfer(
                id = id,
                name = entry.name,
                direction = Direction.RECEIVE,
                total = entry.size,
            )
        )

        withContext(Dispatchers.IO) {
            runCatching {
                client.download(peer, prefs.peerToken, entry.path, prefs) { received, total ->
                    updateTransfer(id) {
                        it.copy(moved = received, total = if (total > 0) total else it.total)
                    }
                }
            }
        }.onSuccess { savedAs ->
            updateTransfer(id) {
                it.copy(
                    status = Status.DONE,
                    moved = it.total.coerceAtLeast(it.moved),
                    detail = savedAs,
                )
            }
            Bridge.log("Received ${entry.name} ← ${peer.name}")
        }.onFailure { error ->
            updateTransfer(id) {
                it.copy(status = Status.FAILED, detail = error.message ?: "failed")
            }
            Bridge.log("Receive failed (${entry.name}): ${error.message}")
        }
    }

    fun clearFinishedTransfers() = _state.update {
        it.copy(transfers = it.transfers.filter { t -> t.status == Status.RUNNING })
    }

    private fun addTransfer(transfer: Transfer) =
        _state.update { it.copy(transfers = it.transfers + transfer) }

    private fun updateTransfer(id: Long, block: (Transfer) -> Transfer) = _state.update { current ->
        current.copy(transfers = current.transfers.map { if (it.id == id) block(it) else it })
    }
}
