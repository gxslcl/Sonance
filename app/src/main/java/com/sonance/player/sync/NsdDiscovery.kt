package com.sonance.player.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * NSD（mDNS）发现。
 *
 * 只用 `_sonance._tcp` 一个服务类型；主机注册时把设备 id / 名称 / 机型写进 TXT，
 * 从机解析后直接拿到「谁 + IP + TCP 端口」，不需要用户填任何东西。
 *
 * 注意：NsdManager 在同一时刻只允许一个 resolve 请求。这里用队列串行化，
 * 否则第二台设备出现时 resolve 会回调 FAILURE_ALREADY_ACTIVE。
 */
class NsdDiscovery(private val context: Context) {

    var onFound: ((id: String, name: String, model: String, host: String, port: Int) -> Unit)? = null
    var onLost: ((id: String) -> Unit)? = null

    private val nsd: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredInfo: NsdServiceInfo? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false

    fun register(
        serviceName: String,
        port: Int,
        selfId: String,
        model: String,
        deviceName: String
    ): Boolean {
        val manager = nsd ?: return false
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = GroupProtocol.SERVICE_TYPE
            this.port = port
            setAttribute("id", selfId)
            setAttribute("model", model)
            setAttribute("name", deviceName)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredInfo = info
                GroupLog.log("nsd", "注册成功 name=${info.serviceName} port=${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                GroupLog.log("nsd", "注册失败 name=${info.serviceName} error=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredInfo = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
            GroupLog.log("nsd", "开始注册 $serviceName port=$port")
            true
        }.getOrDefault(false).also { ok ->
            if (!ok) GroupLog.log("nsd", "注册异常抛出 name=$serviceName port=$port")
        }
    }

    fun unregister() {
        val manager = nsd
        val listener = registrationListener
        registrationListener = null
        registeredInfo = null
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
    }

    fun startDiscovery() {
        val manager = nsd ?: return
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueue(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.txtId()
                if (id.isNotBlank()) onLost?.invoke(id)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                GroupLog.log("nsd", "启动发现失败 error=$errorCode")
                runCatching { manager.stopServiceDiscovery(this) }
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                GroupLog.log("nsd", "停止发现失败 error=$errorCode")
                runCatching { manager.stopServiceDiscovery(this) }
                discoveryListener = null
            }
        }
        runCatching {
            manager.discoverServices(GroupProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
        }
    }

    fun stopDiscovery() {
        val manager = nsd
        val listener = discoveryListener
        discoveryListener = null
        resolveQueue.clear()
        resolving = false
        if (manager != null && listener != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.add(info)
        resolveNext()
    }

    private fun resolveNext() {
        if (resolving) return
        val manager = nsd ?: return
        val info = resolveQueue.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                GroupLog.log("nsd", "解析失败 name=${serviceInfo.serviceName} error=$errorCode")
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress.orEmpty()
                val port = serviceInfo.port
                val id = serviceInfo.txtId()
                val model = String(serviceInfo.attributes["model"] ?: ByteArray(0))
                val name = String(serviceInfo.attributes["name"] ?: ByteArray(0))
                    .ifBlank { serviceInfo.serviceName.removePrefix(GroupProtocol.SERVICE_PREFIX) }
                GroupLog.log("nsd", "解析成功 name=${serviceInfo.serviceName} host=$host port=$port id=$id")
                if (host.isNotBlank() && port > 0 && id.isNotBlank()) {
                    onFound?.invoke(id, name, model, host, port)
                }
                resolving = false
                resolveNext()
            }
        }
        runCatching { manager.resolveService(info, listener) }.onFailure {
            resolving = false
            resolveNext()
        }
    }

    private fun NsdServiceInfo.txtId(): String {
        val raw = attributes["id"] ?: return ""
        return String(raw)
    }
}
