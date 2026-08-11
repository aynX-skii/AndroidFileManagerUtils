package com.aynux.afmu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SAS 必须和 Linux 端 `afmu::computeSas` 逐字符一致。
 *
 * 这个东西算错的后果和别处不一样：两个屏幕上显示不同的码，而用户唯一合理的反应是
 * **认为自己正在被攻击**。一个编码 bug 会被读成一次安全事件，然后配对彻底走不通。
 *
 * 下面的向量取自 C++ 实现的真实输出（`afmu_peerstore_test` 会把它们打出来），
 * 并且都用第三方脚本按草案 §4.2.2 的公式独立复核过 —— 不是照着 Kotlin 自己的结果反填的。
 */
class PairSasTest {

    private fun bytes(value: Int) = ByteArray(32) { value.toByte() }

    private val fp11 = bytes(0x11)
    private val fp22 = bytes(0x22)
    private val na33 = bytes(0x33)
    private val nb44 = bytes(0x44)

    @Test
    fun `matches the C++ implementation`() {
        assertEquals("LZ3SNKNQ", PairSas.compute(fp11, fp22, na33, nb44))
        assertEquals("LZ3S-NKNQ", PairSas.format(PairSas.compute(fp11, fp22, na33, nb44)))
    }

    @Test
    fun `sorts fingerprints as unsigned bytes`() {
        // 0x88 当有符号字节是 -120。按有符号排的话，一半的指纹对会被两端排成
        // 相反的顺序 —— 一个"测试时好好的、装到用户手上一半设备对不上"的 bug。
        val fp88 = bytes(0x88)
        assertEquals("NNL948XF", PairSas.compute(fp88, fp11, na33, nb44))
        assertEquals(
            PairSas.compute(fp88, fp11, na33, nb44),
            PairSas.compute(fp11, fp88, na33, nb44),
        )
    }

    @Test
    fun `who dialled does not change the code`() {
        assertEquals(
            PairSas.compute(fp11, fp22, na33, nb44),
            PairSas.compute(fp22, fp11, na33, nb44),
        )
    }

    @Test
    fun `nonces are not sorted`() {
        // 两个随机数的角色是固定的（发起方 / 接收方）。把它们也排序会白丢一半绑定强度。
        assertNotEquals(
            PairSas.compute(fp11, fp22, na33, nb44),
            PairSas.compute(fp11, fp22, nb44, na33),
        )
    }

    @Test
    fun `one bit of nonce changes the code`() {
        val tweaked = na33.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertNotEquals(
            PairSas.compute(fp11, fp22, na33, nb44),
            PairSas.compute(fp11, fp22, tweaked, nb44),
        )
    }

    @Test
    fun `bad input yields null, never a plausible looking code`() {
        // 返回空字符串然后照常显示，等于让用户"比对"一个不存在的东西。
        assertNull(PairSas.compute(fp11.copyOf(31), fp22, na33, nb44))
        assertNull(PairSas.compute(fp11, fp22, na33.copyOf(16), nb44))
        assertNull(PairSas.compute(fp11, fp11, na33, nb44))
        assertEquals("", PairSas.format(null))
        assertEquals("", PairSas.format("SHORT"))
    }

    @Test
    fun `the code is eight characters from the fingerprint alphabet`() {
        val sas = PairSas.compute(fp11, fp22, na33, nb44)!!
        assertEquals(PairSas.LENGTH, sas.length)
        for (ch in sas) {
            assertNotEquals("字母表外的字符：$ch", -1, ProtocolConstants.FINGERPRINT_ALPHABET.indexOf(ch))
        }
    }
}
