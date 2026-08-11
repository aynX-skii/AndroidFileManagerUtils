package com.aynux.afmu.core

import android.content.Context
import android.os.Build
import java.security.SecureRandom

/** Persisted settings. Kept tiny and synchronous — everything here is read rarely. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("afmu", Context.MODE_PRIVATE)

    init {
        settleGuestMode()
    }

    /**
     * Decides guest mode once, on the very first run after this version lands, and writes the
     * answer down (§9).
     *
     * Off on a fresh install — the draft asks for that and it is right, a password does not
     * stop a man in the middle. On for an upgrade — someone already using the browser
     * interface must not find it silently stops opening one day with nothing to point at.
     * That is the same class of problem as silently swapping the key pair.
     *
     * Deciding it here rather than in a getter's default matters: [token] mints itself on
     * first read, so "does the file have keys in it" gives a different answer depending on
     * what got read first. A one-time decision at construction has no such ordering.
     */
    private fun settleGuestMode() = synchronized(TOKEN_LOCK) {
        if (sp.contains(KEY_GUEST_MODE)) return@synchronized
        val upgrading = sp.all.isNotEmpty()
        sp.edit().putBoolean(KEY_GUEST_MODE, upgrading).apply()
    }

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /**
     * Shared secret required on every request. Regenerating it locks out old clients.
     *
     * Minting on first read has to be atomic across the whole process, not just this
     * instance: the UI and the service each hold their own [Prefs], so two first reads can
     * race and generate two tokens. The loser's is overwritten a moment later, and whoever
     * was already shown it is authenticating with a value the server no longer accepts.
     */
    var token: String
        get() = synchronized(TOKEN_LOCK) {
            sp.getString(KEY_TOKEN, null) ?: newToken().also {
                sp.edit().putString(KEY_TOKEN, it).apply()
            }
        }
        set(value) = synchronized(TOKEN_LOCK) { sp.edit().putString(KEY_TOKEN, value).apply() }

    var deviceName: String
        get() = sp.getString(KEY_NAME, null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    /** Directory incoming files are written to, relative to shared storage. */
    var inboxDir: String
        get() = sp.getString(KEY_INBOX, "Download/FileBridge")!!
        set(value) = sp.edit().putString(KEY_INBOX, value).apply()

    /**
     * Accept unencrypted v1 connections (PROTOCOL.md v2 §8.1).
     *
     * With this off, a connection whose first byte is not `0x16` is dropped without any HTTP
     * response at all — this port then effectively only listens for TLS.
     *
     * Default on, while the draft says default off: that is §8.2 stage 3. Encryption has only
     * just landed and the Linux client is the only thing that speaks it, so turning it off
     * today would mean nothing can connect.
     */
    var allowLegacyPlaintext: Boolean
        get() = sp.getBoolean(KEY_ALLOW_PLAINTEXT, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_PLAINTEXT, value).apply()

    /**
     * Zero-trust mode (PROTOCOL.md v2 §9): only paired devices, only encrypted.
     *
     * Turning it on forces both [allowLegacyPlaintext] and [guestMode] off. The UI keeps
     * showing those switches, greyed — hiding them would leave the user wondering why the
     * browser stopped working with no visible cause.
     */
    var zeroTrustMode: Boolean
        get() = sp.getBoolean(KEY_ZERO_TRUST, false)
        set(value) = sp.edit().putBoolean(KEY_ZERO_TRUST, value).apply()

    /**
     * Guest mode (§9): the browser interface and password authentication.
     *
     * **It cannot meet the v2 bar, and no implementation effort would change that.** Browsers
     * do not present client certificates for mutual TLS, and a self-signed server certificate
     * only produces a scary warning the user clicks through — which is exactly the moment the
     * man-in-the-middle protection is discarded. Over HTTPS it stops passive sniffing, and
     * that is all it claims.
     *
     * Off on a fresh install, on when upgrading — decided once in [settleGuestMode]. Ask
     * [guestModeActive] rather than this, so zero-trust mode is honoured.
     */
    var guestMode: Boolean
        get() = sp.getBoolean(KEY_GUEST_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_GUEST_MODE, value).apply()

    /** What the server should actually ask: the switch, unless zero-trust overrides it. */
    val guestModeActive: Boolean get() = guestMode && !zeroTrustMode

    /** Zero-trust mode greys the guest switch rather than hiding it. */
    val guestModeAvailable: Boolean get() = !zeroTrustMode

    /** Answer discovery probes so the PC can find this phone without typing an IP. */
    var discoverable: Boolean
        get() = sp.getBoolean(KEY_DISCOVERABLE, true)
        set(value) = sp.edit().putBoolean(KEY_DISCOVERABLE, value).apply()

    /**
     * Whether the user last left the server switched on. Opening the app must not start
     * serving on its own — the README's security boundary assumes the user decides when
     * this device is reachable.
     */
    var serverEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVER_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SERVER_ENABLED, value).apply()

    /**
     * UI language: "system" follows the device locale, otherwise a BCP-47 tag we ship
     * translations for ("en" / "zh"). Persisted so the choice survives a restart.
     */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** Allow clients to write and delete, not just read. */
    var writable: Boolean
        get() = sp.getBoolean(KEY_WRITABLE, true)
        set(value) = sp.edit().putBoolean(KEY_WRITABLE, value).apply()

    /**
     * Let a PC ask for permission to connect instead of the user copying the token
     * (PROTOCOL.md §3.8). Off means the endpoint refuses outright, so nobody on the LAN can
     * make this phone light up with a prompt.
     */
    var allowAuthRequests: Boolean
        get() = sp.getBoolean(KEY_ALLOW_AUTH, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_AUTH, value).apply()

    /** Token of the PC we push files to — printed by `afmu serve` on the Linux side. */
    var peerToken: String
        get() = sp.getString(KEY_PEER_TOKEN, "")!!
        set(value) = sp.edit().putString(KEY_PEER_TOKEN, value).apply()

    /** "host:port" of the last PC used, so the app reconnects without a scan. */
    var lastPeer: String
        get() = sp.getString(KEY_LAST_PEER, "")!!
        set(value) = sp.edit().putString(KEY_LAST_PEER, value).apply()

    fun regenerateToken(): String = newToken().also { token = it }

    private fun newToken(): String {
        val rnd = SecureRandom()
        return (1..TOKEN_LENGTH).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
    }

    companion object {
        const val DEFAULT_PORT = ProtocolConstants.DEFAULT_HTTP_PORT
        const val DISCOVERY_PORT = ProtocolConstants.DISCOVERY_PORT

        /** Process-wide: every [Prefs] instance shares the one underlying preferences file. */
        private val TOKEN_LOCK = Any()

        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "name"
        private const val KEY_INBOX = "inbox"
        private const val KEY_DISCOVERABLE = "discoverable"
        private const val KEY_ALLOW_PLAINTEXT = "allowLegacyPlaintext"
        private const val KEY_ZERO_TRUST = "zeroTrustMode"
        private const val KEY_GUEST_MODE = "guestMode"
        private const val KEY_SERVER_ENABLED = "server_enabled"
        private const val KEY_LANGUAGE = "language"

        const val LANG_SYSTEM = "system"
        const val LANG_ENGLISH = "en"
        const val LANG_CHINESE = "zh"
        private const val KEY_WRITABLE = "writable"
        private const val KEY_ALLOW_AUTH = "allow_auth_requests"
        private const val KEY_PEER_TOKEN = "peer_token"
        private const val KEY_LAST_PEER = "last_peer"

        // No look-alike characters: this gets typed by hand on the PC.
        // Both live in docs/constants.json — the Linux side mints tokens too.
        private const val ALPHABET = ProtocolConstants.TOKEN_ALPHABET
        private const val TOKEN_LENGTH = ProtocolConstants.TOKEN_LENGTH
    }
}
