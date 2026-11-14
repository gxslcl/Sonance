# Sonance · 山水有清音

一个 Android 本地音乐播放器。Kotlin + Jetpack Compose + Media3，单 Activity、前台服务常驻后台播放。

设计取向是**界面干净、能力藏在深处**：主界面只留曲库与播放，均衡器、在线补全、容器还原这些高级功能都收进设置与长按菜单，不占视线。

---

## 功能

- **本地曲库** —— 三层扫描兜底（`Audio.Media` 不再依赖 `IS_MUSIC` + `MediaStore.Files` 补漏 + 磁盘直扫未入库文件）+ 文件头二次确认，自动去重、排序、按专辑 / 艺术家 / 文件夹归类；支持收藏、最近播放、播放次数统计。
- **外部打开** —— 文件管理器 / 其他应用用「打开方式」选 Sonance 即可直接播放（`ACTION_VIEW`，含无 MIME 时按扩展名兜底）；优先绑定曲库已有曲目，其次查 MediaStore，最后读标签作为临时曲目（临时曲目不可收藏）。
- **播放页手势** —— 上下滑动切换「晕染背景」模式，颜色过渡无缝；左右滑动在歌词与律动柱之间切换。
- **音频可视化** —— 自研 FFT + AGC 的律动柱；物理模拟用解析解实现，帧率无关，60Hz / 120Hz 下衰减速度一致。
- **软件均衡器** —— 64 位双精度 biquad 的 10 段图形 EQ，绕开系统 `AudioEffect` 在部分机型（如 ColorOS）被拒的兼容坑；取样点在 EQ 之后，可视化读到的是「用户实际听到的声音」。
- **音效工作室** —— 全屏四页签（音色预设 / 均衡器 / 声场效果 / 伴奏消音）：13 套按设备分类的预设、竖向推子与精细滑条双视图、A/B 旁通对比、虚拟低音与 Freeverb 混响、BS2B 双耳交叉馈送、外放高通保护、频带限制的中置人声消除；可按输出设备（外放 / 平板 / 有线 / 蓝牙 / USB）自动记忆音色（默认关闭）。
- **封面 / 歌词** —— 读取音频内嵌的封面与歌词；可选联网补全（iTunes 封面 / LrcAPI 歌词），支持手动搜索 / 导入 `.lrc`·`.txt` / 删除歌词，并支持把下载到的封面**写回原文件**（ID3v2.3，保留原有帧）。
- **在线音乐** —— 搜索、试听、播放并下载，落盘走 MediaStore；可登录网易云账号（扫码 / 短信验证码 / 密码 / Cookie 四种方式），按会员权益播放 VIP 曲目，取流走官方 EAPI 以保证无损与 Hi-Res 完整下发；音质可选 128k / 192k / 320k / 无损 / Hi-Res 并如实显示实际档位。登录后首页为「杂志画报」版（每日私人推荐 / 我喜欢的 / 私人 FM / 云歌单 / 精选探索广场）。
- **桌面小组件** —— 10 套（5 列与 4 列各 5 种尺寸，各自独立 receiver，共用同一套渲染基类）；封面改圆角方图，莫兰迪取色压制饱和度（黑白封面锁 0），卡片明暗可选「深色烟熏 / 浅色磨砂 / 跟随皮肤」；封面解码 ≤384px、背景 ≤240px 以控住 RemoteViews 1MB 预算。
- **D-LAN（DLNA / UPnP）** —— 纯 Kotlin 实现：SSDP 发现 + 手动 IP 探测、`ContentDirectory:Browse` 浏览 NAS / 电脑共享目录并直连播放、`AVTransport` / `RenderingControl` 向音箱电视投屏与远程遥控；本机内置只服务已登记条目的 HTTP/1.1 Range 服务（支持 206 拖进度），不暴露目录。
- **桌面图标变体** —— 53 款自适应图标（`activity-alias` 实现），设置里点一下即时切换，不用重装。
- **Hi-Res 小金标** —— 播放页封面右下角展示「Hi·Res AUDIO」金标；判定基于音频流真实参数（采样率 / 位深 / 码率 / 容器），本地无损与 ≥250kbps 有损都算，可开关与选尺寸。
- **多容器标签编辑** —— 歌名 / 歌手 / 专辑可写回 `mp3`、`m4a`/`mp4`/`3gp`、`aac`、`flac`、`ogg`/`opus`、`wav`、`aiff`、`dsf`；各容器按自身规范改写（Vorbis Comment、Ogg CRC 重算、LIST INFO、尾部 ID3 等），先改副本再写回。

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

### v2.4（2026-11-14）

- **D-LAN 无线投屏与局域网曲库** —— 新增 `data/dlna`（`DlnaClient` SSDP/探测 + Browse + AVTransport）、`DlnaXmlParser`（纯 JDK DOM，禁用 DOCTYPE 与外部实体）、`DlnaHttpMediaServer`（Range 流媒体，仅暴露登记条目）、`DlnaScreen`（局域网曲库 / 投屏设备两分页 + 播放页快速投屏面板）；ViewModel 用 `dlnaSongMap` + `dlnaRevision` 接入队列，`effectivePlayerState` 把远端状态映射回 UI；新增 `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`，`network_security_config` 的 `base-config` 放开明文（设备 IP 无法枚举）。
- **全屏音效工作室** —— `EqualizerStudioScreen` 四页签：13 套按设备分类的预设（自然 / 耳机 / 外放 / 空间）、10 段 EQ 双视图（竖向混音台 / 精细滑条）、A/B 旁通、声场微调、伴奏消音、按输出设备自动记忆（`OutputAudioRouter` 监听 `AudioDeviceCallback`，默认关闭）。
- **音效链重写** —— 新增 `PcmCodec` 统一 16/24/32 位与 float PCM 读写（16 位 TPDF 抖动）、共用 64 位 RBJ biquad；EQ 改双精度 DF2T 状态修掉 96/192 kHz 低频极点漂移；`effectPreampBudgetDb` 统一余量（声场按 `3*log10(width)`，旧版 12 倍会误压 4~5 dB 中频）；外放先合成低音谐波再过高通；伴奏消音改为 160 Hz–4.8 kHz 频带限制中置消除，修掉旧版砍空低频与单声道反相静音；USB 独占切歌时按曲目规格动态重协商（`syncTrackFormat`）。
- **桌面小组件扩到 10 套** —— 5 列保留 + 4 列新增（2×1 / 4×1 / 2×2 / 4×2 / 4×4）；`WidgetSizing` 纯 Kotlin 尺寸计算（封面 ≤384px、背景 ≤240px，控 RemoteViews 1MB 预算）；`WidgetAuraPalette` 莫兰迪取色（黑白封面锁 S=0，深色烟熏 S≤20% / L 10.5~17%，浅色磨砂 S≤9% / L 88~94%）；新增「卡片明暗质感」三档。
- **封面墙几何抽纯函数** —— `NativeWallFocusLayout` 按竖屏 / 横屏 / 平板分栏计算卡片与黑胶展开后的实际边界；状态栏隐藏但图标仍按安全区落位，横屏纳入左右异形屏与导航栏边距。
- **在线搜索** —— 可播统计与「仅看可播」并入工具条；长按单曲直接下载、多选批量下载，已存本地的标出（点击播本地文件）。
- **歌词** —— 新增「一键复制 AI 提示词」（`buildAiLrcPrompt`，自动带歌名 / 歌手 / 时长约束）。
- **其他** —— 「最近」页加统计与一键清空；悬浮卡片在歌手行下显示进度与总时长；Hi-Res 金标在横屏 / 平板且右侧有余量时移到封面右边靠底部。
- **测试** —— 新增 `DlnaProtocolTest`(6)、`WidgetSizingTest`(4)、`WidgetAuraPaletteTest`(5)、`NativeWallLayoutTest`(3)、`TransitionEffectsTest`(7)，`SoundChainDspTest` / `SoundFxDspTest` 扩充到 17 / 16；全量 62 组 652 个用例通过。
- **从文件管理器直接打开音频** —— 新增 `ACTION_VIEW` 入口（`audio/*`、`application/ogg`·`flac`·`itunes`，以及按扩展名兜底的 `content`/`file` 过滤器）；`ExternalAudioResolver` 按「曲库内存匹配（媒体 id → 目录+文件名 → 文件名+字节数 → 唯一同名）→ 直查 MediaStore → 读标签造临时曲目」三级解析，命中即亮选中态并滚动定位。无存储权限时不再被权限墙拦住。
- **曲库扫描补三层兜底** —— Pass 1 去掉 `IS_MUSIC != 0`（下载目录与无标签文件常被判 0）；Pass 2 用 `MediaStore.Files` 捕获未归类的 `.opus`/`.oga`/`.mka`/`.flac` 并排除视频容器；Pass 3 磁盘直扫（≤4000 目录 / 8 层，剪枝 `Android`/`DCIM`/`Pictures`/`Movies`/缓存，`.nomedia` 音乐目录白名单），最多 512 个回写系统索引。未入库文件用路径哈希合成 id，与在线负数 id 严格隔离。
- **录音目录判定改为路径段精确匹配** —— 修掉 `Music/Live Recordings`、`Sun Recording Studio`、`The Alarm` 这类专辑被整目录误杀。
- **封面读取兜底** —— FileProvider / 授权 Uri 直接 `setDataSource` 失败时改走 `openFileDescriptor`。
- **进度条时间轴等宽对齐** —— 时间改用 `tnum` 等宽数字，进度块改 `Box` 对齐，倍速胶囊不再随秒数跳动左右晃。
- **登录后首页变成杂志画报** —— 非对称 Bento（每日私人推荐 / 我喜欢的 / 私人 FM）→ 我的云歌单横向卷轴 → 精选探索广场（榜单横卡 + 推荐双列网格）；未登录保持简化推荐页。推荐歌单支持分页加载更多。
- **四种登录方式** —— 扫码 / 手机验证码 / 账号密码 / 粘贴 Cookie；账号入口移到在线页右上角，点开是账号管理弹窗（会员身份、切音质、换账号、退出）。
- **取流改走官方 EAPI** —— PC 客户端签名优先，其次 Android 通道，最后回落兼容 `/api`，无损与 Hi-Res 稳定拿到完整流；试听判定加入「已知总时长 × 实际下发大小」对照，识别接口未注明却只有 30 秒的截断流。
- **Hi-Res 小金标** —— 播放页封面右下角「Hi·Res AUDIO」金标，脱离封面缩放层不随呼吸抖动，可开关与选尺寸。
- **全格式标签编辑** —— 新增 `MultiFormatTagEditor`，支持 FLAC / OGG·OPUS / WAV / AIFF·AIFC / DSF / AAC / 3GP 写歌名·歌手·专辑，各容器按自身规范改写；先改副本、成功才写回。
- **歌词可手动干预** —— 手动搜索、导入本地 `.lrc`·`.txt`、删除歌词（删除优先级最高）；导入按 UTF-8 严格校验、失败回退 GB18030，老 GBK 歌词不再乱码；内嵌歌词优先于在线。
- **歌单批量操作** —— 歌单详情页多选后可批量下载、加入播放队列。
- **修冷启动在线队列显示「未知歌曲」** —— 恢复时补写 `onlineSongMap` 与版本号，并优先替换为已下载的本地副本。
- **修切换音质被兜底外链顶掉** —— 改为原地更新 metadata，保留已解析的播放地址，正在播的无损流不会被试听外链替换。
- **在线曲库接入网易云账号** —— 扫码登录，Cookie 用 Keystore 加密存本机；登录会员后可播放 VIP 曲目，未登录限于免费开放曲目。
- **音质可选且如实显示** —— 标准 / 较高 / 极高人人可选，无损与 Hi-Res 仅会员可见；播放页在专辑后标出实际拿到的档位（320k / 无损 / 试听），只给到试听片段时标「试听」而不是假装无损。
- **在线列表状态更准** —— VIP 徽标区分「权益已放行」（强调色）与「需会员」（中性色），非 VIP 却取不到地址的标「无版权」；底部说明随登录态变化。
- **下载跟随播放档位** —— 无损落 `.flac`（不写 ID3，避免产出打不开的文件），其余落 `.mp3` 并写入歌名 / 歌手 / 专辑 / 封面标签；取流按接口限频只预取接下来 3 首。
- **50 多款全新应用图标** —— 整套重做为有名字的设计（回响之钮、黑胶唱机、电子管、卡林巴、水墨竹、街机幽灵、悬浮盆景……），替换原 `s1_1`~`s5_8` 编号方案，清单与颜色表同步重命名。
- **换歌晕染支持跟手** —— 左右滑动时背景光晕按分页器偏移在相邻两首颜色间实时混合；程序跳页仍走渐变。修掉快滑时「页停了没换歌」与旧结果把分页器倒拽回上一首。
- **播放页顶栏重做** —— 左右对称微磨砂胶囊、律动指示器按整条栏居中、左侧大半片区域可点击收起。
- **悬浮卡片玻璃重做 + 两种滑动动效** —— 外缘柔和投影（挖空内区避免矩形暗斑）、主题派生内描边；新增「整卡滑动 / 槽内滑动」可切。
- **列表「定位到正在播放」** —— 仅列表视图、当前歌不在可视区时出现的准星圆钮。
- **性能** —— 全屏播放页盖稳后冻结底层主界面与玻璃模糊背景；通知与小组件同步移出主线程并原子合并；液态播放键的水波纹与呼吸全部在绘制阶段读状态。
- 封面墙「正在播放」高亮加强（主色提亮 40% / 内环 3dp / 光晕 22%）；小组件圆环改用样式固定种子色，不再从封面取色。

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
