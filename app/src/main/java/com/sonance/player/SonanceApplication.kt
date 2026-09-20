package com.sonance.player

import android.app.Application
import com.sonance.player.data.AppIconSwitcher

class SonanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 对齐实际启用的 activity-alias 与用户选择（覆盖清数据 / 覆盖安装等情况）
        AppIconSwitcher.reconcile(this)
    }

    companion object {
        lateinit var instance: SonanceApplication
            private set
    }
}
