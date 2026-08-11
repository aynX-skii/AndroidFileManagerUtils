package com.aynux.afmu.core

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short-lived, path-bound download tickets (PROTOCOL.md §2.5).
 *
 * A browser `<a href>` cannot carry a custom header, which is the only reason `?token=`
 * ever existed. But a credential in a URL ends up in proxy logs, browser history and
 * `Referer`, so the API no longer accepts one. A ticket replaces it:
 *
 *  - it is a MAC over one specific path, so it unlocks that file and nothing else;
 *  - it expires in seconds, so a leaked link is worthless almost immediately;
 *  - it is verified statelessly — the server keeps no table of issued tickets.
 *
 * The browser cannot mint these itself: `crypto.subtle` is unavailable on plain HTTP,
 * so the page asks `/api/ticket` (header-authenticated) and gets one back.
 *
 * **Not one-time.** Real single use needs a server-side table of spent nonces, which is
 * exactly what stateless verification rules out. Within the few seconds it lives, a ticket
 * can be replayed — but only to download the same path it was already good for, which is
 * why that trade is worth taking. Do not call it one-time in the UI or the docs.
 */
object DownloadTicket {

    /** Seconds from issue to **start** of the download, not to its completion. */
    const val TTL_SEC = 10L

    /**
     * `<exp>.<mac>` — exp is Unix seconds, mac is base64url of HMAC-SHA256 truncated to
     * 132 bits, which is far past what a 10-second window could ever be brute forced for.
     */
    fun issue(token: String, path: String, nowMs: Long = System.currentTimeMillis()): String {
        val exp = nowMs / 1000 + TTL_SEC
        return "$exp.${mac(token, exp, path)}"
    }

    /** Verifies expiry **and** that the ticket was minted for this exact path. */
    fun verify(token: String, path: String, ticket: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (token.isEmpty() || ticket.isEmpty()) return false
        val dot = ticket.indexOf('.')
        if (dot <= 0 || dot == ticket.length - 1) return false
        val exp = ticket.substring(0, dot).toLongOrNull() ?: return false
        // Checked before the MAC so an expired ticket costs nothing to reject.
        if (nowMs / 1000 > exp) return false
        return HttpServer.constantTimeEquals(ticket.substring(dot + 1), mac(token, exp, path))
    }

    /**
     * The domain prefix keeps this MAC from ever being mistaken for one computed for some
     * other purpose with the same key, and the newlines keep `exp` and `path` from running
     * together into an ambiguous string.
     */
    private fun mac(token: String, exp: Long, path: String): String {
        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val digest = hmac.doFinal("$DOMAIN$exp\n$path".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .take(MAC_CHARS)
    }

    private const val DOMAIN = "afmu-dl-v1\n"
    private const val MAC_CHARS = 22
}
