package com.aynux.afmu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling discovery id (PROTOCOL.md v2 §6.1).
 *
 * The vectors below come from the Linux side's actual output (`afmu_peerstore_test`), not from
 * this file's own [RollingId.compute] — a self-consistent implementation that disagrees with
 * the other end would pass any test written the second way.
 *
 * A mismatch here throws no exception and logs nothing. It shows up as "devices I paired with
 * always appear as bare IP addresses" — a feature quietly gone missing, which is exactly the
 * kind of thing nobody reports as a bug.
 */
class RollingIdTest {

    private val fp1 = ByteArray(32) { 0x11 }
    private val fp2 = ByteArray(32) { 0x22 }

    /** Middle of window 5666667, so ±100s stays inside it. */
    private val t = 1700000250L

    @Test
    fun `matches the vectors the Linux side produced`() {
        assertEquals("3c438e0d", RollingId.compute(fp1, t))
        assertEquals("08b4ad2c", RollingId.compute(fp2, t))
        assertEquals("5b724de7", RollingId.compute(fp1, 0))
    }

    @Test
    fun `is stable inside a window and changes across one`() {
        val rid = RollingId.compute(fp1, t)
        assertEquals(rid, RollingId.compute(fp1, t + 100))
        assertEquals(rid, RollingId.compute(fp1, t - 100))
        // If it did not change, it would just be a permanent identifier with extra steps.
        assertNotEquals(rid, RollingId.compute(fp1, t + RollingId.WINDOW_SEC))
    }

    @Test
    fun `different devices get different values`() {
        assertNotEquals(RollingId.compute(fp1, t), RollingId.compute(fp2, t))
    }

    @Test
    fun `is eight lowercase hex characters`() {
        val rid = RollingId.compute(fp1, t)!!
        assertEquals(8, rid.length)
        assertTrue(rid.all { it in "0123456789abcdef" })
    }

    @Test
    fun `refuses inputs it cannot compute from`() {
        assertNull(RollingId.compute(null, t))
        assertNull(RollingId.compute(ByteArray(31) { 0x11 }, t))
        // A clock that was never set would otherwise produce a value neither end agrees on.
        assertNull(RollingId.compute(fp1, -1))
    }

    @Test
    fun `nothing matches a value we could not compute`() {
        // Were null treated as a match, two devices that both fail to compute a rid would
        // "recognise" each other — the worst possible reading of a failure.
        assertFalse(RollingId.matches(fp1, null, t))
        assertFalse(RollingId.matches(fp1, "", t))
        assertFalse(RollingId.matches(null, "3c438e0d", t))
        assertFalse(RollingId.matches(ByteArray(31), "3c438e0d", t))
    }

    @Test
    fun `matches its own value, in either letter case`() {
        val rid = RollingId.compute(fp1, t)!!
        assertTrue(RollingId.matches(fp1, rid, t))
        assertTrue(RollingId.matches(fp1, rid.uppercase(), t))
        assertFalse(RollingId.matches(fp2, rid, t))
    }

    @Test
    fun `accepts the previous window but not the one before it`() {
        // Two ends on opposite sides of a boundary is normal. Accepting only the current
        // window would leave a stretch every five minutes where nobody recognises anybody.
        val rid = RollingId.compute(fp1, t)!!
        assertTrue(RollingId.matches(fp1, rid, t + RollingId.WINDOW_SEC))
        assertFalse(RollingId.matches(fp1, rid, t + 2 * RollingId.WINDOW_SEC))
    }

    @Test
    fun `picks exactly one device out of a table`() {
        // What Discovery.identify actually does: walk the pairing table looking for the one
        // fingerprint whose rid matches. Two hits would mean we resolve a reply to whichever
        // record happens to come first — so check the search is unambiguous, not just that
        // the right one is in there.
        val table = listOf(fp1, fp2, ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 })
        val rid = RollingId.compute(fp2, t)!!
        assertEquals(listOf(fp2), table.filter { RollingId.matches(it, rid, t) })
    }
}
