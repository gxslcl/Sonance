# Sonance · 山水有清音

一个 Android 本地音乐播放器。Kotlin + Jetpack Compose + Media3，单 Activity、前台服务常驻后台播放。

设计取向是**界面干净、能力藏在深处**：主界面只留曲库与播放，均衡器、在线补全、容器还原这些高级功能都收进设置与长按菜单，不占视线。

---

## 功能

- **本地曲库** —— MediaStore 扫描 + 文件头二次确认，自动去重、排序、按专辑 / 艺术家 / 文件夹归类；支持收藏、最近播放、播放次数统计。
- **播放页手势** —— 上下滑动切换「晕染背景」模式，颜色过渡无缝；左右滑动在歌词与律动柱之间切换。
- **音频可视化** —— 自研 FFT + AGC 的律动柱；物理模拟用解析解实现，帧率无关，60Hz / 120Hz 下衰减速度一致。
- **软件均衡器** —— biquad 实现的软件 EQ，绕开系统 `AudioEffect` 在部分机型（如 ColorOS）被拒的兼容坑；取样点在 EQ 之后，可视化读到的是「用户实际听到的声音」。
- **封面 / 歌词** —— 读取音频内嵌的封面与歌词；可选联网补全（iTunes 封面 / LrcAPI 歌词），并支持把下载到的封面**写回原文件**（ID3v2.3，保留原有帧）。
- **在线音乐** —— 搜索、试听、播放并下载，落盘走 MediaStore。
- **桌面小组件** —— 5 种样式，各自独立 receiver，共用同一套渲染基类。
- **图标变体** —— 5 组共 40 个 `activity-alias` 图标，运行时可切换。

## 技术栈

| 项目 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01） |
| 播放 | AndroidX Media3 1.5.0（`MediaSessionService`） |
| 构建 | AGP 8.7.3 · Gradle Wrapper · JDK 17 |
| 兼容 | minSdk 24 · target / compile SDK 35 |

## 构建

```bash
git clone <repo-url>
cd Sonance
./gradlew assembleDebug
```

release 构建需要签名。签名信息从 `local.properties` 读取（该文件不进版本库）：

```properties
sonance.storeFile=your.keystore
sonance.storePassword=******
sonance.keyAlias=******
sonance.keyPassword=******
```

缺任意一项，release 会回退到 debug 签名 —— clone 下来不改配置也能构建。

## 测试

```bash
./gradlew testDebugUnitTest
```

纯 JVM 单测覆盖歌词解析、匹配打分、去重排序、均衡器 DSP、可视化物理模拟、ID3 读写等最容易静默出错的部分。核心逻辑刻意写成不依赖 `android.net.Uri` 桩的纯函数，因此可以直接在 JVM 上跑。

## 权限

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`(≤32) | 读取本地音频 |
| `WRITE_EXTERNAL_STORAGE`(≤28) | Android 9 及以下写入公开音乐目录；Android 10+ 走 MediaStore 插入自有媒体，无需权限 |
| `MODIFY_AUDIO_SETTINGS` | 读取本应用音频会话的频谱（`Visualizer` 不需要录音权限） |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 在线歌词、在线封面、在线音乐 —— 三者独立开关，可在设置中随时关闭 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS` / `WAKE_LOCK` | 后台播放与通知 |

明文 HTTP 仅放行 CDN 单域（`music.126.net`），见 `app/src/main/res/xml/network_security_config.xml`。

## 项目结构

```
app/src/main/java/com/sonance/player/
├── ui/          Compose 界面：screens（曲库/在线/设置/还原）· components · sheets · theme
├── playback/    播放层：PlaybackService · EqProcessor · PcmTap · VizPhysics · AudioVisualizer
├── data/        数据层：MediaRepository · OnlineMusic · ArtworkLoader · Lyrics · Id3Writer · RestoreEngine
└── widget/      桌面小组件
```

## 更新日志

### v2.1（2026-10-27）

- **沉浸皮肤 · 统一压暗** —— 主界面背景合成亮度钉在恒定深色区间：换歌时只有色相在动、亮度不动，组件与文字固定一套深色样式，不再随封面明暗翻转。
- **主界面背景可调** —— 沉浸皮肤下新增「主界面背景」设置：玻璃样式 / 模糊程度 / 可见度三个滑杆，实时生效、独立记忆。
- **背景切换模式** —— 换歌背景过渡支持「滑动 / 渐变」两种形态，渐变过渡时长 200~1500ms 可调。
- **过渡动效根治「闪一下」** —— 封面与遮罩编组离屏合成、整层混合，过渡全程亮度数学上精确钉亮；真机录屏逐帧验证无过冲无凹谷。
- **定位按钮** —— 曲库列表右下角新增准星按钮，一键平滑滚动到正在播放的歌曲；只显示图标不带底色，点击区域完整、水波纹按圆扩散。
- **默认设置更新** —— 新安装默认：沉浸主题 / 悬浮卡片播放条 / 列表细分隔线 / 封面 1.2 倍 / 在线音乐 · 在线歌词 · 在线封面开启（均可随时在设置中更改）。

### v2.0（2026-10-26）

- 网易云缓存嗅探转换：扫描 → 解码 → 补全元数据（歌名 / 歌手 / 专辑 / 封面）→ 落盘 MediaStore，支持增量转换与试听残曲自动跳过。
- 黑碟播放器模式、播放页晕染背景 5 种模式、歌词圆盘模式（可调圆盘大小 / 双语歌词 / 进度微调）。
- 背景切换设置（滑动 / 渐变）、列表删除二次确认（仅隐藏 / 永久删除）。

## 免责声明

本项目仅供个人学习与技术研究使用。在线音乐功能使用了第三方未公开接口 —— 这类实现可能触及《著作权法》中关于技术措施与合理使用的条款，且第三方接口随时可能变更失效。

请勿将本项目用于商业用途或传播受版权保护的内容。若你所在地区相关实现不合法，请勿使用对应功能。因使用本项目产生的一切后果由使用者自行承担。

## License

[MIT](LICENSE)
