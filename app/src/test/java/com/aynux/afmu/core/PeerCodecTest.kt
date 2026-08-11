package com.aynux.afmu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对表的编解码。
 *
 * 这张表在 v2 里同时是数据和访问控制列表 —— 里面有一条就等于开着一道门 ——
 * 所以「同一个指纹存成两种写法」不是整洁性问题：删掉一条，另一条还开着。
 * 这些又全是纯逻辑，正好能在 JVM 上直接跑，不用真机也不用模拟器。
 *
 * Linux 端的同一批断言在 afmu-linux/tests/peerstore_test.cpp 里，两边刻意保持对应。
 */
class PeerCodecTest {

    private fun fp(filler: Byte) = Base32.encode(ByteArray(32) { filler })

    private val a = fp(0x11)
    private val b = fp(0x22)

    // ------------------------------------------------------------ 指纹合法性

    @Test
    fun `a full length fingerprint is valid`() {
        assertEquals(52, a.length)
        assertTrue(PeerCodec.isValidFingerprint(a))
    }

    @Test
    fun `anything that is not 32 bytes is rejected`() {
        // 截断的指纹必须整串作废。存进去的话，将来比对时永远匹配不上，
        // 而症状是「明明配过了却连不上」。
        assertNull(PeerCodec.normalize(a.take(40)))
        assertNull(PeerCodec.normalize(a + "AAAA"))
        assertNull(PeerCodec.normalize(""))
        assertNull(PeerCodec.normalize(null))
        assertNull(PeerCodec.normalize(a.take(51) + "!"))
    }

    @Test
    fun `grouping and case do not change the identity`() {
        assertEquals(a, PeerCodec.normalize(Base32.group(a)))
        assertEquals(a, PeerCodec.normalize(a.lowercase()))
        assertEquals(a, PeerCodec.normalize(a.chunked(5).joinToString("-")))
    }

    @Test
    fun `padding bits in the last character collapse to one spelling`() {
        // 52 个 base32 字符是 260 bit，指纹只有 256 bit：末字符低 4 bit 是填充。
        // 写成什么都必须归一到同一条记录，否则表里会出现两条指向同一台设备的记录。
        val alphabet = ProtocolConstants.FINGERPRINT_ALPHABET
        val last = alphabet.indexOf(a[51])
        assertTrue(last >= 0)
        val twisted = a.take(51) + alphabet[last xor 0x0F]
        assertTrue("构造出来的应当是不同的字符串", twisted != a)
        assertEquals(a, PeerCodec.normalize(twisted))
    }

    // ------------------------------------------------------------ 编解码

    @Test
    fun `round trips every field`() {
        val r = PeerRecord(a, "Pixel 8", "android", "192.168.1.42", 8765, 1786000000L, true)
        val back = PeerCodec.decode(PeerCodec.encode(listOf(r)))
        assertEquals(listOf(r), back)
    }

    @Test
    fun `unusable rows are dropped and counted`() {
        val json = """
            [{"fp":"$a","name":"好的"},
             {"fp":"太短","name":"坏的"},
             {"name":"没有指纹"},
             42]
        """.trimIndent()
        var dropped = -1
        val out = PeerCodec.decode(json) { dropped = it }
        assertEquals(1, out.size)
        assertEquals("好的", out[0].name)
        // 丢了几条必须数出来 —— 界面上要说，否则用户看到的只是「怎么少了一台」
        assertEquals(3, dropped)
    }

    @Test
    fun `a duplicate fingerprint collapses to one row`() {
        val json = """[{"fp":"$a","name":"旧"},{"fp":"${Base32.group(a)}","name":"新"}]"""
        var dropped = -1
        val out = PeerCodec.decode(json) { dropped = it }
        assertEquals(1, out.size)
        assertEquals("新", out[0].name)
        assertEquals(1, dropped)
    }

    @Test
    fun `garbage json yields an empty table rather than an exception`() {
        assertEquals(emptyList<PeerRecord>(), PeerCodec.decode("{ 这不是数组"))
        assertEquals(emptyList<PeerRecord>(), PeerCodec.decode(null))
    }

    // ------------------------------------------------------------ upsert

    @Test
    fun `upsert adds then updates the same fingerprint`() {
        val (afterAdd, added) = PeerCodec.upsert(emptyList(), PeerRecord(a, name = "一"), now = 100)
        assertTrue(added)
        assertEquals(1, afterAdd.size)
        assertEquals(100L, afterAdd[0].pairedAt)

        val (afterUpdate, addedAgain) =
            PeerCodec.upsert(afterAdd, PeerRecord(a, name = "二", pairedAt = 1), now = 999)
        assertFalse(addedAgain)
        assertEquals(1, afterUpdate.size)
        assertEquals("二", afterUpdate[0].name)
        // 认识的日子不能被重连刷掉，也不能被调用方随手覆盖
        assertEquals(100L, afterUpdate[0].pairedAt)
    }

    @Test
    fun `grouped and plain spellings are the same row`() {
        val (one, _) = PeerCodec.upsert(emptyList(), PeerRecord(a), now = 1)
        val (two, added) = PeerCodec.upsert(one, PeerRecord(Base32.group(a), name = "同一台"), now = 2)
        assertFalse(added)
        assertEquals(1, two.size)
        assertEquals("同一台", two[0].name)
    }

    @Test
    fun `a routine update cannot clear pinned`() {
        // pinned 被抹掉正是降级攻击想要的效果：这个对端从此又可以被打回明文。
        // 要清除只能走 setPinned，那是一次明确的动作。
        val (pinned, _) = PeerCodec.upsert(emptyList(), PeerRecord(a, pinned = true), now = 1)
        val (after, _) = PeerCodec.upsert(pinned, PeerRecord(a, name = "改名", pinned = false), now = 2)
        assertTrue(after[0].pinned)
    }

    @Test
    fun `an invalid fingerprint never enters the table`() {
        val (out, added) = PeerCodec.upsert(emptyList(), PeerRecord("NOT-A-FINGERPRINT"), now = 1)
        assertFalse(added)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `different fingerprints are different devices`() {
        val (one, _) = PeerCodec.upsert(emptyList(), PeerRecord(a), now = 1)
        val (two, added) = PeerCodec.upsert(one, PeerRecord(b), now = 2)
        assertTrue(added)
        assertEquals(2, two.size)
    }
}
