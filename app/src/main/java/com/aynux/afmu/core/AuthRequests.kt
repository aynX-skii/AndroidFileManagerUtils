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

    /** How many times each address has been denied — decides how long its cooldown is. */
    private val denials = HashMap<String, Int>()

    // Global cooldown. Everything above is per-address, and on a LAN changing address costs
    // nothing, so per-address limits alone do not stop a flood. This one ignores who asked.
    private var globalUntil = 0L
    private var consecutiveRefusals = 0
    private var lastRefusalAt = 0L

    private val random = SecureRandom()

    /**
     * Registers a request, or returns null when one is already waiting, the caller is in
     * the penalty box, the global cooldown is running, or requests are switched off.
     */
    @Synchronized
    fun create(prefs: Prefs, name: String, os: String, host: String, port: Int, code: String): Request? {
        if (!prefs.allowAuthRequests) return null
        sweep()
        if (_pending.value != null) return null
        val now = System.currentTimeMillis()
        if (globalUntil > now) return null                  // someone was just refused
        if ((blocked[host] ?: 0) > now) return null          // this address was just refused

        val request = Request(
            id = newId(),
            code = confirmCode(code),
            name = displayText(name, 64).ifBlank { host },
            os = displayText(os, 16),
            host = host,
            port = port,
            createdAt = now,
        )
        _pending.value = request
        return request
    }

    /**
     * Seconds to wait, for the `Retry-After` on a 429. Zero means the refusal was not a
     * cooldown — a request is already waiting on the user, and how long that takes is
     * their business, so there is no honest number to give.
     */
    @Synchronized
    fun retryAfterSec(host: String, now: Long = System.currentTimeMillis()): Int {
        val until = maxOf(globalUntil, blocked[host] ?: 0)
        val left = until - now
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
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
        if (granted) {
            // Allow means this run of requests is ordinary use, not harassment: wipe the tab.
            // Without this, pairing a second device right after the first would be blocked
            // by the cooldown the user's own earlier refusal left behind.
            denials.remove(request.host)
            blocked.remove(request.host)
            consecutiveRefusals = 0
            globalUntil = 0
        } else {
            noteRefusal(request.host, escalate = true)
        }
    }

    /** Books a refusal: that address goes into cooldown, and so does everyone (§3.8). */
    private fun noteRefusal(host: String, escalate: Boolean) {
        val now = System.currentTimeMillis()
        if (escalate) {
            val n = (denials[host] ?: 0) + 1
            denials[host] = n
            blocked[host] = now + backoff(n, DENY_COOLDOWN_MS, DENY_COOLDOWN_MAX_MS)
        } else {
            // A timeout does not escalate, but still cools down: "fire and wait it out"
            // would otherwise raise one prompt a minute forever.
            blocked[host] = now + TIMEOUT_COOLDOWN_MS
        }
        consecutiveRefusals++
        lastRefusalAt = now
        globalUntil = now + backoff(consecutiveRefusals, GLOBAL_COOLDOWN_MS, GLOBAL_COOLDOWN_MAX_MS)
    }

    /** base * 2^(n-1), capped. */
    private fun backoff(n: Int, base: Long, cap: Long): Long = when {
        n <= 1 -> base
        n >= 30 -> cap
        else -> minOf(base shl (n - 1), cap)
    }

    /** Used by the UI's "deny" path when the user dismisses without choosing. */
    @Synchronized
    fun cancel(id: String) = decide(id, granted = false)

    @Synchronized
    fun clear() {
        _pending.value = null
        decided.clear()
        blocked.clear()
        denials.clear()
        globalUntil = 0
        consecutiveRefusals = 0
        lastRefusalAt = 0
    }

    /**
     * Drops anything past its deadline. Called on every entry point rather than on a timer:
     * there is no background loop to keep alive, and the state only matters when someone asks.
     */
    private fun sweep() {
        val now = System.currentTimeMillis()
        _pending.value?.let {
            if (it.expired(now)) {
                // A timeout counts as a soft refusal: the peer did nothing wrong (the user
                // just did not get to it), so it does not escalate — but it still cools
                // down, or "fire and wait it out" raises one prompt a minute forever.
                _pending.value = null
                noteRefusal(it.host, escalate = false)
            }
        }
        decided.entries.removeAll { now - it.value.createdAt > RESULT_RETENTION_MS }
        blocked.entries.removeAll { it.value < now }

        // Quiet for long enough: forget the escalation, so one misclick does not linger.
        if (consecutiveRefusals > 0 && now - lastRefusalAt > REFUSAL_FORGET_MS) {
            consecutiveRefusals = 0
            denials.clear()
        }
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

    // Per-address cooldown after a denial, doubling each time: 60s → 2min → 4min → …
    // capped at 30 minutes. A flat 60s does not stop a patient attacker — deny, wait a
    // minute, ask again, forever.
    private const val DENY_COOLDOWN_MS = 60_000L
    private const val DENY_COOLDOWN_MAX_MS = 30 * 60_000L

    /** A timeout is a soft refusal: it cools down but does not escalate. */
    private const val TIMEOUT_COOLDOWN_MS = 60_000L

    // The global cooldown — the point of A3. Everything above is per-address, and swapping
    // address on a LAN is free, so per-address limits alone do not cap the prompt rate.
    // This one ignores who asked: 10s → 20s → 40s → … capped at 5 minutes. One Allow
    // clears it, so ordinary use never notices.
    private const val GLOBAL_COOLDOWN_MS = 10_000L
    private const val GLOBAL_COOLDOWN_MAX_MS = 5 * 60_000L

    /** Quiet this long and the escalation is forgotten. */
    private const val REFUSAL_FORGET_MS = 30 * 60_000L
}
