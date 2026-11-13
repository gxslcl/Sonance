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

    /**
     * 默认图标：s2_4「均衡器 · 圆弧波形」（深墨绿底 + 青绿三圆弧）。
     * manifest 里默认启用的 alias 与 [com.sonance.player.data.Prefs.appIconId]
     * 的兜底值都以它为准 —— 三处要一起改。
     */
    const val DEFAULT_ID = "s2_4"

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
        v("s2_4", "均衡器 · 圆弧波形", R.drawable.logo_s2_4, R.mipmap.ic_launcher_s2_4, 0xFF0D2826),
        v("sonance_dial", "回响之钮 · 触感徽章", R.drawable.logo_sonance_dial, R.mipmap.ic_launcher_sonance_dial, 0xFF131518),
        v("min_vinyl", "极简黑胶 · 同心韵律", R.drawable.logo_min_vinyl, R.mipmap.ic_launcher_min_vinyl, 0xFF141416),
        v("bauhaus_play", "包豪斯 · 几何棱镜", R.drawable.logo_bauhaus_play, R.mipmap.ic_launcher_bauhaus_play, 0xFFF2ECE1),
        v("tuning_fork", "音叉 · 共振之声", R.drawable.logo_tuning_fork, R.mipmap.ic_launcher_tuning_fork, 0xFF1A2228),
        v("metronome", "节拍器 · 黄金律动", R.drawable.logo_metronome, R.mipmap.ic_launcher_metronome, 0xFF241E1A),
        v("golden_pause", "双柱 · 静谧停顿", R.drawable.logo_golden_pause, R.mipmap.ic_launcher_golden_pause, 0xFFEDE6D8),
        v("retro_cassette", "复古磁带 · 双轮卡座", R.drawable.logo_retro_cassette, R.mipmap.ic_launcher_retro_cassette, 0xFF1E1B18),
        v("turntable_arm", "黑胶唱机 · 落针时刻", R.drawable.logo_turntable_arm, R.mipmap.ic_launcher_turntable_arm, 0xFF261C16),
        v("vacuum_tube", "电子管 · 暖光胆机", R.drawable.logo_vacuum_tube, R.mipmap.ic_launcher_vacuum_tube, 0xFF141010),
        v("studio_fader", "调音台 · 三轨推子", R.drawable.logo_studio_fader, R.mipmap.ic_launcher_studio_fader, 0xFF1C2127),
        v("hifi_headphones", "监听耳机 · 声学架构", R.drawable.logo_hifi_headphones, R.mipmap.ic_launcher_hifi_headphones, 0xFF162032),
        v("speaker_driver", "发烧音箱 · 同轴单元", R.drawable.logo_speaker_driver, R.mipmap.ic_launcher_speaker_driver, 0xFF202022),
        v("aurora_wave", "极光声波 · 流体渐变", R.drawable.logo_aurora_wave, R.mipmap.ic_launcher_aurora_wave, 0xFF090D16),
        v("synth_grid", "合成器 · 脉冲矩阵", R.drawable.logo_synth_grid, R.mipmap.ic_launcher_synth_grid, 0xFF120B24),
        v("midnight_gold", "午夜鎏金 · 黑胶徽章", R.drawable.logo_midnight_gold, R.mipmap.ic_launcher_midnight_gold, 0xFF0B0B0D),
        v("spatial_sonar", "空间音频 · 声呐脉冲", R.drawable.logo_spatial_sonar, R.mipmap.ic_launcher_spatial_sonar, 0xFF071A1D),
        v("cyber_eq", "赛博频谱 · 峰值跃动", R.drawable.logo_cyber_eq, R.mipmap.ic_launcher_cyber_eq, 0xFF140E1C),
        v("gramophone", "留声机 · 晨曦铜号", R.drawable.logo_gramophone, R.mipmap.ic_launcher_gramophone, 0xFFF5EBE0),
        v("acoustic_shell", "海螺共鸣 · 声学螺旋", R.drawable.logo_acoustic_shell, R.mipmap.ic_launcher_acoustic_shell, 0xFF152824),
        v("kalimba_tines", "卡林巴 · 拇指琴键", R.drawable.logo_kalimba_tines, R.mipmap.ic_launcher_kalimba_tines, 0xFF2C1D14),
        v("sunset_tape", "日落磁带 · 暖阳沙丘", R.drawable.logo_sunset_tape, R.mipmap.ic_launcher_sunset_tape, 0xFFF4DFD0),
        v("bamboo_flute", "竹笛 · 东方清韵", R.drawable.logo_bamboo_flute, R.mipmap.ic_launcher_bamboo_flute, 0xFF182218),
        v("sonance_s", "Sonance · 声纹 S 徽章", R.drawable.logo_sonance_s, R.mipmap.ic_launcher_sonance_s, 0xFF111318),
        v("mobius_loop", "莫比乌斯 · 无限循环", R.drawable.logo_mobius_loop, R.mipmap.ic_launcher_mobius_loop, 0xFF1A1826),
        v("dot_matrix", "点阵声场 · 像素律动", R.drawable.logo_dot_matrix, R.mipmap.ic_launcher_dot_matrix, 0xFF0F172A),
        v("acoustic_prism", "声学棱镜 · 折射音束", R.drawable.logo_acoustic_prism, R.mipmap.ic_launcher_acoustic_prism, 0xFF121214),
        v("stereo_wings", "双声道 · 蝴蝶振翅", R.drawable.logo_stereo_wings, R.mipmap.ic_launcher_stereo_wings, 0xFF24101C),
        v("mint_vinyl", "薄荷黑胶 · 清新唱片", R.drawable.logo_mint_vinyl, R.mipmap.ic_launcher_mint_vinyl, 0xFFDFF2EA),
        v("lavender_note", "薰衣草 · 灵动双音符", R.drawable.logo_lavender_note, R.mipmap.ic_launcher_lavender_note, 0xFFEAE4F7),
        v("coral_wave", "珊瑚暖波 · 柔光律动", R.drawable.logo_coral_wave, R.mipmap.ic_launcher_coral_wave, 0xFFFCE8E4),
        v("sky_cloudphone", "晴空云朵 · 轻盈漫步", R.drawable.logo_sky_cloudphone, R.mipmap.ic_launcher_sky_cloudphone, 0xFFE3F0FA),
        v("lemon_disc", "柑橘光盘 · 闪耀 CD", R.drawable.logo_lemon_disc, R.mipmap.ic_launcher_lemon_disc, 0xFFFFF5D6),
        v("saturn_ring", "土星环 · 星际漫游", R.drawable.logo_saturn_ring, R.mipmap.ic_launcher_saturn_ring, 0xFF0B1021),
        v("crescent_cat", "月夜猫影 · 静谧窗台", R.drawable.logo_crescent_cat, R.mipmap.ic_launcher_crescent_cat, 0xFF181A2F),
        v("paper_plane", "折纸飞船 · 逆风启航", R.drawable.logo_paper_plane, R.mipmap.ic_launcher_paper_plane, 0xFFE0F2FE),
        v("origami_crane", "千纸鹤 · 几何折纸", R.drawable.logo_origami_crane, R.mipmap.ic_launcher_origami_crane, 0xFFFDF2F8),
        v("crystal_hourglass", "星沙漏 · 时间胶囊", R.drawable.logo_crystal_hourglass, R.mipmap.ic_launcher_crystal_hourglass, 0xFF1C1427),
        v("nebula_portal", "虫洞 · 折叠时空", R.drawable.logo_nebula_portal, R.mipmap.ic_launcher_nebula_portal, 0xFF090910),
        v("compass_star", "北极星 · 航海罗盘", R.drawable.logo_compass_star, R.mipmap.ic_launcher_compass_star, 0xFF132226),
        v("fuji_mist", "雪顶山峦 · 晨雾微光", R.drawable.logo_fuji_mist, R.mipmap.ic_launcher_fuji_mist, 0xFFECE7DF),
        v("koi_pond", "双鲤戏水 · 太极锦鲤", R.drawable.logo_koi_pond, R.mipmap.ic_launcher_koi_pond, 0xFF0E272A),
        v("matcha_tea", "抹茶碗 · 一期一会", R.drawable.logo_matcha_tea, R.mipmap.ic_launcher_matcha_tea, 0xFFF0EDE4),
        v("paper_lantern", "夜灯笼 · 暖光微醺", R.drawable.logo_paper_lantern, R.mipmap.ic_launcher_paper_lantern, 0xFF1A1110),
        v("torii_gate", "朱红鸟居 · 静谧之门", R.drawable.logo_torii_gate, R.mipmap.ic_launcher_torii_gate, 0xFFF7E8D8),
        v("ink_bamboo", "墨竹 · 留白印章", R.drawable.logo_ink_bamboo, R.mipmap.ic_launcher_ink_bamboo, 0xFFF4F1EA),
        v("pixel_heart", "像素之心 · 8-Bit 脉冲", R.drawable.logo_pixel_heart, R.mipmap.ic_launcher_pixel_heart, 0xFF16161D),
        v("floppy_disk", "3.5寸软盘 · 复古记忆", R.drawable.logo_floppy_disk, R.mipmap.ic_launcher_floppy_disk, 0xFF1D2B53),
        v("arcade_ghost", "街机幽灵 · 霓虹迷宫", R.drawable.logo_arcade_ghost, R.mipmap.ic_launcher_arcade_ghost, 0xFF0D0D1A),
        v("melting_clock", "液态时钟 · 超现实", R.drawable.logo_melting_clock, R.mipmap.ic_launcher_melting_clock, 0xFF26201B),
        v("coffee_steam", "浓缩咖啡 · 灵感热气", R.drawable.logo_coffee_steam, R.mipmap.ic_launcher_coffee_steam, 0xFFF5EBE1),
        v("rubber_duck", "波普小鸭 · 浴室哲学", R.drawable.logo_rubber_duck, R.mipmap.ic_launcher_rubber_duck, 0xFF2563EB),
        v("bonsai_tree", "悬浮盆景 · 微观生态", R.drawable.logo_bonsai_tree, R.mipmap.ic_launcher_bonsai_tree, 0xFF17201C),
    )

    fun byId(id: String?): AppIconVariant =
        all.firstOrNull { it.id == id } ?: byIdDefault()

    private fun byIdDefault(): AppIconVariant = all.first { it.id == DEFAULT_ID }
}
