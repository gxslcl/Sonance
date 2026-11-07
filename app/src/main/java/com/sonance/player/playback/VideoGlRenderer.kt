package com.sonance.player.playback

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** 提亮的四档。存的是 ordinal，读到脏值时夹范围而不是崩 */
enum class GainLevel(val gain: Float, val label: String) {
    OFF(1.0f, "关"),
    WEAK(1.15f, "弱"),
    MEDIUM(1.30f, "中"),
    STRONG(1.45f, "强");

    val isActive: Boolean get() = this != OFF

    companion object {
        fun fromOrdinal(value: Int): GainLevel =
            entries.getOrNull(value.coerceIn(0, entries.size - 1)) ?: OFF
    }
}

/**
 * 画面提亮的 GL 渲染层。
 *
 * ## 为什么必须换 TextureView
 *
 * `SurfaceView` 的内容在独立图层，应用窗口的 RenderEffect / 颜色矩阵都作用不到
 * 视频像素；也不支持在送显前插入自己的 shader。`TextureView` 走 GPU 合成，
 * SurfaceTexture 拿到的外部纹理可以被我们自己的 EGL 上下文采样，从而插入
 * 一次全屏 pass 做色调映射。
 *
 * ## 三个必须守住的点
 *
 * 1. **亮度保持型增益**：不是对 RGB 逐通道乘系数，而是先算 luma、按曲线得到
 *    新 luma，再用 `L'/L` 整体缩放。逐通道缩放会改变 R:G:B 比例，暗部量化
 *    误差比例大，肤色和饱和色最先崩。
 * 2. **soft knee**：输出到 [KNEE_START] 后斜率连续滚降，端点固定 (0,0)、(1,1)，
 *    保证单调、不超调。硬裁切会把高光直接压成平板。
 * 3. **limited range**：BT.709 limited 的 Y 是 16~235。抬升后必须夹回 16~235，
 *    按 0~255 夹会让黑位飘到 16 以下、白点越过 235 变成超白。
 *
 * 增益 = OFF 时**不创建 EGL 上下文、不编译 shader**，直接把 SurfaceTexture
 * 交给 TextureView 默认的 GL 管线显示，零成本、零画质损失。
 *
 * 所有渲染都在 [renderThread] 上串行执行，[frame] 是唯一入口。
 */
class VideoGlRenderer(
    private val tag: String,
    private val logTag: String
) {

    /** 外层 TextureView 的尺寸（像素） */
    @Volatile
    private var viewWidth = 1
    @Volatile
    private var viewHeight = 1

    /** 视频尺寸，由 MediaPlayer 的 onVideoSizeChanged 送进来 */
    @Volatile
    private var videoWidth = 0
    @Volatile
    private var videoHeight = 0
    /** 像素宽高比（SAR）。非方形像素的片源要折算进显示比例 */
    @Volatile
    private var videoSarNum = 1
    @Volatile
    private var videoSarDen = 1
    @Volatile
    private var rotationDegrees = 0

    /** HDR 内容：直接旁路（gain 视为 1.0） */
    @Volatile
    private var hdrContent = false

    @Volatile
    private var gain = 1.0f

    /** 渲染线程与 EGL 资源。全部只在 [renderThread] 上创建/销毁 */
    private var renderThread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var eglReady = false
    /** 初始化失败（EGL / shader 编译）后永久回退，不再重试，避免反复失败刷日志 */
    @Volatile
    private var giveUp = false

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var eglWindowTarget: android.view.Surface? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uGainLoc = 0
    private var uKneeLoc = 0
    private var uScaleLoc = 0
    private var uTexMatrixLoc = 0

    private var textureId = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null

    private var surfaceTexture: SurfaceTexture? = null

    /** 有新视频帧可取（SurfaceTexture 回调置位，渲染线程消费） */
    @Volatile
    private var frameAvailable = false

    /**
     * 需要重画：切档、改尺寸这些"uniform 变了但可能没有新帧"的情况置位。
     * 只靠新帧触发的话，暂停状态下切档永远看不到变化。
     */
    @Volatile
    private var dirty = true

    /** 是否已经成功取到过至少一帧纹理内容（否则重画会把空纹理刷成黑屏） */
    private var hasFrame = false

    private companion object {
        /** 软拐点起点：输出到这个比例后开始滚降 */
        const val KNEE_START = 0.85f

        /** limited range 的上下限（8-bit） */
        const val BLACK_8 = 16.0f
        const val WHITE_8 = 235.0f

        /** luma 保护：低于这个值不做缩放（避免 0 除和把纯黑抬成灰） */
        const val LUMA_EPS = 0.004f

        /** 连续几帧渲染刷新一次 SurfaceTexture，保证 MediaPlayer 帧率不被 30fps 限住 */
        const val FRAME_RATE = 60

        const val FLOAT_SIZE = 4

        val QUAD_POSITIONS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        /**
         * 顶点着色器：单位四边形 + 视图用的 uv。
         *
         * uv 只走 SurfaceTexture 自带的变换矩阵（部分机型的翻转/旋转靠它）；
         * 宽高比适配交给 `uScale` 缩放四边形，不留拉伸。
         */
        const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
varying vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

        /**
         * 片元着色器：luma 保持型增益 + soft knee + limited range 夹取。
         *
         * ## 为什么在 RGB 上做而不是 Y 通道
         *
         * MediaPlayer 经 SurfaceTexture 送出来的是已经由硬件转成 RGB 的
         * `GL_TEXTURE_EXTERNAL_OES` 纹理，拿不到原始 Y 平面（要拿 Y 就得自己用
         * MediaCodec 解码，等于重写播放器）。所以这里算 luma、按曲线求新 luma，
         * 再整体乘 `L'/L` —— 数学上等价于只改 Y，色度和色相比例保持不变。
         *
         * ## 恒等分支
         *
         * `uKnee <= 0.0`（= 档位关闭 / HDR 旁路）时逐像素原样返回：
         * 这不是"接近无损"，是真正零处理，连 luma 都不算。
         */
        const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uGain;
uniform float uKnee;
varying vec2 vTexCoord;

// BT.709 limited：16~235 码值
const float LOW  = 0.0627451;
const float HIGH = 0.9215686;
const float SPAN = 0.8588235;
// 近黑保护：低于这个 luma 直接不动，避免把量化噪声放大成色斑
const float EPS = 0.004;

float toneCurve(float n) {
    float s = n * uGain;
    if (s <= uKnee) return s;
    // soft knee：分母按实际峰值 uGain 归一，保证 t 恰好在 s=uGain 时到 1。
    // 若按固定 1-k 归一，强档会把 0.69 以上的输入全部压成死白。
    float t = min((s - uKnee) / (uGain - uKnee), 1.0);
    float e = 1.0 - t;
    return uKnee + (1.0 - uKnee) * (1.0 - e * e * e);
}

void main() {
    vec4 c = texture2D(uTexture, vTexCoord);
    if (uKnee <= 0.0) { gl_FragColor = c; return; }

    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma <= EPS) { gl_FragColor = c; return; }

    // 还原到 limited 码值域按 16~235 处理，处理完再夹回 16~235
    float lim = LOW + SPAN * luma;
    float n = clamp((lim - LOW) / SPAN, 0.0, 1.0);
    float outLim = clamp(LOW + SPAN * toneCurve(n), LOW, HIGH);
    float outLuma = (outLim - LOW) / SPAN;

    // 亮度保持型增益：只改 luma 幅度，R:G:B 比例不变 → 不漂色
    float scale = outLuma / luma;
    gl_FragColor = vec4(clamp(c.rgb * scale, 0.0, 1.0), c.a);
}
"""
    }

    // ===== 外部调用（任意线程）=====

    fun updateViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewWidth = width
            viewHeight = height
            dirty = true
        }
    }

    fun updateVideoSize(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            videoSarNum = if (sarNum > 0) sarNum else 1
            videoSarDen = if (sarDen > 0) sarDen else 1
            dirty = true
        }
    }

    fun setRotation(degrees: Int) {
        rotationDegrees = ((degrees % 360) + 360) % 360
        dirty = true
    }

    /** HDR 内容直接旁路：不套用 SDR 曲线 */
    fun setHdr(hdr: Boolean) {
        hdrContent = hdr
    }

    /** 档位切换。OFF 或 HDR 时 uKnee 为 0，shader 走恒等分支 */
    fun updateGain(level: GainLevel) {
        val effective = if (hdrContent || !level.isActive) 1.0f else level.gain
        if (effective != gain) {
            gain = effective
            Log.i(logTag, "gain=$effective level=${level.label} hdr=$hdrContent")
        }
        // 切档时可能没有新帧（暂停/刚 seek），主动重画一次才能"点了就变"
        dirty = true
    }

    /** 初始化是否还没失败（外层用它决定是否走 TextureView 路径） */
    fun isUsable(): Boolean = !giveUp

    /** 已经接管画面（成功出过一帧），外层据此停掉首帧图 */
    fun hasOutput(): Boolean = rendered

    /**
     * 允许开始出图。
     *
     * 必须是"外层已经把 MediaPlayer 绑到本渲染器的输入 Surface 之后"才调用：
     * 同一个 BufferQueue 不能有两个生产者，先出图再切 surface 会打架。
     */
    fun beginDraw() {
        dirty = true
        drawing = true
    }

    @Volatile
    private var drawing = false

    /**
     * 启动渲染线程。只在档位不是 OFF 时调用 —— OFF 时完全不碰 EGL，
     * 视频 Surface 直接交给 TextureView 自己显示。
     *
     * 线程创建后先做 EGL + shader 初始化，失败就 [giveUp]，外层回退到默认显示。
     */
    fun start(output: SurfaceTexture) {
        if (running || giveUp) return
        running = true
        val thread = Thread({ loop(output) }, "$tag-gl").apply {
            priority = Thread.NORM_PRIORITY
        }
        renderThread = thread
        thread.start()
    }

    /** 停止线程并释放全部 EGL 资源（页面销毁 / 重试重建时调用） */
    fun dispose() {
        rendered = false
        running = false
        val thread = renderThread
        renderThread = null
        // EGL 资源必须在创建它的线程上销毁，所以释放放在 loop() 的 finally 里，
        // 这里只负责等它退出
        if (thread != null) runCatching { thread.join(600) }
        eglReady = false
    }

    /** 由渲染线程回调，外层拿到后交给 MediaPlayer 的 setSurface */
    @Volatile
    var onTextureReady: ((SurfaceTexture) -> Unit)? = null

    /** 初始化/渲染失败回调（渲染线程上调用，外层负责切回主线程并回退） */
    @Volatile
    var onFailure: ((String) -> Unit)? = null

    // ===== 渲染线程内部 =====

    private fun loop(output: SurfaceTexture) {
        try {
            if (!initEgl(output)) {
                giveUp = true
                Log.w(logTag, "GL 初始化失败，回退到默认显示")
                onFailure?.invoke("egl_init")
                return
            }
            eglReady = true
            Log.i(logTag, "GL 就绪: program=$program texture=$textureId")
            var frames = 0L
            while (running) {
                if (!drawing) {
                    // 等外层把 MediaPlayer 切到本渲染器的输入 Surface，避免双生产者
                    Thread.sleep(4)
                    continue
                }
                val st = surfaceTexture
                if (st == null) {
                    Thread.sleep(4)
                    continue
                }
                val newFrame = frameAvailable
                if (newFrame) {
                    frameAvailable = false
                    if (!updateVideoFrame(st)) {
                        Thread.sleep(2)
                        continue
                    }
                    hasFrame = true
                }
                // 有新帧才取纹理；切档/改尺寸时用上一帧重画，保证"点了就变"。
                // 不能再无条件换帧重画 —— 实测那样是 ~230fps 空转，4K 场景纯属浪费 GPU。
                if (hasFrame && (newFrame || dirty)) {
                    dirty = false
                    drawFrame(st)
                    frames++
                    if (frames % 600L == 0L) {
                        Log.d(logTag, "GL 出图 $frames 帧 size=${viewWidth}x$viewHeight")
                    }
                }
                Thread.sleep(2)
            }
        } catch (t: Throwable) {
            // 兼容性兜底：任何异常都只回退，不让它冒到 UI
            giveUp = true
            Log.w(logTag, "GL 渲染异常，回退到默认显示: ${t.javaClass.simpleName} ${t.message}")
            onFailure?.invoke("render_exception")
        } finally {
            releaseGl()
            eglReady = false
            Log.i(logTag, "GL 已释放")
        }
    }

    private fun updateVideoFrame(st: SurfaceTexture): Boolean =
        runCatching { st.updateTexImage() }.isSuccess

    private fun initEgl(output: SurfaceTexture): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(logTag, "EGL 失败: eglGetDisplay")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(logTag, "EGL 失败: eglInitialize err=${eglError()}")
            return false
        }
        eglDisplay = display

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.w(logTag, "EGL 失败: eglChooseConfig err=${eglError()}")
            return false
        }
        val config = configs[0] ?: return false
        eglConfig = config

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            Log.w(logTag, "EGL 失败: eglCreateContext err=${eglError()}")
            return false
        }
        eglContext = ctx

        // 输出目标 = TextureView 的 SurfaceTexture（我们是它的生产者）
        val windowTarget = android.view.Surface(output)
        eglWindowTarget = windowTarget
        // TextureView 的 SurfaceTexture 只能有一个生产者：MediaPlayer 还占着这个槽位时
        // eglCreateWindowSurface 会直接失败。外层已先 setSurface(null) 解绑，这里再做
        // 几次短重试兜住解绑的异步性（实测失败就是这一句）。
        var windowSurface: EGLSurface? = null
        for (attempt in 1..3) {
            val s = EGL14.eglCreateWindowSurface(
                display, config, windowTarget, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (s != null && s != EGL14.EGL_NO_SURFACE) {
                windowSurface = s
                break
            }
            Log.w(logTag, "EGL 失败: eglCreateWindowSurface 第 $attempt 次 err=${eglError()}")
            try {
                Thread.sleep(30)
            } catch (ignored: InterruptedException) {
            }
        }
        val surface = windowSurface
        if (surface == null) {
            Log.w(logTag, "EGL 失败: 无可用的窗口表面，测试面可能正被解码器占用")
            return false
        }
        eglSurface = surface
        if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) {
            Log.w(logTag, "EGL 失败: eglMakeCurrent err=${eglError()}")
            return false
        }

        if (!compileProgram()) return false

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        if (textureId == 0) return false
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // 在 GL 线程建 SurfaceTexture（构造时需要当前上下文），再交给外层
        val st = SurfaceTexture(textureId)
        // 只在有新帧时出图：不设监听的话只能靠死循环空转，实测 1080p 内容会跑到 ~230fps
        st.setOnFrameAvailableListener { frameAvailable = true }
        surfaceTexture = st
        frameAvailable = false
        dirty = true
        onTextureReady?.invoke(st)
        return true
    }

    private fun eglError(): String {
        val code = EGL14.eglGetError()
        return "0x${Integer.toHexString(code)}"
    }

    private fun compileProgram(): Boolean {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(logTag, "program 链接失败: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return false
        }
        program = p
        aPositionLoc = GLES20.glGetAttribLocation(p, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(p, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(p, "uTexture")
        uGainLoc = GLES20.glGetUniformLocation(p, "uGain")
        uKneeLoc = GLES20.glGetUniformLocation(p, "uKnee")
        uScaleLoc = GLES20.glGetUniformLocation(p, "uScale")
        uTexMatrixLoc = GLES20.glGetUniformLocation(p, "uTexMatrix")
        if (aPositionLoc < 0 || aTexCoordLoc < 0 || uKneeLoc < 0) {
            Log.w(logTag, "shader uniform/attribute 缺失")
            return false
        }
        return true
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            Log.w(logTag, "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun drawFrame(st: SurfaceTexture) {
        val w = viewWidth
        val h = viewHeight
        if (w <= 0 || h <= 0) return
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val matrix = FloatArray(16)
        st.getTransformMatrix(matrix)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, matrix, 0)

        val scale = fitScale(w, h)
        GLES20.glUniform2f(uScaleLoc, scale[0], scale[1])

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uGainLoc, gain)
        // 只有真正要提亮时才启用 knee；OFF / HDR 走逐像素恒等
        GLES20.glUniform1f(uKneeLoc, if (gain > 1.0f) KNEE_START else 0f)

        val quad = FloatBufferHolder.of(QUAD_POSITIONS)
        val uv = FloatBufferHolder.of(QUAD_TEX_COORDS)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!rendered) rendered = true
    }

    @Volatile
    private var rendered = false

    /**
     * 等比适配：返回四边形的缩放系数 `(sx, sy)`，多出来的区域留黑边。
     *
     * 与 VideoView 的默认行为一致 —— 不拉伸、不变形；取景框只有 9:16，
     * 横向视频会上下留黑，竖向视频会左右留黑。
     */
    private fun fitScale(viewW: Int, viewH: Int): FloatArray {
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0) return floatArrayOf(1f, 1f)
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val srcW = if (rotated) vh else vw
        val srcH = if (rotated) vw else vh
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        // SAR（像素宽高比）≠ 1 的片源必须折算进显示比例，否则会横向/纵向拉伸
        val srcAspect = srcW.toFloat() * videoSarNum / (srcH.toFloat() * videoSarDen)
        return if (srcAspect > viewAspect) {
            // 视频更宽 → 上下留黑
            floatArrayOf(1f, viewAspect / srcAspect)
        } else {
            // 视频更高 → 左右留黑
            floatArrayOf(srcAspect / viewAspect, 1f)
        }
    }

    private fun releaseGl() {
        val display = eglDisplay
        if (display != null) {
            runCatching {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        runCatching { eglWindowTarget?.release() }
        eglWindowTarget = null
        program = 0
        textureId = 0
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }
}

/** 复用同一块 direct buffer，避免每帧分配 */
private object FloatBufferHolder {
    private val cache = HashMap<FloatArray, FloatBuffer>()

    fun of(data: FloatArray): FloatBuffer = cache.getOrPut(data) {
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
    }
}
