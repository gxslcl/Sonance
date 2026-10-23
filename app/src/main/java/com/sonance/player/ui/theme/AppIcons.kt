package com.sonance.player.ui.theme

import androidx.annotation.DrawableRes
import com.sonance.player.R

/**
 * 可选的应用图标。切换靠 [android.content.pm.PackageManager.setComponentEnabledSetting]
 * 在 manifest 里声明的 activity-alias 之间互切——这是 Android 上换桌面图标的唯一官方途径。
 */
data class AppIconVariant(
    val id: String,
    val label: String,
    @DrawableRes val preview: Int,
    val aliasName: String,
    @DrawableRes val icon: Int,
    /** 自适应图标背景色（ARGB），设置页预览用 */
    val bgColor: androidx.compose.ui.graphics.Color,
)

object AppIcons {

    const val DEFAULT_ID = "default"

    private fun v(id: String, label: String, preview: Int, icon: Int, bgArgb: Long) =
        AppIconVariant(
            id = id,
            label = label,
            preview = preview,
            aliasName = "com.sonance.player.alias.Icon_$id",
            icon = icon,
            bgColor = androidx.compose.ui.graphics.Color(bgArgb),
        )

    val all: List<AppIconVariant> = listOf(
        v("default", "原版 · 耳机", R.drawable.logo_s4_1, R.mipmap.ic_launcher, 0xFF162032),
        v("s1_1", "播放圆环 · 原版线条", R.drawable.logo_s1_1, R.mipmap.ic_launcher_s1_1, 0xFF0A0A0A),
        v("s1_2", "播放圆环 · 实心填充", R.drawable.logo_s1_2, R.mipmap.ic_launcher_s1_2, 0xFF121212),
        v("s1_3", "播放圆环 · 半切拼接", R.drawable.logo_s1_3, R.mipmap.ic_launcher_s1_3, 0xFFF0EBE0),
        v("s1_4", "播放圆环 · 双环进度", R.drawable.logo_s1_4, R.mipmap.ic_launcher_s1_4, 0xFF162032),
        v("s1_5", "播放圆环 · 负空间挖空", R.drawable.logo_s1_5, R.mipmap.ic_launcher_s1_5, 0xFFE0556A),
        v("s1_6", "播放圆环 · 暂停键变体", R.drawable.logo_s1_6, R.mipmap.ic_launcher_s1_6, 0xFF0F1F18),
        v("s1_7", "播放圆环 · 虚线旋转", R.drawable.logo_s1_7, R.mipmap.ic_launcher_s1_7, 0xFF1E2428),
        v("s1_8", "播放圆环 · App图标", R.drawable.logo_s1_8, R.mipmap.ic_launcher_s1_8, 0xFFEDE8DC),
        v("s2_1", "均衡器 · 原版柱形", R.drawable.logo_s2_1, R.mipmap.ic_launcher_s2_1, 0xFFF5E6DE),
        v("s2_2", "均衡器 · 双色交替", R.drawable.logo_s2_2, R.mipmap.ic_launcher_s2_2, 0xFF121212),
        v("s2_3", "均衡器 · 镜像对称", R.drawable.logo_s2_3, R.mipmap.ic_launcher_s2_3, 0xFFF0EBE0),
        v("s2_4", "均衡器 · 圆弧波形", R.drawable.logo_s2_4, R.mipmap.ic_launcher_s2_4, 0xFF0D2826),
        v("s2_5", "均衡器 · 负空间挖空", R.drawable.logo_s2_5, R.mipmap.ic_launcher_s2_5, 0xFF9B72D9),
        v("s2_6", "均衡器 · 中心播放叠加", R.drawable.logo_s2_6, R.mipmap.ic_launcher_s2_6, 0xFF162032),
        v("s2_7", "均衡器 · 顶部波动线", R.drawable.logo_s2_7, R.mipmap.ic_launcher_s2_7, 0xFF1A1F10),
        v("s2_8", "均衡器 · App图标", R.drawable.logo_s2_8, R.mipmap.ic_launcher_s2_8, 0xFFEDE8DC),
        v("s3_1", "八分音符 · 原版音符", R.drawable.logo_s3_1, R.mipmap.ic_launcher_s3_1, 0xFF2D1B4E),
        v("s3_2", "八分音符 · 双色分件", R.drawable.logo_s3_2, R.mipmap.ic_launcher_s3_2, 0xFFF0EBE0),
        v("s3_3", "八分音符 · 撞色拼接", R.drawable.logo_s3_3, R.mipmap.ic_launcher_s3_3, 0xFF0D2826),
        v("s3_4", "八分音符 · 双符连接", R.drawable.logo_s3_4, R.mipmap.ic_launcher_s3_4, 0xFF2A1018),
        v("s3_5", "八分音符 · 负空间挖空", R.drawable.logo_s3_5, R.mipmap.ic_launcher_s3_5, 0xFF7EB8E8),
        v("s3_6", "八分音符 · 加五线谱", R.drawable.logo_s3_6, R.mipmap.ic_launcher_s3_6, 0xFF2A2A2A),
        v("s3_7", "八分音符 · 倾斜动感", R.drawable.logo_s3_7, R.mipmap.ic_launcher_s3_7, 0xFF2A1028),
        v("s3_8", "八分音符 · App图标", R.drawable.logo_s3_8, R.mipmap.ic_launcher_s3_8, 0xFFEDE8DC),
        v("s4_1", "耳机 · 原版耳机", R.drawable.logo_s4_1, R.mipmap.ic_launcher_s4_1, 0xFF162032),
        v("s4_2", "耳机 · 双色耳罩", R.drawable.logo_s4_2, R.mipmap.ic_launcher_s4_2, 0xFFF0EBE0),
        v("s4_3", "耳机 · 金调双色", R.drawable.logo_s4_3, R.mipmap.ic_launcher_s4_3, 0xFF121212),
        v("s4_4", "耳机 · 两侧出声波", R.drawable.logo_s4_4, R.mipmap.ic_launcher_s4_4, 0xFF0F1F18),
        v("s4_5", "耳机 · 负空间挖空", R.drawable.logo_s4_5, R.mipmap.ic_launcher_s4_5, 0xFFE8552E),
        v("s4_6", "耳机 · 圆耳罩细节", R.drawable.logo_s4_6, R.mipmap.ic_launcher_s4_6, 0xFF1E2428),
        v("s4_7", "耳机 · 虚线头梁", R.drawable.logo_s4_7, R.mipmap.ic_launcher_s4_7, 0xFF2D1B4E),
        v("s4_8", "耳机 · App图标", R.drawable.logo_s4_8, R.mipmap.ic_launcher_s4_8, 0xFFEDE8DC),
        v("s5_1", "声波 · 原版三层波", R.drawable.logo_s5_1, R.mipmap.ic_launcher_s5_1, 0xFF2A2A2A),
        v("s5_2", "声波 · 柱状声波", R.drawable.logo_s5_2, R.mipmap.ic_launcher_s5_2, 0xFF121212),
        v("s5_3", "声波 · 上下分色波", R.drawable.logo_s5_3, R.mipmap.ic_launcher_s5_3, 0xFFF0EBE0),
        v("s5_4", "声波 · 环形声波", R.drawable.logo_s5_4, R.mipmap.ic_launcher_s5_4, 0xFF0D2826),
        v("s5_5", "声波 · 负空间挖空", R.drawable.logo_s5_5, R.mipmap.ic_launcher_s5_5, 0xFFE0556A),
        v("s5_6", "声波 · 中心播放叠加", R.drawable.logo_s5_6, R.mipmap.ic_launcher_s5_6, 0xFF162032),
        v("s5_7", "声波 · 折线锯齿波", R.drawable.logo_s5_7, R.mipmap.ic_launcher_s5_7, 0xFF1A1F10),
        v("s5_8", "声波 · App图标", R.drawable.logo_s5_8, R.mipmap.ic_launcher_s5_8, 0xFFEDE8DC),
    )

    fun byId(id: String?): AppIconVariant = all.firstOrNull { it.id == id } ?: all.first()
}
