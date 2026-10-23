package com.sonance.player.data.bili

import org.json.JSONObject

/**
 * 一条 B 站离线缓存。
 *
 * 数据全部来自缓存目录里的 `entry.json`（明文 JSON），我们不联网、不碰 B 站账号，
 * 只是把「这台机器上已经存在的东西」读出来重新组织一遍。
 *
 * 目录结构（实测 B 站 HD 13.x）：
 * ```
 * download/<avid>/c_<cid>/entry.json
 *                        /danmaku.xml
 *                        /<type_tag>/video.m4s
 *                        /<type_tag>/audio.m4s
 *                        /<type_tag>/index.json
 * ```
 * `<type_tag>` 是清晰度代号（116=1080P60、80=1080P、64=720P…）。
 */
data class BiliEntry(
    /** 稳定主键：`avid_cid_质量`。同一视频的不同分 P 是不同条目 */
    val key: String,
    val avid: Long,
    val cid: Long,
    /** 视频标题（合集名），分 P 时这里是一样的 */
    val title: String,
    /** 分 P 名。单 P 视频通常等于标题 */
    val part: String,
    val owner: String,
    val cover: String,
    /** 「1080P」「720P」这类人话清晰度 */
    val qualityLabel: String,
    /** 帧率角标，例如「60帧」，可能为空 */
    val frameNote: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** 付费/课程类内容，可能加密或需要鉴权，播放前先拦一道 */
    val chargeVideo: Boolean,
    /** 是否是「音视频分离」的 DASH 缓存。老版本是单文件 blv，这里解不了 */
    val dashAudio: Boolean,
    /** 缓存写入时间，用于排序（更新的在前） */
    val createdAt: Long,
    /** `entry.json` 所在目录（老版本缓存的音视频就直接放这里，没有清晰度子目录） */
    val dir: String,
    /** 视频轨绝对路径（video.m4s） */
    val videoPath: String,
    /** 音频轨绝对路径（audio.m4s），没有独立音轨时为 null（老 blv 缓存） */
    val audioPath: String?
) {
    /** 分 P 名和标题重复时只显示一个 */
    val displayTitle: String get() = part.ifBlank { title }.let { if (it == title) title else "$title · $it" }

    /** 有独立音轨才能播；老式 blv 缓存（音视频混流）暂不支持 */
    val playable: Boolean get() = dashAudio && videoPath.isNotBlank()

    val durationText: String
        get() {
            val total = durationMs / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

/**
 * `entry.json` 解析。
 *
 * 各版本字段有出入（有的叫 `owner_name`，有的在 `user_info.uname`；
 * 清晰度有的只在 `type_tag`），所以全部走 `opt*` 兜底，缺字段不抛异常。
 */
object BiliEntryParser {

    /**
     * @param header 扫描脚本输出的头部：`<entry.json 路径>`
     * @param json   紧随其后的 entry.json 全文
     */
    fun parse(header: String, json: String): BiliEntry? = runCatching {
        val obj = JSONObject(json.trim())
        val entryPath = header.substringBefore('|')
        if (entryPath.isBlank()) return@runCatching null

        val entryDir = entryPath.substringBeforeLast('/')
        if (entryDir.isBlank()) return@runCatching null

        // 分 P 的元数据可能嵌在 page_data 里，也可能平铺在根上
        val page = obj.optJSONObject("page_data")
        val userInfo = obj.optJSONObject("user_info")

        val avid = obj.optLong("avid").takeIf { it > 0 }
            ?: obj.optJSONObject("up_info")?.optLong("avid")?.takeIf { it > 0 }
            ?: 0L
        val cid = page?.optLong("cid")?.takeIf { it > 0 } ?: 0L

        val title = obj.optString("title").ifBlank {
            page?.optString("download_title").orEmpty()
        }
        val part = page?.optString("part").orEmpty()
            .ifBlank { page?.optString("download_subtitle").orEmpty() }

        val owner = obj.optString("owner_name").ifBlank {
            userInfo?.optString("uname").orEmpty()
        }

        val tag = obj.optString("type_tag").ifBlank {
            obj.optInt("prefered_video_quality", 0).toString()
        }

        // 清晰度子目录名就是 type_tag（116/80/64…）。老版本缓存直接把 m4s 放在
        // entry.json 旁边，没有这一层 —— 这种情况下路径会落到 entryDir 上。
        // 是否真的存在，等 copy 的时候再验，扫描阶段不额外 stat（每次都 fork
        // 进程会让 300 个条目扫十几秒）。
        val qualityDir = if (tag.isNotBlank()) "$entryDir/$tag" else entryDir
        val videoPath = "$qualityDir/video.m4s"
        val audioPath = "$qualityDir/audio.m4s"

        val qualityLabel = obj.optString("quality_pithy_description").ifBlank {
            when (tag) {
                "127" -> "8K"
                "126" -> "杜比视界"
                "125" -> "HDR"
                "120" -> "4K"
                "116" -> "1080P60"
                "112" -> "1080P+"
                "80" -> "1080P"
                "74" -> "720P60"
                "64" -> "720P"
                "32" -> "480P"
                "16" -> "360P"
                else -> if (tag.isBlank()) "未知" else "${tag}P"
            }
        }

        val displayTitle = title.ifBlank { part }.ifBlank { "未命名视频" }

        BiliEntry(
            key = "${avid}_${cid}_$tag",
            avid = avid,
            cid = cid,
            title = displayTitle,
            part = part,
            owner = owner,
            cover = obj.optString("cover"),
            qualityLabel = qualityLabel,
            frameNote = obj.optString("quality_superscript"),
            durationMs = obj.optLong("total_time_milli"),
            sizeBytes = obj.optLong("total_bytes"),
            chargeVideo = obj.optBoolean("is_charge_video"),
            dashAudio = obj.optBoolean("has_dash_audio", true),
            createdAt = obj.optLong("time_create_stamp"),
            dir = entryDir,
            videoPath = videoPath,
            audioPath = audioPath
        )
    }.getOrNull()
}
