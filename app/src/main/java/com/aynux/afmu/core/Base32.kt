package com.aynux.afmu.core

import java.io.ByteArrayOutputStream

/**
 * The base32 alphabet fingerprints are shown and exchanged in (PROTOCOL.md v2 §3.1).
 *
 * Kept free of any Android dependency on purpose: this has to produce **byte-identical**
 * output to `afmu::Identity::toBase32` on the Linux side — the strings travel between the
 * two ends in QR codes and pairing tables — and the only way to check that cheaply is to
 * run it on a plain JVM against vectors taken from the C++ implementation.
 *
 * The alphabet drops `I` and `O` (`0` and `1` are not in base32 to begin with), because a
 * fingerprint is something a person reads off one screen and compares against another.
 */
object Base32 {

    fun encode(raw: ByteArray): String {
        val alphabet = ProtocolConstants.FINGERPRINT_ALPHABET
        val out = StringBuilder((raw.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in raw) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 0x1F])
            }
        }
        // 32 bytes is 256 bits, which is not a multiple of 5: one bit is left over and is
        // left-aligned into a final group.
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /**
     * Back to raw bytes, tolerating the spaces and dashes used for grouping.
     *
     * Returns null for any character outside the alphabet: a fingerprint the user mistyped
     * has to be rejected outright, never repaired into some other valid-looking value.
     */
    fun decode(text: String): ByteArray? {
        val alphabet = ProtocolConstants.FINGERPRINT_ALPHABET
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (isSeparator(ch)) continue
            val idx = alphabet.indexOf(ch.uppercaseChar())
            if (idx < 0) return null
            buffer = (buffer shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * Characters skipped when reading a fingerprint back: the ones we print, plus the space
     * variants a copy-paste tends to introduce.
     *
     * Spelled out rather than asking the platform whether something "is whitespace", because
     * the two platforms disagree: `QChar::isSpace()` counts U+00A0, U+2007 and U+202F,
     * `Char.isWhitespace()` does not. A fingerprint pasted with a non-breaking space in it was
     * therefore accepted by the Linux end and rejected by the phone — one string, two answers,
     * for the one value that decides which device you are talking to.
     */
    private fun isSeparator(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\r', '-' -> true
        '\u00A0', '\u2007', '\u202F', '\u3000' -> true   // NBSP, figure, narrow NBSP, ideographic
        else -> false
    }

    /** Grouped for reading aloud and comparing on screen. */
    fun group(encoded: String): String {
        val size = ProtocolConstants.FINGERPRINT_GROUP_SIZE
        val out = StringBuilder(encoded.length + encoded.length / size)
        for (i in encoded.indices) {
            if (i > 0 && i % size == 0) out.append(' ')
            out.append(encoded[i])
        }
        return out.toString()
    }
}
