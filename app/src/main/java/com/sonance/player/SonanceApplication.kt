package com.sonance.player

import android.app.Application
import com.sonance.player.data.AppIconSwitcher
import com.sonance.player.data.Prefs
import com.sonance.player.ui.icon.IconSetState

class SonanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 全局图标套系：冷启动时从 Prefs 恢复（设置页切换时会实时改这个 state）
        IconSetState.init(Prefs(this).iconSet)
        // 对齐实际启用的 activity-alias 与用户选择（覆盖清数据 / 覆盖安装等情况）
        AppIconSwitcher.reconcile(this)
        // 默认值改了之后，把老用户已存的旧值也搬过去（只做一次）
        Prefs(this).migrateOnce()
        // 已导入的洛雪音源按平台注册进 OnlineSources 解析映射（仅用于播/下/恢复，不混入内置源列表）
        runCatching { com.sonance.player.data.lx.LxRepository.get(this).refreshOnlineSources() }
    }

    companion object {
        lateinit var instance: SonanceApplication
            private set
    }
}
