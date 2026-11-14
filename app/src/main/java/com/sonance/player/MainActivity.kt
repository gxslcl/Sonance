package com.sonance.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.sonance.player.ui.SonanceRoot

class MainActivity : ComponentActivity() {

    /**
     * 提到 Activity 字段上，`onStop` 里才能拿到它。
     *
     * 和 Compose 里 `viewModel()` 取到的是**同一个实例**（同一个 ViewModelStore），
     * 所以不会多造一个。
     */
    private val vm: SonanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingAudioIntent(intent)
        }
        setContent {
            SonanceRoot(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    private fun handleIncomingAudioIntent(incoming: Intent?) {
        if (incoming == null || incoming.getBooleanExtra(EXTRA_INTENT_CONSUMED, false)) return
        val uri = extractPlayableAudioUri(incoming) ?: return
        incoming.putExtra(EXTRA_INTENT_CONSUMED, true)
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        vm.openExternalAudio(uri, incoming.type)
    }

    private fun extractPlayableAudioUri(incoming: Intent): Uri? {
        val raw = when (incoming.action) {
            Intent.ACTION_VIEW -> {
                incoming.data ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        } ?: return null
        val scheme = raw.scheme?.lowercase() ?: return null
        return if (scheme in PLAYABLE_URI_SCHEMES) raw else null
    }

    companion object {
        private const val EXTRA_INTENT_CONSUMED = "sonance.extra.intent_consumed"
        private val PLAYABLE_URI_SCHEMES = setOf("content", "file", "http", "https")
    }

    override fun onStop() {
        super.onStop()
        // 离开界面时把「播放现场」写盘：下次冷启动恢复成暂停在同一首、同一个进度。
        // 选 onStop 而不是定时写盘：这时进度已经稳定，写一次就够。
        vm.persistPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 返回栈清空（用户真正退出应用）：浮窗 / 视频播放不能留孤儿实例出声。
        // 旋转等非 finishing 的重建不能走这里，否则会把正在播的视频掐掉
        if (isFinishing) {
            com.sonance.player.playback.BiliVideoSession.of(this).onAppExit()
        }
    }

    /**
     * 请求高刷新率。
     *
     * 不显式请求时，系统在没有触摸、或判定界面没有动画的情况下会把刷新率压回 60Hz，
     * 于是可视化这类持续动画看起来就只有 60 帧（实测 120Hz 面板停在 60Hz）。
     * 这里挑一个不超过 120Hz 的最高显示模式；没有合适档位就退到最高档。
     */
    private fun requestHighRefreshRate() {
        runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val modes = display.supportedModes
            val best = modes
                .filter { it.refreshRate <= 120.5f }
                .maxByOrNull { it.refreshRate }
                ?: modes.maxByOrNull { it.refreshRate }
                ?: return

            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }
}
