package com.sonance.player.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识曲接口的纯函数：表单体构造 / 响应解析 / 候选挑选。
 *
 * 请求体字段拼错（漏 URL 编码、字段名对不上）接口会直接 400；
 * 响应结构解析错了会静默变成「未识别」。都在 JVM 里钉死。
 */
class NeteaseRecognizerTest {

    // ===== buildFormBody =====

    @Test
    fun `表单包含全部六个字段且值正确`() {
        val body = NeteaseRecognizer.buildFormBody("FPDATA", "session-1")
        val fields = body.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        assertEquals("session-1", fields["sessionId"])
        assertEquals("shazam_v2", fields["algorithmCode"])
        assertEquals("6", fields["duration"])
        assertEquals("FPDATA", fields["rawdata"])
        assertEquals("2", fields["times"])
        assertEquals("1", fields["decrypt"])
    }

    @Test
    fun `rawdata 的 base64 特殊字符被 URL 编码`() {
        // base64 里的 + / = 不编码的话，+ 会被服务端解成空格，指纹直接作废
        val body = NeteaseRecognizer.buildFormBody("ab+/9=", "s")
        val raw = body.split("&").first { it.startsWith("rawdata=") }
        assertEquals("rawdata=ab%2B%2F9%3D", raw)
    }

    @Test
    fun `字段顺序与参考实现一致`() {
        // 顺序本身服务端不挑，但钉住它可以防止以后改坏时无感知
        val body = NeteaseRecognizer.buildFormBody("F", "s")
        val keys = body.split("&").map { it.substringBefore("=") }
        assertEquals(
            listOf("sessionId", "algorithmCode", "duration", "rawdata", "times", "decrypt"),
            keys
        )
    }

    // ===== parseCandidates =====

    @Test
    fun `解析标准响应结构`() {
        val body = """
            {"code":200,"data":{"result":[
                {"song":{"id":111,"name":"A","duration":210000,"artists":[{"name":"x"}]}},
                {"song":{"id":222,"name":"B","duration":180000}}
            ]}}
        """.trimIndent()
        val list = NeteaseRecognizer.parseCandidates(body)
        assertEquals(2, list.size)
        assertEquals("111", list[0].id)
        assertEquals(210_000L, list[0].durationMs)
        assertEquals("222", list[1].id)
    }

    @Test
    fun `没有 result 字段按未识别处理`() {
        assertTrue(NeteaseRecognizer.parseCandidates("""{"code":200,"data":{}}""").isEmpty())
    }

    @Test
    fun `非法 JSON 不抛异常返回空`() {
        assertTrue(NeteaseRecognizer.parseCandidates("not json").isEmpty())
        assertTrue(NeteaseRecognizer.parseCandidates("").isEmpty())
    }

    @Test
    fun `无效 id 的候选被过滤`() {
        val body = """
            {"data":{"result":[
                {"song":{"id":0,"name":"bad"}},
                {"song":{"id":333,"name":"ok","duration":1000}}
            ]}}
        """.trimIndent()
        val list = NeteaseRecognizer.parseCandidates(body)
        assertEquals(1, list.size)
        assertEquals("333", list[0].id)
    }

    // ===== pickByDuration =====

    private fun c(id: String, durationMs: Long) = NeteaseRecognizer.Candidate(id, durationMs)

    @Test
    fun `挑时长最接近的候选`() {
        val picked = NeteaseRecognizer.pickByDuration(
            listOf(c("a", 200_000), c("b", 232_000), c("c", 300_000)),
            durationMs = 230_000
        )
        assertEquals("b", picked.id)
    }

    @Test
    fun `本地时长未知时取第一条`() {
        val picked = NeteaseRecognizer.pickByDuration(
            listOf(c("a", 200_000), c("b", 232_000)),
            durationMs = 0
        )
        assertEquals("a", picked.id)
    }

    @Test
    fun `最接近的也差太远时退回第一条`() {
        // 指纹结果整体时长都对不上 → 宁可信指纹的第一条
        val picked = NeteaseRecognizer.pickByDuration(
            listOf(c("a", 100_000), c("b", 190_000)),
            durationMs = 180_000
        )
        assertEquals("a", picked.id)
    }

    @Test
    fun `只有一个候选直接返回`() {
        val picked = NeteaseRecognizer.pickByDuration(listOf(c("only", 1)), durationMs = 999_999)
        assertEquals("only", picked.id)
    }
}
