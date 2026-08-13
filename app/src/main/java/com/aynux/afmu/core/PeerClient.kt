package com.aynux.afmu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * The outbound half: pushes files from the phone to a PC running `afmu serve`.
 * Speaks exactly the same protocol [HttpServer] implements, so either side can host.
 */
class PeerClient(private val context: Context) {

    /**
     * The pairing table decides whether a peer is reached over TLS (PROTOCOL.md v2 §5).
     *
     * Built lazily and kept, because every request needs it and reading SharedPreferences on
     * each one would be silly.
     */
    private val peers by lazy { PeerStore(context) }

    data class PickedFile(val uri: Uri, val name: String, val size: Long)

    /** Reads the display name and size a SAF picker hands us. */
    fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                }
            }
        }
        return PickedFile(uri, Storage.sanitizeName(name), size)
    }

    fun info(peer: Discovery.Peer, token: String): JSONObject {
        val connection = open(peer, "info", token, emptyMap(), "GET")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        return connection.readJson()
    }

    /**
     * Streams one file to the peer. [onProgress] reports bytes sent so the UI can show a
     * bar; the body is never buffered in memory, so multi-gigabyte files are fine.
     */
    fun upload(
        peer: Discovery.Peer,
        token: String,
        file: PickedFile,
        remoteDir: String? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val params = buildMap {
            put("name", file.name)
            if (!remoteDir.isNullOrBlank()) put("dir", remoteDir)
        }
        val connection = open(peer, "upload", token, params, "POST")
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = UPLOAD_TIMEOUT
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        if (file.size >= 0) {
            connection.setFixedLengthStreamingMode(file.size)
        } else {
            connection.setChunkedStreamingMode(CHUNK)
        }

        val source: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("cannot read ${file.name}")

        source.use { input ->
            connection.outputStream.use { output ->
                val buffer = ByteArray(CHUNK)
                var sent = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    sent += n
                    onProgress(sent, file.size)
                }
                output.flush()
            }
        }

        val body = connection.readJson()
        // `saved` is where the bytes actually landed. An empty one alongside ok:true means
        // the peer wrote nothing, and falling back to the name we sent would report that as
        // a successful transfer — the one way a file disappears without anyone noticing.
        return body.optJSONArray("saved")?.optString(0)?.takeIf { it.isNotEmpty() }
            ?: throw IOException("peer reported success but saved no file")
    }

    /**
     * Pulls a file from the peer into this phone's inbox, resuming an interrupted attempt
     * where it left off (PROTOCOL.md §3.3).
     *
     * The leftover `.afmu-part` is the state; there is nothing to remember between runs.
     * Three answers mean it cannot be built on, and each restarts from zero rather than
     * appending onto bytes that do not line up:
     *
     *  - **200** — the peer ignored `Range` and is sending the whole file from the start;
     *  - **206 with the wrong offset** — it granted a range, but not the one we asked for;
     *  - **416** — the leftover is already at or past the file's length, so it belongs to
     *    some earlier, different version of that path. Stale, not finished.
     *
     * Only that last one costs a second request, and only in the rare case.
     */
    fun download(
        peer: Discovery.Peer,
        token: String,
        remotePath: String,
        prefs: Prefs,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val fallbackName = remotePath.substringAfterLast('/')
        return Storage.createResumableInboxSink(context, prefs, fallbackName, remotePath)
            .use { destination ->
                var connection = openDownload(peer, token, remotePath, destination.existingBytes)
                if (connection.responseCode == 416) {
                    // Stale leftover. Throw it away and ask again from the top — the retry is
                    // unconditional, so a peer that answers 416 to a plain request too will
                    // surface as the error below rather than as a silent empty file.
                    connection.disconnect()
                    destination.restart()
                    connection = openDownload(peer, token, remotePath, 0L)
                }
                if (connection.responseCode !in 200..299) {
                    throw IOException("peer refused: HTTP ${connection.responseCode}")
                }

                // 200 to a request that carried a Range means the peer is starting over, so
                // whatever is on disk must go. Same when the granted range does not begin
                // where we asked: appending would splice two unrelated offsets together.
                var alreadyHave = destination.existingBytes
                if (alreadyHave > 0) {
                    val granted = rangeStartOf(connection.getHeaderField("Content-Range"))
                    if (connection.responseCode != 206 || granted != alreadyHave) {
                        destination.restart()
                        alreadyHave = 0L
                    }
                }

                // Content-Length describes what is still coming; the bar wants the whole file.
                val remaining = connection.contentLengthLong
                val total = if (remaining >= 0) alreadyHave + remaining else -1L
                var received = alreadyHave
                onProgress(received, total)

                connection.inputStream.use { input ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        destination.stream.write(buffer, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
                // A peer that hangs up mid-file ends the stream cleanly as far as read() is
                // concerned. Committing on a short read would pass half a file off as the
                // whole one — so this throws, and the partial survives for the next attempt.
                if (total >= 0 && received != total) {
                    throw IOException("download truncated: got $received of $total bytes")
                }
                destination.commitAs(
                    HttpServer.extractParameter(
                        connection.getHeaderField("Content-Disposition").orEmpty(), "filename"
                    )
                )
                destination.displayPath
            }
    }

    private fun openDownload(
        peer: Discovery.Peer,
        token: String,
        remotePath: String,
        from: Long,
    ): HttpURLConnection {
        val connection = open(peer, "download", token, mapOf("path" to remotePath), "GET")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = UPLOAD_TIMEOUT
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        return connection
    }

    /** First byte of `bytes <start>-<end>/<total>`, or -1 when the header is unusable. */
    private fun rangeStartOf(contentRange: String?): Long {
        val spec = contentRange?.trim()?.removePrefix("bytes")?.trim() ?: return -1L
        return spec.substringBefore('-').trim().toLongOrNull() ?: -1L
    }

    /**
     * Hands this phone's own address and token to the peer (PROTOCOL.md §3.9), so a pairing
     * done one way — scanning the PC's QR code — leaves both directions usable.
     */
    fun pair(
        peer: Discovery.Peer,
        token: String,
        selfName: String,
        selfPort: Int,
        selfToken: String,
    ): JSONObject {
        val connection = open(
            peer, "pair", token,
            mapOf(
                "name" to selfName,
                "os" to "android",
                "port" to selfPort.toString(),
                "token" to selfToken,
            ),
            "POST",
        )
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setFixedLengthStreamingMode(0)
        return connection.readJson()
    }

    /**
     * Asks a peer we hold no token for to put a prompt on its own screen (PROTOCOL.md §3.8).
     * Returns the request id, which is the only key to the token once the user allows it.
     *
     * The peer may not implement this at all — a `404`, or a `401` from peers that run every
     * `/api/` route past a token check. Both mean "not supported", never "failed": the caller
     * has to fall back to typing the token by hand.
     */
    fun requestAuth(
        peer: Discovery.Peer,
        selfName: String,
        code: String,
        selfPort: Int,
    ): AuthTicket {
        val connection = open(
            peer, "authorize", "",
            mapOf("name" to selfName, "os" to "android", "code" to code, "port" to selfPort.toString()),
            "POST",
        )
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setFixedLengthStreamingMode(0)

        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()

        return when {
            status == 404 || status == 401 -> AuthTicket(outcome = AuthOutcome.UNSUPPORTED)
            status == 403 -> AuthTicket(outcome = AuthOutcome.DISABLED)
            status == 429 -> AuthTicket(outcome = AuthOutcome.BUSY)
            json?.optBoolean("ok", false) == true && !json.optString("request").isNullOrEmpty() ->
                AuthTicket(outcome = AuthOutcome.OK, id = json.optString("request"))
            else -> AuthTicket(outcome = AuthOutcome.FAILED)
        }
    }

    /**
     * Polls a request registered by [requestAuth]. A `404` means the peer has forgotten it,
     * which the protocol says to treat as "expired" rather than as an error.
     */
    fun pollAuth(peer: Discovery.Peer, requestId: String): JSONObject {
        val connection = open(peer, "authorize", "", mapOf("request" to requestId), "GET")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        val status = connection.responseCode
        if (status == 404) return JSONObject().put("status", "expired")
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("peer returned HTTP $status")
    }

    enum class AuthOutcome { OK, UNSUPPORTED, DISABLED, BUSY, FAILED }

    data class AuthTicket(val outcome: AuthOutcome, val id: String = "")

    fun list(peer: Discovery.Peer, token: String, path: String): JSONObject {
        val connection = open(peer, "list", token, mapOf("path" to path), "GET")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        return connection.readJson()
    }

    // ------------------------------------------------------------ v2 配对（草案 §4.2）

    /**
     * The three-step pairing handshake, driven from this phone.
     *
     * The connection is pinned to [expectedFp] — the fingerprint the QR code carried — so
     * this channel is already authenticated in one direction and a relay cannot sit in it.
     * The compare code still exists because the *other* end has no way to know a QR was
     * used, and its user is the one deciding whether to open the door.
     */
    fun pairCommit(
        host: String,
        port: Int,
        expectedFp: String,
        commitHex: String,
        selfName: String,
        selfPort: Int,
    ): JSONObject = openPinned(
        host, port, expectedFp, "pair-v2", "POST",
        mapOf(
            "step" to "commit", "commit" to commitHex, "name" to selfName, "os" to "android",
            // Our listening port. From the socket the peer sees our IP and our *source* port,
            // not where we receive — without this the address hint it stores is a guess, and
            // dialling us back later fails. v1's /api/authorize always carried it.
            "port" to selfPort.toString(),
        ),
    ).readJson()

    /**
     * Step 1 against a peer whose fingerprint we do not know yet — the typed-address path,
     * where no QR authenticated anything out of band.
     *
     * Returns the reply **and** the fingerprint the server presented. Every later step pins to
     * that value: this connection is unpinned, the rest of the pairing must not be. What stops
     * a man in the middle here is the user comparing the SAS on both screens, and that only
     * works because the peer in this state serves nothing but `/api/pair-v2`.
     */
    fun pairCommitUnpinned(
        host: String,
        port: Int,
        commitHex: String,
        selfName: String,
        selfPort: Int,
    ): Pair<JSONObject, String> {
        val identity = Identity.ensure() ?: throw IOException("this device has no usable identity")
        val trust = Tls.RecordingTrustManager()
        val factory = Tls.pairingFactory(identity, trust)
            ?: throw IOException("this device has no usable identity")
        val params = mapOf(
            "step" to "commit", "commit" to commitHex, "name" to selfName, "os" to "android",
            "port" to selfPort.toString(),
        )
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val url = URL("https://$host:$port/api/pair-v2?$query")
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(0)
        }
        val body = connection.readJson()
        val fp = trust.serverFingerprint
        if (fp.isEmpty()) throw IOException("the peer presented no usable certificate")
        return body to fp
    }

    fun pairReveal(
        host: String,
        port: Int,
        expectedFp: String,
        session: String,
        naHex: String,
    ): JSONObject = openPinned(
        host, port, expectedFp, "pair-v2", "POST",
        mapOf("step" to "reveal", "session" to session, "na" to naHex),
    ).readJson()

    fun pairPoll(host: String, port: Int, expectedFp: String, session: String): JSONObject =
        openPinned(host, port, expectedFp, "pair-v2", "GET", mapOf("session" to session)).readJson()

    /**
     * Opens a TLS connection pinned to one specific fingerprint, bypassing the pairing table.
     *
     * Only pairing may use this: everywhere else the table is the authority, and taking the
     * fingerprint from the caller instead would make it possible to "pin" to whatever the
     * network just handed us — which is not pinning at all.
     */
    private fun openPinned(
        host: String,
        port: Int,
        expectedFp: String,
        endpoint: String,
        method: String,
        params: Map<String, String>,
    ): HttpsURLConnection {
        val factory = pinningFactory(expectedFp)
            ?: throw IOException("this device has no usable identity")
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val url = URL("https://$host:$port/api/$endpoint" + if (query.isEmpty()) "" else "?$query")
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                // No body — every parameter is in the query, the same as v1's endpoints.
                setFixedLengthStreamingMode(0)
            }
        }
    }

    private fun open(
        peer: Discovery.Peer,
        endpoint: String,
        token: String,
        params: Map<String, String>,
        method: String,
    ): HttpURLConnection {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val suffix = "/api/$endpoint" + if (query.isEmpty()) "" else "?$query"

        // Is this address a peer we have paired with? The address only picks the candidate;
        // it never establishes identity. Picking wrong means the fingerprint will not match
        // and the connection is refused — the failure direction is the safe one (§13 Q3).
        val expected = peers.findByAddressHint(peer.host, peer.port)
        val pinning = if (expected != null) pinningFactory(expected.fp) else null

        if (pinning != null) {
            val url = URL("https://${peer.host}:${peer.port}$suffix")
            return (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = pinning
                // The certificate has no meaningful host name in it — pinning replaced that
                // whole question. Verifying the name here would reject every peer; not
                // verifying it costs nothing, because the fingerprint check already ran and
                // throws from inside the handshake.
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                requestMethod = method
                instanceFollowRedirects = false
                // No token on a v2 connection: the handshake is the authentication (§5.2).
                setRequestProperty("Accept", "application/json")
            }
        }

        val url = URL("${peer.url}$suffix")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            // /api/authorize is the one endpoint reached without a token; sending an empty
            // header there would only invite a peer to treat it as a bad one.
            if (token.isNotEmpty()) setRequestProperty("X-AFMU-Token", token)
            setRequestProperty("Accept", "application/json")
        }
    }

    /**
     * A socket factory that presents this device's identity and accepts exactly one peer.
     *
     * Returns null when there is no usable identity, which sends the caller down the v1 path
     * rather than into a "TLS connection that authenticates nobody".
     *
     * Note what is *not* here: no fallback. If the handshake fails against a paired peer the
     * request fails, and nothing retries in plaintext — that retry is the whole downgrade
     * attack (§8.1 rule 1).
     */
    private fun pinningFactory(expectedFp: String): javax.net.ssl.SSLSocketFactory? {
        val identity = Identity.ensure() ?: return null
        // No guest allowance on this side: when we dial out, an unpaired peer is exactly the
        // man in the middle we are checking for.
        val trust = Tls.PinningTrustManager(isAllowed = { fp -> fp == expectedFp })
        return Tls.socketFactory(identity, trust)
    }

    private fun HttpURLConnection.readJson(): JSONObject {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IOException("peer returned HTTP $responseCode: ${text.take(200)}")
        if (!json.optBoolean("ok", false)) {
            throw IOException(json.optString("error", "peer returned HTTP $responseCode"))
        }
        return json
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val CHUNK = 128 * 1024
        const val CONNECT_TIMEOUT = 5_000
        const val READ_TIMEOUT = 10_000
        const val UPLOAD_TIMEOUT = 0 // no limit: large files take as long as they take
    }
}
