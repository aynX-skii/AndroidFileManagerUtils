package com.aynux.afmu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * 钉扎这一层能在纯 JVM 上验证的部分。
 *
 * 握手本身要真机（AndroidKeyStore 在 headless 环境里不存在），但两件最容易出错、
 * 出错了又最难查的事**不需要**真机：
 *
 * 1. 指纹算得对不对 —— 下面的证书是 Linux 端真实生成的一张，期望值由 `openssl`
 *    独立算出。两端对 SPKI 的理解差一层封装的话，症状是「证书明明对却一直不匹配」，
 *    而且要等握手调通之后才暴露。
 * 2. 不认识的对端会不会被**抛异常**挡住 —— 返回值形式的拒绝会被忽略，
 *    而异常会中止握手。这个区别就是"有没有真的在校验"。
 */
class TlsPinningTest {

    /**
     * 一张真实的 EC P-256 自签证书，来自 Linux 端的 `afmu --fingerprint`。
     * 期望指纹由外部工具独立算出，不是照着 Kotlin 的结果反填的：
     *
     *   openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum
     */
    private val certDerBase64 = """
        MIIBJzCBzqADAgECAhAGP0jYftBlS1ncGh8nOmAeMAoGCCqGSM49BAMCMBYxFDASBgNVBAMMC0FGTVUgZGV2aWNlMB4XDTI2
        MDgxMTE0MzY0OFoXDTQ2MDgwNjE0MzY0OFowFjEUMBIGA1UEAwwLQUZNVSBkZXZpY2UwWTATBgcqhkjOPQIBBggqhkjOPQMB
        BwNCAATLKTVNlIC9HwlVBrCjPf7YNbq9codi84HTxVppv60VcAyY9IaZwG77QIgzcsj7EgTl1TwyVAGzmHP+F61pZo63MAoG
        CCqGSM49BAMCA0gAMEUCIQC+5kdy7+qIACHVUeoSGuRoT64wtho+0oihEuSRz9WoYAIgWmwxiC5TqxjePbwaODLmDx5DT/zc
        j0RQMDPp+oMYFxQ=
    """.trimIndent().replace("\n", "")

    private val expectedHex = "997c317b1f6b82485b37a00e8150bccab6266964e3a1d129b746dbb2353ab949"
    private val expectedBase32 = "VF8DC829PQBESY3ZWAHJCWF63L5CN4ME6QS7CLPZJ5P5EPK4ZFES"

    private val certDer: ByteArray = Base64.getDecoder().decode(certDerBase64)

    private val cert: X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

    private fun hex(raw: ByteArray) = raw.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ 指纹

    @Test
    fun `matches what openssl computes for the same certificate`() {
        assertEquals(expectedHex, hex(Identity.spkiFingerprint(cert)))
        assertEquals(expectedBase32, Tls.fingerprintOf(cert))
    }

    @Test
    fun `pins the public key, not the certificate`() {
        // 对照组：整张证书的哈希是完全不同的值。钉整张证书的话，对端每次重签
        // （到期、改 subject）都要重新配对，而公钥根本没变。
        val wholeCert = MessageDigest.getInstance("SHA-256").digest(certDer)
        assertNotEquals(hex(wholeCert), expectedHex)
    }

    @Test
    fun `an unreadable certificate yields an empty fingerprint, never a fake one`() {
        assertEquals("", Tls.fingerprintOf(null))
    }

    // ------------------------------------------------------------ TrustManager

    @Test
    fun `a paired fingerprint is accepted`() {
        val tm = Tls.PinningTrustManager { it == expectedBase32 }
        tm.checkClientTrusted(arrayOf(cert), "EC")
        tm.checkServerTrusted(arrayOf(cert), "EC")
        assertEquals(expectedBase32, tm.lastFingerprint)
    }

    @Test
    fun `an unpaired fingerprint throws`() {
        // 必须是**抛异常**：返回值形式的拒绝会被 TLS 栈忽略，握手照常完成，
        // 于是「校验过了」变成一句空话。
        val tm = Tls.PinningTrustManager { false }
        try {
            tm.checkServerTrusted(arrayOf(cert), "EC")
            fail("未配对的指纹必须抛异常")
        } catch (e: CertificateException) {
            assertTrue(e.message.orEmpty().contains(expectedBase32))
        }
    }

    @Test
    fun `no certificate at all throws`() {
        // 服务端方向尤其要紧：wantClientAuth 下对端不交证书握手照样能成，
        // 判空是把双向 TLS 变回单向 TLS 的唯一防线。
        val tm = Tls.PinningTrustManager { true }
        for (chain in listOf(null, emptyArray<X509Certificate>())) {
            try {
                tm.checkClientTrusted(chain, "EC")
                fail("没有证书时必须抛异常")
            } catch (e: CertificateException) {
                // 正是想要的
            }
        }
    }

    @Test
    fun `accepts no certificate authority`() {
        // 空的 issuer 列表是有意的：这里根本没有 CA，链校验被整个换成了指纹比对。
        assertEquals(0, Tls.PinningTrustManager { true }.acceptedIssuers.size)
    }

    @Test
    fun `the fingerprint is what the pairing table would store`() {
        // 两边必须是同一个规范化形式，否则「配过了却连不上」。
        assertEquals(expectedBase32, PeerCodec.normalize(Tls.fingerprintOf(cert)))
        assertTrue(PeerCodec.isValidFingerprint(Tls.fingerprintOf(cert)))
    }
}
