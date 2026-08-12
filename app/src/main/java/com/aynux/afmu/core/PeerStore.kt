package com.aynux.afmu.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing table: fingerprint → peer, persisted in SharedPreferences.
 *
 * In v2 "is it in this table" *is* the whole authorization decision — a fingerprint that is
 * not here cannot even complete the TLS handshake — so this is as much an access control list
 * as it is a list of devices.
 *
 * Two things are deliberately absent (draft §13 question 2): no cap on rows, and no expiry.
 * Dropping a device you have not used in six months means re-pairing it next time, and all
 * the user perceives is "why do I have to do this again". Cleanup is manual, from the UI.
 *
 * All mutation goes through a lock and rewrites the whole list: the table is small (a handful
 * of devices), and the server thread and the UI both touch it.
 */
class PeerStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("afmu", Context.MODE_PRIVATE)

    /**
     * The table itself is **process-wide**, not per instance.
     *
     * Several things hold a `PeerStore`: the server (through `Bridge`), the UI, a one-off for
     * a discovery probe. If each kept its own copy, approving a pairing in the UI would write
     * the record into the UI's copy while the handshake kept asking the server's — and the
     * peer that was just approved would be refused by the very device that approved it, until
     * something happened to recreate that instance. This list is an access-control list; there
     * is exactly one of it.
     */
    private val _peers get() = shared
    val peers: StateFlow<List<PeerRecord>> = shared.asStateFlow()

    /**
     * How many rows the last load threw away (unusable or duplicate fingerprints), so the UI
     * can say so. Silently showing a shorter list than what is stored is how a "wait, wasn't
     * that phone paired?" mystery starts.
     */
    val droppedOnLoad: Int get() = dropped

    init {
        synchronized(lock) {
            if (!loaded) {
                shared.value = PeerCodec.decode(sp.getString(KEY, null)) { dropped = it }
                loaded = true
            }
        }
        // Note what was dropped but do **not** write the cleaned list back here: that would
        // destroy the original before the user has seen any notice about it.
    }

    fun all(): List<PeerRecord> = _peers.value

    /** Is this fingerprint paired — the only question TLS pinning should ask. */
    fun isPaired(fp: String?): Boolean = find(fp) != null

    /** Paired *and* v2-only. An unpaired fingerprint is not pinned. */
    fun isPinned(fp: String?): Boolean = find(fp)?.pinned == true

    /**
     * Finds a record by address hint, to decide *which* fingerprint to pin for this address.
     *
     * **This is not identity.** The address only picks a candidate: picking the wrong one
     * means the handshake fails on a fingerprint mismatch, which is the safe direction. The
     * inverse — "the address matched, so trust it" — is what must never be written.
     */
    fun findByAddressHint(host: String, port: Int): PeerRecord? {
        if (host.isEmpty()) return null
        return _peers.value.firstOrNull { it.lastHost == host && (port <= 0 || it.lastPort == port) }
    }

    fun find(fp: String?): PeerRecord? {
        val key = PeerCodec.normalize(fp) ?: return null
        return _peers.value.firstOrNull { it.fp == key }
    }

    /** Adds or updates by fingerprint. Returns true when a new pairing was created. */
    fun upsert(record: PeerRecord, now: Long = System.currentTimeMillis() / 1000): Boolean =
        synchronized(lock) {
            val (next, added) = PeerCodec.upsert(_peers.value, record, now)
            if (next !== _peers.value) commit(next)
            added
        }

    /**
     * Updates the reconnect hint only — not the identity, not `pairedAt`. Does nothing for a
     * fingerprint that is not in the table: having seen a device is not having trusted it.
     */
    fun noteSeen(fp: String?, host: String, port: Int) {
        synchronized(lock) {
            val key = PeerCodec.normalize(fp) ?: return
            val i = _peers.value.indexOfFirst { it.fp == key }
            if (i < 0) return
            val current = _peers.value[i]
            if (current.lastHost == host && current.lastPort == port) return
            commit(_peers.value.toMutableList().also {
                it[i] = current.copy(lastHost = host, lastPort = port)
            })
        }
    }

    /** Set once a v2 connection has succeeded; from then on this peer never falls back. */
    fun setPinned(fp: String?, on: Boolean): Boolean {
        synchronized(lock) {
            val key = PeerCodec.normalize(fp) ?: return false
            val i = _peers.value.indexOfFirst { it.fp == key }
            if (i < 0 || _peers.value[i].pinned == on) return false
            commit(_peers.value.toMutableList().also { it[i] = it[i].copy(pinned = on) })
            return true
        }
    }

    fun remove(fp: String?): Boolean {
        synchronized(lock) {
            val key = PeerCodec.normalize(fp) ?: return false
            val next = _peers.value.filter { it.fp != key }
            if (next.size == _peers.value.size) return false
            commit(next)
            return true
        }
    }

    private fun commit(next: List<PeerRecord>) {
        // commit() rather than apply(): the pairing table decides who gets in, and losing the
        // last write to a process death would leave a device paired that the user just removed.
        sp.edit().putString(KEY, PeerCodec.encode(next)).commit()
        _peers.value = next
    }

    private companion object {
        const val KEY = "peersV2"

        /** See [peers]: one table per process, not one per holder. */
        val lock = Any()
        val shared = MutableStateFlow<List<PeerRecord>>(emptyList())
        var loaded = false
        @Volatile var dropped = 0
    }
}
