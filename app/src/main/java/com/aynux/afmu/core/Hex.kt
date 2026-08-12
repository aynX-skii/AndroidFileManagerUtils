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
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = nibble(text[i * 2])
            val lo = nibble(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * ASCII `0-9a-fA-F` and nothing else; -1 for anything else.
     *
     * Written out rather than using `Char.digitToInt(16)`, which accepts **any Unicode decimal
     * digit** — `١١` (Arabic-Indic) decodes to a real byte there. Nobody gains much from that
     * on its own, but the two ends of this protocol have to agree on what a commit *is*, and
     * Qt's `QByteArray::fromHex` silently **skips** characters it does not like rather than
     * rejecting them. Three different answers to "is this valid hex" is how a handshake starts
     * failing, or worse succeeding, for reasons nobody can reproduce.
     */
    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
