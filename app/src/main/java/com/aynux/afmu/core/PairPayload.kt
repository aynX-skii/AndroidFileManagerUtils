package com.aynux.afmu.core

/**
 * The `afmu://pair?…` string carried by a QR code (PROTOCOL.md §5, draft §4.1).
 *
 * The PC shows one; scanning it hands over its address and identity in a single step, which
 * is the whole reason the feature exists — the alternative is copying a fingerprint by hand.
 *
 * Parsed **without `android.net.Uri`**, on purpose. Two reasons, both practical:
 *
 * - This is the one parser fed by whatever a camera happened to decode, so it deserves real
 *   tests, and the platform's `Uri` is an empty stub in JVM unit tests.
 * - It has to agree with the Linux side's `afmu::buildPairUri` character for character. Both
 *   ends encoding spaces as `%20` and treating `+` as a literal plus is a decision, not an
 *   accident, and it is easier to keep honest with a parser we can read.
 */
data class PairPayload(
    val hosts: List<String>,
    val port: Int,
    /** v1 only. Empty in a v2 code — and that is the point, see [fingerprint]. */
    val token: String,
    /**
     * The displaying device's SPKI fingerprint, base32. Non-empty means a v2 code.
     *
     * A v1 code carries a plaintext token, so screenshotting or forwarding it hands over
     * access. A fingerprint is public information by construction: leaking it costs nothing.
     * That is one real problem v2 fixes on the way past (PROTOCOL.md v2 §4.1).
     */
    val fingerprint: String,
    val name: String,
    val os: String,
) {
    val isV2: Boolean get() = fingerprint.isNotEmpty()

    companion object {
        private const val SCHEME = "afmu"
        private const val ACTION = "pair"

        /**
         * Returns null for anything that is not one of our codes — a scanner points at
         * whatever the camera sees, so most of what it decodes is not ours.
         */
        fun parse(raw: String): PairPayload? {
            val text = raw.trim()
            val prefix = "$SCHEME://$ACTION?"
            if (!text.startsWith(prefix, ignoreCase = true)) return null

            val q = parseQuery(text.substring(prefix.length))

            // A major version we do not know may mean anything at all; refuse rather than guess.
            val version = q["v"]?.toIntOrNull() ?: HttpServer.PROTOCOL_VERSION
            if (version > PAIR_VERSION_V2) return null

            val token = q["token"]?.trim().orEmpty()
            // Normalised on the way in, and rejected outright if it will not normalise: a
            // fingerprint that is "close" is worse than none — it would be stored, never
            // match, and present as "we paired but it will not connect".
            val fingerprint = PeerCodec.normalize(q["fp"]?.trim()).orEmpty()

            // One of the two has to be there, or the code cannot do anything at all.
            if (token.isEmpty() && fingerprint.isEmpty()) return null

            // `hosts` carries every address of a multi-homed PC; try them in order.
            val hosts = buildList {
                q["host"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                q["hosts"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.let { addAll(it) }
            }.distinct()
            if (hosts.isEmpty()) return null

            return PairPayload(
                hosts = hosts,
                port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Prefs.DEFAULT_PORT,
                token = token,
                fingerprint = fingerprint,
                name = q["name"]?.trim().orEmpty(),
                os = q["os"]?.trim().orEmpty().ifEmpty { "linux" },
            )
        }

        /**
         * Splits `a=1&b=2` and percent-decodes both sides.
         *
         * `+` is left as a literal plus, **not** turned into a space: the protocol says space
         * is `%20` (PROTOCOL.md §5), and the Linux side encodes it that way. Decoding `+` as
         * a space here would quietly corrupt any device name containing one.
         */
        private fun parseQuery(query: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                out[percentDecode(pair.substring(0, eq))] = percentDecode(pair.substring(eq + 1))
            }
            return out
        }

        private fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val bytes = java.io.ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val hex = if (c == '%' && i + 2 < text.length) {
                    text.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    // 不合法的 % 序列原样留着，不猜。二维码里出现它多半意味着
                    // 这根本不是我们的码，而猜出来的结果只会更难查。
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString("UTF-8")
        }

        /**
         * The mirror image: what this phone would show if it ever displays a code itself.
         *
         * Passing a fingerprint makes it a v2 code, and then **the token is left out** — not
         * merely optional. Shipping both would keep the screenshot problem alive for no gain.
         */
        fun build(
            hosts: List<String>,
            port: Int,
            token: String,
            name: String,
            os: String,
            fingerprint: String = "",
        ): String {
            val v2 = fingerprint.isNotEmpty()
            val parts = ArrayList<String>()
            parts += "v=" + if (v2) "$PAIR_VERSION_V2" else "${HttpServer.PROTOCOL_VERSION}"
            hosts.firstOrNull()?.let { parts += "host=" + encode(it) }
            if (hosts.size > 1) parts += "hosts=" + encode(hosts.joinToString(","))
            parts += "port=$port"
            parts += if (v2) "fp=" + encode(fingerprint) else "token=" + encode(token)
            parts += "name=" + encode(name)
            parts += "os=" + encode(os)
            return "$SCHEME://$ACTION?" + parts.joinToString("&")
        }

        /** Percent-encodes everything outside the unreserved set; space becomes `%20`. */
        private fun encode(text: String): String {
            val out = StringBuilder(text.length)
            for (b in text.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            return out.toString()
        }

        /** The pairing-code version that carries a fingerprint (draft §4.1). */
        const val PAIR_VERSION_V2 = 2
    }
}
