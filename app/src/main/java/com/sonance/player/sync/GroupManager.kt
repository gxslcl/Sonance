package com.sonance.player.sync

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sonance.player.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 多设备同步播放的编排中心（进程内单例，随 [com.sonance.player.playback.PlaybackService] 生命周期）。
 *
 * 分工：
 * - 主机：唯一时间轴权威。按 500ms 广播 `anchor`（曲目 key + 锚点位置 + 主机时钟 + 播放意图），
 *   并从自己的 MediaStore 读文件、通过裸 TCP 供给从机。
 * - 从机：对时 → 拉取文件到 cache → 用 FileProvider 转 content:// 交给 ExoPlayer →
 *   按锚点粗 seek + 速率微调，把「听到的声音」对齐到主机。
 *
 * 所有状态变更都在主线程串行执行（网络回调统一 post 到 main），播放器只在主线程被访问。
 */
object GroupManager {

    private lateinit var appContext: Context
    private var prefs: Prefs? = null
    private var player: ExoPlayer? = null

    /** 播放服务还没起来时，所有需要 Context 的入口都要短路，否则会踩 lateinit */
    private fun serviceReady(): Boolean = this::appContext.isInitialized

    /** 已经在主线程就直接执行，避免 leave() 里的播放器清理被推迟到服务销毁之后 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = _state.asStateFlow()

    private var selfId = ""
    private var selfName = ""
    private var role = GroupRole.NONE
    private var phase = GroupPhase.IDLE

    // 主机侧
    private var server: GroupServer? = null
    private var nsd: NsdDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var hostToken = ""
    private val peers = LinkedHashMap<String, PeerConn>()
    private val pending = LinkedHashMap<String, PeerConn>()
    private val accepted = mutableSetOf<String>()
    private val peerSync = HashMap<String, Pair<Long?, Long?>>()
    private var hostClock: ClockSync? = null

    // 从机侧
    private var client: GroupClient? = null
    private var guestClock: ClockSync? = null
    private var hostIp = ""
    private var hostPort = GroupProtocol.TCP_PORT
    private var token = ""
    private var hostName = ""
    private var anchor: Anchor? = null
    private var currentKey: String? = null
    private var preparedKey: String? = null
    private var downloadingKey: String? = null
    private var downloadJob: Job? = null
    private var prefetchKey: String? = null
    private var lastPingMs = 0L
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var reconnectStartedMs = 0L

    // 公共
    private var cache: TrackCache? = null
    private var calibrator: PulseCalibrator? = null
    private var calibrationActive = false
    private var calibrationStartNanos = 0L
    private var calibrationRoute = "speaker"
    private var routeCached = "speaker"
    private var localLatency = 0L
    private var hostLatency = 0L
    private var hadPeers = false
    private var lastAnchorMs = 0L
    private var lastRouteCheckMs = 0L
    private var lastSyncReportMs = 0L
    private var lastUiMs = 0L
    private var lastPeerUiMs = 0L
    private var hostTick: Runnable? = null
    private var guestTick: Runnable? = null
    private var remoteApplying = false
    private var connecting = false
    private var prefetchJob: Job? = null
    /** 同一时刻只允许一条文件传输：预取和前台下载撞到同一首时，避免两个协程写同一个 .part */
    private val downloadMutex = Mutex()

    // 阶段超时与诊断：每一步都必须能在 N 秒后给出「失败原因」，不能无限等
    private var stageName = ""
    private var stageStartMs = 0L
    private var helloSentMs = 0L
    private var helloAttempts = 0
    /** 主机明确告知「当前没有可同步曲目」时为 true，此时不判定同步失败 */
    private var hostIdle = false
    private var prepareStartedMs = 0L
    private var clockFailReported = false
    private var transferLastByteMs = 0L
    /** 主机「当前没有可同步曲目」只提示一次，避免 500ms 定时器刷屏 */
    private var hostIdleNotified = false

    private val registry = ConcurrentHashMap<String, TrackEntry>()

    // ------------------------------------------------------------------ 生命周期

    fun attach(context: Context, exo: ExoPlayer) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        selfId = deviceId()
        selfName = prefs?.groupDeviceName ?: Build.MODEL.orEmpty()
        player = exo
        exo.addListener(playerListener)
        refreshLatency()
        _state.update {
            it.copy(
                selfName = selfName,
                outputLatencyMs = localLatency,
                calibrationOffsetMs = localLatency,
                calibrationRoute = calibrationRoute
            )
        }
    }

    fun detach() {
        if (role != GroupRole.NONE) leave(null)
        player?.removeListener(playerListener)
        player = null
    }

    /** 当前是否处于「本机是从机」的可拦截状态（供 PlayerController 转发控制命令） */
    fun interceptGuestCommand(cmd: String, posMs: Long = 0L): Boolean {
        if (role != GroupRole.GUEST) return false
        val conn = client?.conn ?: return false
        if (conn.closed || anchor == null) return false
        val p = player ?: return false
        val msg = JSONObject().put("t", GroupProtocol.T_CMD)
        when (cmd) {
            "toggle" -> msg.put("a", if (p.isPlaying) "pause" else "play")
            "seek" -> msg.put("a", "seek").put("pos", posMs)
            else -> msg.put("a", cmd)
        }
        client?.send(msg)
        return true
    }

    // ------------------------------------------------------------------ 发现 / 配对

    fun startDiscovery() {
        if (role != GroupRole.NONE) return
        if (!serviceReady()) return
        phase = GroupPhase.DISCOVERING
        acquireLocks()
        val discovery = NsdDiscovery(appContext).apply {
            onFound = { id, name, model, host, port ->
                if (id != selfId) main.post {
                    if (role == GroupRole.NONE) {
                        val peer = GroupPeer(
                            id = id, name = name, model = model,
                            address = "$host:$port", hostIp = host, hostPort = port, online = true
                        )
                        _state.update { st ->
                            st.copy(
                                phase = GroupPhase.DISCOVERING,
                                hosts = st.hosts.filterNot { it.id == id } + peer
                            )
                        }
                    }
                    // 已在配对/入组时忽略新发现
                }
            }
            onLost = { id ->
                main.post {
                    _state.update { it.copy(hosts = it.hosts.filterNot { h -> h.id == id }) }
                }
            }
        }
        nsd = discovery
        discovery.startDiscovery()
        _state.update {
            it.copy(role = GroupRole.NONE, phase = GroupPhase.DISCOVERING, message = "正在查找附近设备")
        }
    }

    fun stopDiscovery() {
        if (!serviceReady()) return
        nsd?.stopDiscovery()
        nsd = null
        if (role == GroupRole.NONE) {
            releaseLocks()
            phase = GroupPhase.IDLE
            _state.update { it.copy(phase = GroupPhase.IDLE, hosts = emptyList()) }
        }
    }

    fun createGroup() {
        if (role != GroupRole.NONE) return
        if (!serviceReady()) return
        stopDiscovery()
        hostToken = randomToken()
        cache = TrackCache(appContext)
        acquireLocks()
        role = GroupRole.HOST
        phase = GroupPhase.CONNECTED
        hostClock = ClockSync().apply {
            if (!startHost()) GroupLog.log("host", "UDP 对时端口 ${GroupProtocol.UDP_PORT} 启动失败")
        }
        GroupLog.log("host", "创建组合 name=$selfName tcp=${GroupProtocol.TCP_PORT} udp=${GroupProtocol.UDP_PORT}")

        val srv = GroupServer { socket, reader, first -> handleAccept(socket, reader, first) }
        if (!srv.start()) {
            role = GroupRole.NONE
            phase = GroupPhase.IDLE
            releaseLocks()
            GroupLog.log("host", "TCP ${GroupProtocol.TCP_PORT} 监听失败")
            _state.update { it.copy(message = "端口 ${GroupProtocol.TCP_PORT} 被占用，无法创建组合") }
            return
        }
        server = srv
        GroupLog.log("host", "TCP 监听已就绪 port=${srv.port}")
        val serviceName = "${GroupProtocol.SERVICE_PREFIX}${selfName}-${selfId.takeLast(4)}"
        nsd = NsdDiscovery(appContext).apply {
            register(serviceName, srv.port, selfId, Build.MODEL, selfName)
        }
        startHostTick()
        _state.update {
            it.copy(
                role = GroupRole.HOST,
                phase = GroupPhase.CONNECTED,
                selfName = selfName,
                peers = emptyList(),
                hosts = emptyList(),
                connectedCount = 0,
                outputLatencyMs = localLatency,
                calibrationOffsetMs = localLatency,
                message = "等待其他设备加入"
            )
        }
    }

    fun acceptPeer(id: String) {
        val conn = pending[id] ?: return
        acceptPeerInternal(conn)
    }

    fun denyPeer(id: String) {
        val conn = pending.remove(id) ?: return
        conn.send(JSONObject().put("t", GroupProtocol.T_PAIR_DENIED))
        conn.close()
        _state.update { it.copy(pendingPeerId = null, pendingPeerName = null) }
    }

    fun joinHost(id: String, name: String, host: String, port: Int) {
        if (role != GroupRole.NONE) return
        if (!serviceReady()) return
        stopDiscovery()
        role = GroupRole.GUEST
        phase = GroupPhase.PAIRING
        hostIp = host
        hostPort = port
        hostName = name
        hostIdle = false
        clockFailReported = false
        helloAttempts = 0
        resetStage("connect")
        GroupLog.log("join", "加入主机 name=$name addr=$host:$port id=$id")
        cache = TrackCache(appContext)
        acquireLocks()
        refreshLatency()
        guestClock = ClockSync().apply {
            onUpdate = { offset, rtt ->
                main.post {
                    if (role != GroupRole.GUEST) return@post
                    _state.update { it.copy(clockOffsetMs = offset, rttMs = rtt) }
                    if (clockFailReported) {
                        clockFailReported = false
                        GroupLog.log("clock", "收到对时样本，时钟同步已恢复")
                        if (phase == GroupPhase.DEGRADED) {
                            phase = if (anchor != null) GroupPhase.SYNCING else GroupPhase.CONNECTED
                            _state.update {
                                it.copy(
                                    phase = phase,
                                    message = "时钟同步已恢复",
                                    canRetry = false,
                                    lastError = null
                                )
                            }
                        }
                    }
                }
            }
            startGuest(host)
        }
        _state.update {
            it.copy(
                role = GroupRole.GUEST,
                phase = GroupPhase.PAIRING,
                hostName = name,
                selfName = selfName,
                hosts = emptyList(),
                outputLatencyMs = localLatency,
                calibrationOffsetMs = localLatency,
                resolvedHost = host,
                resolvedPort = port,
                tcpState = "未连接",
                message = "正在连接 $name"
            )
        }
        connect()
        startGuestTick()
    }

    fun connectManual(ip: String, port: Int = GroupProtocol.TCP_PORT) {
        if (role != GroupRole.NONE) return
        if (!serviceReady()) return
        prefs?.groupLastHost = "$ip:$port"
        joinHost("manual-$ip", ip, ip, port)
    }

    fun leave(message: String? = null) {
        if (role == GroupRole.NONE) {
            _state.update { it.copy(message = message) }
            return
        }
        val wasGuest = role == GroupRole.GUEST
        peers.values.forEach {
            it.send(JSONObject().put("t", GroupProtocol.T_BYE))
            it.close()
        }
        client?.send(JSONObject().put("t", GroupProtocol.T_BYE))
        client?.close()
        client = null
        peers.clear()
        pending.clear()
        accepted.clear()
        peerSync.clear()
        server?.stop(); server = null
        nsd?.stopDiscovery(); nsd?.unregister(); nsd = null
        hostClock?.stop(); hostClock = null
        guestClock?.stop(); guestClock = null
        hostTick?.let { main.removeCallbacks(it) }; hostTick = null
        guestTick?.let { main.removeCallbacks(it) }; guestTick = null
        reconnectJob?.cancel(); reconnectJob = null
        downloadJob?.cancel(); downloadJob = null
        prefetchJob?.cancel(); prefetchJob = null
        prefetchKey = null
        calibrator?.stop(); calibrator = null
        calibrationActive = false
        releaseLocks()
        if (wasGuest) {
            val c = cache
            onMain {
                val p = player
                p?.setPlaybackParameters(PlaybackParameters(1f, 1f))
                p?.stop()
                p?.clearMediaItems()
                c?.clear()
            }
        } else {
            cache?.clear()
        }
        cache = null
        registry.clear()
        anchor = null
        currentKey = null
        preparedKey = null
        downloadingKey = null
        prefetchKey = null
        hadPeers = false
        connecting = false
        hostIdle = false
        clockFailReported = false
        helloAttempts = 0
        stageName = ""
        stageStartMs = 0L
        prepareStartedMs = 0L
        role = GroupRole.NONE
        phase = GroupPhase.IDLE
        GroupLog.log("leave", message ?: "退出组合")
        _state.value = GroupUiState(
            selfName = selfName,
            outputLatencyMs = localLatency,
            calibrationOffsetMs = localLatency,
            calibrationRoute = calibrationRoute,
            message = message
        )
    }

    // ------------------------------------------------------------------ 校准

    fun startCalibration() {
        if (role == GroupRole.NONE) return
        refreshLatency()
        if (role == GroupRole.GUEST) {
            client?.send(JSONObject().put("t", GroupProtocol.T_CAL_START).put("request", true))
            _state.update { it.copy(message = "已请求主机开始校准") }
            return
        }
        hostStartCalibration()
    }

    /** 主机发起校准：本机先起脉冲，再把基准时刻下发给所有从机 */
    private fun hostStartCalibration() {
        beginCalibration(GroupProtocol.nowNanos() + 2_000_000_000L)
        peers.values.forEach { conn ->
            conn.send(
                JSONObject().put("t", GroupProtocol.T_CAL_START)
                    .put("clock", calibrationStartNanos)
                    .put("hostLat", localLatency)
            )
        }
    }

    fun stopCalibration() {
        calibrationActive = false
        calibrator?.stop(); calibrator = null
        if (role == GroupRole.HOST) {
            peers.values.forEach { it.send(JSONObject().put("t", GroupProtocol.T_CAL_STOP)) }
        }
        _state.update { it.copy(calibrationActive = false) }
    }

    /** 滑块：直接设定本机输出延迟估计（毫秒） */
    fun setCalibrationLatency(ms: Long) {
        localLatency = ms.coerceIn(0L, 400L)
        prefs?.setGroupOutputLatency(Build.MODEL, calibrationRoute, localLatency)
        _state.update {
            it.copy(
                outputLatencyMs = localLatency,
                calibrationOffsetMs = localLatency,
                calibrationRoute = calibrationRoute
            )
        }
        if (calibrationActive) schedulePulse()
        val report = JSONObject().put("t", "calOffset").put("ms", localLatency)
        if (role == GroupRole.HOST) peers.values.forEach { it.send(report) } else client?.send(report)
    }

    private fun beginCalibration(hostStartNanos: Long) {
        calibrationStartNanos = hostStartNanos
        calibrationActive = true
        // 主机的时间基准就是自己：把 hostLatency 对齐到本机，脉冲相位差为 0
        if (role == GroupRole.HOST) hostLatency = localLatency
        if (calibrator == null) calibrator = PulseCalibrator(appContext)
        main.post { player?.pause() }
        schedulePulse()
        _state.update {
            it.copy(
                calibrationActive = true,
                calibrationStartNanos = hostStartNanos,
                calibrationOffsetMs = localLatency,
                calibrationRoute = calibrationRoute
            )
        }
    }

    /** 按 600ms 网格排下一次脉冲，滑块改变只移动相位、不破坏分钟级的稳定 */
    private fun schedulePulse() {
        val cal = calibrator ?: return
        val offsetNanos = guestClock?.offsetNanos ?: 0L
        val refLocal = calibrationStartNanos - offsetNanos
        // 输出延迟大的设备要**提前**发声，声音才会同时到达耳朵：
        // 可听时刻 = startAt + localLat，要求两边相等 ⇒ startAt 差值 = hostLat - localLat
        val deltaNanos = (hostLatency - localLatency) * 1_000_000L
        val now = GroupProtocol.nowNanos()
        var next = refLocal
        val beat = GroupProtocol.CAL_BEAT_MS * 1_000_000L
        while (next < now + 400_000_000L) next += beat
        cal.startAt(next + deltaNanos)
    }

    // ------------------------------------------------------------------ 主机逻辑

    private fun handleAccept(socket: Socket, reader: java.io.BufferedReader, first: JSONObject) {
        if (first.optString("t") == GroupProtocol.T_FILE_REQ) {
            // 文件连接是一次性的：传完必须关掉，否则每传一首就漏一个 socket
            serveFile(socket, first)
            runCatching { socket.close() }
            return
        }
        val conn = PeerConn(socket, reader, first.toString())
        conn.onMessage = { msg -> main.post { handleHostMessage(conn, msg) } }
        conn.onClosed = { main.post { onHostPeerClosed(conn) } }
        main.post { handleHostMessage(conn, first) }
    }

    private fun handleHostMessage(conn: PeerConn, msg: JSONObject) {
        when (msg.optString("t")) {
            GroupProtocol.T_HELLO -> {
                conn.id = msg.optString("id")
                conn.name = msg.optString("name").ifBlank { "Android" }
                conn.model = msg.optString("model")
                conn.outputLatencyMs = msg.optLong("lat", 0L)
                GroupLog.log("host", "收到 hello id=${conn.id} name=${conn.name}")
                if (conn.id.isBlank()) {
                    GroupLog.log("host", "hello 缺少设备 id，断开")
                    conn.close()
                    return
                }
                if (accepted.contains(conn.id)) {
                    acceptPeerInternal(conn)
                } else {
                    // 同名旧连接顶掉，避免重连时堆积
                    pending[conn.id] = conn
                    _state.update {
                        it.copy(
                            pendingPeerId = conn.id,
                            pendingPeerName = conn.name,
                            phase = GroupPhase.CONNECTED
                        )
                    }
                }
            }

            GroupProtocol.T_CMD -> {
                val p = player ?: return
                when (msg.optString("a")) {
                    "play" -> p.play()
                    "pause" -> p.pause()
                    "seek" -> p.seekTo(msg.optLong("pos", 0L).coerceAtLeast(0L))
                    "next" -> p.seekToNext()
                    "prev" -> p.seekToPrevious()
                }
                broadcastAnchorNow()
            }

            "sync" -> {
                peerSync[conn.id] = msg.optLongOrNull("err") to msg.optLongOrNull("rtt")
                publishPeers()
            }

            "calOffset" -> {
                conn.outputLatencyMs = msg.optLong("ms", conn.outputLatencyMs)
                publishPeers()
            }

            GroupProtocol.T_PONG, GroupProtocol.T_PING -> Unit

            GroupProtocol.T_CAL_START -> {
                // 从机点「播放校准脉冲」：由主机统一发基准时刻，否则两台设备各打各的节拍对不上
                if (msg.optBoolean("request")) hostStartCalibration()
            }

            GroupProtocol.T_CAL_STOP -> {
                // 从机停止，主机保持自己的脉冲；从机只影响自己
            }

            GroupProtocol.T_BYE -> {
                removePeer(conn.id)
            }
        }
    }

    private fun acceptPeerInternal(conn: PeerConn) {
        pending.remove(conn.id)
        accepted.add(conn.id)
        peers[conn.id] = conn
        hadPeers = true
        GroupLog.log("host", "允许加入 id=${conn.id} name=${conn.name}")
        conn.send(
            JSONObject().put("t", GroupProtocol.T_PAIR_OK)
                .put("token", hostToken)
                .put("hostName", selfName)
                .put("hostLat", localLatency)
                .put("clock", GroupProtocol.nowNanos())
        )
        val next = pending.values.firstOrNull()
        _state.update {
            it.copy(
                pendingPeerId = next?.id,
                pendingPeerName = next?.name,
                phase = GroupPhase.SYNCING,
                message = null
            )
        }
        publishPeers()
        broadcastAnchorNow()
    }

    private fun onHostPeerClosed(conn: PeerConn) {
        if (pending.remove(conn.id) != null) {
            val next = pending.values.firstOrNull()
            _state.update { it.copy(pendingPeerId = next?.id, pendingPeerName = next?.name) }
            return
        }
        if (peers.containsKey(conn.id)) removePeer(conn.id)
    }

    private fun removePeer(id: String) {
        peers.remove(id)?.let { it.close() }
        peerSync.remove(id)
        publishPeers()
        if (peers.isEmpty() && hadPeers) {
            hadPeers = false
            leave("组内已无设备，已回到单机播放")
        }
    }

    private fun startHostTick() {
        hostTick?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (role != GroupRole.HOST) return
                val now = GroupProtocol.nowMs()
                if (now - lastRouteCheckMs > 2000L) {
                    lastRouteCheckMs = now
                    val route = PulseCalibrator.currentRoute(appContext)
                    if (route != routeCached) {
                        refreshLatency()
                        _state.update {
                            it.copy(message = "输出设备已变化，建议重新校准")
                        }
                    }
                }
                if (now - lastAnchorMs >= 500L) {
                    lastAnchorMs = now
                    broadcastAnchorNow()
                }
                checkPeerTimeout(now)
                main.postDelayed(this, 500L)
            }
        }
        hostTick = tick
        main.postDelayed(tick, 500L)
    }

    private fun checkPeerTimeout(now: Long) {
        val dead = peers.values.filter { now - it.lastSeenMs > GroupProtocol.PEER_DROP_MS }
        dead.forEach { removePeer(it.id) }
        publishPeers()
    }

    private fun broadcastAnchorNow() {
        if (role != GroupRole.HOST) return
        val a = buildAnchor()
        if (a == null) {
            // 主机没有 currentMediaItem：明确告知从机「没有可同步的曲目」，
            // 否则从机只能一直显示「正在同步」，完全看不出原因。
            if (peers.isNotEmpty()) {
                val idle = JSONObject()
                    .put("t", GroupProtocol.T_IDLE)
                    .put("reason", "主机当前没有正在播放的曲目")
                peers.values.forEach { it.send(idle) }
                if (!hostIdleNotified) {
                    hostIdleNotified = true
                    GroupLog.log("host", "当前没有可播放曲目，已通知 ${peers.size} 台从机")
                }
            }
            _state.update {
                it.copy(connectedCount = peers.size, phase = GroupPhase.CONNECTED)
            }
            return
        }
        hostIdleNotified = false
        val json = anchorJson(a)
        val now = GroupProtocol.nowMs()
        peers.values.forEach { conn ->
            conn.send(JSONObject().put("t", GroupProtocol.T_PING))
            conn.send(json)
            // 心跳本身也计入 lastSeen 的「对方还活着」判断由 pong 负责；
            // 这里只保证对方每 500ms 有东西可收，断线能被 TCP 感知。
        }
        publishPeers()
        _state.update { st ->
            st.copy(
                trackKey = a.key,
                trackTitle = a.title,
                trackArtist = a.artist,
                trackAlbum = a.album,
                trackDurationMs = a.durationMs,
                connectedCount = peers.size,
                phase = if (peers.isEmpty()) GroupPhase.CONNECTED else GroupPhase.SYNCING,
                syncErrorMs = if (role == GroupRole.HOST) 0L else st.syncErrorMs
            )
        }
    }

    private fun buildAnchor(): Anchor? {
        val p = player ?: return null
        val item = p.currentMediaItem ?: return null
        ensureRegistry()
        val key = item.mediaId.toString()
        val entry = registry[key]
        val md = item.mediaMetadata
        val nextEntry = if (p.currentMediaItemIndex + 1 < p.mediaItemCount) {
            registry[p.getMediaItemAt(p.currentMediaItemIndex + 1).mediaId.toString()]
        } else null
        return Anchor(
            key = key,
            title = md.title?.toString()?.ifBlank { "未知曲目" } ?: "未知曲目",
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            durationMs = when {
                p.duration != C.TIME_UNSET && p.duration > 0 -> p.duration
                else -> md.durationMs ?: 0L
            },
            size = entry?.size ?: 0L,
            mime = entry?.mime.orEmpty(),
            ext = entry?.ext.orEmpty(),
            posMs = p.currentPosition.coerceAtLeast(0L),
            clockNanos = GroupProtocol.nowNanos(),
            hostLatencyMs = localLatency,
            playing = p.playWhenReady,
            unsupported = entry == null || entry.size <= 0L,
            next = nextEntry
        )
    }

    private fun anchorJson(a: Anchor): JSONObject = JSONObject().apply {
        put("t", GroupProtocol.T_ANCHOR)
        put("key", a.key)
        put("title", a.title)
        put("artist", a.artist)
        put("album", a.album)
        put("dur", a.durationMs)
        put("size", a.size)
        put("mime", a.mime)
        put("ext", a.ext)
        put("pos", a.posMs)
        put("clock", a.clockNanos)
        put("playing", a.playing)
        put("hostLat", a.hostLatencyMs)
        put("unsupported", a.unsupported)
        a.next?.let { n ->
            put("nkey", n.key)
            put("ntitle", n.title)
            put("nartist", n.artist)
            put("ndur", n.durationMs)
            put("nsize", n.size)
            put("nmime", n.mime)
            put("nextExt", n.ext)
        }
    }

    private fun ensureRegistry() {
        val p = player ?: return
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: continue
            val key = item.mediaId.toString()
            if (registry.containsKey(key)) continue
            val scheme = uri.scheme ?: continue
            if (scheme != "content" && scheme != "file") continue
            val size = sizeOf(uri)
            if (size <= 0L) continue
            val mime = appContext.contentResolver.getType(uri).orEmpty()
            val md = item.mediaMetadata
            registry[key] = TrackEntry(
                key = key,
                uri = uri,
                mime = mime,
                ext = extOf(uri, mime),
                size = size,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(),
                durationMs = md.durationMs ?: 0L
            )
        }
    }

    private fun serveFile(socket: Socket, req: JSONObject) {
        try {
            val okToken = req.optString("token") == hostToken && hostToken.isNotBlank()
            val key = req.optString("key")
            val entry = if (okToken) registry[key] else null
            val offset = req.optLong("offset", 0L)
            val ok = entry != null && offset in 0L..entry.size
            val reason = when {
                !okToken -> "token 校验失败"
                entry == null -> "主机未登记该曲目"
                else -> ""
            }
            val out = socket.getOutputStream()
            val head = JSONObject().put("t", GroupProtocol.T_FILE_HEAD)
                .put("ok", ok)
                .put("size", entry?.size ?: 0L)
                .put("offset", if (ok) offset else 0L)
                .put("mime", entry?.mime.orEmpty())
                .put("reason", reason)
            out.write((head.toString() + "\n").toByteArray())
            out.flush()
            if (!ok || entry == null) {
                GroupLog.log("file", "拒绝取文件 key=$key reason=$reason")
                return
            }
            val input = openInput(entry.uri)
            if (input == null) {
                GroupLog.log("file", "主机读不了文件 key=$key uri=${entry.uri.scheme}")
                return
            }
            GroupLog.log("file", "开始推送 key=$key size=${entry.size} offset=$offset")
            input.use {
                var remaining = offset
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = it.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) return
                    remaining -= n
                }
                var sent = offset
                while (sent < entry.size) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
                out.flush()
            }
        } catch (_: Exception) {
            // 对端主动断开、文件被删除等：会话继续，下一次锚点会重新发起传输
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ 从机逻辑

    private fun connect() {
        if (role != GroupRole.GUEST) return
        if (connecting) return
        val c = GroupClient(hostIp, hostPort)
        connecting = true
        GroupLog.log("tcp", "开始连接 $hostIp:$hostPort")
        scope.launch {
            // Socket.connect 是阻塞调用，绝不能放在主线程：真机冷启动时可能卡 3 秒
            val conn = withContext(Dispatchers.IO) { c.connect() }
            withContext(Dispatchers.Main) {
                connecting = false
                if (role != GroupRole.GUEST) {
                    conn?.close()
                    return@withContext
                }
                if (conn == null) {
                    GroupLog.log("tcp", "连接失败 $hostIp:$hostPort（超时或被拒绝）")
                    _state.update { it.copy(tcpState = "连接失败 $hostIp:$hostPort") }
                    degrade("无法连接对方设备（网络不可达），正在重试")
                    scheduleReconnect()
                    return@withContext
                }
                GroupLog.log("tcp", "连接成功 $hostIp:$hostPort")
                client = c
                conn.onMessage = { msg -> main.post { handleGuestMessage(msg) } }
                conn.onClosed = {
                    main.post {
                        if (role == GroupRole.GUEST) {
                            GroupLog.log("tcp", "与主机的连接已断开")
                            degrade("与主机的连接已断开，正在重连")
                            scheduleReconnect()
                        }
                    }
                }
                _state.update {
                    it.copy(
                        tcpState = "已连接 $hostIp:$hostPort",
                        message = "已连上 ${hostName}，等待对方确认加入"
                    )
                }
                // 首次连上（还没拿到过锚点）回到配对阶段，让超时看门狗重新计时
                if (anchor == null) {
                    phase = GroupPhase.PAIRING
                    _state.update { it.copy(phase = GroupPhase.PAIRING) }
                }
                resetStage("pair")
                helloAttempts = 0
                sendHello()
            }
        }
    }

    /** hello 可能因为主机刚建连时首包被吞，配对阶段允许重发 */
    private fun sendHello() {
        val conn = client?.conn ?: return
        if (conn.closed) return
        helloSentMs = GroupProtocol.nowMs()
        helloAttempts += 1
        GroupLog.log("pair", "发送 hello #$helloAttempts id=$selfId")
        conn.send(
            JSONObject().put("t", GroupProtocol.T_HELLO)
                .put("id", selfId)
                .put("name", selfName)
                .put("model", Build.MODEL)
                .put("lat", localLatency)
        )
        lastPingMs = GroupProtocol.nowMs()
    }

    private fun handleGuestMessage(msg: JSONObject) {
        lastPingMs = GroupProtocol.nowMs()
        when (msg.optString("t")) {
            GroupProtocol.T_PAIR_OK -> {
                token = msg.optString("token")
                hostName = msg.optString("hostName", hostName)
                hostLatency = msg.optLong("hostLat", hostLatency)
                phase = GroupPhase.CONNECTED
                hostIdle = false
                reconnectAttempts = 0
                reconnectStartedMs = 0L
                reconnectJob?.cancel()
                reconnectJob = null
                val handshakeMs = if (helloSentMs > 0L) GroupProtocol.nowMs() - helloSentMs else null
                GroupLog.log("pair", "收到 pairOk host=$hostName 握手耗时=${handshakeMs}ms")
                resetStage("anchor")
                _state.update {
                    it.copy(
                        phase = GroupPhase.CONNECTED,
                        hostName = hostName,
                        hostLatencyMs = hostLatency,
                        handshakeRttMs = handshakeMs,
                        message = null,
                        canRetry = false,
                        lastError = null
                    )
                }
            }

            GroupProtocol.T_PAIR_DENIED -> {
                leave("主机拒绝了加入请求")
            }

            GroupProtocol.T_ANCHOR -> {
                val a = parseAnchor(msg)
                anchor = a
                hostLatency = a.hostLatencyMs
                hostIdle = false
                _state.update {
                    it.copy(
                        hostLatencyMs = a.hostLatencyMs,
                        trackKey = a.key,
                        trackTitle = a.title,
                        trackArtist = a.artist,
                        trackAlbum = a.album,
                        trackDurationMs = a.durationMs,
                        phase = GroupPhase.SYNCING
                    )
                }
                if (a.unsupported) {
                    GroupLog.log("anchor", "主机曲目不可同步 key=${a.key} size=${a.size}")
                    remoteApplying = true
                    player?.pause()
                    remoteApplying = false
                    _state.update {
                        it.copy(
                            message = "主机当前曲目是在线流或本机读不到，无法同步到本机",
                            lastError = "主机曲目不可同步（size=${a.size}）",
                            lastErrorAtMs = GroupProtocol.nowMs()
                        )
                    }
                    return
                }
                if (currentKey != a.key) {
                    currentKey = a.key
                    preparedKey = null
                    prepareStartedMs = 0L
                    resetStage("sync")
                    GroupLog.log("anchor", "收到新锚点 key=${a.key} size=${a.size} pos=${a.posMs} playing=${a.playing}")
                    downloadJob?.cancel()
                    prefetchJob?.cancel()
                    prefetchJob = null
                    prefetchKey = null
                    // 立刻掐掉旧曲目：否则下载新歌的这段时间里，从机会继续放一首和主机不对应的歌
                    if (player?.isPlaying == true) {
                        remoteApplying = true
                        player?.pause()
                        remoteApplying = false
                    }
                    startDownload(a)
                } else if (preparedKey == a.key) {
                    applyPlayIntent(a)
                    prefetch(a)
                }
            }

            GroupProtocol.T_IDLE -> {
                // 主机在播但当前没有可同步的曲目：不算同步失败，给出明确文案
                hostIdle = true
                anchor = null
                if (phase != GroupPhase.DEGRADED) phase = GroupPhase.CONNECTED
                _state.update {
                    it.copy(
                        phase = phase,
                        message = msg.optString("reason")
                            .ifBlank { "主机当前没有正在播放的曲目" },
                        canRetry = false
                    )
                }
            }

            GroupProtocol.T_PING -> {
                client?.send(JSONObject().put("t", GroupProtocol.T_PONG))
            }

            GroupProtocol.T_CAL_START -> {
                if (msg.optBoolean("request")) {
                    if (role == GroupRole.HOST) {
                        beginCalibration(GroupProtocol.nowNanos() + 2_000_000_000L)
                        peers.values.forEach { peer ->
                            peer.send(
                                JSONObject().put("t", GroupProtocol.T_CAL_START)
                                    .put("clock", calibrationStartNanos)
                                    .put("hostLat", localLatency)
                            )
                        }
                    }
                } else {
                    hostLatency = msg.optLong("hostLat", hostLatency)
                    beginCalibration(msg.optLong("clock", GroupProtocol.nowNanos()))
                }
            }

            GroupProtocol.T_CAL_STOP -> stopCalibration()

            GroupProtocol.T_BYE -> onHostLeft()

            GroupProtocol.T_PONG -> Unit

            else -> Unit
        }
    }

    private fun onHostLeft() {
        if (role != GroupRole.GUEST) return
        // 主机主动结束组合：立刻回到单机播放，不再等重连
        leave("主机已结束组合，已回到单机播放")
    }

    private fun degrade(message: String) {
        phase = GroupPhase.DEGRADED
        _state.update {
            it.copy(
                phase = GroupPhase.DEGRADED,
                message = message,
                lastError = message,
                lastErrorAtMs = GroupProtocol.nowMs(),
                canRetry = role == GroupRole.GUEST
            )
        }
    }

    private fun resetStage(name: String) {
        stageName = name
        stageStartMs = GroupProtocol.nowMs()
    }

    /** 某一步卡超时：落成 DEGRADED + 可读原因 + 「重试连接」入口，而不是无限等待 */
    private fun failStage(reason: String) {
        GroupLog.log("timeout", "阶段[$stageName] 超时 → $reason")
        phase = GroupPhase.DEGRADED
        _state.update {
            it.copy(
                phase = GroupPhase.DEGRADED,
                message = reason,
                lastError = reason,
                lastErrorAtMs = GroupProtocol.nowMs(),
                canRetry = role == GroupRole.GUEST
            )
        }
    }

    /** 看门狗：按阶段检查是否已卡死（只负责「报错」，不自动拆组） */
    private fun checkGuestStage(now: Long) {
        if (role != GroupRole.GUEST) return
        if (stageName.isEmpty()) return
        val elapsed = now - stageStartMs
        when (phase) {
            GroupPhase.PAIRING -> {
                if (stageName == "pair" && elapsed > GroupProtocol.PAIR_TIMEOUT_MS) {
                    failStage("对方未确认加入（请在主机上点「允许」；若已允许，多为 47801 端口不通）")
                }
            }

            GroupPhase.CONNECTED -> {
                if (!hostIdle && stageName == "anchor" && elapsed > GroupProtocol.ANCHOR_TIMEOUT_MS) {
                    failStage("主机未下发播放状态（可能没有正在播放的曲目，或 TCP 数据被拦截）")
                }
            }

            GroupPhase.SYNCING -> {
                val clock = guestClock
                if (clock != null && !clock.hasSample && !clockFailReported &&
                    elapsed > GroupProtocol.CLOCK_TIMEOUT_MS
                ) {
                    clockFailReported = true
                    failStage("时钟同步失败（主机 UDP ${GroupProtocol.UDP_PORT} 可能被系统或路由器拦截）")
                } else if (preparedKey != null && prepareStartedMs > 0L &&
                    now - prepareStartedMs > GroupProtocol.READY_TIMEOUT_MS &&
                    player?.playbackState != Player.STATE_READY
                ) {
                    failStage("本机无法打开该曲目（文件异常或解码失败）")
                }
            }

            else -> Unit
        }
    }

    /** 失败后的手动重试：连接还在就重发 hello，不在就重新建 TCP */
    fun retryJoin() {
        if (role != GroupRole.GUEST) return
        GroupLog.log("join", "用户点击重试连接")
        clockFailReported = false
        hostIdle = false
        hostLatency = 0L
        canRetryReset()
        val conn = client?.conn
        if (conn != null && !conn.closed) {
            phase = GroupPhase.PAIRING
            resetStage("pair")
            helloAttempts = 0
            _state.update {
                it.copy(
                    phase = GroupPhase.PAIRING,
                    message = "正在重新握手…",
                    canRetry = false,
                    lastError = null
                )
            }
            sendHello()
        } else {
            client = null
            phase = GroupPhase.PAIRING
            resetStage("connect")
            _state.update {
                it.copy(
                    phase = GroupPhase.PAIRING,
                    message = "正在重新连接 $hostName",
                    canRetry = false,
                    lastError = null
                )
            }
            connect()
        }
    }

    private fun canRetryReset() {
        _state.update { it.copy(canRetry = false) }
        if (phase == GroupPhase.DEGRADED) phase = GroupPhase.PAIRING
    }

    /** 导出诊断日志（cacheDir/group_logs），UI 再通过 FileProvider 分享出去 */
    fun exportDiagLog(): File? {
        if (!serviceReady()) return null
        GroupLog.log("diag", "导出诊断日志")
        return GroupLog.export(appContext)
    }

    private fun scheduleReconnect() {
        if (role != GroupRole.GUEST) return
        if (reconnectJob?.isActive == true) return
        if (reconnectStartedMs == 0L) reconnectStartedMs = GroupProtocol.nowMs()
        reconnectJob = scope.launch {
            while (role == GroupRole.GUEST && reconnectAttempts < 6) {
                delay(2000L)
                reconnectAttempts += 1
                val current = client?.conn
                if (current != null && !current.closed) return@launch
                withContext(Dispatchers.Main) {
                    if (role == GroupRole.GUEST) {
                        client?.close()
                        client = null
                        connect()
                    }
                }
            }
            if (role == GroupRole.GUEST && (client?.conn?.closed != false)) {
                withContext(Dispatchers.Main) {
                    leave("主机长时间无响应，已回到单机播放")
                }
            }
        }
    }

    private fun startGuestTick() {
        guestTick?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (role != GroupRole.GUEST) return
                val now = GroupProtocol.nowMs()
                val route = PulseCalibrator.currentRoute(appContext)
                if (now - lastRouteCheckMs > 2000L) {
                    lastRouteCheckMs = now
                    if (route != routeCached) {
                        refreshLatency()
                        _state.update {
                            it.copy(outputLatencyMs = localLatency, message = "输出设备已变化，建议重新校准")
                        }
                    }
                }
                if (now - lastPingMs > 3000L && phase != GroupPhase.DEGRADED && anchor != null) {
                    degrade("与主机连接不稳定，已暂停")
                    player?.pause()
                }
                if (now - lastPingMs > 8000L && phase == GroupPhase.DEGRADED) {
                    scheduleReconnect()
                }
                // 配对阶段 hello 重发：主机可能在建连瞬间丢掉首包
                if (phase == GroupPhase.PAIRING &&
                    helloAttempts in 1..GroupProtocol.HELLO_MAX_ATTEMPTS &&
                    now - helloSentMs > GroupProtocol.HELLO_RETRY_MS
                ) {
                    sendHello()
                }
                checkGuestStage(now)
                // 心跳 500ms；1.5 秒收不到任何消息就把主机标灰
                if (now - lastPeerUiMs > 400L) {
                    lastPeerUiMs = now
                    publishPeers()
                }
                syncTick(now)
                main.postDelayed(this, 150L)
            }
        }
        guestTick = tick
        main.postDelayed(tick, 150L)
    }

    private fun syncTick(now: Long) {
        val p = player ?: return
        val a = anchor ?: return
        if (a.key != preparedKey) return
        if (a.unsupported) return

        if (p.playbackState != Player.STATE_READY) {
            p.setPlaybackParameters(PlaybackParameters(1f, 1f))
            return
        }

        if (!a.playing) {
            p.setPlaybackParameters(PlaybackParameters(1f, 1f))
            if (p.isPlaying) {
                remoteApplying = true
                p.pause()
                remoteApplying = false
            }
            _state.update { it.copy(syncErrorMs = null) }
            return
        }

        val clock = guestClock ?: return
        // 首次对时样本还没到：此时 offset=0，跨设备的 elapsedRealtime 差好几个小时，
        // 千万不能拿去 align / 起播，否则会从 0 秒开始放、并且和主机完全错位。
        if (!clock.hasSample) return

        if (!p.isPlaying && !remoteApplying) {
            remoteApplying = true
            p.play()
            remoteApplying = false
        }

        val hostNow = clock.hostNowNanos()
        val timeline = a.timelineMs(hostNow)
        val target = timeline + (localLatency - a.hostLatencyMs)
        val err = target - p.currentPosition

        if (now - lastUiMs > 400L) {
            lastUiMs = now
            _state.update { it.copy(syncErrorMs = err, clockOffsetMs = GroupProtocol.nanosToMs(clock.offsetNanos), rttMs = GroupProtocol.nanosToMs(clock.rttNanos)) }
        }
        if (now - lastSyncReportMs > 2000L) {
            lastSyncReportMs = now
            client?.send(
                JSONObject().put("t", "sync").put("err", err)
                    .put("rtt", GroupProtocol.nanosToMs(clock.rttNanos))
            )
        }

        val absErr = abs(err)
        when {
            absErr > GroupProtocol.SYNC_HARD_SEEK_MS -> {
                remoteApplying = true
                p.seekTo(target.coerceAtLeast(0L))
                p.setPlaybackParameters(PlaybackParameters(1f, 1f))
                remoteApplying = false
            }

            absErr > GroupProtocol.SYNC_SOFT_TRIM_MS -> {
                val delta = (err.toFloat() / 2000f)
                    .coerceIn(-GroupProtocol.SYNC_MAX_RATE_DELTA, GroupProtocol.SYNC_MAX_RATE_DELTA)
                val desired = 1f + delta
                if (abs(p.playbackParameters.speed - desired) > 0.002f) {
                    p.setPlaybackParameters(PlaybackParameters(desired, 1f))
                }
            }

            absErr <= GroupProtocol.SYNC_TRIM_RESET_MS -> {
                if (abs(p.playbackParameters.speed - 1f) > 0.002f) {
                    p.setPlaybackParameters(PlaybackParameters(1f, 1f))
                }
            }
        }
    }

    private fun applyPlayIntent(a: Anchor) {
        val p = player ?: return
        if (p.playbackState != Player.STATE_READY) return
        remoteApplying = true
        if (a.playing && !p.isPlaying) p.play()
        if (!a.playing && p.isPlaying) p.pause()
        remoteApplying = false
    }

    private fun startDownload(a: Anchor) {
        if (downloadingKey == a.key && downloadJob?.isActive == true) return
        downloadingKey = a.key
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update { it.copy(transferProgress = 0f, transferTitle = a.title) }
            val ok = downloadTrack(a, silent = false)
            withContext(Dispatchers.Main) {
                downloadingKey = null
                _state.update { it.copy(transferProgress = null, transferTitle = null) }
                if (ok) {
                    val f = cache?.doneFile(a.key, a.ext)
                    if (f != null && currentKey == a.key) {
                        prepareMedia(a, f)
                    }
                } else if (role == GroupRole.GUEST && currentKey == a.key) {
                    GroupLog.log("file", "曲目传输失败 key=${a.key}")
                    _state.update {
                        it.copy(
                            message = "曲目传输失败（${a.size / 1024} KB），可点「重试连接」重新拉取",
                            lastError = "曲目传输失败",
                            lastErrorAtMs = GroupProtocol.nowMs()
                        )
                    }
                }
            }
        }
    }

    private suspend fun downloadTrack(a: Anchor, silent: Boolean): Boolean =
        downloadEntry(a.key, a.ext, a.size, silent)

    /**
     * 从机拉取文件。协议：新开一条到主机 TCP 的文件连接，发一行 fileReq，
     * 主机回一行 fileHead 后开始推送裸字节；支持从已有 .part 长度续传。
     */
    private suspend fun downloadEntry(
        key: String,
        ext: String,
        size: Long,
        silent: Boolean
    ): Boolean = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
        val cacheRef = cache ?: return@withContext false
        if (size <= 0L) return@withContext false
        // 已经传完（可能是刚才的预取）就直接成功，不必再连主机
        cacheRef.doneFile(key, ext)?.let { return@withContext true }
        val part = cacheRef.partFor(key, ext)
        val existing = part.length().coerceAtMost(size)
        val socket = runCatching {
            Socket().apply {
                // 不能是 0：主机卡死时 read 会永久阻塞，界面就永远停在「正在传输」
                soTimeout = GroupProtocol.TRANSFER_IDLE_TIMEOUT_MS.toInt()
                connect(InetSocketAddress(hostIp, hostPort), 4000)
                tcpNoDelay = true
            }
        }.getOrNull() ?: run {
            GroupLog.log("file", "取文件连接失败 $hostIp:$hostPort")
            return@withContext false
        }
        var success = false
        socket.use { s ->
            runCatching {
                val out = s.getOutputStream()
                val req = JSONObject().put("t", GroupProtocol.T_FILE_REQ)
                    .put("token", token)
                    .put("key", key)
                    .put("offset", existing)
                out.write((req.toString() + "\n").toByteArray())
                out.flush()

                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val headLine = readLine(input) ?: run {
                    GroupLog.log("file", "主机没回 fileHead key=$key")
                    return@runCatching
                }
                val head = JSONObject(headLine)
                if (!head.optBoolean("ok", false)) {
                    GroupLog.log("file", "主机拒绝传输 key=$key reason=${head.optString("reason")}")
                    return@runCatching
                }
                val total = head.optLong("size", size)
                val offset = head.optLong("offset", existing)
                GroupLog.log("file", "开始接收 key=$key total=$total offset=$offset")
                if (offset != existing) {
                    // 主机侧对不上续传偏移，只能从头来
                    if (offset == 0L) part.delete()
                }
                transferLastByteMs = GroupProtocol.nowMs()
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(offset)
                    var written = offset
                    val buf = ByteArray(64 * 1024)
                    var lastProgress = 0L
                    while (written < total) {
                        if (!isActive) break
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        transferLastByteMs = GroupProtocol.nowMs()
                        val now = GroupProtocol.nowMs()
                        if (!silent && now - lastProgress > 100L) {
                            lastProgress = now
                            _state.update {
                                it.copy(
                                    transferProgress = (written.toFloat() / total.toFloat())
                                        .coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                    success = written >= total
                    if (!success) {
                        GroupLog.log("file", "传输中断 key=$key written=$written total=$total")
                    }
                }
            }.onFailure {
                GroupLog.log("file", "传输异常 key=$key ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        if (success) {
            GroupLog.log("file", "传输完成 key=$key size=$size")
            cacheRef.markDone(key, ext, part) != null
        } else {
            false
        }
        }
    }

    private fun prepareMedia(a: Anchor, file: File) {
        val p = player ?: return
        if (preparedKey == a.key) return
        val uri = cache?.uriFor(file)
        if (uri == null) {
            GroupLog.log("play", "FileProvider URI 生成失败 key=${a.key}")
            _state.update {
                it.copy(
                    message = "本机缓存文件无法读取，请重试",
                    lastError = "FileProvider URI 生成失败",
                    lastErrorAtMs = GroupProtocol.nowMs()
                )
            }
            return
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(a.title)
            .setArtist(a.artist)
            .setAlbumTitle(a.album)
            .apply { if (a.durationMs > 0L) setDurationMs(a.durationMs) }
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(peerMediaId(a.key).toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
        if (a.mime.isNotBlank()) builder.setMimeType(a.mime)
        remoteApplying = true
        p.setMediaItem(builder.build())
        p.prepare()
        preparedKey = a.key
        prepareStartedMs = GroupProtocol.nowMs()
        remoteApplying = false
        GroupLog.log("play", "准备本地播放 key=${a.key} bytes=${file.length()}")
        waitReadyThenAlign(a.key)
    }

    private fun waitReadyThenAlign(key: String) {
        scope.launch {
            var waited = 0L
            while (waited < 2000L) {
                val ready = withContext(Dispatchers.Main) { player?.playbackState == Player.STATE_READY }
                if (ready) break
                delay(50L)
                waited += 50L
            }
            withContext(Dispatchers.Main) {
                if (preparedKey == key && currentKey == key) {
                    val ready = player?.playbackState == Player.STATE_READY
                    if (!ready) {
                        GroupLog.log("play", "2s 内未进入 READY（交给看门狗继续观察） key=$key")
                    }
                    alignStart()
                }
            }
        }
    }

    private fun alignStart() {
        val p = player ?: return
        val a = anchor ?: return
        val clock = guestClock ?: return
        if (!clock.hasSample) {
            GroupLog.log("sync", "还没有对时样本，暂不起播（等待 UDP 对时）")
            return
        }
        val hostNow = clock.hostNowNanos()
        val target = a.timelineMs(hostNow) + (localLatency - a.hostLatencyMs)
        remoteApplying = true
        p.setPlaybackParameters(PlaybackParameters(1f, 1f))
        p.seekTo(target.coerceAtLeast(0L))
        if (a.playing) p.play() else p.pause()
        remoteApplying = false
        GroupLog.log("sync", "起播对齐 target=${target}ms playing=${a.playing}")
        prepareStartedMs = 0L
        _state.update { it.copy(phase = GroupPhase.SYNCING) }
    }

    /** 预取下一首：不影响当前传输进度提示，静默下载 */
    private fun prefetch(a: Anchor) {
        val next = a.next ?: return
        if (next.size <= 0L) return
        if (prefetchKey == next.key) return
        if (cache?.doneFile(next.key, next.ext) != null) return
        prefetchKey = next.key
        prefetchJob = scope.launch {
            runCatching { downloadEntry(next.key, next.ext, next.size, silent = true) }
            prefetchKey = null
        }
    }

    // ------------------------------------------------------------------ 播放器监听

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (role == GroupRole.HOST) main.post { broadcastAnchorNow() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (role == GroupRole.HOST) main.post { broadcastAnchorNow() }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                    )
            ) {
                // 来电/其他应用抢焦点：整组暂停；恢复后等用户手动继续
                if (role == GroupRole.HOST) {
                    main.post { broadcastAnchorNow() }
                } else if (role == GroupRole.GUEST) {
                    client?.send(JSONObject().put("t", GroupProtocol.T_CMD).put("a", "pause"))
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (role != GroupRole.GUEST) return
            GroupLog.log("play", "播放失败 ${error.errorCodeName}: ${error.message}")
            main.post {
                if (role == GroupRole.GUEST) {
                    _state.update {
                        it.copy(
                            message = "本机无法播放该曲目（${error.errorCodeName}）",
                            lastError = "播放失败 ${error.errorCodeName}",
                            lastErrorAtMs = GroupProtocol.nowMs()
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun publishPeers() {
        val now = GroupProtocol.nowMs()
        if (role == GroupRole.HOST) {
            val list = peers.values.map { conn ->
                val sync = peerSync[conn.id]
                GroupPeer(
                    id = conn.id,
                    name = conn.name.ifBlank { "Android" },
                    model = conn.model,
                    online = now - conn.lastSeenMs <= GroupProtocol.PEER_TIMEOUT_MS,
                    syncErrorMs = sync?.first,
                    rttMs = sync?.second,
                    outputLatencyMs = conn.outputLatencyMs
                )
            }
            _state.update { it.copy(peers = list, connectedCount = list.size) }
        } else if (role == GroupRole.GUEST) {
            // 配对还没成功时不要显示「已连接 1 台」，否则界面看起来像已经入组
            val joined = phase == GroupPhase.CONNECTED || phase == GroupPhase.SYNCING
            _state.update {
                it.copy(
                    connectedCount = if (joined) 1 else 0,
                    peers = listOf(
                        GroupPeer(
                            id = "host",
                            name = hostName,
                            model = "",
                            isHost = true,
                            online = GroupProtocol.nowMs() - lastPingMs <= GroupProtocol.PEER_TIMEOUT_MS
                        )
                    )
                )
            }
        }
    }

    private fun refreshLatency() {
        val route = PulseCalibrator.currentRoute(appContext)
        routeCached = route
        calibrationRoute = route
        val saved = prefs?.groupOutputLatency(Build.MODEL, route) ?: -1L
        localLatency = if (saved >= 0L) saved else PulseCalibrator.defaultLatencyFor(route)
        _state.update {
            it.copy(
                outputLatencyMs = localLatency,
                calibrationOffsetMs = localLatency,
                calibrationRoute = calibrationRoute
            )
        }
    }

    private fun acquireLocks() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("sonance-group-mdns").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        if (wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sonance-group-wifi")
                .apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }
                }
        }
    }

    private fun releaseLocks() {
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        multicastLock = null
        wifiLock = null
    }

    private fun deviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val raw = androidId?.takeIf { it.isNotBlank() } ?: Build.FINGERPRINT
        return "d" + (raw.hashCode().toLong() and 0xFFFF_FFFFL).toString(16)
    }

    private fun randomToken(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun sizeOf(uri: Uri): Long = runCatching {
        when (uri.scheme) {
            "content" -> appContext.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize } ?: 0L
            "file" -> uri.path?.let { File(it).length() } ?: 0L
            else -> 0L
        }
    }.getOrDefault(0L)

    private fun openInput(uri: Uri): InputStream? = runCatching {
        when (uri.scheme) {
            "content" -> appContext.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { FileInputStream(File(it)) }
            else -> null
        }
    }.getOrNull()

    private fun extOf(uri: Uri, mime: String): String {
        uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.length in 1..5 }?.let { return it }
        return when {
            mime.contains("mpeg") -> "mp3"
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            mime.contains("flac") -> "flac"
            mime.contains("ogg") -> "ogg"
            mime.contains("wav") -> "wav"
            else -> "audio"
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    /** org.json 没有 optLongOrNull，缺字段时返回 null 而不是 0 */
    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name, 0L) else null

    // 数据类 ------------------------------------------------------------------

    private data class TrackEntry(
        val key: String,
        val uri: Uri,
        val mime: String,
        val ext: String,
        val size: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private data class Anchor(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val size: Long,
        val mime: String,
        val ext: String,
        val posMs: Long,
        val clockNanos: Long,
        val hostLatencyMs: Long,
        val playing: Boolean,
        val unsupported: Boolean,
        val next: TrackEntry?
    ) {
        fun timelineMs(hostNowNanos: Long): Long =
            posMs + (hostNowNanos - clockNanos) / 1_000_000L
    }

    private fun parseAnchor(o: JSONObject): Anchor {
        val nextKey = o.optString("nkey")
        val next = if (nextKey.isNotBlank()) {
            TrackEntry(
                key = nextKey,
                uri = Uri.EMPTY,
                mime = o.optString("nmime"),
                ext = o.optString("nextExt"),
                size = o.optLong("nsize", 0L),
                title = o.optString("ntitle"),
                artist = o.optString("nartist"),
                album = "",
                durationMs = o.optLong("ndur", 0L)
            )
        } else null
        return Anchor(
            key = o.optString("key"),
            title = o.optString("title", "未知曲目"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("dur", 0L),
            size = o.optLong("size", 0L),
            mime = o.optString("mime"),
            ext = o.optString("ext").ifBlank { "audio" },
            posMs = o.optLong("pos", 0L),
            clockNanos = o.optLong("clock", GroupProtocol.nowNanos()),
            hostLatencyMs = o.optLong("hostLat", 0L),
            playing = o.optBoolean("playing", false),
            unsupported = o.optBoolean("unsupported", false),
            next = next
        )
    }
}
