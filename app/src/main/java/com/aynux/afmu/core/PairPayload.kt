package com.aynux.afmu.core

import android.net.Uri

/**
 * The `afmu://pair?…` string carried by a QR code (PROTOCOL.md §5).
 *
 * The PC shows one; scanning it hands over its address and token in a single step, which is
 * the whole reason the feature exists — the alternative is typing ten characters by hand.
 */
data class PairPayload(
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val name: String,
    val os: String,
) {
    companion object {
        private const val SCHEME = "afmu"
        private const val ACTION = "pair"

        /**
         * Returns null for anything that is not one of our codes — a scanner points at
         * whatever the camera sees, so most of what it decodes is not ours.
         */
        fun parse(raw: String): PairPayload? {
            val text = raw.trim()
            if (!text.startsWith("$SCHEME://", ignoreCase = true)) return null

            val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
            if (!uri.host.equals(ACTION, ignoreCase = true)) return null

            // A major version we do not know may mean anything at all; refuse rather than guess.
            val version = uri.getQueryParameter("v")?.toIntOrNull() ?: HttpServer.PROTOCOL_VERSION
            if (version > HttpServer.PROTOCOL_VERSION) return null

            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (token.isEmpty()) return null

            // `hosts` carries every address of a multi-homed PC; try them in order.
            val hosts = buildList {
                uri.getQueryParameter("host")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                uri.getQueryParameter("hosts")?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { addAll(it) }
            }.distinct()
            if (hosts.isEmpty()) return null

            return PairPayload(
                hosts = hosts,
                port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: Prefs.DEFAULT_PORT,
                token = token,
                name = uri.getQueryParameter("name")?.trim().orEmpty(),
                os = uri.getQueryParameter("os")?.trim().orEmpty().ifEmpty { "linux" },
            )
        }

        /** The mirror image: what this phone would show if it ever displays a code itself. */
        fun build(hosts: List<String>, port: Int, token: String, name: String, os: String): String {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(ACTION)
                .appendQueryParameter("v", HttpServer.PROTOCOL_VERSION.toString())
            hosts.firstOrNull()?.let { builder.appendQueryParameter("host", it) }
            if (hosts.size > 1) builder.appendQueryParameter("hosts", hosts.joinToString(","))
            return builder
                .appendQueryParameter("port", port.toString())
                .appendQueryParameter("token", token)
                .appendQueryParameter("name", name)
                .appendQueryParameter("os", os)
                .build()
                .toString()
        }
    }
}
