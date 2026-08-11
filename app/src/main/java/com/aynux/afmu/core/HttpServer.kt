package com.aynux.afmu.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small HTTP/1.1 server implemented directly on [ServerSocket]. It serves both the JSON
 * API used by the Linux client and a browser UI, so a PC with nothing installed can still
 * transfer files by opening the URL.
 *
 * Every /api route requires the shared token from [Prefs]; path traversal is blocked by
 * [Storage.resolve].
 */
class HttpServer(
    private val context: Context,
    private val prefs: Prefs,
    private val peers: PeerStore? = null,
    private val log: (String) -> Unit = {},
) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null

    /**
     * v2 (PROTOCOL-v2-DRAFT.md §5). Null means this device can only speak v1 — either there
     * is no usable identity, or no pairing table to check fingerprints against. Both are
     * needed: an identity with nothing to compare against is not pinning, it is decoration.
     */
    @Volatile private var tlsFactory: SSLSocketFactory? = null
    @Volatile private var tlsTrust: Tls.PinningTrustManager? = null

    val tlsReady: Boolean get() = tlsFactory != null

    @Volatile var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket != null

    fun start(): Int {
        stop()
        setUpTls()
        val socket = bind(prefs.port)
        val executor = Executors.newCachedThreadPool()
        serverSocket = socket
        pool = executor
        port = socket.localPort
        Thread({ acceptLoop(socket, executor) }, "afmu-accept").apply { isDaemon = true }.start()
        log("Server listening on port $port")
        return port
    }

    /**
     * Builds the TLS stack, or leaves it null and stays v1-only.
     *
     * Deliberately silent about *why* on the happy path but loud on failure: "encryption is
     * off and nobody said so" is the failure mode that matters here.
     */
    private fun setUpTls() {
        tlsFactory = null
        tlsTrust = null
        // Plaintext allowed means v1, full stop: see handle() for why this end cannot serve
        // both at once. The toggle is the whole decision.
        if (prefs.allowLegacyPlaintext) return
        val store = peers ?: return
        val identity = Identity.ensure()
        if (identity == null) {
            log("No usable device identity — encrypted connections are unavailable")
            return
        }
        val trust = Tls.PinningTrustManager(
            isAllowed = { fp -> store.isPaired(fp) },
            // Guest mode lets an unpaired client past the handshake and on to the password
            // check. Read per-connection, not captured, so flipping the switch takes effect
            // on the next connection instead of the next restart.
            allowUnpairedClient = { prefs.guestModeActive },
        )
        val factory = Tls.socketFactory(identity, trust)
        if (factory == null) {
            log("TLS could not be set up — encrypted connections are unavailable")
            return
        }
        tlsTrust = trust
        tlsFactory = factory
    }

    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }
        pool?.shutdownNow()
        pool = null
        port = 0
        log("Server stopped")
    }

    /** Falls back to nearby ports, then to any free port, so a stale socket can't block us. */
    private fun bind(desired: Int): ServerSocket {
        var lastError: IOException? = null
        for (candidate in intArrayOf(desired, desired + 1, desired + 2, 0)) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidate), 64)
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("cannot bind any port")
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (serverSocket === socket) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (serverSocket === socket) log("Accept failed: ${e.message}")
                return
            }
            runCatching {
                executor.execute {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        if (e !is SocketException) log("Connection error: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }.onFailure { runCatching { client.close() } }
        }
    }

    // ---------------------------------------------------------------- request handling

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val input: HttpInput,
        /** Taken from the socket, never from the request — a peer must not name itself. */
        val remoteHost: String,
        /**
         * Set once the connection completed a v2 handshake against a paired fingerprint.
         *
         * When true the token check is skipped: **a completed handshake against a pinned
         * key is the authentication** (PROTOCOL-v2-DRAFT.md §5.2). Asking for a token on
         * top would carry v1's weakness — one long-lived shared secret — into v2.
         */
        val pairedPeer: String = "",
    ) {
        val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: -1L
        val isChunked: Boolean get() = headers["transfer-encoding"]?.contains("chunked", true) == true
        val hasBody: Boolean get() = contentLength > 0 || isChunked
        var bodyConsumed = false
    }

    /**
     * Serves one connection, wrapping it in TLS when this device is in encrypted-only mode.
     *
     * **This end does not do the first-byte sniffing of PROTOCOL-v2-DRAFT.md §8.1 rule 4, and
     * it is not an oversight.** Sniffing means peeking the first byte and then handing it
     * back to the TLS engine, which on the JVM is
     * `SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean)` — **an overload
     * Android does not have** (the platform kept the pre-Java-8 signature set). Without it a
     * consumed ClientHello byte cannot be given back, and the handshake can never complete.
     *
     * So the phone serves one protocol at a time, chosen by [Prefs.allowLegacyPlaintext]:
     * plaintext v1 (the default, and what every existing client speaks) or encrypted v2.
     * The Linux side keeps serving both on one port, because Qt lets it peek. The cost is
     * only on the phone-as-server direction, and it is visible in the setting rather than
     * hidden in a fallback.
     */
    private fun handle(raw: Socket) {
        raw.soTimeout = SOCKET_TIMEOUT_MS
        raw.tcpNoDelay = true
        val remoteHost = raw.inetAddress?.hostAddress?.removePrefix("::ffff:").orEmpty()

        var socket = raw
        var pairedPeer = ""

        val factory = tlsFactory
        if (factory != null) {
            val ssl = factory.createSocket(raw, remoteHost, raw.port, true) as SSLSocket
            Tls.harden(ssl, server = true)
            try {
                // Throws when the peer offers a certificate we do not accept — and it throws
                // *here*, before a single request has been read, let alone answered.
                ssl.startHandshake()
            } catch (e: Exception) {
                log("Refused an encrypted connection from $remoteHost: ${e.message}")
                return
            }

            // Read the identity off **this connection's** session, not off the TrustManager.
            //
            // The TrustManager is shared by every connection, so a field it sets would still
            // hold the previous peer's fingerprint — and under `wantClientAuth` a client that
            // presents no certificate never reaches the TrustManager at all. Those two facts
            // together mean a certificate-less connection would have inherited the last paired
            // peer's identity and been served as that device. The session cannot be inherited.
            val leaf = runCatching {
                ssl.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
            }.getOrNull()
            val fp = Tls.fingerprintOf(leaf)

            if (fp.isNotEmpty() && peers?.isPaired(fp) == true) {
                pairedPeer = fp
                // A completed v2 connection is proof this peer speaks v2; from here on it is
                // never allowed back to plaintext (draft §8.2 stage 2).
                peers.setPinned(fp, true)
                log("$remoteHost connected over an encrypted link")
            } else if (prefs.guestModeActive) {
                // Encrypted, but we have no idea who this is — a browser, most likely, since
                // browsers never present a client certificate. It stays unpaired for the rest
                // of the connection and has the password check ahead of it (§9).
                pairedPeer = ""
                log("$remoteHost connected as a guest — encrypted, identity unverified")
            } else {
                log("Refused an unidentified encrypted connection from $remoteHost")
                return
            }
            socket = ssl
        }

        val input = HttpInput(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)

        while (serverSocket != null) {
            val requestLine = input.readLine() ?: return
            if (requestLine.isEmpty()) continue

            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                sendText(out, "400 Bad Request", "text/plain", "malformed request line", false)
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val sep = line.indexOf(':')
                if (sep > 0) {
                    headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
                }
            }

            val queryStart = target.indexOf('?')
            val rawPath = if (queryStart < 0) target else target.substring(0, queryStart)
            val query = if (queryStart < 0) emptyMap() else parseQuery(target.substring(queryStart + 1))
            val request =
                Request(method, decode(rawPath), query, headers, input, remoteHost, pairedPeer)

            try {
                route(request, out)
            } catch (e: Exception) {
                log("Request ${request.method} ${request.path} failed: ${e.message}")
                runCatching {
                    sendJson(out, "500 Internal Server Error", jsonError(e.message ?: "failed"), false)
                }
                return
            }
            out.flush()

            // A body we did not read leaves the stream misaligned; drop the connection.
            val clientWantsClose = headers["connection"].equals("close", ignoreCase = true)
            if (clientWantsClose || (request.hasBody && !request.bodyConsumed)) return
        }
    }

    private fun route(req: Request, out: OutputStream) {
        // DNS rebinding / cross-site protection (PROTOCOL.md §2.4). Ahead of every route,
        // including GET / — the browser UI is precisely the way in for these.
        val hostHeader = req.headers["host"].orEmpty()
        if (!isLocalHostHeader(hostHeader)) {
            drainBody(req)
            log("Refused a request with Host \"$hostHeader\" (looks like DNS rebinding)")
            sendJson(out, "403 Forbidden", jsonError("host not allowed"), false)
            return
        }
        val origin = req.headers["origin"].orEmpty()
        if (!originMatchesHost(origin, hostHeader)) {
            drainBody(req)
            log("Refused a cross-origin request from \"$origin\"")
            sendJson(out, "403 Forbidden", jsonError("cross-origin request refused"), false)
            return
        }

        val chinese = LocaleHelper.effective(context) == Prefs.LANG_CHINESE
        if (req.path == "/" || req.path == "/index.html") {
            // The browser interface *is* guest mode (§9). With guest mode off it is not a
            // page that fails to log in — it is not served at all, and the reply says why.
            // A login box that can never succeed just gets the password typed twenty times.
            if (!prefs.guestModeActive) {
                sendText(out, "403 Forbidden", "text/html; charset=utf-8", WebUi.guestModeOff(chinese), true)
                return
            }
            sendText(out, "200 OK", "text/html; charset=utf-8", WebUi.page(prefs.deviceName, chinese), true)
            return
        }
        if (req.path == "/favicon.ico") {
            sendText(out, "404 Not Found", "text/plain", "", true)
            return
        }
        if (!req.path.startsWith("/api/")) {
            sendText(out, "404 Not Found", "text/plain", "no such route", true)
            return
        }
        // Guest mode off means the password route does not exist at all (§7/§9).
        //
        // /api/authorize is blocked with it: its whole job is to hand out a token, and that
        // token would open nothing. Issuing a credential that cannot work is worse than
        // saying the route is closed — the user retries it, then goes off checking the
        // network and the firewall.
        if (!prefs.guestModeActive && req.pairedPeer.isEmpty()) {
            drainBody(req)
            sendJson(
                out, "403 Forbidden",
                jsonError("guest mode is off; pair over an encrypted connection"), false,
            )
            return
        }

        // Deliberately before the token check: this is how a PC that has no token asks for
        // one. Everything that keeps it from being abused lives in [AuthRequests].
        if (req.path == "/api/authorize") {
            handleAuthorize(req, out)
            return
        }
        // Guessing the token has to cost something (PROTOCOL.md §2.2). While backed off we
        // do not even compare: the comparison is constant time, but whether we reached it
        // at all is observable, so the cleanest answer is to stop at the door.
        // A paired v2 peer skips the throttle as well: it never guesses a token, and letting
        // some other device on the same address back it off would be a free denial of service.
        val throttled = if (req.pairedPeer.isEmpty()) AuthThrottle.retryAfterSec(req.remoteHost) else 0
        if (throttled > 0) {
            drainBody(req)
            sendRetryAfter(out, throttled)
            return
        }
        // Download also accepts a path-bound ticket: it is the one route a browser reaches
        // by plain navigation, where no header can be attached (§2.5).
        // v2 skips all of this: handshake done + fingerprint in the pairing table means
        // authentication already happened (draft §5.2).
        val ok = req.pairedPeer.isNotEmpty() ||
            if (req.path == "/api/download") authorizedForDownload(req) else authorized(req)
        if (!ok) {
            drainBody(req)
            val wait = AuthThrottle.noteFailure(req.remoteHost)
            if (wait > 0) {
                log("${req.remoteHost} keeps getting the token wrong — pausing ${wait}s")
                sendRetryAfter(out, wait)
                return
            }
            sendJson(out, "401 Unauthorized", jsonError("invalid or missing token"), false)
            return
        }
        AuthThrottle.noteSuccess(req.remoteHost)

        when (req.path) {
            "/api/pair" -> handlePair(req, out)
            "/api/ticket" -> handleTicket(req, out)
            "/api/info" -> handleInfo(out)
            "/api/list" -> handleList(req, out)
            "/api/download" -> handleDownload(req, out)
            "/api/upload" -> handleUpload(req, out)
            "/api/mkdir" -> handleMkdir(req, out)
            "/api/delete" -> handleDelete(req, out)
            else -> {
                drainBody(req)
                sendJson(out, "404 Not Found", jsonError("unknown endpoint ${req.path}"), true)
            }
        }
    }

    /**
     * Two equivalent ways to send the token; take the first non-empty one (PROTOCOL.md §2.2).
     *
     * `?token=` used to be a third. It is gone: a credential in the URL lands in proxy logs,
     * browser history and `Referer`. What it was there for — a browser `<a href>` that
     * cannot set a header — is now served by [DownloadTicket] (§2.5).
     */
    private fun authorized(req: Request): Boolean {
        val supplied = req.headers["x-afmu-token"]?.takeIf { it.isNotEmpty() }
            ?: req.headers["authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        return constantTimeEquals(supplied, prefs.token)
    }

    /**
     * Download is the one route that also takes a ticket, because it is the one a browser
     * reaches by plain navigation. The ticket is bound to this exact path (§2.5).
     */
    private fun authorizedForDownload(req: Request): Boolean {
        if (authorized(req)) return true
        val ticket = req.query["ticket"]?.takeIf { it.isNotEmpty() } ?: return false
        return DownloadTicket.verify(prefs.token, req.query["path"].orEmpty(), ticket)
    }

    // ---------------------------------------------------------------------- endpoints

    /**
     * `POST` registers a request and answers with an id; `GET ?request=<id>` polls it
     * (PROTOCOL.md §3.8). The token is handed over only once the user has tapped Allow, and
     * only to whoever holds the id — which was given to the requester alone.
     */
    private fun handleAuthorize(req: Request, out: OutputStream) {
        when (req.method) {
            "POST", "PUT" -> {
                drainBody(req)
                if (!prefs.allowAuthRequests) {
                    sendJson(out, "403 Forbidden", jsonError("authorization requests are disabled"), true)
                    return
                }
                val request = AuthRequests.create(
                    prefs = prefs,
                    name = req.query["name"].orEmpty(),
                    os = req.query["os"].orEmpty(),
                    host = req.remoteHost,
                    port = req.query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0,
                    code = req.query["code"].orEmpty(),
                )
                if (request == null) {
                    // Only a cooldown gets a Retry-After. "A request is already waiting on
                    // the user" is a different thing: how long that takes is up to them, so
                    // there is no honest number to put in the header.
                    val wait = AuthRequests.retryAfterSec(req.remoteHost)
                    val body = jsonError(
                        "another request is pending, or this address was refused recently"
                    ).toString().toByteArray(Charsets.UTF_8)
                    sendHeaders(
                        out, "429 Too Many Requests", "application/json; charset=utf-8",
                        body.size.toLong(),
                        if (wait > 0) mapOf("Retry-After" to wait.toString()) else emptyMap(),
                        true,
                    )
                    out.write(body)
                    out.flush()
                    return
                }
                log("${request.name} (${request.host}) wants to connect — code ${request.code}")
                sendJson(
                    out, "200 OK",
                    JSONObject()
                        .put("ok", true)
                        .put("request", request.id)
                        .put("expires", AuthRequests.TIMEOUT_SEC),
                    true,
                )
            }

            "GET" -> {
                val id = req.query["request"].orEmpty()
                val request = if (id.isEmpty()) null else AuthRequests.status(id)
                if (request == null) {
                    sendJson(out, "404 Not Found", jsonError("unknown or expired request"), true)
                    return
                }
                val json = JSONObject().put("ok", true)
                when (request.status) {
                    AuthRequests.Status.PENDING -> json.put("status", "pending")
                    AuthRequests.Status.DENIED -> json.put("status", "denied")
                    AuthRequests.Status.EXPIRED -> json.put("status", "expired")
                    AuthRequests.Status.GRANTED -> json
                        .put("status", "granted")
                        .put("token", prefs.token)
                        .put("name", prefs.deviceName)
                        .put("port", port)
                }
                sendJson(out, "200 OK", json, true)
            }

            else -> {
                drainBody(req)
                sendJson(out, "405 Method Not Allowed", jsonError("use POST or GET"), true)
            }
        }
    }

    /**
     * The peer hands back its own address and token, so a pairing done in one direction
     * (a scanned QR code, an approved request) leaves both directions usable (PROTOCOL.md §3.9).
     */
    private fun handlePair(req: Request, out: OutputStream) {
        drainBody(req)
        if (req.method != "POST" && req.method != "PUT") {
            sendJson(out, "405 Method Not Allowed", jsonError("use POST"), true)
            return
        }
        val token = req.query["token"]?.trim().orEmpty()
        if (token.isEmpty()) {
            sendJson(out, "400 Bad Request", jsonError("need token"), true)
            return
        }
        val peerPort = req.query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Prefs.DEFAULT_PORT
        val peer = Discovery.Peer(
            name = req.query["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: req.remoteHost,
            os = req.query["os"]?.trim()?.takeIf { it.isNotEmpty() } ?: "linux",
            host = req.remoteHost,
            port = peerPort,
        )
        prefs.peerToken = token
        prefs.lastPeer = "${peer.host}:${peer.port}"
        Bridge.notePaired(peer)
        log("Paired with ${peer.name} (${peer.host}:${peer.port})")

        sendJson(
            out, "200 OK",
            JSONObject()
                .put("ok", true)
                .put("name", prefs.deviceName)
                .put("os", "android")
                .put("protocol", PROTOCOL_VERSION),
            true,
        )
    }

    /**
     * Mints a download ticket for one path (PROTOCOL.md §2.5). Header-authenticated, so the
     * page has to already hold the token; the ticket only spares the `<a href>` from
     * carrying it.
     *
     * The path is resolved first: handing out a ticket for something out of bounds would
     * confirm to the caller that it exists.
     */
    private fun handleTicket(req: Request, out: OutputStream) {
        drainBody(req)
        val path = req.query["path"].orEmpty()
        val file = Storage.resolve(context, path)
        if (file == null || !file.isFile || !file.canRead()) {
            sendJson(out, "404 Not Found", jsonError("no readable file at $path"), true)
            return
        }
        sendJson(
            out, "200 OK",
            JSONObject()
                .put("ok", true)
                .put("ticket", DownloadTicket.issue(prefs.token, path))
                .put("expires", DownloadTicket.TTL_SEC),
            true,
        )
    }

    private fun handleInfo(out: OutputStream) {
        val json = JSONObject().apply {
            put("ok", true)
            put("name", prefs.deviceName)
            put("os", "android")
            put("androidRelease", android.os.Build.VERSION.RELEASE)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("protocol", PROTOCOL_VERSION)
            put("writable", prefs.writable)
            put("fullStorageAccess", Storage.hasFullAccess(context))
            put("inbox", Storage.inboxDir(context, prefs).absolutePath)
            put("roots", JSONArray().apply {
                Storage.roots(context).forEach {
                    put(JSONObject().put("name", it.name).put("path", it.dir.absolutePath))
                }
            })
        }
        sendJson(out, "200 OK", json, true)
    }

    private fun handleList(req: Request, out: OutputStream) {
        val path = req.query["path"].orEmpty()
        val json = JSONObject().put("ok", true).put("path", path.ifEmpty { "/" })

        if (path.isEmpty() || path == "/") {
            json.put("parent", JSONObject.NULL)
            json.put("entries", JSONArray().apply {
                Storage.roots(context).forEach { root ->
                    put(JSONObject().apply {
                        put("name", root.name)
                        put("path", root.dir.absolutePath)
                        put("dir", true)
                        put("size", 0)
                        put("mtime", root.dir.lastModified() / 1000)
                    })
                }
            })
            sendJson(out, "200 OK", json, true)
            return
        }

        val dir = Storage.resolve(context, path)
        if (dir == null || !dir.isDirectory) {
            sendJson(out, "404 Not Found", jsonError("not a readable directory: $path"), true)
            return
        }
        val children = dir.listFiles().orEmpty().sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        )
        json.put("parent", if (Storage.resolve(context, dir.parent ?: "") != null) dir.parent else "/")
        json.put("entries", JSONArray().apply {
            children.forEach { child ->
                put(JSONObject().apply {
                    put("name", child.name)
                    put("path", child.absolutePath)
                    put("dir", child.isDirectory)
                    put("size", if (child.isDirectory) 0L else child.length())
                    put("mtime", child.lastModified() / 1000)
                })
            }
        })
        sendJson(out, "200 OK", json, true)
    }

    private fun handleDownload(req: Request, out: OutputStream) {
        if (req.method != "GET" && req.method != "HEAD") {
            drainBody(req)
            sendJson(out, "405 Method Not Allowed", jsonError("use GET or HEAD"), true)
            return
        }
        val path = req.query["path"].orEmpty()
        val file = Storage.resolve(context, path)
        if (file == null || !file.isFile || !file.canRead()) {
            sendJson(out, "404 Not Found", jsonError("no readable file at $path"), true)
            return
        }

        val total = file.length()
        var start = 0L
        var end = total - 1
        var status = "200 OK"

        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-').trim()
            // A malformed range is a client error, not a server one: answer 416 rather
            // than letting NumberFormatException bubble up as a 500.
            val fromValue = if (from.isEmpty()) null else from.toLongOrNull()
            val toValue = if (to.isEmpty()) null else to.toLongOrNull()
            val malformed = (from.isNotEmpty() && fromValue == null) ||
                (to.isNotEmpty() && toValue == null) ||
                (fromValue == null && toValue == null)
            when {
                malformed -> start = total // forces the 416 below
                fromValue == null && toValue != null -> start = (total - toValue).coerceAtLeast(0)
                fromValue != null -> {
                    start = fromValue
                    if (toValue != null) end = minOf(toValue, total - 1)
                }
            }
            if (malformed || start > end || start >= total) {
                sendHeaders(
                    out, "416 Range Not Satisfiable", "application/json",
                    0L, mapOf("Content-Range" to "bytes */$total"), true
                )
                return
            }
            status = "206 Partial Content"
        }

        val length = end - start + 1
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Disposition" to contentDisposition(file.name),
            "Last-Modified" to httpDate(file.lastModified()),
        )
        if (status.startsWith("206")) headers["Content-Range"] = "bytes $start-$end/$total"

        sendHeaders(out, status, Storage.mimeTypeOf(file.name), length, headers, true)
        if (req.method == "HEAD") {
            out.flush()
            return
        }

        FileInputStream(file).use { input ->
            if (start > 0) input.channel.position(start)
            val buffer = ByteArray(COPY_BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(left, COPY_BUFFER.toLong()).toInt())
                if (n <= 0) break
                out.write(buffer, 0, n)
                left -= n
            }
        }
        out.flush()
        log("Sent ${file.name} (${formatBytes(length)})")
    }

    private fun handleUpload(req: Request, out: OutputStream) {
        if (req.method != "POST" && req.method != "PUT") {
            drainBody(req)
            sendJson(out, "405 Method Not Allowed", jsonError("use POST"), true)
            return
        }
        if (!prefs.writable) {
            drainBody(req)
            sendJson(out, "403 Forbidden", jsonError("this device is read-only"), false)
            return
        }

        // Out of bounds, not a directory, or not writable all mean the same thing to the
        // peer: the file lands in the inbox instead (PROTOCOL.md §3.4).
        val targetDir = req.query["dir"]?.takeIf { it.isNotBlank() }
            ?.let { Storage.resolve(context, it) }
            ?.takeIf { it.isDirectory && it.canWrite() }
        val overwrite = req.query["overwrite"] == "1"
        val contentType = req.headers["content-type"].orEmpty()
        val saved = ArrayList<String>()

        try {
            if (contentType.startsWith("multipart/form-data", ignoreCase = true)) {
                val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
                if (boundary.isEmpty()) {
                    sendJson(out, "400 Bad Request", jsonError("missing multipart boundary"), false)
                    return
                }
                saved += receiveMultipart(req, boundary, targetDir, overwrite)
            } else {
                val name = req.query["name"] ?: "upload-${System.currentTimeMillis()}"
                saved += receiveRawBody(req, name, targetDir, overwrite)
            }
        } catch (e: TruncatedBody) {
            // The peer's body ran out. Answering 200 here is the one failure the client
            // cannot detect, so it has to be an error even though nothing on our side broke.
            req.bodyConsumed = false
            log("Upload truncated: ${e.message}")
            sendJson(out, "400 Bad Request", jsonError(e.message ?: "request body truncated"), false)
            return
        } catch (e: Exception) {
            req.bodyConsumed = false
            log("Upload failed: ${e.message}")
            sendJson(out, "500 Internal Server Error", jsonError(e.message ?: "upload failed"), false)
            return
        }

        // `ok: true` has to mean bytes really landed. A client that gets an empty list falls
        // back to showing the name it sent, and the file goes missing with nobody the wiser.
        if (saved.isEmpty()) {
            req.bodyConsumed = false
            sendJson(out, "400 Bad Request", jsonError("request contained no file part"), false)
            return
        }

        val json = JSONObject()
            .put("ok", true)
            .put("saved", JSONArray(saved))
        sendJson(out, "200 OK", json, false)
    }

    private fun receiveRawBody(
        req: Request,
        name: String,
        targetDir: File?,
        overwrite: Boolean,
    ): List<String> {
        val sink = if (targetDir != null) {
            Storage.createFileSink(targetDir, name, overwrite)
        } else {
            Storage.createInboxSink(context, prefs, name)
        }
        return sink.use {
            when {
                req.isChunked -> req.input.copyChunked(it.stream)
                req.contentLength >= 0 -> req.input.copyExact(it.stream, req.contentLength)
                else -> throw IOException("upload needs Content-Length or chunked encoding")
            }
            it.commit()
            req.bodyConsumed = true
            log("Received ${it.displayPath}")
            listOf(it.displayPath)
        }
    }

    /**
     * Streams each multipart file part straight to disk — never buffers a file in memory.
     *
     * Every read that can run out has to be checked: a body that ends early leaves the last
     * part incomplete, and committing it would rename half a file over the real name while
     * the peer is told the transfer succeeded (PROTOCOL.md §3.4).
     */
    private fun receiveMultipart(
        req: Request,
        boundary: String,
        targetDir: File?,
        overwrite: Boolean,
    ): List<String> {
        val input = req.input
        val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val saved = ArrayList<String>()

        // Position the stream just past the first boundary line.
        if (!input.copyUntil("--$boundary".toByteArray(Charsets.ISO_8859_1), null)) {
            throw TruncatedBody("body ended before the first multipart boundary")
        }
        var more = input.readLine().orTruncated() != "--"

        while (more) {
            val partHeaders = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: throw TruncatedBody("multipart part headers truncated")
                if (line.isEmpty()) break
                val sep = line.indexOf(':')
                if (sep > 0) {
                    partHeaders[line.substring(0, sep).trim().lowercase()] =
                        line.substring(sep + 1).trim()
                }
            }
            val disposition = partHeaders["content-disposition"].orEmpty()
            val fileName = extractParameter(disposition, "filename")

            if (fileName.isNullOrBlank()) {
                // a plain form field: ignore the value, but a truncated one still means the
                // request as a whole is incomplete
                if (!input.copyUntil(delimiter, null)) throw TruncatedBody("multipart body truncated")
            } else {
                val sink = if (targetDir != null) {
                    Storage.createFileSink(targetDir, fileName, overwrite)
                } else {
                    Storage.createInboxSink(context, prefs, fileName)
                }
                sink.use {
                    if (!input.copyUntil(delimiter, it.stream)) {
                        // Leaving `use` without commit() deletes the .afmu-part.
                        throw TruncatedBody("multipart body truncated inside $fileName")
                    }
                    it.commit()
                    saved += it.displayPath
                    log("Received ${it.displayPath}")
                }
            }
            more = input.readLine().orTruncated() != "--"
        }
        req.bodyConsumed = true
        return saved
    }

    /** The closing `--<boundary>--` is part of the body; missing it means the body was cut. */
    private fun String?.orTruncated(): String =
        this ?: throw TruncatedBody("multipart body ended before the closing boundary")

    private fun handleMkdir(req: Request, out: OutputStream) {
        drainBody(req)
        if (!prefs.writable) {
            sendJson(out, "403 Forbidden", jsonError("this device is read-only"), true)
            return
        }
        val parent = Storage.resolve(context, req.query["path"].orEmpty())
        val name = req.query["name"]?.let { Storage.sanitizeName(it) }
        if (parent == null || name.isNullOrEmpty()) {
            sendJson(out, "400 Bad Request", jsonError("need path and name"), true)
            return
        }
        val created = File(parent, name)
        if (!created.isDirectory && !created.mkdirs()) {
            sendJson(out, "500 Internal Server Error", jsonError("mkdir failed"), true)
            return
        }
        sendJson(out, "200 OK", JSONObject().put("ok", true).put("path", created.absolutePath), true)
    }

    private fun handleDelete(req: Request, out: OutputStream) {
        drainBody(req)
        if (!prefs.writable) {
            sendJson(out, "403 Forbidden", jsonError("this device is read-only"), true)
            return
        }
        val target = Storage.resolve(context, req.query["path"].orEmpty())
        if (target == null || !target.exists()) {
            sendJson(out, "404 Not Found", jsonError("nothing to delete"), true)
            return
        }
        // A root is the whole volume; recursive delete on one is unrecoverable and is
        // never what the user meant. Individual files inside it are still deletable.
        if (Storage.isRoot(context, target)) {
            sendJson(out, "403 Forbidden", jsonError("refusing to delete a shared root"), true)
            return
        }
        // Deleting a whole tree is deliberately opt-in.
        val recursive = req.query["recursive"] == "1"
        val ok = if (target.isDirectory && recursive) target.deleteRecursively() else target.delete()
        if (!ok) {
            sendJson(out, "500 Internal Server Error", jsonError("delete failed"), true)
            return
        }
        log("Deleted ${target.absolutePath}")
        sendJson(out, "200 OK", JSONObject().put("ok", true), true)
    }

    // ------------------------------------------------------------------------ plumbing

    private fun drainBody(req: Request) {
        if (req.bodyConsumed || !req.hasBody) return
        if (req.contentLength in 0..MAX_DRAIN) {
            req.input.discard(req.contentLength)
            req.bodyConsumed = true
        }
    }

    private fun sendHeaders(
        out: OutputStream,
        status: String,
        contentType: String,
        contentLength: Long,
        extra: Map<String, String>,
        keepAlive: Boolean,
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun sendText(
        out: OutputStream,
        status: String,
        contentType: String,
        body: String,
        keepAlive: Boolean,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendHeaders(out, status, contentType, bytes.size.toLong(), emptyMap(), keepAlive)
        out.write(bytes)
        out.flush()
    }

    private fun sendJson(out: OutputStream, status: String, body: JSONObject, keepAlive: Boolean) {
        sendText(out, status, "application/json; charset=utf-8", body.toString(), keepAlive)
    }

    /** 429 plus the header that tells a well-behaved client exactly how long to wait. */
    private fun sendRetryAfter(out: OutputStream, seconds: Int) {
        val body = jsonError("too many failed attempts, retry in ${seconds}s")
            .toString().toByteArray(Charsets.UTF_8)
        sendHeaders(
            out, "429 Too Many Requests", "application/json; charset=utf-8",
            body.size.toLong(), mapOf("Retry-After" to seconds.toString()), false,
        )
        out.write(body)
        out.flush()
    }

    private fun jsonError(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        const val PROTOCOL_VERSION = ProtocolConstants.PROTOCOL_VERSION
        private const val SOCKET_TIMEOUT_MS = ProtocolConstants.SOCKET_TIMEOUT_SEC * 1000
        private const val COPY_BUFFER = 128 * 1024
        private const val MAX_DRAIN = 1L shl 20

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) map[decode(pair)] = "" else map[decode(pair.substring(0, eq))] =
                    decode(pair.substring(eq + 1))
            }
            return map
        }

        fun decode(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

        /** Pulls `filename="x"` or RFC 5987 `filename*=UTF-8''x` out of a header. */
        fun extractParameter(header: String, key: String): String? {
            Regex("""$key\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.let { return decode(it.groupValues[1].trim()) }
            Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(header)?.let { return it.groupValues[1] }
            Regex("""$key\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.let { return it.groupValues[1].trim() }
            return null
        }

        fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 32..126 && it != '"') it else '_' }.joinToString("")
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return """attachment; filename="$ascii"; filename*=UTF-8''$encoded"""
        }

        fun httpDate(millis: Long): String {
            val format = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US
            )
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            return format.format(java.util.Date(millis))
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var index = 0
            while (value >= 1024 && index < units.lastIndex) {
                value /= 1024
                index++
            }
            return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }

        /** Hostname out of a Host/Origin authority: strips the port and IPv6 brackets. */
        private fun hostNameOf(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            if (s.startsWith("[")) {
                // [::1]:8765 — everything inside the brackets is the address
                val close = s.indexOf(']')
                return if (close > 0) s.substring(1, close) else ""
            }
            // A bare IPv6 literal (not bracketed — out of spec but seen in the wild) has
            // several colons, and then there is no port to split off.
            if (s.count { it == ':' } > 1) return s
            val colon = s.indexOf(':')
            return if (colon >= 0) s.substring(0, colon) else s
        }

        private fun portOf(authority: String, default: Int = 80): Int {
            val s = authority.trim()
            val name = hostNameOf(s)
            if (name.isEmpty()) return default
            val rest = s.substring(if (s.startsWith("[")) name.length + 2 else name.length)
            return if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: default else default
        }

        private fun isIpLiteral(name: String): Boolean {
            if (name.isEmpty()) return false
            // IPv6: only hex digits, colons and a possible zone/embedded-v4 tail
            if (name.contains(':')) {
                return name.all { it.isDigit() || it in "abcdefABCDEF:.%" } && name.contains(':')
            }
            // IPv4: four decimal octets. Deliberately strict — "1.2.3" and "0x7f.1" are not
            // accepted, because a lax parser here is exactly what a rebinding host wants.
            val parts = name.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    (part.toIntOrNull() ?: -1) in 0..255
            }
        }

        /**
         * Does the Host header point at *this machine*? (PROTOCOL.md §2.4) — blocks DNS
         * rebinding.
         *
         * The attack: the victim opens the attacker's page, which requests
         * `http://evil.example.com:8765/` where that name resolves to 192.168.1.42 — the
         * victim's phone. Same-origin policy is no help; the origin *is* the attacker's
         * domain. The one thing the server can tell them apart by is the Host header: it
         * says evil.example.com, not an IP or a `.local` name.
         *
         * So the rule is just a shape check: the hostname must be an IP literal,
         * `localhost`, or end in `.local`. We deliberately do not enumerate our own
         * addresses — those drift with DHCP and multiple interfaces — and we do not need
         * to: rebinding requires a DNS name, and none of those three shapes is one.
         */
        fun isLocalHostHeader(hostHeader: String): Boolean {
            val name = hostNameOf(hostHeader)
            if (name.isEmpty()) return false // HTTP/1.1 requires Host; absent is malformed
            if (name.equals("localhost", ignoreCase = true)) return true
            if (name.endsWith(".local", ignoreCase = true)) return true
            return isIpLiteral(name)
        }

        /**
         * Does the Origin match our own Host? (PROTOCOL.md §2.4) — blocks cross-site calls.
         *
         * Native clients send no Origin, so **absent means allowed**; once present it has
         * to line up. Browsers always attach Origin to cross-origin fetches and form POSTs,
         * which is what stops an attacker's page from driving the API directly by IP.
         */
        fun originMatchesHost(origin: String, hostHeader: String): Boolean {
            val o = origin.trim()
            if (o.isEmpty()) return true
            // Some browsers send the literal "null" from opaque origins. Not us.
            if (o.equals("null", ignoreCase = true)) return false

            val scheme = o.substringBefore("://", "").lowercase()
            if (scheme.isEmpty()) return false
            val authority = o.substringAfter("://").substringBefore('/')
            if (hostNameOf(authority).isEmpty()) return false

            if (!hostNameOf(authority).equals(hostNameOf(hostHeader), ignoreCase = true)) {
                return false
            }
            // The port has to match too. Comparing only the hostname would let any other
            // HTTP service on this same device (a page on :9999) drive our API.
            val originPort = portOf(authority, if (scheme == "https") 443 else 80)
            return originPort == portOf(hostHeader)
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
