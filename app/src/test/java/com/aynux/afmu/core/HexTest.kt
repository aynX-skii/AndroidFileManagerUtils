package com.aynux.afmu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hex codec used by the pairing handshake (PROTOCOL.md v2 §4.2.3).
 *
 * What it decodes comes straight off the wire — `commit`, `na` and the session id arrive as
 * query parameters from a peer that has not been authorised yet. So the cases worth pinning
 * are the malformed ones, and the property that matters is that **anything malformed decodes
 * to nothing at all, never to a shorter-than-expected array**. Every caller checks the result
 * against an expected 32 bytes; a partial decode is the one shape that could sail past such a
 * check by coincidence.
 */
class HexTest {

    @Test
    fun `round trips`() {
        val raw = ByteArray(32) { (it * 7).toByte() }
        assertArrayEquals(raw, Hex.decode(Hex.encode(raw)))
    }

    @Test
    fun `encodes lower case, two characters per byte`() {
        assertEquals("00", Hex.encode(byteArrayOf(0)))
        assertEquals("ff", Hex.encode(byteArrayOf(-1)))
        assertEquals("0a1b", Hex.encode(byteArrayOf(0x0a, 0x1b)))
        assertEquals(64, Hex.encode(ByteArray(32)).length)
    }

    @Test
    fun `decodes either letter case`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte()), Hex.decode("ab"))
        assertArrayEquals(byteArrayOf(0xAB.toByte()), Hex.decode("AB"))
        assertArrayEquals(byteArrayOf(0xAB.toByte()), Hex.decode("aB"))
    }

    @Test
    fun `every byte value survives the trip`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.decode(Hex.encode(all)))
    }

    @Test
    fun `malformed input decodes to nothing, never to a partial array`() {
        // The important one. "11 valid bytes then garbage" must not come back as 11 bytes:
        // a caller checking `size != 32` would reject it, but a caller checking a shorter
        // length — or a future one — would be handed attacker-chosen prefix bytes.
        for (bad in listOf(
            "0",           // odd length
            "abc",         // odd length
            "zz",          // not hex
            "11zz",        // valid prefix, then not hex
            "11 22",       // spaces
            "0x11",        // prefix
            "11-22",       // separators
            "١١",          // Arabic-Indic digits: digitToInt(16) would take these
            "",            // empty
        )) {
            assertEquals("应当解成空：$bad", 0, Hex.decode(bad).size)
        }
    }

    @Test
    fun `a full-length commit survives, and a truncated one is refused by its length`() {
        val commit = ByteArray(32) { 0x5A }
        val text = Hex.encode(commit)
        assertEquals(32, Hex.decode(text).size)
        // 63 characters is odd, so it decodes to nothing rather than to 31 bytes.
        assertEquals(0, Hex.decode(text.dropLast(1)).size)
        // 62 characters is even and decodes to 31 — caught by the caller's length check,
        // which is why that check is not optional anywhere this is used.
        assertEquals(31, Hex.decode(text.dropLast(2)).size)
    }
}
