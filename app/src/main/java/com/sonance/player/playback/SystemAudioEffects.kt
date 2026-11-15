package com.sonance.player.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting

internal data class EffectSessionBroadcast(
    val action: String,
    val audioSessionId: Int,
    val packageName: String,
    val contentType: Int?
)

/**
 * Pure session-state state machine for Android system audio effect control sessions
 * (Dolby Atmos, SoundAlive, MiSound, OReality, Histen, MusicFX, etc.).
 * Kept Android-free so every session transition is directly unit-testable on the JVM.
 */
internal class SystemEffectSessionTracker {

    var activeSessionId: Int = 0
        private set

    @Synchronized
    fun reconcile(
        enabled: Boolean,
        playerSessionId: Int,
        packageName: String
    ): List<EffectSessionBroadcast> {
        val target = if (enabled && playerSessionId > 0) playerSessionId else 0
        val broadcasts = ArrayList<EffectSessionBroadcast>(2)
        if (activeSessionId > 0 && activeSessionId != target) {
            broadcasts += EffectSessionBroadcast(
                action = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION,
                audioSessionId = activeSessionId,
                packageName = packageName,
                contentType = null
            )
            activeSessionId = 0
        }
        if (target > 0 && activeSessionId != target) {
            broadcasts += EffectSessionBroadcast(
                action = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                audioSessionId = target,
                packageName = packageName,
                contentType = AudioEffect.CONTENT_TYPE_MUSIC
            )
            activeSessionId = target
        }
        return broadcasts
    }

    @Synchronized
    fun closeAll(packageName: String): List<EffectSessionBroadcast> =
        reconcile(enabled = false, playerSessionId = 0, packageName = packageName)

    @VisibleForTesting
    @Synchronized
    internal fun resetForTest() {
        activeSessionId = 0
    }
}

/**
 * 手机 / 平板系统音效桥接（杜比全景声、各厂商系统音效与声音控制面板）。
 *
 * 原理：
 * - 向系统音效守护进程广播 [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] /
 *   [AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION]，让系统识别本应用的音乐播放会话；
 * - 用户点击「打开系统音效面板」时，优先通过 `startActivityForResult` 唤起系统标准音效控制面板
 *   （`ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`，带 `callingPackage` 与 `EXTRA_AUDIO_SESSION`），
 *   若部分厂商平板/ROM 未导出标准 Action，再尝试常见厂商 Dolby / Sound 入口，最后平滑兜底到系统「声音设置」。
 */
object SystemAudioEffects {

    private const val TAG = "SonanceSysFx"
    private const val REQ_CODE_AUDIO_EFFECT_PANEL = 0x5346

    private val KNOWN_OEM_EFFECT_PACKAGES = listOf(
        "com.dolby.daxappui",
        "com.motorola.dolby.dolbyui",
        "com.sec.android.app.soundalive",
        "com.miui.misound",
        "com.oplus.melody",
        "com.oneplus.sound.tuner",
        "com.android.musicfx"
    )

    private val tracker = SystemEffectSessionTracker()

    @Volatile
    var latestPlayerSessionId: Int = 0
        private set

    enum class PanelSupport { DEDICATED_PANEL, SOUND_SETTINGS_ONLY }

    enum class OpenOutcome { OPENED_DEDICATED_PANEL, OPENED_SOUND_SETTINGS, FAILED }

    fun syncSession(context: Context, enabled: Boolean, playerSessionId: Int) {
        if (playerSessionId > 0) latestPlayerSessionId = playerSessionId
        val app = context.applicationContext
        val events = tracker.reconcile(enabled, playerSessionId, app.packageName)
        dispatchBroadcasts(app, events)
    }

    fun releaseSession(context: Context) {
        val app = context.applicationContext
        val events = tracker.closeAll(app.packageName)
        latestPlayerSessionId = 0
        dispatchBroadcasts(app, events)
    }

    fun detectPanelSupport(context: Context): PanelSupport {
        val pm = context.packageManager
        val panelIntent = buildDisplayPanelIntent(0, context.packageName)
        val hasStandardPanel = resolveExternalActivity(pm, panelIntent, context.packageName) != null
        if (hasStandardPanel) return PanelSupport.DEDICATED_PANEL
        val hasOemApp = findOemEffectLaunchIntent(pm, context.packageName) != null
        return if (hasOemApp) PanelSupport.DEDICATED_PANEL else PanelSupport.SOUND_SETTINGS_ONLY
    }

    fun openEffectPanel(context: Context, audioSessionId: Int): OpenOutcome {
        val pm = context.packageManager
        val effectiveSession = if (audioSessionId > 0) audioSessionId else latestPlayerSessionId.coerceAtLeast(0)
        val activity = context.findActivity()

        val panelIntent = buildDisplayPanelIntent(effectiveSession, context.packageName)
        if (resolveExternalActivity(pm, panelIntent, context.packageName) != null) {
            if (launchIntent(context, activity, panelIntent, forResult = true)) {
                return OpenOutcome.OPENED_DEDICATED_PANEL
            }
        }

        val oemIntent = findOemEffectLaunchIntent(pm, context.packageName)
        if (oemIntent != null && launchIntent(context, activity, oemIntent, forResult = false)) {
            return OpenOutcome.OPENED_DEDICATED_PANEL
        }

        val soundSettings = Intent(Settings.ACTION_SOUND_SETTINGS)
        if (soundSettings.resolveActivity(pm) != null &&
            launchIntent(context, activity, soundSettings, forResult = false)
        ) {
            return OpenOutcome.OPENED_SOUND_SETTINGS
        }

        return OpenOutcome.FAILED
    }

    internal fun buildDisplayPanelIntent(audioSessionId: Int, packageName: String): Intent =
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId.coerceAtLeast(0))
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }

    private fun dispatchBroadcasts(context: Context, events: List<EffectSessionBroadcast>) {
        for (event in events) {
            runCatching {
                val intent = Intent(event.action).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, event.audioSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, event.packageName)
                    if (event.contentType != null) {
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, event.contentType)
                    }
                }
                context.sendBroadcast(intent)
            }.onFailure {
                Log.w(TAG, "系统音效会话广播失败 ${event.action}: ${it.message}")
            }
        }
    }

    private fun resolveExternalActivity(
        pm: PackageManager,
        intent: Intent,
        ownPackage: String
    ): android.content.pm.ResolveInfo? = runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.takeIf { it.activityInfo?.packageName != ownPackage && it.activityInfo?.exported != false }
    }.getOrNull()

    private fun findOemEffectLaunchIntent(pm: PackageManager, ownPackage: String): Intent? {
        for (pkg in KNOWN_OEM_EFFECT_PACKAGES) {
            if (pkg == ownPackage) continue
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launch != null) return launch
        }
        return null
    }

    private fun launchIntent(
        context: Context,
        activity: Activity?,
        intent: Intent,
        forResult: Boolean
    ): Boolean = runCatching {
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            if (forResult) {
                activity.startActivityForResult(intent, REQ_CODE_AUDIO_EFFECT_PANEL)
            } else {
                activity.startActivity(intent)
            }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    }.onFailure {
        Log.w(TAG, "打开系统音效界面失败 ${intent.action}: ${it.message}")
    }.getOrDefault(false)

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
