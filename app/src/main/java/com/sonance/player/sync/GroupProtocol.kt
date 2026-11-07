package com.sonance.player.sync

import android.os.SystemClock

/** 协议常量与公共小工具。消息统一是「一行一个 JSON」。 */
object GroupProtocol {
    /** NSD 服务类型，必须是 `_xxx._tcp` 形式 */
    const val SERVICE_TYPE = "_sonance._tcp"
    const val SERVICE_PREFIX = "Sonance-"

    const val TCP_PORT = 47801
    const val UDP_PORT = 47802

    // 消息类型
    const val T_HELLO = "hello"
    const val T_PAIR_OK = "pairOk"
    const val T_PAIR_DENIED = "pairDenied"
    const val T_ANCHOR = "anchor"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_CMD = "cmd"
    const val T_BYE = "bye"
    const val T_FILE_REQ = "fileReq"
    const val T_FILE_HEAD = "fileHead"
    const val T_CAL_START = "calStart"
    const val T_CAL_STOP = "calStop"
    /** 主机当前没有可同步的曲目（从机保持连接、不要判定为同步中） */
    const val T_IDLE = "idle"

    // 心跳/超时（毫秒）
    const val HEARTBEAT_MS = 500L
    const val PEER_TIMEOUT_MS = 1500L
    const val PEER_DROP_MS = 6000L

    // 分阶段超时（毫秒）：任何一步卡住都要有可见失败原因，不能永远显示「正在同步」
    const val CONNECT_TIMEOUT_MS = 6_000L
    const val PAIR_TIMEOUT_MS = 12_000L
    const val ANCHOR_TIMEOUT_MS = 12_000L
    const val CLOCK_TIMEOUT_MS = 15_000L
    const val TRANSFER_IDLE_TIMEOUT_MS = 10_000L
    const val READY_TIMEOUT_MS = 15_000L

    /** 配对阶段重发 hello 的间隔与次数（首包可能在主机刚建立连接时被吞） */
    const val HELLO_RETRY_MS = 1_500L
    const val HELLO_MAX_ATTEMPTS = 3

    // 同步阈值（毫秒）
    const val SYNC_HARD_SEEK_MS = 150L
    const val SYNC_SOFT_TRIM_MS = 15L
    const val SYNC_TRIM_RESET_MS = 8L
    const val SYNC_MAX_RATE_DELTA = 0.02f

    /** 校准脉冲：每 600ms 一声，32 声 = 19.2 秒（保证循环衔接不破坏节拍网格） */
    const val CAL_BEAT_MS = 600L
    const val CAL_TOTAL_MS = 19_200L
    const val CAL_BEAT_LEN_MS = 40

    fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
    fun nowMs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000_000L

    fun nanosToMs(n: Long): Long = n / 1_000_000L
    fun msToNanos(ms: Long): Long = ms * 1_000_000L
}
