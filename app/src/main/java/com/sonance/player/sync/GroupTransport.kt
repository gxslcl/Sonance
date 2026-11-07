package com.sonance.player.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条已建立的控制连接。
 *
 * 读写都在 IO 协程里：写走 [Channel] 队列，避免主机主线程被「对方不收包」卡住；
 * 读是阻塞 readLine，socket 关闭即退出。
 */
class PeerConn(
    private val socket: Socket,
    reader: BufferedReader,
    firstLine: String?
) {
    var id: String = ""
    var name: String = ""
    var model: String = ""
    var outputLatencyMs: Long = 0

    @Volatile
    var lastSeenMs: Long = GroupProtocol.nowMs()

    @Volatile
    var closed: Boolean = false
        private set

    var onMessage: ((JSONObject) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    private val closedFlag = AtomicBoolean(false)

    init {
        scope.launch {
            for (line in outgoing) {
                try {
                    socket.getOutputStream().apply {
                        write(line.toByteArray())
                        write(10)
                        flush()
                    }
                } catch (_: Exception) {
                    close()
                    break
                }
            }
        }
        scope.launch {
            var line: String? = firstLine
            while (isActive) {
                try {
                    if (line == null) line = reader.readLine()
                    val text = line ?: break
                    line = null
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    if (obj != null) {
                        lastSeenMs = GroupProtocol.nowMs()
                        onMessage?.invoke(obj)
                    }
                } catch (_: Exception) {
                    break
                }
            }
            close()
        }
    }

    fun send(obj: JSONObject) {
        if (closed) return
        outgoing.trySend(obj.toString())
    }

    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        closed = true
        runCatching { socket.close() }
        scope.cancel()
        onClosed?.invoke()
    }
}

/** 主机端：监听 TCP，把每条新连接的「首行 JSON」读出来交给上层分流（控制 / 取文件） */
class GroupServer(
    private val onAccept: (socket: Socket, reader: BufferedReader, first: JSONObject) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var port: Int = 0
        private set

    fun start(): Boolean {
        stop()
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(GroupProtocol.TCP_PORT))
            }
        }.getOrNull() ?: return false
        serverSocket = socket
        port = socket.localPort
        scope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                scope.launch {
                    runCatching {
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                        val first = reader.readLine() ?: return@launch
                        val obj = JSONObject(first)
                        onAccept(client, reader, obj)
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }
}

/** 从机端：主动连主机 */
class GroupClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000
) {
    var conn: PeerConn? = null
        private set

    fun connect(): PeerConn? {
        val socket = runCatching {
            Socket().apply {
                soTimeout = 0
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                tcpNoDelay = true
            }
        }.getOrNull() ?: return null
        // 先发 hello 之前不能读；这里构造 PeerConn 时首行为 null，由读协程自己等待。
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val conn = PeerConn(socket, reader, null)
        this.conn = conn
        return conn
    }

    fun send(obj: JSONObject) {
        conn?.send(obj)
    }

    fun close() {
        conn?.close()
        conn = null
    }
}
