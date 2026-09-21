package com.sonance.player

import android.app.Application
import com.sonance.player.data.AppIconSwitcher
import com.sonance.player.data.Prefs

class SonanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 对齐实际启用的 activity-alias 与用户选择（覆盖清数据 / 覆盖安装等情况）
        AppIconSwitcher.reconcile(this)
        // 默认值改了之后，把老用户已存的旧值也搬过去（只做一次）
        Prefs(this).migrateOnce()
    }

    companion object {
        lateinit var instance: SonanceApplication
            private set
    }
}
