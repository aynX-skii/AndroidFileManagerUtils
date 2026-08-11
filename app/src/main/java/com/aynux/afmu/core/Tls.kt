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
 * The TLS layer for v2 (PROTOCOL-v2-DRAFT.md §5).
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
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = check(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = check(chain)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = check(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = check(chain)

        /** Empty on purpose: no CA is acceptable, because no CA is involved. */
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        /** The fingerprint of the peer on the last connection this manager vetted. */
        @Volatile
        var lastFingerprint: String = ""
            private set

        private fun check(chain: Array<out X509Certificate>?) {
            // No certificate at all has to fail here too. On the server side a peer that
            // offers nothing would otherwise complete the handshake anonymously, which turns
            // mutual TLS back into one-way TLS without anything looking wrong.
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("peer presented no certificate")

            val fp = fingerprintOf(leaf)
            lastFingerprint = fp
            if (fp.isEmpty()) throw CertificateException("cannot compute the peer's fingerprint")
            if (!isAllowed(fp)) throw CertificateException("fingerprint $fp is not paired")
        }
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
