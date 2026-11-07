package com.sonance.player.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP 四时间戳对时（NTP 的最简版）。
 *
 * 主机在 [GroupProtocol.UDP_PORT] 上应答；从机连发 20 组，取 **RTT 最小** 的一组：
 * 排队抖动越大 RTT 越大，最小值那一组最接近「纯链路延迟」，偏移估计最准。
 * 之后每 2 秒补一组，按 20% 步进缓慢修正，避免时钟跳变导致音频瞬间错位。
 *
 * 时间基准统一用 [android.os.SystemClock.elapsedRealtimeNanos]，两边都是单调时钟，
 * 不依赖系统时间/时区；重启后重新对时即可。
 */
class ClockSync {

    @Volatile
    var offsetNanos: Long = 0L
        private set

    @Volatile
    var rttNanos: Long = Long.MAX_VALUE
        private set

    /** 每次拿到新样本回调（IO 线程，调用方自己切主线程） */
    var onUpdate: ((offsetMs: Long, rttMs: Long) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hostSocket: DatagramSocket? = null
    private var guestSocket: DatagramSocket? = null
    private var hostJob: Job? = null
    private var guestJob: Job? = null

    /** 是否已经拿到至少一个有效样本（首次对时完成） */
    val hasSample: Boolean
        get() = rttNanos != Long.MAX_VALUE

    /** 用本机时钟 + 偏移，换算成「主机时间轴」 */
    fun hostNowNanos(): Long = GroupProtocol.nowNanos() + offsetNanos

    fun startHost(): Boolean {
        stop()
        val socket = runCatching { DatagramSocket(GroupProtocol.UDP_PORT) }.getOrNull()
        if (socket == null) {
            GroupLog.log("clock", "主机 UDP ${GroupProtocol.UDP_PORT} 绑定失败（端口被占用？）")
            return false
        }
        hostSocket = socket
        hostJob = scope.launch {
            val buf = ByteArray(256)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                // receive 是阻塞的；stop() 里 close socket 会抛异常打断它
                val ok = runCatching { socket.receive(packet) }.isSuccess
                if (!ok) break
                val text = String(packet.data, 0, packet.length)
                if (!text.startsWith("Q ")) continue
                val seq = text.split(' ').getOrNull(1) ?: continue
                val t1 = GroupProtocol.nowNanos()
                val t2 = GroupProtocol.nowNanos()
                val reply = "R $seq $t1 $t2"
                runCatching {
                    socket.send(
                        DatagramPacket(
                            reply.toByteArray(),
                            reply.length,
                            packet.address,
                            packet.port
                        )
                    )
                }
            }
        }
        return true
    }

    fun startGuest(hostAddress: String, hostPort: Int = GroupProtocol.UDP_PORT) {
        stop()
        guestJob = scope.launch {
            val socket = runCatching { DatagramSocket() }.getOrNull() ?: run {
                GroupLog.log("clock", "UDP socket 创建失败")
                return@launch
            }
            val target = runCatching { InetAddress.getByName(hostAddress) }.getOrNull() ?: run {
                GroupLog.log("clock", "主机地址无法解析：$hostAddress")
                return@launch
            }
            guestSocket = socket
            socket.soTimeout = 400
            var bestOffset = 0L
            var bestRtt = Long.MAX_VALUE
            var primed = false
            var seq = 0

            fun once(): Pair<Long, Long>? {
                val t0 = GroupProtocol.nowNanos()
                seq += 1
                val mySeq = seq
                val query = "Q $mySeq $t0"
                val sent = runCatching {
                    socket.send(
                        DatagramPacket(
                            query.toByteArray(),
                            query.length,
                            target,
                            hostPort
                        )
                    )
                }.isSuccess
                if (!sent) return null

                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                if (!runCatching { socket.receive(packet) }.isSuccess) return null
                val t3 = GroupProtocol.nowNanos()
                val parts = String(packet.data, 0, packet.length).trim().split(' ')
                if (parts.size < 4 || parts[0] != "R") return null
                // 序号必须对上：UDP 乱序时旧应答会让偏移算错
                if (parts[1].toIntOrNull() != mySeq) return null
                val t1 = parts[2].toLongOrNull() ?: return null
                val t2 = parts[3].toLongOrNull() ?: return null
                // 注意：t1/t2 是**主机**的 elapsedRealtime，t0/t3 是本机的，
                // 两台设备的开机时长互不相同，绝对不能拿 t1 和 t0 直接比较
                // （旧实现里的 t1 < t0 会把「主机比本机开机晚」的合法样本全部丢掉）。
                if (t2 < t1) return null
                val rtt = (t3 - t0) - (t2 - t1)
                if (rtt < 0L || rtt > 3_000_000_000L) return null
                val offset = ((t1 - t0) + (t2 - t3)) / 2L
                return offset to rtt
            }

            // 首次快速取样（首轮 20 组，取 RTT 最小的一组）
            repeat(20) { i ->
                val sample = once()
                if (sample != null && sample.second < bestRtt) {
                    bestRtt = sample.second
                    bestOffset = sample.first
                }
                if (!primed && i == 4 && bestRtt == Long.MAX_VALUE) {
                    GroupLog.log("clock", "前 5 组对时无应答（主机 UDP ${hostPort} 可能被拦截）")
                }
                delay(if (i < 4) 40 else 120)
            }
            if (bestRtt != Long.MAX_VALUE) {
                offsetNanos = bestOffset
                rttNanos = bestRtt
                primed = true
                GroupLog.log(
                    "clock",
                    "对时完成 offset=${GroupProtocol.nanosToMs(bestOffset)}ms rtt=${GroupProtocol.nanosToMs(bestRtt)}ms"
                )
                onUpdate?.invoke(GroupProtocol.nanosToMs(bestOffset), GroupProtocol.nanosToMs(bestRtt))
            } else {
                GroupLog.log("clock", "首轮 20 组对时全部失败，进入后台重试")
            }

            // 之后周期补样
            while (isActive) {
                delay(2000)
                val sample = once() ?: continue
                val candidate = sample.first
                val rtt = sample.second
                if (rtt < bestRtt) bestRtt = rtt
                if (!primed) {
                    offsetNanos = candidate
                    primed = true
                    GroupLog.log("clock", "延迟对时成功 offset=${GroupProtocol.nanosToMs(candidate)}ms rtt=${GroupProtocol.nanosToMs(rtt)}ms")
                } else {
                    // 每 2 秒只修正 20%，避免可闻的跳变
                    offsetNanos += (candidate - offsetNanos) / 5L
                    // 只有 RTT 明显变好才更新展示值，避免诊断数字乱跳
                    if (rtt <= bestRtt) rttNanos = rtt
                }
                onUpdate?.invoke(
                    GroupProtocol.nanosToMs(offsetNanos),
                    GroupProtocol.nanosToMs(rttNanos)
                )
            }
        }
    }

    fun stop() {
        hostJob?.cancel(); hostJob = null
        guestJob?.cancel(); guestJob = null
        runCatching { hostSocket?.close() }
        runCatching { guestSocket?.close() }
        hostSocket = null
        guestSocket = null
        offsetNanos = 0L
        rttNanos = Long.MAX_VALUE
    }
}
