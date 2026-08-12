package com.aynux.afmu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * base32 必须和 Linux 端 `afmu::Identity::toBase32` 逐字节一致 —— 指纹要以这个形式
 * 走二维码和配对表在两端之间传递，编码差一点就等于两台设备永远配不上，
 * 而症状是「指纹看着差不多但就是不匹配」。
 *
 * 下面的向量**取自 C++ 实现的真实输出**（`afmu --fingerprint` 打印的那个身份），
 * 不是照着 Kotlin 自己的结果反填的 —— 那样测的只是「它和自己一致」。
 */
class Base32Test {

    /** 一个真实生成的 P-256 身份的 SPKI 指纹，和它在 Linux 端算出的 base32。 */
    private val vectorHex = "97e5704c8032e1c65f4610be41c4d0c6301340eb37ce619bc3a9aa65594dec0c"
    private val vectorBase32 = "U9UZAVEAGMS6NZ4GCC9EDTGS222BGSHMG9HGDG8DXGXGLYLP7SGA"
    private val vectorDisplay = "U9UZA VEAGM S6NZ4 GCC9E DTGS2 22BGS HMG9H GDG8D XGXGL YLP7S GA"

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `matches the C++ implementation on a real fingerprint`() {
        assertEquals(vectorBase32, Base32.encode(hex(vectorHex)))
    }

    @Test
    fun `256 bits encodes to 52 characters`() {
        // 256 不是 5 的整数倍：51 组之后还剩 1 bit，要左对齐补成第 52 组。
        // 少一个字符就意味着最后一 bit 被丢掉了 —— 两个只差最后一位的指纹会撞在一起。
        assertEquals(52, Base32.encode(hex(vectorHex)).length)
    }

    @Test
    fun `round trips`() {
        assertArrayEquals(hex(vectorHex), Base32.decode(vectorBase32))
    }

    @Test
    fun `display form parses back`() {
        assertEquals(vectorDisplay, Base32.group(vectorBase32))
        assertArrayEquals(hex(vectorHex), Base32.decode(vectorDisplay))
    }

    @Test
    fun `accepts lowercase and dashes`() {
        assertArrayEquals(hex(vectorHex), Base32.decode(vectorBase32.lowercase()))
        assertArrayEquals(hex(vectorHex), Base32.decode(vectorBase32.chunked(5).joinToString("-")))
    }

    @Test
    fun `rejects anything outside the alphabet`() {
        // 打错的指纹必须整串作废，绝不能被修补成另一个看起来合法的值 ——
        // 那等于把「你比对错了」变成「你钉到了别的设备上」。
        assertNull(Base32.decode("AAAA!BBB"))
        for (excluded in listOf('I', 'O', '0', '1')) {
            assertNull("字母表里不该有 $excluded", Base32.decode("AAAA$excluded"))
        }
    }

    @Test
    fun `alphabet is exactly 32 unambiguous characters`() {
        val alphabet = ProtocolConstants.FINGERPRINT_ALPHABET
        assertEquals(32, alphabet.length)
        assertEquals("字母表有重复字符", 32, alphabet.toSet().size)
        for (excluded in listOf('I', 'O', '0', '1')) {
            assertEquals("易混字符 $excluded 不该在字母表里", -1, alphabet.indexOf(excluded))
        }
    }

    @Test
    fun `empty input round trips`() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertArrayEquals(ByteArray(0), Base32.decode(""))
    }

    @Test
    fun `the separators skipped when reading a fingerprint are pinned, not the platform's idea of whitespace`() {
        // Qt's QChar::isSpace() counts U+00A0, U+2007 and U+202F; Kotlin's Char.isWhitespace()
        // does not. Asking each platform its own opinion gave one string two answers — and it
        // is the string that decides which device you are talking to. So the set is explicit,
        // and this pins it on both ends.
        val fp = Base32.encode(ByteArray(32) { 0x11 })
        val raw = ByteArray(32) { 0x11 }
        for (sep in listOf(' ', '\t', '\n', '\r', '-', '\u00A0', '\u2007', '\u202F', '\u3000')) {
            val withSep = fp.take(26) + sep + fp.drop(26)
            assertArrayEquals("应当跳过 U+%04X".format(sep.code), raw, Base32.decode(withSep))
        }
        // Anything else is still fatal for the whole string — we skip separators, we do not
        // skip "characters we did not understand".
        assertNull(Base32.decode(fp.take(26) + "!" + fp.drop(26)))
    }
}
