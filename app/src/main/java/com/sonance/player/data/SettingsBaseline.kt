package com.sonance.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 出厂设置基线。
 *
 * ## 干什么
 * 把「这台机器上已经调好的一整套外观/行为偏好」作为新用户的默认值：
 * 首次启动（或清除数据后）把基线写进本地 SharedPreferences，之后用户怎么改都各自独立，
 * 升级安装不会覆盖已有设置。
 *
 * ## 文件从哪来
 * `app/src/main/assets/settings_baseline.json`，由设置 →「关于」长按版本号导出后
 * 用 adb 拉出来放进 assets。导出只读，不修改本地任何值。
 *
 * ## 加新设置时怎么维护
 * 1. 只有**外观/行为类**偏好才进基线；凡是设备相关（扫描路径、缓存目录、Shizuku）、
 *    运行时状态（播放现场、队列、播放位置、窗口/菜单坐标）、用户数据（收藏、最近、
 *    歌单、歌词偏移、B 站播放位置）一律**不要**加。
 * 2. 在 [specs] 里按真实存储类型加一条 `Spec("key", Type.XXX)`。
 * 3. 在真机上把设置调成想要的出厂状态 → 长按版本号导出 →
 *    `adb pull /sdcard/Android/data/com.sonance.player/files/settings_baseline.json` →
 *    覆盖 `app/src/main/assets/settings_baseline.json` → 重新打包。
 *
 * ## 首次启动判定
 * 独立标志 [FLAG_APPLIED]：
 * - 标志不存在且 `sp.all` 为空 → 真·首次启动/清数据 → 播种基线并写 true；
 * - 标志不存在但已有其他 key → 升级安装 → 写 false，不覆盖用户设置。
 * 播种之外，[BaselineSharedPreferences] 还会在「key 没被写过」时回退到基线值，
 * 所以即使某个 key 后来被清掉，默认值仍然是基线里的那套。
 */
object SettingsBaseline {

    const val ASSET_NAME = "settings_baseline.json"
    const val FILE_NAME = "settings_baseline.json"

    /** 首次启动播种标记；true = 这台设备按基线出厂 */
    const val FLAG_APPLIED = "settings_baseline_applied"

    enum class Type { BOOLEAN, INT, FLOAT, LONG, STRING, STRING_SET }

    data class Spec(val key: String, val type: Type)

    /**
     * 允许进入基线的白名单（**唯一来源**）。
     *
     * 只列外观与行为类偏好。设备相关、运行时状态、用户数据见类注释，永不加入。
     */
    val specs: List<Spec> = listOf(
        // ---- 皮肤与主题 ----
        Spec("skin_id", Type.STRING),
        Spec("icon_set", Type.INT),
        Spec("glow_mode", Type.STRING),
        Spec("glow_strength", Type.FLOAT),
        Spec("glow_transition", Type.STRING),
        Spec("glow_fade_ms", Type.INT),
        Spec("art_transition_mode", Type.STRING),
        Spec("art_fade_ms", Type.INT),
        Spec("app_icon_id", Type.STRING),
        Spec("top_bar_logo_id", Type.STRING),
        Spec("app_display_name", Type.STRING),

        // ---- 列表外观 ----
        Spec("sort_field", Type.STRING),
        Spec("sort_asc", Type.BOOLEAN),
        Spec("view_mode", Type.STRING),
        Spec("list_row_style", Type.INT),
        Spec("list_cover_scale", Type.INT),
        Spec("scroll_haptic_enabled", Type.BOOLEAN),
        Spec("right_rail_letters", Type.BOOLEAN),

        // ---- 播放与音效 ----
        Spec("play_mode", Type.STRING),
        Spec("play_speed", Type.FLOAT),
        Spec("karaoke_enabled", Type.BOOLEAN),
        Spec("player_vinyl_mode", Type.BOOLEAN),
        Spec("player_vinyl_sfx_enabled", Type.BOOLEAN),
        Spec("player_vinyl_sfx_volume", Type.FLOAT),
        Spec("play_btn_haptics", Type.BOOLEAN),
        Spec("play_btn_glow", Type.BOOLEAN),
        Spec("play_btn_color_mode", Type.STRING),
        Spec("play_btn_icon_style", Type.INT),
        Spec("play_btn_icon_scale", Type.INT),
        Spec("eq_enabled", Type.BOOLEAN),
        Spec("eq_preset", Type.STRING),
        Spec("eq_custom_bands", Type.STRING),
        Spec("sound_fx_enabled", Type.BOOLEAN),
        Spec("sound_preset_id", Type.STRING),
        Spec("sound_fx_params", Type.STRING),

        // ---- 歌词 ----
        Spec("lyrics_t2s", Type.BOOLEAN),
        Spec("lyrics_font_sp", Type.INT),
        Spec("lyrics_adapt_mode", Type.STRING),
        Spec("lyrics_style", Type.STRING),
        Spec("lyrics_playbar", Type.BOOLEAN),
        Spec("lyrics_playbar_style", Type.STRING),
        Spec("lyrics_hide_status_bar", Type.BOOLEAN),
        Spec("visualizer_hide_status_bar", Type.BOOLEAN),
        Spec("lock_screen_lyrics", Type.BOOLEAN),

        // ---- 封面与背景（玻璃）----
        Spec("glass_style", Type.INT),
        Spec("glass_blur_dp", Type.INT),
        Spec("glass_visibility", Type.INT),
        Spec("shell_glass_style", Type.INT),
        Spec("shell_glass_blur_dp", Type.INT),
        Spec("shell_glass_visibility", Type.INT),
        Spec("viz_glass_style", Type.INT),
        Spec("viz_glass_blur_dp", Type.INT),
        Spec("viz_glass_visibility", Type.INT),
        Spec("online_cover_enabled", Type.BOOLEAN),

        // ---- 底部播放条 ----
        Spec("mini_player_style", Type.INT),
        Spec("mini_glass_style", Type.INT),
        Spec("mini_glass_blur_dp", Type.INT),
        Spec("mini_glass_visibility", Type.INT),
        Spec("mini_card_opacity", Type.INT),
        Spec("mini_progress_style", Type.INT),
        Spec("mini_swipe_left_pct", Type.INT),
        Spec("mini_swipe_right_pct", Type.INT),
        Spec("mini_height_scale", Type.INT),
        Spec("mini_cover_scale", Type.INT),

        // ---- 可视化 ----
        Spec("viz_style", Type.INT),
        Spec("viz_bars", Type.INT),
        Spec("viz_sensitivity", Type.FLOAT),
        Spec("viz_mirror", Type.BOOLEAN),
        Spec("viz_color_index", Type.INT),
        Spec("viz_bar_width", Type.FLOAT),
        Spec("viz_show_fps", Type.BOOLEAN),
        Spec("viz_show_lyrics", Type.BOOLEAN),
        Spec("viz_attack", Type.FLOAT),
        Spec("viz_decay_damping", Type.FLOAT),
        Spec("viz_gravity", Type.FLOAT),
        Spec("viz_peak_hold_ticks", Type.INT),
        Spec("viz_show_peak", Type.BOOLEAN),
        Spec("viz_ema_alpha", Type.FLOAT),

        // ---- 在线 / 扫描行为开关（不含路径与历史）----
        Spec("online_lyrics_enabled", Type.BOOLEAN),
        Spec("online_music_enabled", Type.BOOLEAN),
        Spec("online_music_source", Type.STRING),
        Spec("online_hide_unplayable", Type.BOOLEAN),
        Spec("online_download_enabled", Type.BOOLEAN),
        Spec("online_download_wifi_only", Type.BOOLEAN),
        Spec("netease_fill_enabled", Type.BOOLEAN),
        Spec("sniff_targets", Type.STRING_SET),
        Spec("scan_exclude_locked", Type.BOOLEAN),
        Spec("scan_exclude_unsupported", Type.BOOLEAN),
        Spec("scan_exclude_nonsong", Type.BOOLEAN),
        Spec("filter_short", Type.BOOLEAN),

        // ---- B 站播放行为（不含坐标与播放位置）----
        Spec("bili_play_mode", Type.INT),
        Spec("bili_brightness_gain", Type.INT),
        Spec("bili_background_play", Type.BOOLEAN)
    )

    private val specMap: Map<String, Type> = specs.associate { it.key to it.type }

    /** 读 assets 里的基线；文件不存在/格式损坏一律返回空表（不崩）。 */
    fun load(context: Context): Map<String, Any> = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }.mapCatching { parse(JSONObject(it)) }.getOrDefault(emptyMap())

    private fun parse(root: JSONObject): Map<String, Any> {
        val settings = root.optJSONObject("settings") ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (spec in specs) {
            val entry = settings.optJSONObject(spec.key) ?: continue
            if (entry.optString("type") != spec.type.name) continue
            if (!entry.has("value")) continue
            val raw: Any? = entry.opt("value")
            when (spec.type) {
                Type.BOOLEAN -> if (raw is Boolean) out[spec.key] = raw
                Type.INT -> if (raw is Number) out[spec.key] = raw.toInt()
                Type.FLOAT -> if (raw is Number) out[spec.key] = raw.toFloat()
                Type.LONG -> if (raw is Number) out[spec.key] = raw.toLong()
                Type.STRING -> if (raw is String) out[spec.key] = raw
                Type.STRING_SET -> if (raw is JSONArray) {
                    val set = LinkedHashSet<String>()
                    for (i in 0 until raw.length()) {
                        val item = raw.opt(i)
                        if (item is String) set.add(item)
                    }
                    out[spec.key] = set
                }
            }
        }
        return out
    }

    /**
     * 首次启动播种。返回「本机是否按基线出厂」（true 时读取会回退基线）。
     *
     * 只在真正首次启动/清数据后写入；升级安装一律保留用户已有设置。
     */
    fun seedIfFresh(sp: SharedPreferences, baseline: Map<String, Any>): Boolean {
        // 还没有基线文件：完全保持旧行为
        if (baseline.isEmpty()) return sp.getBoolean(FLAG_APPLIED, false)
        // 已经判定过就不再改
        if (sp.contains(FLAG_APPLIED)) return sp.getBoolean(FLAG_APPLIED, false)

        val fresh = sp.all.isEmpty()
        val ed = sp.edit()
        if (fresh) {
            for ((key, value) in baseline) {
                when (value) {
                    is Boolean -> ed.putBoolean(key, value)
                    is Int -> ed.putInt(key, value)
                    is Float -> ed.putFloat(key, value)
                    is Long -> ed.putLong(key, value)
                    is String -> ed.putString(key, value)
                    is Set<*> -> ed.putStringSet(key, value.map { it.toString() }.toSet())
                }
            }
            ed.putBoolean(FLAG_APPLIED, true)
            // 阻断 Prefs.migrateOnce() 对基线做二次改写（+2sp / 清空玻璃）
            ed.putBoolean("lyrics_font_bumped", true)
            ed.putBoolean("glass_cleared", true)
        } else {
            // 升级安装（或已用过）：不播种，走原有硬编码默认值
            ed.putBoolean(FLAG_APPLIED, false)
        }
        ed.commit()
        return fresh
    }

    /**
     * 只读导出：把本地**实际值**里命中白名单的项写成 JSON，放到
     * `Android/data/<包名>/files/settings_baseline.json`。
     *
     * 设备相关/运行时状态/用户数据不在 [specs] 里，天然不会被带出。
     */
    fun export(context: Context, sp: SharedPreferences): File? = runCatching {
        val all = sp.all
        val settings = JSONObject()
        for (spec in specs) {
            val value = all[spec.key] ?: continue
            val entry = JSONObject().put("type", spec.type.name)
            when (spec.type) {
                Type.BOOLEAN -> if (value is Boolean) entry.put("value", value) else continue
                Type.INT -> if (value is Int) entry.put("value", value) else continue
                Type.FLOAT -> if (value is Float) entry.put("value", value) else continue
                Type.LONG -> if (value is Long) entry.put("value", value) else continue
                Type.STRING -> if (value is String) entry.put("value", value) else continue
                Type.STRING_SET -> if (value is Set<*>) {
                    val arr = JSONArray()
                    value.forEach { arr.put(it.toString()) }
                    entry.put("value", arr)
                } else continue
            }
            settings.put(spec.key, entry)
        }
        val root = JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", settings)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, FILE_NAME).apply { writeText(root.toString(2)) }
    }.getOrNull()

    /** 导出的 key 是否在白名单里（调试/测试用） */
    internal fun isWhitelisted(key: String): Boolean = specMap.containsKey(key)
}

/**
 * 读取兜底：仅当本机按基线出厂（[enabled]）且本地从未写过该 key 时，
 * 返回基线值；类型不符则回退调用方给的默认值。写入仍走 [base]。
 */
internal class BaselineSharedPreferences(
    private val base: SharedPreferences,
    private val baseline: Map<String, Any>,
    private val enabled: Boolean
) : SharedPreferences by base {

    private fun fallback(key: String): Any? = if (enabled) baseline[key] else null

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null || base.contains(key)) return base.getBoolean(key, defValue)
        return fallback(key) as? Boolean ?: defValue
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null || base.contains(key)) return base.getInt(key, defValue)
        return fallback(key) as? Int ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null || base.contains(key)) return base.getFloat(key, defValue)
        return fallback(key) as? Float ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null || base.contains(key)) return base.getLong(key, defValue)
        return fallback(key) as? Long ?: defValue
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null || base.contains(key)) return base.getString(key, defValue)
        return fallback(key) as? String ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        if (key == null || base.contains(key)) return base.getStringSet(key, defValues)
        @Suppress("UNCHECKED_CAST")
        val set = fallback(key) as? Set<String>
        return set?.toMutableSet() ?: defValues
    }
}
