package com.sonance.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 流式还原管线的测试。
 *
 * 这段逻辑原本长在 `RestoreEngine` 里，挂着 Android 的 `Context` / `DocumentFile`，
 * 纯 JVM 单测碰不到 —— 可它偏偏是最容易出错的地方：
 *
 * - NCM 的封面动辄上百 KB，音频起点会**超出探测窗口**，要补读一段再拼回去；
 * - 头部窗口里「已经读进内存、但其实属于载荷」的那段必须接着用，不能再去流里要一遍；
 * - 跨块解密的 `startPos` 必须首尾相接；
 * - 前置标签会改变输出大小，而「是否已转换过」正是拿这个大小比对的。
 *
 * 任何一处算错，结果都是一个**能写完、但听不了**的文件 —— 所以这些都要被钉住。
 */
class RestorePlannerTest {

    // ===== NCM：大封面把音频起点推到探测窗口之外 =====

    @Test
    fun `ncm 大封面：载荷偏移超过探测窗口也能正确解密并插入封面`() {
        val audio = TestContainers.flacAudio(frameBytes = 5000)
        // 100KB 封面，远大于 16KB 探测窗口 —— 走「补读一段再拼回去」的分支
        val image = ByteArray(100_000) { i ->
            when {
                i == 0 -> 0xFF.toByte()
                i == 1 -> 0xD8.toByte()
                else -> (i and 0xFF).toByte()
            }
        }
        val meta = """{"musicName":"大封面","artist":[["某人",1]],"album":"专辑","format":"flac"}"""
        val file = TestContainers.buildNcm(audio, "0123456789abcdef".toByteArray(), meta, image)

        val plan = readyPlan(file, "大封面.ncm")

        assertTrue(
            "封面必须把音频起点推到探测窗口之外，否则这条分支没被测到",
            plan.info.payloadOffset > RestorePlanner.PROBE_WINDOW
        )
        assertEquals(".flac", plan.ext)
        assertEquals("大封面", plan.info.title)
        assertArrayEquals(image, plan.cover)

        val out = writePlan(plan)
        // 输出 = fLaC + STREAMINFO(last 清零) + PICTURE + 原音频帧
        assertEquals(plan.expectedSize, out.size.toLong())
        assertEquals("fLaC", String(out, 0, 4, Charsets.ISO_8859_1))
        assertEquals("STREAMINFO 的 last 标记必须被清掉", 0x00, out[4].toInt())
        assertEquals(0x86, out[RestoreTagWriter.FLAC_HEAD_BYTES].toInt() and 0xFF)
        assertArrayEquals(
            audio.copyOfRange(RestoreTagWriter.FLAC_HEAD_BYTES, audio.size),
            out.copyOfRange(plan.headPrefix.size, out.size)
        )
        assertEquals(plan.expectedSize, plan.written)
    }

    // ===== NCM：自带 ID3 标签的 mp3 =====

    @Test
    fun `ncm 里的 mp3 自带旧标签时整段跳过 不产生标签套标签`() {
        val tagPayload = 100
        val audio = TestContainers.mp3WithExistingTag(tagPayload = tagPayload, frameBytes = 5000)
        val meta = """{"musicName":"旧标签","artist":[["某人",1]],"format":"mp3"}"""
        val file = TestContainers.buildNcm(audio, "fedcba9876543210".toByteArray(), meta, ByteArray(0))

        val plan = readyPlan(file, "旧标签.ncm")
        assertEquals(".mp3", plan.ext)

        val out = writePlan(plan)
        // 输出开头是我们写的新标签，长度正好等于标签本身
        assertEquals("ID3", String(out, 0, 3, Charsets.ISO_8859_1))
        assertEquals(plan.headPrefix.size, Id3Writer.existingTagSize(out))
        // 旧标签（10 + 100 字节）被整段丢弃，音频帧原样跟在后面
        val oldTagSize = 10 + tagPayload
        assertArrayEquals(
            audio.copyOfRange(oldTagSize, audio.size),
            out.copyOfRange(plan.headPrefix.size, out.size)
        )
        assertEquals(audio.size.toLong() - oldTagSize + plan.headPrefix.size, plan.expectedSize)
    }

    // ===== KWM：跨多个 64KB 块 =====

    @Test
    fun `kwm 大文件跨块解密后与原始音频一致`() {
        val frameBytes = 200_000
        val audio = ByteArray(frameBytes) { (it * 7 + 3).toByte() }
        audio[0] = 0xFF.toByte()
        audio[1] = 0xFB.toByte() // MPEG 帧同步，嗅探判成 mp3

        val file = TestContainers.buildKwm(audio, 0x0FEDCBA987654321L, "320mp3")
        val plan = readyPlan(file, "跨块.kwm")

        assertEquals(".mp3", plan.ext)
        // KWM 没有元数据、没有封面 -> 不写标签，输出就是解密后的裸音频
        assertEquals(0, plan.headPrefix.size)

        val out = writePlan(plan)
        assertTrue("必须跨过至少两个 64KB 块", plan.payloadSize > 3 * 64 * 1024)
        assertArrayEquals(audio, out)
        assertEquals(audio.size.toLong(), plan.expectedSize)
        assertEquals(plan.expectedSize, plan.written)
    }

    // ===== 跳过路径 =====

    @Test
    fun `普通文件与 qmc 文件给出各自的跳过原因`() {
        val junk = ByteArray(4096) { (it * 5).toByte() }
        assertEquals("非受支持的加密容器", skipReason(junk, "普通.mp3"))

        // QMC 没有魔数，只能靠文件名给出准确原因
        assertEquals(QmcDecoder.MISSING_TAIL_REASON, skipReason(junk, "某首歌.mflac"))

        val tiny = ByteArray(10)
        assertEquals("文件过小", skipReason(tiny, "太小.ncm"))
    }

    @Test
    fun `kgm 不支持的版本在计划阶段就被拦下`() {
        val header = TestContainers.kgmHeader(ByteArray(16), version = 5)
        val file = header + ByteArray(1000)
        assertTrue(skipReason(file, "旧版.kgm").contains("5"))
    }

    // ===== 工具 =====

    private fun readyPlan(file: ByteArray, name: String): RestorePlan {
        val probe = RestorePlanner.plan(ByteArrayInputStream(file), file.size.toLong(), name)
        assertTrue("应当可以还原，实际是 ${(probe as? RestoreProbe.Skip)?.reason}", probe is RestoreProbe.Ready)
        return (probe as RestoreProbe.Ready).plan
    }

    private fun skipReason(file: ByteArray, name: String): String {
        val probe = RestorePlanner.plan(ByteArrayInputStream(file), file.size.toLong(), name)
        assertTrue("应当被跳过，实际可以还原", probe is RestoreProbe.Skip)
        return (probe as RestoreProbe.Skip).reason
    }

    private fun writePlan(plan: RestorePlan): ByteArray {
        val out = ByteArrayOutputStream()
        plan.writeTo(out)
        return out.toByteArray()
    }
}
