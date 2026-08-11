package com.aynux.afmu.core

import java.security.MessageDigest

/**
 * The short authentication string (PROTOCOL.md v2 §4.2.2) — what the user compares when
 * there is no QR code to scan.
 *
 * ```
 * sas = base32( SHA-256( "AFMU-SAS-v2" || min(fp_a,fp_b) || max(fp_a,fp_b) || n_a || n_b ) )[0:8]
 * ```
 *
 * Kept free of any Android dependency, for the same reason [Base32] is: this has to produce
 * **character-identical** output to `afmu::computeSas` on the Linux side, and a mismatch is
 * uniquely nasty — two screens showing different codes, whose only reasonable reading by the
 * user is "I am being attacked". An encoding bug would be read as a security incident.
 *
 * Three things must be copied exactly:
 *
 * - **The nonces come from commit-reveal.** Neither side may pick its own after seeing the
 *   other's, or a man in the middle can search for a collision offline against a known value —
 *   about 2²⁰ tries, a minute or two on one core, and it can be done *before* the attack
 *   (§4.2.1). The commit closes that door; this function assumes it was closed.
 * - **Fingerprints are sorted**, so both ends compute the same value no matter who dialled.
 * - **The initiator displays the value it computed itself**, never the one the server sent
 *   back. Echoing the server's turns the whole scheme into "the attacker sends you the string
 *   you were hoping for".
 */
object PairSas {

    /** Domain separator, ASCII, no trailing NUL. Changing it changes the protocol version. */
    private const val CONTEXT = "AFMU-SAS-v2"

    /** Characters in the code, excluding the dash. 40 bits — §4.2.2 explains why that is enough. */
    const val LENGTH = 8

    private const val FINGERPRINT_BYTES = 32
    private const val NONCE_BYTES = 32

    /**
     * Computes the code, or returns null when an input is the wrong length or the two
     * fingerprints are identical.
     *
     * **Null has to be treated as a failure**, never as "no code, carry on": showing nothing
     * where a comparison belongs is how a user ends up approving a pairing they never checked.
     * Identical fingerprints mean either a loopback or a bug at one end, and neither should
     * produce a normal-looking code for someone to successfully "compare".
     */
    fun compute(fpA: ByteArray, fpB: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String? {
        if (fpA.size != FINGERPRINT_BYTES || fpB.size != FINGERPRINT_BYTES) return null
        if (nonceA.size != NONCE_BYTES || nonceB.size != NONCE_BYTES) return null
        if (fpA.contentEquals(fpB)) return null

        val aFirst = compareUnsigned(fpA, fpB) < 0
        val lo = if (aFirst) fpA else fpB
        val hi = if (aFirst) fpB else fpA

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(CONTEXT.toByteArray(Charsets.US_ASCII))
            update(lo)
            update(hi)
            // The nonces are **not** sorted: their roles are fixed (initiator / responder),
            // and sorting them would throw away half the binding for nothing.
            update(nonceA)
            update(nonceB)
        }.digest()

        return Base32.encode(digest).take(LENGTH)
    }

    /** Display form, `XXXX-XXXX`. This is what the user compares. */
    fun format(sas: String?): String =
        if (sas == null || sas.length != LENGTH) "" else "${sas.take(4)}-${sas.drop(4)}"

    /**
     * Byte-wise unsigned comparison.
     *
     * Kotlin's `ByteArray` has no ordering, and the obvious `Byte` comparison is **signed** —
     * which would sort `0x80…` before `0x00…` and make the two ends disagree on which
     * fingerprint is "min" for exactly half of all pairs. That is a coin-flip bug: it would
     * work in testing and fail in the field on half the devices.
     */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
