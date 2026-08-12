package com.aynux.afmu.core

/**
 * Lower-case hex, the encoding the v2 pairing handshake uses for `commit`, `nb` and `na`
 * (PROTOCOL.md v2 §4.2.3), and for the discovery `rid` (§6.1).
 *
 * Shared rather than written out where it is needed. Both ends of the pairing handshake parse
 * each other's hex, so "what counts as malformed" is a protocol decision, not a local one —
 * two copies of this that disagree by one edge case is how a handshake starts failing for
 * reasons nobody can see.
 *
 * **Malformed input decodes to an empty array, never a partial one.** Every caller checks the
 * length against what it expects (32 bytes), so an empty result is refused there; a partial
 * decode would sail past a length check that happened to match.
 */
object Hex {

    fun encode(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    fun decode(text: String): ByteArray {
        if (text.isEmpty() || text.length % 2 != 0) return ByteArray(0)
        return runCatching {
            ByteArray(text.length / 2) {
                val hi = text[it * 2].digitToInt(16)
                val lo = text[it * 2 + 1].digitToInt(16)
                ((hi shl 4) or lo).toByte()
            }
        }.getOrDefault(ByteArray(0))
    }
}
