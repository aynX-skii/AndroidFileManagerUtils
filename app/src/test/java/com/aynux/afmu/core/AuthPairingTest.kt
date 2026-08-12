package com.aynux.afmu.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * The commit-reveal pairing session (PROTOCOL.md v2 §4.2.3).
 *
 * This is the piece that stands between a user and a man in the middle, and **none of it is
 * visible when it breaks**. A weakened commit check still produces a code, still shows a
 * dialog, still pairs; what changes is that an attacker who can relay the connection gets to
 * pick a code the user will happily approve. So the interesting cases here are all failure
 * cases, and every one of them asserts that the session is *destroyed* rather than retried.
 *
 * All of it is plain logic — no device needed, which is exactly why it is worth pinning here
 * rather than leaving it to an end-to-end run somebody does by hand every few weeks.
 */
class AuthPairingTest {

    private val peerFp = Base32.encode(ByteArray(32) { 0x11 })
    private val peerRaw = ByteArray(32) { 0x11 }
    private val localFp = ByteArray(32) { 0x22 }

    private fun nonce(fill: Int) = ByteArray(32) { fill.toByte() }
    private fun commitOf(n: ByteArray) = MessageDigest.getInstance("SHA-256").digest(n)

    @Before fun setUp() = AuthRequests.clear()
    @After fun tearDown() = AuthRequests.clear()

    private fun start(
        allow: Boolean = true,
        host: String = "10.0.0.5",
        n: ByteArray = nonce(0x33),
    ) = AuthRequests.createPairing(allow, "PC", "linux", host, peerFp, commitOf(n))

    // ------------------------------------------------------------------ happy path

    @Test
    fun `a pairing session produces the same code both ends compute`() {
        val nonceA = nonce(0x33)
        val request = start(n = nonceA)!!
        assertTrue(request.isPairing)
        assertEquals(32, request.nonceB.size)

        val sas = AuthRequests.revealPairing(request.id, nonceA, localFp)
        assertNotNull(sas)

        // What the *initiator* computes, with the roles the other way round. The fingerprints
        // are sorted inside compute(), so both ends must land on the same string — if they did
        // not, the two screens would disagree and the only sane reading for a user is "I am
        // being attacked".
        val theirs = PairSas.compute(localFp, peerRaw, nonceA, request.nonceB)
        assertEquals(theirs, sas)
    }

    @Test
    fun `the code is remembered on the pending request, so the dialog can show it`() {
        val nonceA = nonce(0x44)
        val request = start(n = nonceA)!!
        val sas = AuthRequests.revealPairing(request.id, nonceA, localFp)!!
        assertEquals(sas, AuthRequests.pending.value?.sas)
    }

    @Test
    fun `granting reports back through status, with no token anywhere`() {
        val nonceA = nonce(0x33)
        val request = start(n = nonceA)!!
        AuthRequests.revealPairing(request.id, nonceA, localFp)

        assertEquals(AuthRequests.Status.PENDING, AuthRequests.status(request.id)?.status)
        AuthRequests.decide(request.id, granted = true)

        val settled = AuthRequests.status(request.id)!!
        assertEquals(AuthRequests.Status.GRANTED, settled.status)
        assertEquals(peerFp, settled.peerFp)
    }

    // ------------------------------------------------------------------ the commit

    @Test
    fun `a nonce that does not match the commit destroys the session`() {
        // The whole point of committing first. Without this an attacker relays the exchange,
        // sees n_b, and then searches for an n_a that lands on a code it likes — about 2^20
        // tries for an 8-character code, a minute or two on one core.
        val request = start(n = nonce(0x33))!!
        assertNull(AuthRequests.revealPairing(request.id, nonce(0x99), localFp))

        // Destroyed, not merely refused: retrying with the *correct* nonce must also fail.
        // Anything less means the attacker just keeps guessing.
        assertNull(AuthRequests.revealPairing(request.id, nonce(0x33), localFp))
        assertNull(AuthRequests.pending.value)
    }

    @Test
    fun `a short nonce is refused`() {
        val request = start()!!
        assertNull(AuthRequests.revealPairing(request.id, ByteArray(31), localFp))
    }

    @Test
    fun `a commit that is not 32 bytes never opens a session`() {
        assertNull(AuthRequests.createPairing(true, "PC", "linux", "10.0.0.5", peerFp, ByteArray(31)))
        assertNull(AuthRequests.createPairing(true, "PC", "linux", "10.0.0.5", peerFp, ByteArray(0)))
        assertNull(AuthRequests.pending.value)
    }

    @Test
    fun `a peer with no fingerprint never opens a session`() {
        // No fingerprint means the handshake gave us nothing to authorise. Pairing "someone"
        // is not a thing: the table entry *is* the fingerprint.
        assertNull(AuthRequests.createPairing(true, "PC", "linux", "10.0.0.5", "", commitOf(nonce(0x33))))
    }

    @Test
    fun `revealing against the wrong session id does nothing`() {
        val nonceA = nonce(0x33)
        val request = start(n = nonceA)!!
        assertNull(AuthRequests.revealPairing("not-the-session", nonceA, localFp))
        assertNull(AuthRequests.revealPairing("", nonceA, localFp))
        // The real session survives someone else's bad guess.
        assertNotNull(AuthRequests.revealPairing(request.id, nonceA, localFp))
    }

    @Test
    fun `a fingerprint identical to ours produces no code at all`() {
        // Either we reached ourselves or one end is confused. Neither should hand the user a
        // normal-looking code to successfully "compare".
        val mirror = Base32.encode(localFp)
        val nonceA = nonce(0x33)
        val request = AuthRequests.createPairing(
            true, "PC", "linux", "10.0.0.5", mirror, commitOf(nonceA),
        )!!
        assertNull(AuthRequests.revealPairing(request.id, nonceA, localFp))
        assertNull(AuthRequests.pending.value)
    }

    // ------------------------------------------------------------------ the shared slot

    @Test
    fun `v1 and v2 requests share one pending slot`() {
        // Counting them separately would let one of each raise two prompts at once, and
        // "one at a time" is the only thing stopping a wall of dialogs.
        assertNotNull(start())
        assertNull(AuthRequests.create(true, "another", "linux", "10.0.0.9", 8765, "1234"))
        assertNull(AuthRequests.createPairing(true, "another", "linux", "10.0.0.9", peerFp, commitOf(nonce(0x55))))
    }

    @Test
    fun `switching requests off refuses pairing too`() {
        assertNull(start(allow = false))
        assertNull(AuthRequests.pending.value)
    }

    @Test
    fun `a denial puts that address in the penalty box`() {
        val request = start()!!
        AuthRequests.decide(request.id, granted = false)
        assertNull(start())
        assertTrue(AuthRequests.retryAfterSec("10.0.0.5") > 0)
    }

    @Test
    fun `each session gets its own nonce`() {
        val first = start()!!
        AuthRequests.decide(first.id, granted = true)   // clears the slot and the cooldown
        val second = start()!!
        assertFalse(first.nonceB.contentEquals(second.nonceB))
        assertNotEquals(first.id, second.id)
    }
}
