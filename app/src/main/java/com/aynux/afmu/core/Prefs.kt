package com.aynux.afmu.core

import android.content.Context
import android.os.Build
import java.security.SecureRandom

/** Persisted settings. Kept tiny and synchronous — everything here is read rarely. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("afmu", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /** Shared secret required on every request. Regenerating it locks out old clients. */
    var token: String
        get() {
            sp.getString(KEY_TOKEN, null)?.let { return it }
            val fresh = newToken()
            sp.edit().putString(KEY_TOKEN, fresh).apply()
            return fresh
        }
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var deviceName: String
        get() = sp.getString(KEY_NAME, null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    /** Directory incoming files are written to, relative to shared storage. */
    var inboxDir: String
        get() = sp.getString(KEY_INBOX, "Download/FileBridge")!!
        set(value) = sp.edit().putString(KEY_INBOX, value).apply()

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
        const val DEFAULT_PORT = 8765
        const val DISCOVERY_PORT = 8766

        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "name"
        private const val KEY_INBOX = "inbox"
        private const val KEY_DISCOVERABLE = "discoverable"
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
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val TOKEN_LENGTH = 10
    }
}
