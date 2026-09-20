package com.sonance.player

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonance.player.ui.SonanceRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        enableEdgeToEdge()
        setContent {
            SonanceEntry()
        }
    }

    @Composable
    private fun SonanceEntry() {
        val vm: SonanceViewModel = viewModel()
        SonanceRoot(vm)
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
