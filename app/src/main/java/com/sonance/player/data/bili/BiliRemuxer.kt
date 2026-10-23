package com.sonance.player.data.bili

import java.io.File

/**
 * 把 B 站的 `video.m4s` + `audio.m4s` 合成一个标准 MP4。
 *
 * ## 最终方案：自己写容器
 *
 * 这台设备上走不通的路（都实测过）：
 *
 * 1. **Media3 `MergingMediaSource`**：两条 dynamic 时间轴合并时越界，
 *    拿不到轨组（`index (0) must be less than size (0)`）；
 * 2. **`MediaExtractor` + `MediaMuxer`**：`MediaExtractor` 读不出 fMP4 的样本，
 *    产出 0 帧空壳；
 * 3. **自己解析样本 + `MediaMuxer`**：偏移和 NAL 前缀都验证正确，但
 *    `MPEG4Writer::addLengthPrefixedSample_l` 把长度算成 `(size_t)-1`，
 *    整个进程 SIGABRT（`FORTIFY: write: count 18446744073709551615`）。
 *
 * 所以样本索引由 [BiliFmp4] 解析、MP4 结构由 [BiliMp4Writer] 直接拼 —— 不经过
 * 任何平台封装器，行为完全可预期。`stsd` 里的编码参数（`hvcC`/`esds`）直接
 * 复用原文件字节，没有重新构造参数集的风险。
 */
object BiliRemuxer {

    fun remux(
        video: File,
        audio: File,
        out: File,
        durationMs: Long = 0L,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val videoTrack = BiliFmp4.parse(video) ?: return false
        val audioTrack = BiliFmp4.parse(audio) ?: return false
        if (videoTrack.samples.isEmpty() || audioTrack.samples.isEmpty()) return false
        if (videoTrack.sampleEntry == null || audioTrack.sampleEntry == null) return false

        return BiliMp4Writer.write(
            videoFile = video,
            video = videoTrack,
            audioFile = audio,
            audio = audioTrack,
            out = out,
            onProgress = onProgress ?: {}
        )
    }
}
