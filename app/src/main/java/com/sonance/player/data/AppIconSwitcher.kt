package com.sonance.player.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.sonance.player.ui.theme.AppIcons

/**
 * 切换桌面图标。
 *
 * Android 上应用自己不能改 launcher 图标，唯一官方途径是：在 manifest 里为同一个
 * Activity 声明多个 [activity-alias]，每个 alias 挂不同的 `android:icon`，
 * 运行时用 [PackageManager.setComponentEnabledSetting] 只启用其中一个。
 *
 * 注意：
 * - 必须带 [PackageManager.DONT_KILL_APP]，否则切图标会把当前进程杀掉。
 * - 桌面刷新有几秒延迟，这是系统行为，不是 bug。
 */
object AppIconSwitcher {

    fun apply(context: Context, id: String) {
        val pm = context.packageManager
        val target = AppIcons.byId(id)
        for (variant in AppIcons.all) {
            val component = ComponentName(context.packageName, variant.aliasName)
            val desired = if (variant.id == target.id) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(component) != desired) {
                pm.setComponentEnabledSetting(
                    component,
                    desired,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    /** 启动时把实际启用的 alias 与用户选择对齐（比如清过数据 / 换过包） */
    fun reconcile(context: Context) {
        runCatching { apply(context, Prefs(context).appIconId) }
    }
}
