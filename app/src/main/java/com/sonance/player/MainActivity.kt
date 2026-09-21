package com.sonance.player

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
        setContent {
            SonanceRoot(vm)
        }
    }

    override fun onStop() {
        super.onStop()
        // 离开界面时把「播放现场」写盘：下次冷启动恢复成暂停在同一首、同一个进度。
        // 选 onStop 而不是定时写盘：这时进度已经稳定，写一次就够。
        vm.persistPlayback()
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
