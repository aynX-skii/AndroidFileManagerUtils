package com.aynux.afmu.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The peer stopped sending before the body was complete.
 *
 * Kept distinct from a plain [IOException] because the two mean opposite things to the caller:
 * a write that failed is a server problem (500), a body that ran out is the client's (400).
 * Either way the request must never be answered with `ok: true` — PROTOCOL.md §3.4.
 */
class TruncatedBody(message: String) : IOException(message)

/**
 * Buffered reader over a socket stream. It can read protocol lines and copy raw bytes up
 * to a delimiter, which is everything HTTP framing and multipart parsing need. Written by
 * hand so the app pulls in no networking dependency at all.
 */
class HttpInput(private val src: InputStream, capacity: Int = 1 shl 16) {

    private val buf = ByteArray(capacity)
    private var pos = 0
    private var lim = 0

    /** Compacts the buffer and reads more. Returns false once the peer is done sending. */
    private fun fill(): Boolean {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        if (lim == buf.size) return true
        val n = src.read(buf, lim, buf.size - lim)
        if (n <= 0) return false
        lim += n
        return true
    }

    private fun buffered() = lim - pos

    /**
     * Reads one CRLF- or LF-terminated line. Returns null at end of stream.
     *
     * The trailing CR is stripped from the assembled string rather than from the buffer:
     * a CRLF can straddle a refill, leaving the CR already flushed into [acc] where a
     * buffer-local check would never see it.
     */
    fun readLine(maxLength: Int = 16 * 1024): String? {
        val acc = ByteArrayOutputStream(64)
        while (true) {
            var i = pos
            while (i < lim) {
                if (buf[i] == NL) {
                    acc.write(buf, pos, i - pos)
                    pos = i + 1
                    return acc.toLine()
                }
                i++
            }
            acc.write(buf, pos, lim - pos)
            pos = lim
            if (acc.size() > maxLength) throw IOException("line exceeds $maxLength bytes")
            if (!fill()) return if (acc.size() == 0) null else acc.toLine()
        }
    }

    private fun ByteArrayOutputStream.toLine(): String = toString("UTF-8").removeSuffix("\r")

    /**
     * Copies bytes to [out] until [delim] is seen, consuming the delimiter itself.
     * Returns false if the stream ended before the delimiter appeared.
     */
    fun copyUntil(delim: ByteArray, out: OutputStream?): Boolean {
        require(delim.isNotEmpty())
        while (true) {
            val hit = indexOf(delim)
            if (hit >= 0) {
                out?.write(buf, pos, hit - pos)
                pos = hit + delim.size
                return true
            }
            // Everything except a possible partial delimiter at the tail is safe to emit.
            val safe = buffered() - (delim.size - 1)
            if (safe > 0) {
                out?.write(buf, pos, safe)
                pos += safe
            }
            if (!fill()) {
                out?.write(buf, pos, buffered())
                pos = lim
                return false
            }
        }
    }

    private fun indexOf(delim: ByteArray): Int {
        val last = lim - delim.size
        var i = pos
        outer@ while (i <= last) {
            var j = 0
            while (j < delim.size) {
                if (buf[i + j] != delim[j]) {
                    i++
                    continue@outer
                }
                j++
            }
            return i
        }
        return -1
    }

    /** Copies exactly [length] bytes. Throws if the peer hangs up early. */
    fun copyExact(out: OutputStream, length: Long, onProgress: ((Long) -> Unit)? = null) {
        var left = length
        var done = 0L
        while (left > 0) {
            if (buffered() == 0 && !fill()) throw TruncatedBody("stream ended $left bytes early")
            val n = minOf(buffered().toLong(), left).toInt()
            out.write(buf, pos, n)
            pos += n
            left -= n
            done += n
            onProgress?.invoke(done)
        }
    }

    /** Copies a `Transfer-Encoding: chunked` body. */
    fun copyChunked(out: OutputStream, onProgress: ((Long) -> Unit)? = null) {
        var done = 0L
        while (true) {
            val header = readLine() ?: throw TruncatedBody("truncated chunk header")
            val size = header.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("bad chunk header: $header")
            if (size == 0L) {
                while (true) {
                    val trailer = readLine() ?: break
                    if (trailer.isEmpty()) break
                }
                return
            }
            copyExact(out, size) { onProgress?.invoke(done + it) }
            done += size
            readLine() // trailing CRLF of the chunk
        }
    }

    /** Discards up to [length] bytes, used to keep a connection reusable. */
    fun discard(length: Long) {
        var left = length
        while (left > 0) {
            if (buffered() == 0 && !fill()) return
            val n = minOf(buffered().toLong(), left).toInt()
            pos += n
            left -= n
        }
    }

    private companion object {
        const val NL = '\n'.code.toByte()
    }
}
