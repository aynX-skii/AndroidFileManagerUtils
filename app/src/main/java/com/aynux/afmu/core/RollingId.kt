package com.aynux.afmu.core

import java.security.MessageDigest

/**
 * The rolling identifier in discovery replies (PROTOCOL.md v2 §6.1).
 *
 * ```
 * rid = hex( SHA-256( "AFMU-RID-v2" || fp || decimalASCII(floor(unix_time / 300)) )[0:4] )
 * ```
 *
 * A v1 reply carries the device name and OS: one UDP packet and anyone on the LAN learns
 * "this phone is called Pixel 8 and runs Android". The rolling id replaces that. A stranger
 * sees a random-looking hex string, while **a device that already holds our fingerprint
 * computes the same value** and so still recognises "that's my phone".
 *
 * It also answers v2 §13 question 3: a PC that changed IP is still recognisable, no re-pairing.
 *
 * Do not mistake this for anonymity. §6.1 has the honest table: an observer watching several
 * consecutive windows can still correlate a device, and **anyone who has ever seen the `fp`
 * can compute every future window's rid forever**. That is inherent to broadcast discovery.
 *
 * Kept free of Android dependencies for the same reason [Base32] and [PairSas] are: this must
 * be byte-identical to `afmu::rollingId` on the Linux side. A mismatch here does not throw —
 * paired devices simply stop being recognised and show up as bare addresses, a feature quietly
 * going missing rather than an error anyone would report.
 */
object RollingId {

    /** Domain separator, ASCII, no trailing NUL. Changing it changes the protocol version. */
    private const val CONTEXT = "AFMU-RID-v2"

    /** Window length in seconds. Both ends must agree or they never compute the same value. */
    const val WINDOW_SEC = 300L

    /**
     * Bytes kept from the digest → 8 hex characters. 32 bits, not 16: at 16 bits two devices
     * collide once in 65536, and a UDP reply can spare the two bytes.
     */
    private const val RID_BYTES = 4

    private const val FINGERPRINT_BYTES = 32

    /**
     * The rid for a point in time, or null when the fingerprint is not a 32-byte SHA-256.
     *
     * **Null means "cannot compute"** and must never be treated as a value that can take part
     * in a comparison — otherwise two devices that both fail to compute one would "recognise"
     * each other.
     */
    fun compute(fp: ByteArray?, unixSeconds: Long): String? {
        if (fp == null || fp.size != FINGERPRINT_BYTES) return null
        // A negative timestamp (clock never set, or a bad value passed in) makes the window
        // number negative, and one end in that state never matches the other again.
        if (unixSeconds < 0) return null

        val window = unixSeconds / WINDOW_SEC
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(CONTEXT.toByteArray(Charsets.US_ASCII))
            update(fp)
            update(window.toString().toByteArray(Charsets.US_ASCII))
        }.digest()

        return digest.take(RID_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether a received `rid` belongs to this fingerprint.
     *
     * Accepts the current window **and the previous one**: two ends sitting on opposite sides
     * of a boundary is normal, and accepting only the current window would leave a stretch
     * every five minutes where nobody recognises anybody. The effective acceptance window is
     * therefore 5–10 minutes, which is also the clock skew this tolerates.
     */
    fun matches(fp: ByteArray?, rid: String?, unixSeconds: Long): Boolean {
        if (rid.isNullOrEmpty()) return false
        // Hex is valid in either case; failing to recognise a device we paired with over
        // letter casing would be a silly way to lose the feature.
        val want = rid.lowercase()
        for (back in 0..1) {
            val mine = compute(fp, unixSeconds - back * WINDOW_SEC) ?: return false
            if (mine == want) return true
        }
        return false
    }

    /** Convenience: the rid to advertise right now, or null when we have no identity yet. */
    fun current(fp: ByteArray?, now: Long = System.currentTimeMillis() / 1000): String? =
        compute(fp, now)
}
