package com.sonance.player.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlin.math.sqrt

/**
 * 纯逻辑摇一摇切歌检测器（双峰反向回弹 + 冷却 + 口袋遮挡屏蔽），可直接进行 JVM 单测。
 *
 * 算法：
 * 1. 计算去除重力后的线性加速度模长 `mag = sqrt(ax^2 + ay^2 + az^2)`；
 * 2. 当首次超过 [peakThreshold] 时记录首峰方向向量 `(px, py, pz)` 与时间戳；
 * 3. 在 [windowMs]（450ms）内、且距首峰至少 [minReboundGapMs]（50ms）后，若出现反向回弹峰值
 *    （`mag >= peakThreshold * 0.8f` 且点积 `px*ax + py*ay + pz*az < 0`），判定为一次有效甩腕/摇晃；
 * 4. 触发后进入 [cooldownMs]（1000ms）冷却期，避免单次摇晃连续跳过多首。
 */
internal class ShakeDetector(
    sensitivity: Int = 1,
    private val windowMs: Long = 450L,
    private val minReboundGapMs: Long = 50L,
    private val cooldownMs: Long = 1000L
) {
    var sensitivity: Int = sensitivity.coerceIn(0, 2)
        set(value) {
            field = value.coerceIn(0, 2)
        }

    var pocketGuardEnabled: Boolean = true
    var isProximityNear: Boolean = false

    private var hasFirstPeak = false
    private var firstPeakAtMs = 0L
    private var peakX = 0f
    private var peakY = 0f
    private var peakZ = 0f
    private var lastTriggerAtMs = Long.MIN_VALUE / 4

    val peakThreshold: Float
        get() = when (sensitivity) {
            0 -> 15.5f
            2 -> 9.0f
            else -> 12.0f
        }

    fun reset() {
        hasFirstPeak = false
        firstPeakAtMs = 0L
        peakX = 0f
        peakY = 0f
        peakZ = 0f
    }

    /**
     * 输入一帧线性加速度（单位 m/s²）与单调时间戳（ms）。
     * @return true 表示本次命中有效摇一摇切歌，调用方应执行切歌。
     */
    fun onLinearAcceleration(ax: Float, ay: Float, az: Float, nowMs: Long): Boolean {
        if (pocketGuardEnabled && isProximityNear) {
            reset()
            return false
        }
        if (nowMs - lastTriggerAtMs < cooldownMs) {
            return false
        }

        val threshold = peakThreshold
        val mag = sqrt(ax * ax + ay * ay + az * az)

        if (hasFirstPeak && nowMs - firstPeakAtMs > windowMs) {
            hasFirstPeak = false
        }

        if (!hasFirstPeak) {
            if (mag >= threshold) {
                hasFirstPeak = true
                firstPeakAtMs = nowMs
                peakX = ax
                peakY = ay
                peakZ = az
            }
            return false
        }

        val elapsed = nowMs - firstPeakAtMs
        if (elapsed < minReboundGapMs) {
            return false
        }

        val reboundThreshold = threshold * 0.8f
        val dot = peakX * ax + peakY * ay + peakZ * az
        if (mag >= reboundThreshold && dot < 0f) {
            reset()
            lastTriggerAtMs = nowMs
            return true
        }
        return false
    }
}

/**
 * 服务端的摇一摇切歌传感器控制器：
 * - 仅在播放中，且（亮屏 或 开启息屏摇一摇）时注册加速度计，其余时刻零耗电；
 * - 息屏触发时申请 500ms `PARTIAL_WAKE_LOCK` 保证切歌逻辑完整执行。
 */
internal class ShakeTrackController(
    context: Context,
    private val onShakeNext: () -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor = if (linearAccelSensor == null) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    } else null
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val detector = ShakeDetector()
    private val gravity = FloatArray(3)
    private var accelRegistered = false
    private var proximityRegistered = false

    fun updateState(
        enabled: Boolean,
        isPlaying: Boolean,
        screenInteractive: Boolean,
        screenOffAllowed: Boolean,
        pocketGuard: Boolean,
        sensitivity: Int
    ) {
        detector.sensitivity = sensitivity
        detector.pocketGuardEnabled = pocketGuard

        val shouldListen = enabled && isPlaying && (screenInteractive || screenOffAllowed)
        if (shouldListen) {
            registerAccelIfNeeded()
            if (pocketGuard) {
                registerProximityIfNeeded()
            } else {
                unregisterProximityIfNeeded()
                detector.isProximityNear = false
            }
        } else {
            release()
        }
    }

    fun release() {
        unregisterAccelIfNeeded()
        unregisterProximityIfNeeded()
        detector.reset()
        detector.isProximityNear = false
        gravity.fill(0f)
    }

    private fun registerAccelIfNeeded() {
        if (accelRegistered) return
        val sm = sensorManager ?: return
        val sensor = linearAccelSensor ?: rawAccelSensor ?: return
        accelRegistered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterAccelIfNeeded() {
        if (!accelRegistered) return
        sensorManager?.unregisterListener(this, linearAccelSensor ?: rawAccelSensor)
        accelRegistered = false
    }

    private fun registerProximityIfNeeded() {
        if (proximityRegistered) return
        val sm = sensorManager ?: return
        val sensor = proximitySensor ?: return
        proximityRegistered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterProximityIfNeeded() {
        if (!proximityRegistered) return
        val sensor = proximitySensor ?: return
        sensorManager?.unregisterListener(this, sensor)
        proximityRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = event.sensor.maximumRange
                val dist = event.values.firstOrNull() ?: maxRange
                detector.isProximityNear = dist < maxRange && dist < 5f
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleAcceleration(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.8f
                gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]
                handleAcceleration(
                    event.values[0] - gravity[0],
                    event.values[1] - gravity[1],
                    event.values[2] - gravity[2]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleAcceleration(ax: Float, ay: Float, az: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!detector.onLinearAcceleration(ax, ay, az, now)) return

        val interactive = powerManager?.isInteractive ?: true
        if (!interactive) {
            runCatching {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonance:ShakeSkip")
                    ?.acquire(500L)
            }
        }
        onShakeNext()
    }
}
