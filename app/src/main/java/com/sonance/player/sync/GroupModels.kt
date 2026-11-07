package com.sonance.player.sync

import androidx.compose.runtime.Immutable

/** 本机在多设备组里的角色 */
enum class GroupRole { NONE, HOST, GUEST }

/** 组会话阶段（UI 直接按它渲染文案/状态点） */
enum class GroupPhase { IDLE, DISCOVERING, PAIRING, CONNECTED, SYNCING, DEGRADED }

/** 一台对端设备（主机视角 = 已加入的从机；从机视角 = 发现到的可加入主机） */
@Immutable
data class GroupPeer(
    val id: String,
    val name: String,
    val model: String,
    val address: String = "",
    /** 发现主机的直连参数（从机视角；主机侧为空） */
    val hostIp: String = "",
    val hostPort: Int = 0,
    val online: Boolean = true,
    /** 从机视角下这条代表主机 */
    val isHost: Boolean = false,
    /** 最近一次上报的同步偏差（毫秒），null = 还没有样本 */
    val syncErrorMs: Long? = null,
    val rttMs: Long? = null,
    val outputLatencyMs: Long = 0,
    /** 0..1；null 表示当前没有传输任务 */
    val transferProgress: Float? = null,
    val transferTitle: String? = null
)

/**
 * 组状态快照。UI 每帧只看这一份，不要直接摸 GroupManager 内部字段。
 *
 * 标 [Immutable] 的理由和 [com.sonance.player.playback.PlayerUiState] 一样：
 * 里面全是 List/基本类型，不标会被 Compose 判成 unstable。
 */
@Immutable
data class GroupUiState(
    val role: GroupRole = GroupRole.NONE,
    val phase: GroupPhase = GroupPhase.IDLE,
    val selfName: String = "",
    /** 已连接的对端（主机的从机列表 / 从机只有主机一条） */
    val peers: List<GroupPeer> = emptyList(),
    /** 发现到的、可加入的主机（从机视角） */
    val hosts: List<GroupPeer> = emptyList(),
    /** 本机当前同步偏差（毫秒）；主机恒为 0 */
    val syncErrorMs: Long? = null,
    /** 对时得到的主从时钟偏移（毫秒，正数 = 主机时钟比本机快） */
    val clockOffsetMs: Long? = null,
    /** 对时 RTT（毫秒） */
    val rttMs: Long? = null,
    /** 本机输出延迟估计（毫秒，按机型+路由持久化的校准值） */
    val outputLatencyMs: Long = 0,
    val hostLatencyMs: Long = 0,
    val connectedCount: Int = 0,
    val trackKey: String? = null,
    val trackTitle: String? = null,
    val trackArtist: String = "",
    val trackAlbum: String = "",
    val trackDurationMs: Long = 0L,
    /** 从机接收当前曲目的进度，0..1；null = 没在传 */
    val transferProgress: Float? = null,
    val transferTitle: String? = null,
    /** 校准面板是否处于「脉冲播放中」 */
    val calibrationActive: Boolean = false,
    /** 校准模式下，主机下发的脉冲起始时刻（主机 elapsedRealtime 的 ns） */
    val calibrationStartNanos: Long = 0L,
    val calibrationOffsetMs: Long = 0L,
    val calibrationRoute: String = "",
    /** 主机待确认的入组请求（非 null 时 UI 弹确认框） */
    val pendingPeerId: String? = null,
    val pendingPeerName: String? = null,
    val hostName: String? = null,
    val message: String? = null,
    // ---- 诊断：任何一步失败都要能说清「卡在哪」 ----
    /** NSD 解析到的直连地址（诊断栏展示） */
    val resolvedHost: String? = null,
    val resolvedPort: Int = 0,
    /** TCP 连接结果，例如「已连接 192.168.1.5:47801」「连接失败」 */
    val tcpState: String? = null,
    /** hello → pairOk 的往返耗时 */
    val handshakeRttMs: Long? = null,
    /** 最后一次失败原因与发生时刻（elapsedRealtime，用于算「多久之前」） */
    val lastError: String? = null,
    val lastErrorAtMs: Long = 0L,
    /** 失败后是否允许「重试连接」 */
    val canRetry: Boolean = false
) {
    val active: Boolean get() = role != GroupRole.NONE
    val groupSize: Int get() = connectedCount + if (active) 1 else 0
}

/** 远端曲目的合成 mediaId 段：负数，避免和本地 MediaStore id / 在线歌 id 冲突。
 *  下游（ViewModel 的最近播放/播放次数）只认 `id > 0` 的本地歌，负数天然不记账。 */
fun peerMediaId(key: String): Long {
    val h = key.hashCode().toLong() and 0x3FFF_FFFFL
    return -2_000_000_000L - h
}
