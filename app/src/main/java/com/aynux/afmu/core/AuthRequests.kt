package com.aynux.afmu.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Pending "may I connect?" requests (PROTOCOL.md §3.8).
 *
 * A PC that has no token posts here; the user approves on the phone and only then does the
 * token leave the device. The endpoint is deliberately unauthenticated — that is the whole
 * point — so everything that limits the blast radius lives in this class:
 *
 *  - one pending request at a time, so nobody can spam a wall of prompts;
 *  - the reply id is a 128-bit secret handed only to the requester, and it is the sole key
 *    to the granted token;
 *  - a denial blackballs that address for a while, so "just keep asking until they tap the
 *    wrong button" does not work;
 *  - everything expires on a wall clock, decided here rather than by the requester.
 */
object AuthRequests {

    enum class Status { PENDING, GRANTED, DENIED, EXPIRED }

    data class Request(
        val id: String,
        /** Shown on both screens; a request the user did not start will show a different code. */
        val code: String,
        val name: String,
        val os: String,
        val host: String,
        val port: Int,
        val createdAt: Long,
        val status: Status = Status.PENDING,
    ) {
        fun expired(now: Long = System.currentTimeMillis()) =
            now - createdAt > TIMEOUT_MS
    }

    /** The request awaiting a decision, or null. Drives both the notification and the dialog. */
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val decided = HashMap<String, Request>()
    private val blocked = HashMap<String, Long>()
    private val random = SecureRandom()

    /**
     * Registers a request, or returns null when one is already waiting, the caller is in
     * the penalty box, or requests are switched off.
     */
    @Synchronized
    fun create(prefs: Prefs, name: String, os: String, host: String, port: Int, code: String): Request? {
        if (!prefs.allowAuthRequests) return null
        sweep()
        if (_pending.value != null) return null
        if ((blocked[host] ?: 0) > System.currentTimeMillis()) return null

        val request = Request(
            id = newId(),
            code = confirmCode(code),
            name = displayText(name, 64).ifBlank { host },
            os = displayText(os, 16),
            host = host,
            port = port,
            createdAt = System.currentTimeMillis(),
        )
        _pending.value = request
        return request
    }

    /** Looks up a decision by id. Only the requester knows the id. */
    @Synchronized
    fun status(id: String): Request? {
        val current = _pending.value
        // Read before sweeping, so a request that just ran out is reported as expired
        // rather than as one we have never heard of.
        val answer = if (current != null && current.id == id) {
            if (current.expired()) current.copy(status = Status.EXPIRED) else current
        } else {
            decided[id]
        }
        sweep()
        return answer
    }

    @Synchronized
    fun decide(id: String, granted: Boolean) {
        val request = _pending.value?.takeIf { it.id == id } ?: return
        val settled = request.copy(status = if (granted) Status.GRANTED else Status.DENIED)
        decided[id] = settled
        _pending.value = null
        if (!granted) blocked[request.host] = System.currentTimeMillis() + DENY_COOLDOWN_MS
    }

    /** Used by the UI's "deny" path when the user dismisses without choosing. */
    @Synchronized
    fun cancel(id: String) = decide(id, granted = false)

    @Synchronized
    fun clear() {
        _pending.value = null
        decided.clear()
        blocked.clear()
    }

    /**
     * Drops anything past its deadline. Called on every entry point rather than on a timer:
     * there is no background loop to keep alive, and the state only matters when someone asks.
     */
    private fun sweep() {
        val now = System.currentTimeMillis()
        _pending.value?.let { if (it.expired(now)) _pending.value = null }
        decided.entries.removeAll { now - it.value.createdAt > RESULT_RETENTION_MS }
        blocked.entries.removeAll { it.value < now }
    }

    /**
     * Whatever the caller sent lands on the screen and in a notification, and this is the one
     * endpoint anyone on the LAN can reach without a token. Strip control characters and cap
     * the length so nobody can push the buttons off the dialog with a name of their choosing.
     */
    private fun displayText(raw: String, max: Int): String =
        raw.filter { !it.isISOControl() }.trim().take(max)

    /** The requester picks the code and both screens show it; anything else is not a code. */
    private fun confirmCode(raw: String): String =
        if (raw.length == 4 && raw.all { it in '0'..'9' }) raw else "----"

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Must match afmu::kAuthTimeoutSec on the Linux side. */
    const val TIMEOUT_SEC = 60
    private const val TIMEOUT_MS = TIMEOUT_SEC * 1000L

    // The requester polls once a second; keeping the verdict a little past the deadline means
    // a decision made at the last moment still reaches it.
    private const val RESULT_RETENTION_MS = TIMEOUT_MS + 30_000L
    private const val DENY_COOLDOWN_MS = 60_000L
}
