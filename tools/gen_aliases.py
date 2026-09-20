# -*- coding: utf-8 -*-
"""生成 activity-alias 清单块 + Kotlin AppIcons 常量表"""
import os, re

RES = r"C:\Users\1\WorkBuddy\2026-09-19-18-01-53\Sonance\app\src\main\res"
PKG = "com.sonance.player"

variants_txt = open(os.path.join(RES, 'values', 'ic_launcher_variants.xml'), encoding='utf-8').read()
labels = dict(re.findall(r'<string name="icon_label_(\w+)">(.*?)</string>', variants_txt))
ids = re.findall(r'<item>(\w+)</item>', variants_txt)

colors_txt = open(os.path.join(RES, 'values', 'ic_launcher_colors.xml'), encoding='utf-8').read()
colors = dict(re.findall(r'<color name="ic_bg_(\w+)">(.*?)</color>', colors_txt))

# 额外保留原版图标作为第一个选项
all_ids = ['default'] + ids
labels['default'] = '原版 · Sonance'

out_manifest = []
for i, vid in enumerate(all_ids):
    enabled = 'true' if i == 0 else 'false'
    icon = '@mipmap/ic_launcher' if vid == 'default' else '@mipmap/ic_launcher_%s' % vid
    out_manifest.append('''
        <!-- %s -->
        <activity-alias
            android:name=".alias.Icon_%s"
            android:targetActivity=".MainActivity"
            android:icon="%s"
            android:roundIcon="%s"
            android:label="@string/app_name"
            android:enabled="%s"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
''' % (labels[vid], vid, icon, icon, enabled))

with open(r"C:\Users\1\WorkBuddy\2026-09-19-18-01-53\Sonance\tools\_aliases.xml", 'w', encoding='utf-8') as f:
    f.write("\n".join(out_manifest))

# Kotlin
kt = []
kt.append('package com.sonance.player.ui.theme\n')
kt.append('import androidx.annotation.DrawableRes')
kt.append('import com.sonance.player.R\n')
kt.append('/**')
kt.append(' * 可选的应用图标。切换靠 [android.content.pm.PackageManager.setComponentEnabledSetting]')
kt.append(' * 在 manifest 里声明的 activity-alias 之间互切——这是 Android 上换桌面图标的唯一官方途径。')
kt.append(' */')
kt.append('data class AppIconVariant(')
kt.append('    val id: String,')
kt.append('    val label: String,')
kt.append('    @DrawableRes val preview: Int,')
kt.append('    val aliasName: String,')
kt.append('    @DrawableRes val icon: Int,')
kt.append('    /** 自适应图标背景色（ARGB），设置页预览用 */')
kt.append('    val bgColor: androidx.compose.ui.graphics.Color,')
kt.append(')')
kt.append('')
kt.append('object AppIcons {')
kt.append('')
kt.append('    const val DEFAULT_ID = "default"')
kt.append('')
kt.append('    private fun v(id: String, label: String, preview: Int, icon: Int, bgArgb: Long) =')
kt.append('        AppIconVariant(')
kt.append('            id = id,')
kt.append('            label = label,')
kt.append('            preview = preview,')
kt.append('            aliasName = "$PKG.alias.Icon_$id",'.replace('$PKG', PKG))
kt.append('            icon = icon,')
kt.append('            bgColor = androidx.compose.ui.graphics.Color(bgArgb),')
kt.append('        )')
kt.append('')
kt.append('    val all: List<AppIconVariant> = listOf(')
kt.append('        v("default", "%s", R.drawable.ic_launcher_foreground, R.mipmap.ic_launcher, "#FF121212"),'
          % labels['default'])
for vid in ids:
    kt.append('        v("%s", "%s", R.drawable.logo_%s, R.mipmap.ic_launcher_%s, %s),' % (
        vid, labels[vid], vid, vid,
        colors.get(vid, '#FF121212').replace('#', '0x')))
kt.append('    )')
kt.append('')
kt.append('    fun byId(id: String?): AppIconVariant = all.firstOrNull { it.id == id } ?: all.first()')
kt.append('}')
kt.append('')

with open(r"C:\Users\1\WorkBuddy\2026-09-19-18-01-53\Sonance\tools\_AppIcons.kt", 'w', encoding='utf-8') as f:
    f.write("\n".join(kt))

print("aliases: %d" % len(all_ids))
print("generated _aliases.xml and _AppIcons.kt")
