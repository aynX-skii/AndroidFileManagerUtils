package com.aynux.afmu.core

/**
 * Per-source-IP backoff for failed token checks (PROTOCOL.md §2.2).
 *
 * The v1 token carries about 49 bits of entropy, which is plenty — but only if guessing
 * wrong costs something. Without this, one address can push 200 wrong tokens through in
 * under a second and pay nothing, which drops the cost of a brute force to bandwidth alone.
 *
 * The penalty is an immediate 429, never a sleep: holding the thread is exactly what an
 * attacker wants, and on the Linux side the server is a single event loop where sleeping
 * would stall everything.
 *
 * The values come from docs/constants.json and are generated into both ends, so they
 * cannot drift. Two ends throttling differently on one network looks like a flaky
 * network, not like a policy — which is why this is generated rather than copied.
 */
object AuthThrottle {

    private class Entry(
        var fails: Int = 0,
        var blockedUntil: Long = 0,
        var lastFail: Long = 0,
    )

    private val entries = HashMap<String, Entry>()

    /** Seconds still to wait; 0 means let it through. Does **not** count as an attempt. */
    @Synchronized
    fun retryAfterSec(host: String, now: Long = System.currentTimeMillis()): Int {
        sweep(now)
        val left = (entries[host]?.blockedUntil ?: 0) - now
        // Round up: 200 ms left still has to report 1, because reporting 0 tells the peer
        // it may retry immediately.
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** Call after a token comparison failed. Returns the backoff this failure earned. */
    @Synchronized
    fun noteFailure(host: String, now: Long = System.currentTimeMillis()): Int {
        sweep(now)
        val entry = entries.getOrPut(host) { Entry() }
        entry.fails++
        entry.lastFail = now
        if (entry.fails <= GRACE) return 0

        // 1, 2, 4, 8, 16, 32, 60, 60, …
        val over = entry.fails - GRACE
        val delay = if (over >= 31) MAX_BACKOFF_SEC else minOf(1 shl (over - 1), MAX_BACKOFF_SEC)
        entry.blockedUntil = now + delay * 1000L
        return delay
    }

    /** A check passed: wipe that address's tab. */
    @Synchronized
    fun noteSuccess(host: String) {
        entries.remove(host)
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun sweep(now: Long) {
        // Only the last failure matters: anything still blocked is also inside the window.
        entries.entries.removeAll { now - it.value.lastFail > FORGET_MS }
    }

    // All three come from docs/constants.json, so the two ends cannot drift apart.
    private const val GRACE = ProtocolConstants.AUTH_FAIL_GRACE
    private const val MAX_BACKOFF_SEC = ProtocolConstants.AUTH_BACKOFF_MAX_SEC
    private const val FORGET_MS = ProtocolConstants.AUTH_FAIL_FORGET_SEC * 1000L
}
