package com.aynux.afmu.core

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The TLS layer for v2 (PROTOCOL.md v2 §5).
 *
 * Two decisions worth reading before changing anything here:
 *
 * - **Chain validation is off; the fingerprint decides.** These are self-signed certificates,
 *   so there is no CA to chain to. What replaces it is [PinningTrustManager], which throws
 *   for any peer whose SPKI fingerprint is not in the pairing table.
 * - **Rejection must be an exception, never a return value.** Throwing from a TrustManager
 *   aborts the handshake before a single byte of application data moves. A check done after
 *   the handshake — "connect, then verify" — has already leaked the request (§5.1).
 */
object Tls {

    /** ALPN is not a security boundary here; see [applyAlpn]. */
    private val ALPN = arrayOf(ProtocolConstants.TLS_ALPN, "http/1.1")

    /** The SPKI fingerprint of a peer certificate, base32, or empty if it cannot be computed. */
    fun fingerprintOf(cert: X509Certificate?): String {
        if (cert == null) return ""
        val raw = runCatching { Identity.spkiFingerprint(cert) }.getOrNull() ?: return ""
        return if (raw.size == 32) Base32.encode(raw) else ""
    }

    /**
     * Presents this device's AndroidKeyStore identity to the peer.
     *
     * Written by hand rather than going through `KeyManagerFactory`: the private key is
     * non-exportable, and a factory that tries to extract it fails in ways that are awkward
     * to diagnose. Returning the KeyStore's own [PrivateKey] handle keeps the key inside the
     * TEE/StrongBox — signing happens there, we only hold a reference.
     */
    private class IdentityKeyManager(
        private val info: Identity.Info,
    ) : X509ExtendedKeyManager() {

        override fun getCertificateChain(alias: String?): Array<X509Certificate> =
            arrayOf(info.certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = info.privateKey

        // There is exactly one identity, and it is the answer to every question: this device
        // has nothing else to offer, and offering nothing means the peer sees an anonymous
        // client and rejects it.
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = ALIAS

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        private companion object {
            /** Any non-null string works — this KeyManager has a single key. */
            const val ALIAS = "afmu"
        }
    }

    /**
     * Accepts a peer only if [isAllowed] says its fingerprint is paired.
     *
     * [isAllowed] is passed in rather than the store itself so this class stays testable on a
     * plain JVM: the whole point is that "not paired" must reach the handshake as a thrown
     * exception, and that is worth checking without a device.
     */
    class PinningTrustManager(
        private val isAllowed: (String) -> Boolean,
        /**
         * Let an unpaired **client** finish the handshake, so it can go on to whatever the
         * route layer allows it — pairing (§4.2.4) or the guest password check (§9). Never
         * applies to servers we dial: there, an unpaired peer is the man in the middle we are
         * looking for, and there is no later gate to catch it.
         *
         * Note this only covers a client that offers a certificate we do not know. A client
         * offering none never reaches a TrustManager at all under `wantClientAuth`, so who is
         * allowed in anonymously is decided by the caller after the handshake, from the
         * session — see HttpServer.handle.
         */
        private val allowUnpairedClient: () -> Boolean = { false },
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain, client = true)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain, client = false)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = check(chain, client = true)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = check(chain, client = false)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = check(chain, client = true)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = check(chain, client = false)

        /** Empty on purpose: no CA is acceptable, because no CA is involved. */
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun check(chain: Array<out X509Certificate>?, client: Boolean) {
            // No certificate at all has to fail here too. On the server side a peer that
            // offers nothing would otherwise complete the handshake anonymously, which turns
            // mutual TLS back into one-way TLS without anything looking wrong.
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("peer presented no certificate")

            val fp = fingerprintOf(leaf)
            if (fp.isEmpty()) throw CertificateException("cannot compute the peer's fingerprint")
            if (isAllowed(fp)) return
            // Unpaired. Fatal, except for a client in guest mode — which still has a password
            // to get past, and is still marked unpaired for the rest of the connection.
            if (client && allowUnpairedClient()) return
            throw CertificateException("fingerprint $fp is not paired")
        }
    }

    /**
     * Records the server's fingerprint instead of checking it. **Pairing only.**
     *
     * There is nothing to pin against yet — finding out what to pin is what pairing is for
     * (v2 §4.2.4). Two things make this safe, and it is unsafe the moment either stops
     * holding:
     *
     *  - the peer in this state serves **only** `/api/pair-v2` and 403s everything else, so
     *    the connection cannot do anything but pair;
     *  - the protection against a man in the middle is the user comparing the SAS on both
     *    screens, not the pinning.
     *
     * So this must never be used for anything but the first step of a pairing, and the
     * fingerprint it records must be pinned for every step after it.
     */
    class RecordingTrustManager : X509ExtendedTrustManager() {

        /** The server certificate seen on the connection this instance vetted. */
        @Volatile
        var serverFingerprint: String = ""
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            refuseClients()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?, authType: String?, socket: Socket?,
        ) = refuseClients()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?,
        ) = refuseClients()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            record(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?, authType: String?, socket: Socket?,
        ) = record(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?,
        ) = record(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        /**
         * This manager exists for one outbound pairing connection. Being asked to vet a
         * *client* means it was installed on a server socket, where "accept anyone" is
         * exactly the hole v2 exists to close.
         */
        private fun refuseClients(): Unit =
            throw CertificateException("the pairing trust manager must not be used server-side")

        private fun record(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("peer presented no certificate")
            val fp = fingerprintOf(leaf)
            if (fp.isEmpty()) throw CertificateException("cannot compute the peer's fingerprint")
            serverFingerprint = fp
        }
    }

    /** The factory for that first pairing request. See [RecordingTrustManager]. */
    fun pairingFactory(
        identity: Identity.Info?,
        trust: RecordingTrustManager,
    ): SSLSocketFactory? {
        val info = identity ?: return null
        return runCatching {
            SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(IdentityKeyManager(info)), arrayOf(trust), null)
            }.socketFactory
        }.getOrNull()
    }

    /**
     * A socket factory that presents this device's identity and pins the peer.
     *
     * Returns null when there is no usable identity — better than handing back a factory that
     * silently authenticates nobody.
     */
    fun socketFactory(identity: Identity.Info?, trust: PinningTrustManager): SSLSocketFactory? {
        val info = identity ?: return null
        return runCatching {
            SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(IdentityKeyManager(info)), arrayOf(trust), null)
            }.socketFactory
        }.getOrNull()
    }

    /**
     * TLS 1.3 only, and the ALPN list when the platform allows setting it.
     *
     * TLS 1.2 is refused deliberately: it does not encrypt the certificate messages, so the
     * handshake would broadcast which devices are talking — exactly what v2 exists to stop.
     *
     * ALPN is best-effort: `setApplicationProtocols` is API 29+, and on the client side
     * `HttpsURLConnection` picks its own list anyway. Nothing here depends on it — a peer
     * without a paired certificate cannot get past the handshake regardless (§5.2).
     */
    fun harden(socket: javax.net.ssl.SSLSocket, server: Boolean) {
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = !server
        if (server) {
            // Ask for the client's certificate but let PinningTrustManager decide: "needed"
            // would let the platform's own verdict end the handshake before our check runs,
            // and its verdict is meaningless for self-signed certificates.
            socket.wantClientAuth = true
        }
        applyAlpn(socket)
    }

    private fun applyAlpn(socket: javax.net.ssl.SSLSocket) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        runCatching {
            socket.sslParameters = socket.sslParameters.apply { applicationProtocols = ALPN }
        }
    }
}
