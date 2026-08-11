package com.aynux.afmu.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One pairing (PROTOCOL-v2-DRAFT.md §4.3).
 *
 * **[fp] is the identity; nothing else here is.** The peer renames itself, DHCP moves it to
 * another address — only a different fingerprint means a different device. Every lookup,
 * every deduplication, every pin check goes through [fp].
 */
data class PeerRecord(
    /** base32 fingerprint, 52 characters once normalized. See [PeerCodec.normalize]. */
    val fp: String,
    val name: String = "",
    val os: String = "",
    /**
     * Where it was last seen. **A reconnect hint only, never part of the identity**
     * (draft §13 question 3): a multi-homed PC changing address is routine, and the
     * fingerprint not changing means it is still the same machine.
     */
    val lastHost: String = "",
    val lastPort: Int = 0,
    /** Unix seconds of first pairing. A reconnect must not refresh it — the user wants to
     *  know when they met this device, not when they last saw it. */
    val pairedAt: Long = 0,
    /**
     * This peer talks v2 only: a failed TLS handshake is a failure, never a fallback to
     * plaintext (draft §8.1 rule 1). It is the sole defence against downgrade, so it is set
     * by "a v2 connection succeeded", not by a checkbox someone can flip while distracted.
     */
    val pinned: Boolean = false,
)

/**
 * The pairing table's on-disk form, kept free of any Android dependency.
 *
 * Same reasoning as [Base32]: this table is simultaneously data and an access control list —
 * a row in it is an open door — so "the same fingerprint written two ways" is a security bug,
 * not an untidiness. Keeping the codec pure is what makes it checkable on a plain JVM.
 */
object PeerCodec {

    /** SHA-256. */
    private const val FINGERPRINT_BYTES = 32

    /**
     * Strips grouping spaces and dashes, upper-cases, and zeroes the padding bits in the last
     * character. Returns null if the text is not a fingerprint at all.
     *
     * That last part is worth spelling out: 52 base32 characters carry 260 bits while a
     * fingerprint is 256, so the final character's low 4 bits are padding. Hand-transcription
     * or another implementation may leave them non-zero, giving one fingerprint sixteen
     * spellings. Decoding and re-encoding collapses them all — otherwise the table can hold
     * two rows for one device and deleting one leaves the other door open.
     */
    fun normalize(fp: String?): String? {
        val raw = fp?.let { Base32.decode(it) } ?: return null
        if (raw.size != FINGERPRINT_BYTES) return null
        return Base32.encode(raw)
    }

    fun isValidFingerprint(fp: String?): Boolean = normalize(fp) != null

    /**
     * Parses the stored array.
     *
     * Rows whose fingerprint will not normalize are dropped rather than kept: keeping one
     * would show the user a device that can never actually connect. Duplicates collapse to
     * the last occurrence for the reason in [normalize]. [onDropped] reports how many rows
     * went away so the caller can say so instead of silently shrinking the list.
     */
    fun decode(json: String?, onDropped: ((Int) -> Unit)? = null): List<PeerRecord> {
        val out = ArrayList<PeerRecord>()
        var dropped = 0
        val arr = runCatching { JSONArray(json ?: "[]") }.getOrNull()
        if (arr == null) {
            onDropped?.invoke(0)
            return emptyList()
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val fp = normalize(o?.optString("fp"))
            if (o == null || fp == null) {
                dropped++
                continue
            }
            val record = PeerRecord(
                fp = fp,
                name = o.optString("name"),
                os = o.optString("os"),
                lastHost = o.optString("lastHost"),
                lastPort = o.optInt("lastPort"),
                pairedAt = o.optLong("pairedAt"),
                pinned = o.optBoolean("pinned"),
            )
            val existing = out.indexOfFirst { it.fp == fp }
            if (existing >= 0) {
                out[existing] = record
                dropped++
            } else {
                out.add(record)
            }
        }
        onDropped?.invoke(dropped)
        return out
    }

    fun encode(items: List<PeerRecord>): String {
        val arr = JSONArray()
        for (r in items) {
            arr.put(
                JSONObject()
                    .put("fp", r.fp)
                    .put("name", r.name)
                    .put("os", r.os)
                    .put("lastHost", r.lastHost)
                    .put("lastPort", r.lastPort)
                    .put("pairedAt", r.pairedAt)
                    .put("pinned", r.pinned)
            )
        }
        return arr.toString()
    }

    /**
     * Inserts or updates by fingerprint, returning the new list and whether a row was added
     * (i.e. whether a new door was opened). Returns the list untouched for an invalid
     * fingerprint — storing one means a pin check that can never match, whose symptom is
     * "it says we're paired but it won't connect".
     *
     * An existing row keeps its `pairedAt`, and `pinned` can only go up here: a routine
     * update clearing it is exactly what a downgrade attack wants. Clearing is [setPinned]'s
     * job, which is a deliberate act.
     */
    fun upsert(
        items: List<PeerRecord>,
        record: PeerRecord,
        now: Long,
    ): Pair<List<PeerRecord>, Boolean> {
        val fp = normalize(record.fp) ?: return items to false
        val out = ArrayList(items)
        val i = out.indexOfFirst { it.fp == fp }
        if (i >= 0) {
            out[i] = record.copy(
                fp = fp,
                pairedAt = out[i].pairedAt,
                pinned = record.pinned || out[i].pinned,
            )
            return out to false
        }
        out.add(record.copy(fp = fp, pairedAt = if (record.pairedAt > 0) record.pairedAt else now))
        return out to true
    }
}
