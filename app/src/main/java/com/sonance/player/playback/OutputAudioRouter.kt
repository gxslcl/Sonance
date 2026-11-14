package com.sonance.player.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 音频输出通道分类，用于「按输出设备自动记忆音效预设」与 UI 展示。
 */
enum class AudioOutputRoute(val id: String, val label: String) {
    PHONE_SPEAKER("phone_speaker", "手机外放"),
    TABLET_SPEAKER("tablet_speaker", "平板扬声器"),
    WIRED_HEADPHONE("wired_headphone", "有线耳机"),
    BLUETOOTH("bluetooth", "蓝牙 / 车载"),
    USB_DAC("usb_dac", "USB 音频");

    companion object {
        fun fromId(id: String?): AudioOutputRoute? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 监听当前活跃的音频输出硬件路径（内置外放 / 平板 / 有线耳机 / 蓝牙 / USB DAC）。
 * 只保留主线程回调，切出 ViewModel 时通过 [release] 反注册。
 */
internal class OutputAudioRouter(
    context: Context,
    private val onRouteChanged: (AudioOutputRoute) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: AudioDeviceCallback? = null
    private var lastRoute: AudioOutputRoute = detectCurrentRoute(appContext)

    fun currentRoute(): AudioOutputRoute = lastRoute

    fun start() {
        val am = audioManager ?: return
        if (callback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = dispatch()
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = dispatch()
        }
        callback = cb
        am.registerAudioDeviceCallback(cb, mainHandler)
        dispatch()
    }

    fun release() {
        val cb = callback ?: return
        audioManager?.unregisterAudioDeviceCallback(cb)
        callback = null
    }

    private fun dispatch() {
        val detected = detectCurrentRoute(appContext)
        if (detected != lastRoute) {
            lastRoute = detected
            onRouteChanged(detected)
        }
    }

    companion object {
        fun isTabletHardware(context: Context): Boolean =
            context.resources.configuration.smallestScreenWidthDp >= 600

        fun detectCurrentRoute(context: Context): AudioOutputRoute {
            val am = context.getSystemService(AudioManager::class.java)
            val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            val hasUsb = outputs.any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            if (hasUsb) return AudioOutputRoute.USB_DAC

            val hasWired = outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_AUX_LINE
            }
            if (hasWired) return AudioOutputRoute.WIRED_HEADPHONE

            val hasBt = outputs.any { info ->
                info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 28 && info.type == AudioDeviceInfo.TYPE_HEARING_AID) ||
                    (Build.VERSION.SDK_INT >= 31 && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                    (Build.VERSION.SDK_INT >= 33 && info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (hasBt) return AudioOutputRoute.BLUETOOTH

            return if (isTabletHardware(context)) {
                AudioOutputRoute.TABLET_SPEAKER
            } else {
                AudioOutputRoute.PHONE_SPEAKER
            }
        }
    }
}
