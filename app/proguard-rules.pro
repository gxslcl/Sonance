# --------------------------------------------------------------------------
# Sonance: 移除全量 -keep，让 R8 真正能裁剪 + 内联 + 优化。
# 之前那个 -keep class com.sonance.player.** 把整个项目都保留下来了，
# R8 等于只跑了半天，等同于关闭。下面只保留 Android 入口 + Media3 必需的。
# --------------------------------------------------------------------------

# Android 入口类（清单里声明的）—— Activity / Service / Receiver 的
# 框架回调方法必须按名保留。AGP 默认的 proguard-android-optimize.txt
# 已经处理了 androidx.* 与 kotlin 元数据，下面只补项目自己需要的。
-keep class com.sonance.player.SonanceApplication { *; }
-keep class com.sonance.player.MainActivity { *; }
-keep class com.sonance.player.playback.PlaybackService { *; }

# Media3 的 MediaSessionService 是按名 new 出来的，类 + 公开构造必须保留。
# Media3 自己的 consumer rules 已经覆盖大部分，这里只补显式扩展类。
-keep public class * extends androidx.media3.session.MediaSessionService {
    public <init>(...);
}
-keep public class * extends androidx.media3.session.MediaSession {
    public <init>(...);
}
-keep public class * extends androidx.media3.session.MediaBrowserServiceCompat {
    public <init>(...);
}

# Media3 内部用了反射读一些字段。保留必要的接口契约。
-keep interface androidx.media3.common.Player { *; }

# 视图绑定 / 数据绑定反射需要 View 构造可见。
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# SonanceIcons 里 ImageVector.Builder 用反射不存在的资源，所以保留 icon 类。
-keep class com.sonance.player.ui.icon.SonanceIcons$* { *; }

# 别再 -keep 整个项目，让 R8 真正起作用。
# Shizuku：newProcess 在 13.x 里是 private，我们靠反射按名字调它。
# R8 会把没被直接引用的 private 方法改名，反射就找不到了 —— 整个类保住。
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep interface rikka.shizuku.Shizuku$OnRequestPermissionResultListener { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
