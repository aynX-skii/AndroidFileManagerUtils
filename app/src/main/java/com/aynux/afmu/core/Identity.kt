package com.aynux.afmu.core

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * This device's long-term identity: an EC P-256 key pair and a self-signed certificate
 * (PROTOCOL-v2-DRAFT.md §3).
 *
 * v2's central change is that **a device's identity is a long-term key pair**, with the
 * token demoted. This class only mints and keeps that key pair and computes the fingerprint
 * used for pinning; how TLS consumes it comes later.
 *
 * It is worth finishing and verifying on its own precisely because the fingerprint is the
 * one thing both ends must agree on byte for byte — and when it is wrong the symptom is
 * "the certificate is obviously right but never matches", which is miserable to debug once
 * a handshake is layered on top.
 *
 * The private key lives in AndroidKeyStore, so it is **not exportable**: even a full app
 * data dump does not yield the identity. On hardware that has it, the key sits in StrongBox;
 * otherwise the TEE; a software key is the last resort and is reported as such
 * (§13 待定问题 1).
 */
object Identity {

    /** Where the key pair lives inside AndroidKeyStore. */
    private const val ALIAS = "afmu-identity-v2"
    private const val KEYSTORE = "AndroidKeyStore"

    /** How well the private key is protected. Shown to the user; not a security control. */
    enum class Backing { STRONGBOX, TEE, SOFTWARE }

    data class Info(
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        /** `SHA-256(DER(SubjectPublicKeyInfo))`, 32 bytes. */
        val fingerprint: ByteArray,
        val backing: Backing,
    ) {
        val fingerprintBase32: String get() = toBase32(fingerprint)
        val fingerprintDisplay: String get() = group(fingerprintBase32)

        // ByteArray in a data class compares by reference; identity is compared by
        // fingerprint everywhere, so make that explicit rather than leave a trap.
        override fun equals(other: Any?): Boolean =
            other is Info && fingerprint.contentEquals(other.fingerprint)

        override fun hashCode(): Int = fingerprint.contentHashCode()
    }

    @Volatile
    private var cached: Info? = null

    /**
     * Loads the existing identity, minting one on first use.
     *
     * Minting is once and for all: a new key pair *is* a new device, and every pairing made
     * against the old one is void. So a key that exists but cannot be read is an **error**,
     * never a reason to generate a fresh one — silently swapping keys shows up as "all my
     * paired devices stopped working" with nothing to point at.
     */
    @Synchronized
    fun ensure(): Info? {
        cached?.let { return it }

        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = runCatching { load(store) }.getOrNull()
        if (existing != null) {
            cached = existing
            return existing
        }
        // Nothing usable there yet. If an entry exists but is broken we deliberately do not
        // wipe it: that would be the silent key swap described above.
        if (store.containsAlias(ALIAS)) return null

        generate()
        cached = runCatching { load(KeyStore.getInstance(KEYSTORE).apply { load(null) }) }
            .getOrNull()
        return cached
    }

    private fun load(store: KeyStore): Info? {
        val key = store.getKey(ALIAS, null) as? PrivateKey ?: return null
        val cert = store.getCertificate(ALIAS) as? X509Certificate ?: return null
        return Info(cert, key, spkiFingerprint(cert), backingOf(key))
    }

    /**
     * `SHA-256(DER(SubjectPublicKeyInfo))` — **not** the whole certificate, and not the raw
     * EC point.
     *
     * Pinning SPKI rather than the certificate means a certificate can be re-issued, or its
     * subject changed, without voiding a single pairing as long as the public key holds.
     *
     * `PublicKey.getEncoded()` returns exactly SubjectPublicKeyInfo DER ("X.509" format in
     * JCA's naming), which is the same bytes OpenSSL's `i2d_X509_PUBKEY` produces on the
     * Linux side. Verify it — see the cross-check in PROTOCOL-v2-DRAFT.md §12 step 1.
     */
    fun spkiFingerprint(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    /**
     * Asks the KeyStore where the key actually lives, rather than remembering what we asked
     * for at generation time — that answer would be lost on the next app launch and would
     * quietly report TEE for a StrongBox key.
     */
    private fun backingOf(key: PrivateKey): Backing = runCatching {
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Backing.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Backing.TEE
                else -> Backing.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Backing.TEE else Backing.SOFTWARE
        }
    }.getOrDefault(Backing.SOFTWARE)

    /**
     * Generates the key pair, preferring StrongBox and stepping down as hardware allows.
     *
     * Deliberately *not* requiring user authentication for now: that is §13 待定问题 1's
     * stronger option, but turning it on means every handshake can prompt for a fingerprint,
     * which needs UI design first.
     */
    private fun generate() {
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, ProtocolConstants.IDENTITY_VALIDITY_DAYS)
        }

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec(ProtocolConstants.IDENTITY_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // The subject is a placeholder on purpose. Pinning is on the public key, so the
            // name changes nothing about security — and putting the device name here would
            // print it into the pairing table and the logs for no benefit.
            .setCertificateSubject(X500Principal("CN=AFMU device"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE))
            .setCertificateNotBefore(notBefore.time)
            .setCertificateNotAfter(notAfter.time)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.initialize(spec(strongBox = true))
                generator.generateKeyPair()
                return
            } catch (e: StrongBoxUnavailableException) {
                // Common: most devices have no StrongBox. Not an error, just a step down.
            } catch (e: Exception) {
                // Some vendors throw other things when StrongBox is half-implemented.
            }
        }

        generator.initialize(spec(strongBox = false))
        generator.generateKeyPair()
    }

    // base32 在 [Base32] 里 —— 它不依赖任何 Android 类，所以能在 JVM 上
    // 直接对着 C++ 那份的输出做校验（见 src/test 里的用例）。
    fun toBase32(raw: ByteArray): String = Base32.encode(raw)
    fun fromBase32(text: String): ByteArray? = Base32.decode(text)
    fun group(base32: String): String = Base32.group(base32)
}
