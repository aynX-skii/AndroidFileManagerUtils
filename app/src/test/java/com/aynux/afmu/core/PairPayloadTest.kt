package com.aynux.afmu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对二维码的解析。
 *
 * 这是整个应用里唯一一个「输入来自摄像头随便扫到的东西」的解析器 —— 值得认真测。
 * 而且它必须和 Linux 端 `afmu::buildPairUri` 生成的字符串对得上：对不上的表现是
 * 「扫了没反应」，用户完全无从下手。
 *
 * 下面用的 v2 样例就是 Linux 端实际生成的形状。
 */
class PairPayloadTest {

    private val fp = Base32.encode(ByteArray(32) { 0x11 })

    private val v2 =
        "afmu://pair?v=2&host=192.168.1.30&hosts=192.168.1.30,10.42.0.1&port=8765" +
            "&fp=$fp&name=ice-desktop&os=linux"

    private val v1 =
        "afmu://pair?v=1&host=192.168.1.30&port=8765&token=abc123xyz9&name=ice-desktop&os=linux"

    @Test
    fun `parses a v2 code`() {
        val p = PairPayload.parse(v2)!!
        assertTrue(p.isV2)
        assertEquals(fp, p.fingerprint)
        assertEquals("", p.token)
        assertEquals(listOf("192.168.1.30", "10.42.0.1"), p.hosts)
        assertEquals(8765, p.port)
        assertEquals("ice-desktop", p.name)
    }

    @Test
    fun `parses a v1 code`() {
        val p = PairPayload.parse(v1)!!
        assertFalse(p.isV2)
        assertEquals("abc123xyz9", p.token)
        assertEquals("", p.fingerprint)
    }

    @Test
    fun `a v2 code never carries a token`() {
        // 二维码里的 token 是 v1 的真问题：截图、转发、投屏都等于交出访问权。
        // 指纹是公开信息，泄露不造成损失。两者同时出现就等于这个问题还在。
        val built = PairPayload.build(
            hosts = listOf("192.168.1.30"), port = 8765, token = "abc123xyz9",
            name = "ice", os = "linux", fingerprint = fp,
        )
        assertTrue(built.contains("v=2"))
        assertTrue(built.contains("fp=$fp"))
        assertFalse("v2 的码里绝不能有 token", built.contains("token="))
    }

    @Test
    fun `round trips through build and parse`() {
        val built = PairPayload.build(
            hosts = listOf("192.168.1.30", "10.42.0.1"), port = 9000, token = "",
            name = "客厅 电脑", os = "linux", fingerprint = fp,
        )
        val p = PairPayload.parse(built)!!
        assertEquals(fp, p.fingerprint)
        assertEquals(listOf("192.168.1.30", "10.42.0.1"), p.hosts)
        assertEquals(9000, p.port)
        // 空格走 %20，中文走 UTF-8 百分号编码，两边都要能还原
        assertEquals("客厅 电脑", p.name)
    }

    @Test
    fun `a plus sign stays a plus sign`() {
        // 协议规定空格是 %20。把 + 解成空格会悄悄改掉带加号的设备名，
        // 而这种 bug 只有那个用户会遇到，也只有他会觉得"名字怎么变了"。
        val built = PairPayload.build(
            hosts = listOf("10.0.0.1"), port = 8765, token = "", name = "a+b", os = "linux",
            fingerprint = fp,
        )
        assertEquals("a+b", PairPayload.parse(built)!!.name)
    }

    @Test
    fun `a fingerprint that will not normalize is refused, not stored`() {
        // 存一个「差不多」的指纹比不存更糟：它会一直匹配不上，而表现是
        // 「明明配过了却连不上」。
        val truncated = "afmu://pair?v=2&host=10.0.0.1&port=8765&fp=${fp.take(40)}&name=x"
        assertNull(PairPayload.parse(truncated))
    }

    @Test
    fun `grouped and lowercase fingerprints normalize on the way in`() {
        val grouped = Base32.group(fp).replace(" ", "%20")
        val p = PairPayload.parse("afmu://pair?v=2&host=10.0.0.1&port=8765&fp=$grouped&name=x")!!
        assertEquals(fp, p.fingerprint)
    }

    @Test
    fun `anything that is not one of our codes returns null`() {
        // 扫描器对着什么都能解出东西来，绝大部分都不是我们的。
        for (junk in listOf(
            "https://example.com",
            "afmu://something?v=2",
            "afmu://pair?v=2&host=10.0.0.1&port=8765",   // 既没 token 也没指纹
            "afmu://pair?v=2&port=8765&fp=$fp",           // 没有地址
            "afmu://pair?v=9&host=10.0.0.1&fp=$fp",       // 不认识的版本，拒绝而不是猜
            "",
        )) {
            assertNull("应当拒绝：$junk", PairPayload.parse(junk))
        }
    }

    @Test
    fun `parses a URI actually produced by the Linux side`() {
        // 取自 afmu_peerstore_test 的真实输出，不是照着 Kotlin 自己的 build() 反填的 ——
        // 那样测的只是「它和自己一致」。空格是 %20、中文是 UTF-8 百分号编码，
        // 这几点两端必须完全一样，否则「扫了没反应」。
        val fromCpp = "afmu://pair?v=2&host=192.168.1.30&port=8765" +
            "&fp=CEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJTCEJS" +
            "&name=%E5%AE%A2%E5%8E%85%20%E7%94%B5%E8%84%91&os=linux"

        val p = PairPayload.parse(fromCpp)!!
        assertTrue(p.isV2)
        assertEquals(Base32.encode(ByteArray(32) { 0x11 }), p.fingerprint)
        assertEquals("192.168.1.30", p.hosts.single())
        assertEquals(8765, p.port)
        assertEquals("客厅 电脑", p.name)
        assertEquals("", p.token)
    }

    @Test
    fun `an out of range port falls back to the default`() {
        val p = PairPayload.parse("afmu://pair?v=2&host=10.0.0.1&port=99999&fp=$fp&name=x")!!
        assertEquals(Prefs.DEFAULT_PORT, p.port)
    }
}
