package com.sonance.player.ui.wall

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sonance.player.data.Album
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.InlineLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

private fun wallNow(): Long = SystemClock.uptimeMillis()
private val WallBackground = Color(0xFF050507)

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun NativeCoverWallScreen(
    state: PlayerUiState,
    albums: List<Album>,
    songs: List<Song>,
    favorites: Set<Long>,
    onClose: () -> Unit,
    onPlayAlbum: (WallAlbum, Int) -> Unit,
    onQueueAlbum: (WallAlbum) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val density = LocalDensity.current
    val d = density.density
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentState = rememberUpdatedState(state)
    val cbClose = rememberUpdatedState(onClose)
    val cbPlay = rememberUpdatedState(onPlayAlbum)
    val cbQueue = rememberUpdatedState(onQueueAlbum)
    val cbToggle = rememberUpdatedState(onTogglePlay)
    val cbPrev = rememberUpdatedState(onPrev)
    val cbNext = rememberUpdatedState(onNext)
    val cbSeek = rememberUpdatedState(onSeek)
    val wallAlbums = remember(albums, songs, favorites) { buildWallAlbums(albums, songs, favorites) }
    val tune = remember { WallTune().also { loadTune(context, it) } }
    val engine = remember { WallEngine(tune).apply { markInput(wallNow()) } }
    val painter = remember(d) { WallCardPainter(d) }
    val cache = remember {
        object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val textures = remember { Channel<Unit>(Channel.CONFLATED) }
    val poses = remember { HashMap<String, WallPose>() }
    val visibleTiles = remember { ArrayList<WallTile>() }
    val pullJob = remember { arrayOfNulls<Job>(1) }
    var layout by remember { mutableStateOf<WallLayout?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var drawTick by remember { mutableStateOf(0L) }
    var resumed by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    // 状态栏在本页隐藏，卡片内容冲顶；图标仍按状态栏/刘海高度落位，横屏并同时纳入左右异形屏/导航栏安全边距。
    val layoutDir = LocalLayoutDirection.current
    val top = 0f
    val barsTop = max(
        WindowInsets.statusBarsIgnoringVisibility.getTop(density),
        WindowInsets.displayCutout.getTop(density)
    ).toFloat()
    val barsTopLandscape = max(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.displayCutout.getTop(density)
    ).toFloat()
    val bottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    val safeLeft = max(
        WindowInsets.navigationBars.getLeft(density, layoutDir),
        WindowInsets.displayCutout.getLeft(density, layoutDir)
    ).toFloat()
    val safeRight = max(
        WindowInsets.navigationBars.getRight(density, layoutDir),
        WindowInsets.displayCutout.getRight(density, layoutDir)
    ).toFloat()
    var tuning by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    var mini by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<WallTile?>(null) }
    var preview by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var focusBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val flight = remember { Animatable(0f) }
    val flip = remember { Animatable(0f) }
    val disc = remember { Animatable(0f) }
    val pull = remember { Animatable(0f) }
    val queueFlight = remember { Animatable(0f) }
    val sourcePose = remember { WallPose() }
    val heroPose = remember { WallPose() }
    val focusJob = remember { arrayOfNulls<Job>(1) }
    val spinJob = remember { arrayOfNulls<Job>(1) }
    val recent = remember { ArrayDeque<String>() }
    val lastHaptic = remember { LongArray(5) }
    val heavyUntil = remember { longArrayOf(0L) }
    var discAngle by remember { mutableStateOf(0f) }
    var drifting by remember { mutableStateOf(false) }

    fun wakeUp() {
        engine.dirty = true
        wake.trySend(Unit)
    }

    fun haptic(level: Int, label: String, duration: Int = 0) {
        val now = wallNow()
        if (!resumed || tune.haptics == "off") return
        if (level == 1 && (now < heavyUntil[0] || now - lastHaptic[1] < 35L)) return
        if (level > 1 && now - lastHaptic[level] < 60L) return
        lastHaptic[level] = now
        if (level >= 3) heavyUntil[0] = now + if (level == 4) 180L else 65L
        performWallHaptic(context, level, duration, tune.haptics)
    }

    val hapticRef = rememberUpdatedState(::haptic)

    fun cacheKey(t: WallTile): String =
        "${t.album.key}|${t.spec.title.length}:${t.spec.title}|${t.spec.artist.length}:${t.spec.artist}|${t.spec.style}|${if (t.size * d > 280f) 512 else 256}"

    suspend fun texture(t: WallTile): Bitmap {
        val key = cacheKey(t)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            ensureActive()
            renderCoverBitmap(t.spec, if (t.size * d > 280f) 512 else 256, 0f)
        }.also { cache.put(key, it) }
    }

    fun resetPull() {
        pullJob[0]?.cancel()
        pullJob[0] = scope.launch { pull.animateTo(0f, spring(0.75f, 350f)) }
    }

    fun releaseFocus() {
        pullJob[0]?.cancel()
        focusJob[0]?.cancel()
        scope.launch {
            flight.snapTo(0f); flip.snapTo(0f); disc.snapTo(0f)
            pull.snapTo(0f); queueFlight.snapTo(0f)
        }
        focus = null
        back = false
        closing = false
        focusBitmap = null
        engine.playerOpen = false
        engine.obscured = tuning
        engine.busy = false
        engine.current?.let { engine.releaseTile(it) }
        engine.markInput(wallNow())
        wakeUp()
    }

    fun closeFocus() {
        val t = focus ?: return
        if (closing) return
        closing = true
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            engine.busy = true
            flip.animateTo(0f, tween(if (back) 420 else 120, easing = FastOutSlowInEasing))
            back = false
            disc.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            poses[t.album.key]?.let { sourcePose.copyFrom(it) }
            val returnPull = launch {
                pull.animateTo(0f, tween(480, easing = FastOutSlowInEasing))
            }
            flight.animateTo(0f, tween(480, easing = FastOutSlowInEasing))
            returnPull.join()
            t.ps = 0.93f
            engine.releaseTile(t)
            engine.addRipple(t, tune.rippleAmp * 0.5f, wallNow())
            haptic(3, "插回墙上")
            releaseFocus()
        }
    }

    fun flipFocus(showBack: Boolean) {
        if (closing || engine.busy) return
        back = showBack
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            engine.busy = true
            if (showBack) disc.animateTo(0f, tween(220))
            haptic(3, if (showBack) "翻到背面" else "翻回封面")
            flip.animateTo(
                if (showBack) 1f else 0f,
                tween(if (showBack) 580 else 420, easing = FastOutSlowInEasing)
            )
            if (!showBack && tune.vinyl)
                disc.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            engine.busy = false
        }
    }

    fun openFocus(t: WallTile, details: Boolean, explicitIndex: Int? = null) {
        if (focus != null || closing) return
        engine.vel = 0f
        engine.anim = null
        engine.lockPending = false
        engine.dragging = false
        engine.pressing = null
        engine.playerOpen = true
        engine.obscured = true
        engine.busy = true
        engine.current = t
        engine.dimT = 0f
        engine.dimExcept = null
        focus = t
        back = details
        preview = false
        sourcePose.copyFrom(
            poses[t.album.key] ?: WallPose().apply {
                x = t.x * d
                y = (t.y - engine.scrollY) * d
                size = t.size * d
            }
        )
        val playingHere = t.album.tracks.any { it.id == currentState.value.mediaId }
        preview = !playingHere
        if (!details && (explicitIndex != null || (tune.autoPlay && !playingHere))) {
            preview = false
            cbPlay.value(t.album, explicitIndex ?: 0)
        }
        haptic(if (details) 3 else 4, if (details) "浮起" else "起飞")
        engine.addRipple(t, tune.rippleAmp, wallNow())
        focusJob[0]?.cancel()
        focusJob[0] = scope.launch {
            focusBitmap = texture(t)
            flight.snapTo(0f)
            flip.snapTo(0f)
            disc.snapTo(0f)
            pull.snapTo(0f)
            queueFlight.snapTo(0f)
            val flipJob = if (details) launch {
                delay(60)
                flip.animateTo(1f, tween(580, easing = FastOutSlowInEasing))
            } else null
            flight.animateTo(1f, tween(if (details) 520 else 660, easing = FastOutSlowInEasing))
            flipJob?.join()
            haptic(if (details) 1 else 3, if (details) "翻面落位" else "落位")
            if (!details && tune.vinyl)
                disc.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            engine.busy = false
        }
        wakeUp()
    }

    val openFocusRef = rememberUpdatedState(::openFocus)

    fun cancelSpin() {
        spinJob[0]?.cancel()
        engine.anim = null
        engine.vel = 0f
        engine.forceCurve = 0f
        engine.lockPending = false
        engine.dimT = 0f
        engine.dimExcept?.let { engine.releaseTile(it) }
        engine.dimExcept = null
        spinning = false
        haptic(2, "抓停")
        wakeUp()
    }

    fun randomListen() {
        if (focus != null || tuning) return
        if (spinning) {
            cancelSpin()
            return
        }
        val tiles = layout?.tiles ?: return
        val candidates = tiles.filter {
            it.album.key != engine.current?.album?.key && it.album.key !in recent
        }
        val target = candidates.randomOrNull() ?: tiles.randomOrNull() ?: return
        recent.addLast(target.album.key)
        if (recent.size > 20) recent.removeFirst()
        val to = clamp(target.y + target.size / 2f - engine.heightCss * 0.46f, 0f, engine.maxY)
        var from = engine.scrollY
        if (abs(to - from) > 3f * engine.heightCss)
            from = to - sign(to - from) * 2.2f * engine.heightCss
        if (abs(to - from) < 1.4f * engine.heightCss) {
            val candidate = clamp(
                to + (if (to < engine.maxY / 2f) 1f else -1f) * 2.2f * engine.heightCss,
                0f,
                engine.maxY
            )
            if (abs(candidate - to) > abs(from - to)) from = candidate
        }
        engine.scrollY = from
        engine.forceCurve = 1f
        engine.userMotion = true
        engine.markInput(wallNow())
        spinning = true
        haptic(4, "启动滚筒")
        textures.trySend(Unit)
        val duration = clamp(700f + abs(to - from) * 0.12f, 800f, 1250f)
        engine.anim = ScrollAnim(from, to, wallNow(), duration) {
            engine.dimT = 0.62f
            engine.dimExcept = target
            target.pst = 1.08f
            if (!target.active) {
                target.active = true
                engine.activeTiles.add(target)
            }
            engine.lockPending = true
            haptic(3, "锁定")
            spinJob[0] = scope.launch {
                delay(320)
                engine.lockPending = false
                spinning = false
                openFocusRef.value(
                    target,
                    false,
                    if (target.album.tracks.isEmpty()) 0 else Random.nextInt(target.album.tracks.size)
                )
            }
        }
        spinJob[0] = scope.launch {
            delay(220)
            engine.forceCurve = 0f
            wakeUp()
        }
        wakeUp()
    }

    // 队列耗尽（手动下一曲 / 自动播完末首）→ 收起聚焦，回到「随便听听」随机切歌。
    fun leaveToRandom() {
        closeFocus()
        scope.launch {
            withTimeoutOrNull(1600L) { while (focus != null) delay(50L) }
            randomListen()
        }
    }

    // 自动播完：顺序队列中间的续播由播放器完成；末首结束后回到「随便听听」继续随机。
    val endHandledId = remember { mutableStateOf(-1L) }
    LaunchedEffect(state.mediaId, state.isPlaybackActive) {
        if (preview || !state.hasItem || state.isPlaybackActive) return@LaunchedEffect
        val dur = state.durationMs
        if (dur <= 0L || state.positionMs < dur - 300L) return@LaunchedEffect
        if (endHandledId.value == state.mediaId) return@LaunchedEffect
        val tracks = focus?.album?.tracks ?: return@LaunchedEffect
        val idx = tracks.indexOfFirst { it.id == state.mediaId }
        if (idx < 0 || idx < tracks.size - 1) return@LaunchedEffect
        endHandledId.value = state.mediaId
        leaveToRandom()
    }

    DisposableEffect(owner) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
                engine.markInput(wallNow())
                controller?.hide(WindowInsetsCompat.Type.statusBars())
                wakeUp()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
                drifting = false
                engine.dragging = false
                engine.vel = 0f
                engine.pressing?.let { engine.releaseTile(it) }
                engine.pressing = null
                pullJob[0]?.cancel()
                scope.launch { pull.snapTo(0f) }
                cancelSpin()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.statusBars())
            cache.evictAll()
        }
    }

    DisposableEffect(tune.parallax, resumed, view) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = if (tune.parallax && resumed && sensor != null) object : SensorEventListener {
            var primed = false
            var baseX = 0f
            var baseY = 0f
            var x = 0f
            var y = 0f
            var last = 0L

            override fun onSensorChanged(e: SensorEvent) {
                var gx: Float
                var gy: Float
                if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    val a = e.values[0]
                    val b = e.values[1]
                    val c = e.values[2]
                    val w = if (e.values.size > 3) e.values[3]
                    else kotlin.math.sqrt(max(0f, 1f - a * a - b * b - c * c))
                    gx = 2f * (a * c - w * b)
                    gy = 2f * (b * c + w * a)
                } else {
                    gx = e.values[0] / SensorManager.GRAVITY_EARTH
                    gy = e.values[1] / SensorManager.GRAVITY_EARTH
                }
                val oldX = gx
                when (view.display?.rotation) {
                    Surface.ROTATION_90 -> {
                        gx = -gy
                        gy = oldX
                    }
                    Surface.ROTATION_180 -> {
                        gx = -gx
                        gy = -gy
                    }
                    Surface.ROTATION_270 -> {
                        gx = gy
                        gy = -oldX
                    }
                }
                if (!primed) {
                    baseX = gx
                    baseY = gy
                    x = gx
                    y = gy
                    primed = true
                }
                x += (gx - x) * 0.18f
                y += (gy - y) * 0.18f
                val now = wallNow()
                if (now - last < 32L) return
                last = now
                val tx = ((x - baseX) / 0.16f).coerceIn(-1f, 1f)
                val ty = ((y - baseY) / 0.16f).coerceIn(-1f, 1f)
                if (abs(tx - engine.gxT) < 0.008f && abs(ty - engine.gyT) < 0.008f) return
                engine.gxT = tx
                engine.gyT = ty
                wakeUp()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        } else null
        if (listener != null)
            manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        else {
            engine.gxT = 0f
            engine.gyT = 0f
            wakeUp()
        }
        onDispose {
            if (listener != null) manager?.unregisterListener(listener)
        }
    }

    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    LaunchedEffect(wallAlbums, viewport, tune.gap, tune.seed, d, isTablet) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val next = buildWallLayout(
            wallAlbums,
            viewport.width / d,
            viewport.height / d,
            tune.gap,
            tune.seed,
            isTablet = isTablet
        )
        engine.scrollY = clamp(engine.scrollY, 0f, next.maxY)
        for (t in next.tiles)
            if (t.y + t.size > engine.scrollY - 200 && t.y < engine.scrollY + viewport.height / d + 200)
                texture(t)
        engine.anim = null
        engine.activeTiles.clear()
        engine.pressing = null
        if (spinning) { spinJob[0]?.cancel(); engine.forceCurve = 0f; engine.lockPending = false; engine.dimT = 0f; engine.dimExcept?.let { engine.releaseTile(it) }; engine.dimExcept = null; spinning = false }
        engine.blockEdges = next.blockEdges
        engine.maxY = next.maxY
        engine.heightCss = viewport.height / d
        engine.widthCss = viewport.width / d
        engine.edgeIdx = -1
        if (layout == null) engine.intro = Intro(engine.widthCss / 2, engine.heightCss * 0.4f, wallNow())
        layout = next
        poses.clear()
        next.tiles.forEach { poses[it.album.key] = WallPose() }
        engine.current = next.tiles.firstOrNull {
            it.album.tracks.any { s -> s.id == currentState.value.mediaId }
        }
        if (focus != null && next.tiles.none { it.album.key == focus?.album?.key }) {
            focusJob[0]?.cancel()
            releaseFocus()
        }
        wakeUp()
        textures.trySend(Unit)
    }

    LaunchedEffect(layout, resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            textures.receive()
            val l = layout ?: continue
            val y = engine.scrollY
            val h = engine.heightCss
            for (t in l.tiles) {
                if (t.y + t.size < y - h || t.y > y + h * 2) continue
                if (cache.get(cacheKey(t)) == null) {
                    texture(t)
                    wakeUp()
                }
            }
        }
    }

    LaunchedEffect(state.mediaId, layout) {
        val tile = layout?.tiles?.firstOrNull {
            it.album.tracks.any { s -> s.id == state.mediaId }
        }
        engine.current = tile
        if (tile != null && focus != null && !back && !preview && !engine.busy &&
            !closing && focus?.album?.key != tile.album.key
        ) {
            focusBitmap = texture(tile)
            focus = tile
            engine.scrollY = clamp(tile.y + tile.size / 2 - engine.heightCss * 0.46f, 0f, engine.maxY)
            sourcePose.x = tile.x * d
            sourcePose.y = (tile.y - engine.scrollY) * d
            sourcePose.size = tile.size * d
            sourcePose.rx = 0f
            sourcePose.ry = 0f
            sourcePose.z = 0f
            sourcePose.scale = 1f
            textures.trySend(Unit)
        }
        wakeUp()
    }

    LaunchedEffect(tuning, focus) {
        engine.obscured = tuning || focus != null
        engine.markInput(wallNow())
        wakeUp()
    }

    LaunchedEffect(engine, layout, resumed) {
        if (layout == null || !resumed) return@LaunchedEffect
        var last = withFrameNanos { it }
        var requestedY = Float.NaN
        var pressHaptic = false
        while (isActive) {
            val frame = withFrameNanos { it }
            val now = wallNow()
            val driftNow = engine.driftCond(now)
            if (driftNow != drifting) drifting = driftNow
            val dt = ((frame - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = frame
            if (engine.pressing == null) pressHaptic = false
            else if (!pressHaptic && now - engine.pressStart >= 70 && !engine.pressMoved) {
                hapticRef.value(1, "按下", 9)
                pressHaptic = true
            }
            val needsDraw = engine.step(now, dt, hapticRef.value)
            if (engine.longFired && engine.pressing != null && !engine.pressMoved && focus == null) {
                val t = engine.pressing!!
                engine.releaseTile(t)
                engine.pressing = null
                openFocusRef.value(t, true, null)
            }
            if (needsDraw) drawTick = frame
            if (requestedY.isNaN() || abs(engine.scrollY - requestedY) > engine.heightCss * 0.35f) {
                requestedY = engine.scrollY
                textures.trySend(Unit)
            }
            if (abs(engine.vel) > 180f && engine.anim == null) {
                engine.fabLastScroll = now
                mini = true
            } else if (mini && now - engine.fabLastScroll > 900L) mini = false
            if (!engine.active(now) && !mini) {
                val driftWait = engine.idleDelay(now)
                val glowVisible = engine.current?.let {
                    it.y + it.size > engine.scrollY && it.y < engine.scrollY + engine.heightCss
                } == true && focus == null
                val wait = if (glowVisible) min(driftWait ?: 40L, 40L) else driftWait
                if (wait == null) wake.receive()
                else withTimeoutOrNull(wait.coerceAtLeast(1)) { wake.receive() }
                drawTick = System.nanoTime()
                last = withFrameNanos { it }
            }
        }
    }

    LaunchedEffect(resumed, state.isPlaying, focus, tuning, tune.vinyl) {
        if (!resumed || !state.isPlaying || focus == null || tuning || !tune.vinyl)
            return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val n = withFrameNanos { it }
            discAngle = (discAngle + (n - last) / 1e9f * 100f) % 360f
            last = n
        }
    }

    BackHandler {
        when {
            tuning -> {
                tuning = false
                saveTune(context, tune)
            }
            focus != null -> if (back) flipFocus(false) else closeFocus()
            spinning -> cancelSpin()
            else -> cbClose.value()
        }
    }

    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val focusLayout = computeWallFocusLayout(
        width = width,
        height = height,
        density = d,
        barsTopPortrait = barsTop,
        barsTopLandscape = barsTopLandscape,
        bottom = bottom,
        safeLeft = safeLeft,
        safeRight = safeRight,
        vinylEnabled = tune.vinyl,
        isTablet = isTablet
    )
    val heroSize = focusLayout.heroSize
    val heroX = focusLayout.heroX
    val heroY = focusLayout.heroY
    val backSize = focusLayout.backSize
    val backX = focusLayout.backX
    val backY = focusLayout.backY
    // 自动滚动开始后顶角图标渐隐到 0.2（仍可点）；任何操作重置空闲计时后渐变恢复。
    val iconsAlpha by animateFloatAsState(if (drifting) 0.2f else 1f, tween(600), label = "wallIcons")

    Box(
        modifier
            .fillMaxSize()
            .background(WallBackground)
            .onSizeChanged { viewport = it }
    ) {
        val l = layout
        if (wallAlbums.isEmpty()) EmptyWall { cbClose.value() }
        if (l != null)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = flight.value.coerceIn(0f, 1f) *
                            (1f - pull.value.coerceIn(0f, 1f) * 0.6f)
                        val details = flip.value
                        scaleX = 1f - lerp(0.14f, 0.06f, details) * p
                        scaleY = scaleX
                        rotationX = lerp(8f, 3f, details) * p
                        cameraDistance = 900f * d
                        alpha = 1f - lerp(0.68f, 0.55f, details) * p
                        if (Build.VERSION.SDK_INT >= 31)
                            renderEffect = if (p > 0.001f) BlurEffect(
                                lerp(14f, 7f, details) * d * p,
                                lerp(14f, 7f, details) * d * p
                            ) else null
                    }
                    .pointerInput(l, d) {
                        val velocity = VelocityTracker()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (focus != null || tuning) return@awaitEachGesture
                            down.consume()
                            val caught = spinning || abs(engine.vel) > 250f
                            if (spinning) cancelSpin() else if (caught) haptic(2, "抓停")
                            val tile = if (caught) null else visibleTiles.asReversed().firstOrNull {
                                poses[it.album.key]?.contains(down.position.x, down.position.y) == true
                            }
                            val startY = down.position.y
                            val startX = down.position.x
                            val scroll = engine.scrollY
                            engine.vel = 0f
                            engine.dragging = true
                            engine.pressing = tile
                            engine.pressStart = wallNow()
                            engine.lastDragTime = engine.pressStart
                            engine.longFired = false
                            engine.pressMoved = false
                            engine.markInput(engine.pressStart)
                            velocity.resetTracking()
                            velocity.addPosition(down.uptimeMillis, down.position)
                            var moved = false
                            var out = false
                            if (tile != null)
                                engine.pressTile(
                                    tile,
                                    ((startX / d - tile.x) / tile.size - 0.5f) * 2,
                                    ((startY / d + scroll - tile.y) / tile.size - 0.5f) * 2
                                )
                            wakeUp()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    velocity.addPosition(ch.uptimeMillis, ch.position)
                                    if (ch.changedToUpIgnoreConsumed()) {
                                        ch.consume()
                                        if (!engine.longFired && focus == null) {
                                            if (moved) {
                                                engine.vel = (-velocity.calculateVelocity().y / d)
                                                    .coerceIn(-9000f, 9000f)
                                                engine.userMotion = true
                                            } else if (tile != null)
                                                openFocusRef.value(tile, false, null)
                                        }
                                        break
                                    }
                                    if (focus != null || ch.isConsumed) break
                                    val dx = ch.position.x - startX
                                    val dy = ch.position.y - startY
                                    if (!moved && hypot(dx, dy) > viewConfiguration.touchSlop) {
                                        moved = true
                                        engine.pressMoved = true
                                        engine.pressing = null
                                        tile?.let { engine.releaseTile(it) }
                                    }
                                    if (moved) {
                                        var y = scroll - dy / d
                                        if (y < 0f) y = -rubber(-y, engine.heightCss)
                                        else if (y > engine.maxY)
                                            y = engine.maxY + rubber(y - engine.maxY, engine.heightCss)
                                        val now = wallNow()
                                        val dt = max(1L, now - engine.lastDragTime) / 1000f
                                        engine.vel = lerp(engine.vel, (y - engine.scrollY) / dt, 0.5f)
                                        engine.lastDragTime = now
                                        engine.scrollY = y
                                        engine.userMotion = true
                                        engine.markInput(now)
                                        val beyond = y < 0f || y > engine.maxY
                                        if (beyond && !out) haptic(3, "撞到边缘")
                                        out = beyond
                                    } else if (tile != null)
                                        engine.pressTile(
                                            tile,
                                            ((ch.position.x / d - tile.x) / tile.size - 0.5f) * 2,
                                            ((ch.position.y / d + scroll - tile.y) / tile.size - 0.5f) * 2
                                        )
                                    ch.consume()
                                    wakeUp()
                                }
                            } finally {
                                engine.dragging = false
                                engine.pressing = null
                                tile?.let { engine.releaseTile(it) }
                                wakeUp()
                            }
                        }
                    }
            ) {
                @Suppress("UNUSED_EXPRESSION")
                drawTick
                val now = wallNow()
                val current = engine.current
                if (current != null)
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(current.spec.mainColor).copy(alpha = 0.42f), Color.Transparent),
                            Offset(size.width / 2, size.height * 0.46f),
                            max(size.width, size.height) * 0.6f
                        )
                    )
                drawIntoCanvas { canvas ->
                    visibleTiles.clear()
                    for (t in l.tiles) {
                        val sy = t.y - engine.scrollY
                        if (sy > engine.heightCss + 260f || sy + t.size < -260f) continue
                        val p = poses[t.album.key] ?: continue
                        visibleTiles.add(t)
                        val spanNorm = t.span / l.cols.toFloat()
                        p.x = (t.x + engine.gxR * spanNorm * 16f) * d
                        p.y = (sy + engine.gyR * spanNorm * 16f) * d
                        p.size = t.size * d
                        val ny = clamp((p.y + p.size / 2 - size.height / 2) / (size.height / 2), -1.7f, 1.7f)
                        val ang = ny * tune.maxAngle * 0.017453293f * engine.curve
                        p.rx = -ang * 57.2958f + t.rx + engine.tilt - engine.gyR * tune.gyroDeg
                        p.ry = t.ry + engine.gxR * tune.gyroDeg
                        p.z = -(1f - cos(ang)) * tune.radius * d
                        p.scale = t.ps
                        p.alpha = 1f
                        p.shade = min(0.62f, (1f - cos(ang)) * 2.4f + max(0f, abs(ny) - 0.8f) * 0.3f)
                        for (r in engine.ripples) {
                            val distance = hypot(t.x + t.size / 2 - r.x, t.y + t.size / 2 - r.y)
                            val time = (now - r.t0) / 1000f - distance / tune.rippleSpeed
                            if (time > 0f && time < 1.5f) {
                                val f = r.amp * kotlin.math.exp(-distance / (engine.heightCss * 0.7f)) *
                                    sin(time * 13.5f) * kotlin.math.exp(-time * 4.2f)
                                p.scale -= f
                                p.z -= f * 500f * d
                            }
                        }
                        engine.intro?.let {
                            val distance = hypot(t.x + t.size / 2 - it.ox, sy + t.size / 2 - it.oy)
                            val intro = clamp((now - it.t0 - distance * 0.38f) / 440f, 0f, 1f)
                            p.scale *= 0.6f + 0.4f * easeOutBack(intro)
                            p.z -= (1f - easeOutCubic(intro)) * 260f * d
                            p.rx += (1f - easeOutCubic(intro)) * 24f
                            p.alpha = clamp(intro * 2.5f, 0f, 1f)
                        }
                        if (t !== engine.dimExcept) p.shade += (1f - p.shade) * engine.dim
                        painter.project(p)
                        if (focus?.album?.key == t.album.key) continue
                        val glow = if (t.album.key == current?.album?.key)
                            0.55f + 0.45f * (0.5f + 0.5f * sin((now % 2400L) / 2400f * 6.283185f))
                        else 0f
                        painter.draw(
                            canvas.nativeCanvas,
                            cache.get(cacheKey(t)),
                            t.spec,
                            p,
                            6f * d,
                            engine.gxR + t.pressGx,
                            engine.gyR + t.pressGy,
                            glow,
                            when {
                                spanNorm >= 0.75f -> 1.4f
                                spanNorm >= 0.36f -> 1f
                                else -> 0.7f
                            }
                        )
                    }
                }
                drawRect(
                    Brush.radialGradient(
                        colorStops = arrayOf(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = max(size.width, size.height) * 0.72f
                    )
                )
            }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = iconsAlpha }
                .padding(
                    top = with(density) { focusLayout.topBarPaddingPx.toDp() },
                    start = with(density) { safeLeft.toDp() } + 14.dp,
                    end = with(density) { safeRight.toDp() } + 14.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(if (focus == null) "exit" else "chevron") {
                engine.markInput(wallNow())
                wakeUp()
                haptic(2, "返回")
                if (focus == null) cbClose.value() else closeFocus()
            }
            GlassIconButton("tune") {
                engine.markInput(wallNow())
                wakeUp()
                haptic(2, "调参")
                tuning = true
            }
        }
        val nowPlayingTile = remember(state.mediaId, layout) {
            layout?.tiles?.firstOrNull { it.album.tracks.any { s -> s.id == state.mediaId } }
        }
        if (focus == null && wallAlbums.isNotEmpty())
            RandomFab(
                spinning,
                mini,
                bottom,
                nowPlaying = nowPlayingTile != null,
                onNowPlaying = { nowPlayingTile?.let { openFocusRef.value(it, false, null) } }
            ) {
                randomListen()
            }
        val tile = focus
        if (tile != null) {
            Canvas(Modifier.fillMaxSize()) {
                val reveal = ((flight.value - 0.16f) / 0.84f).coerceIn(0f, 1f) *
                    (1f - pull.value.coerceIn(0f, 1f) * 0.6f)
                val color = Color(tile.spec.mainColor)
                val radius = hypot(size.width, size.height) * reveal
                if (radius > 0f) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                heroX + heroSize / 2 - radius,
                                heroY + heroSize / 2 - radius,
                                heroX + heroSize / 2 + radius,
                                heroY + heroSize / 2 + radius
                            )
                        )
                    }
                    clipPath(path) {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    color.copy(alpha = 0.62f * reveal),
                                    Color.Black.copy(alpha = 0.72f * reveal),
                                    WallBackground.copy(alpha = reveal)
                                )
                            )
                        )
                    }
                }
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tile.album.key, d) {
                        val tracker = VelocityTracker()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (engine.busy || back || closing) return@awaitEachGesture
                            val x = down.position.x
                            val y = down.position.y
                            tracker.resetTracking()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var dragging = false
                            var dy = 0f
                            try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                // 歌词滚动 / 控件已占有手势时让位，背景其余区域才触发下拉返回。
                                if (ch.isConsumed) break
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                if (ch.changedToUpIgnoreConsumed()) {
                                    if (dragging) {
                                        if (dy / (height * 0.55f) > 0.2f ||
                                            tracker.calculateVelocity().y / d > 800f && dy / d > 20f
                                        ) closeFocus()
                                        else resetPull()
                                    } else if (heroPose.contains(x, y, disc.value)) flipFocus(true)
                                    ch.consume()
                                    break
                                }
                                dy = ch.position.y - y
                                if (abs(dy) > viewConfiguration.touchSlop) dragging = true
                                if (dragging) {
                                    val p = if (dy >= 0f) dy / (height * 0.55f)
                                    else -rubber(-dy / d, engine.heightCss) * d / (height * 0.55f) * 0.3f
                                    pullJob[0]?.cancel()
                                    pullJob[0] = scope.launch { pull.snapTo(p) }
                                    ch.consume()
                                }
                            }
                            } finally { if (dragging && !closing) resetPull() }
                        }
                    }
            ) {
                @Suppress("UNUSED_EXPRESSION")
                drawTick
                val f = flight.value
                val turn = flip.value
                val pullP = pull.value
                val tx = lerp(heroX, backX, turn)
                val ty = lerp(heroY, backY, turn)
                val ts = lerp(heroSize, backSize, turn)
                val p = heroPose
                p.x = lerp(sourcePose.x, tx, f)
                p.y = lerp(sourcePose.y, ty, f) + pullP * height * 0.55f
                p.size = lerp(sourcePose.size, ts, f)
                val lift = sin(f * 3.141593f) * 0.10f
                p.scale = (lerp(sourcePose.scale, 1f, f) + lift) *
                    (1f - 0.32f * pullP.coerceAtLeast(0f)) * (1f - queueFlight.value * 0.6f)
                p.rx = sourcePose.rx * (1f - f) - engine.gyR * tune.gyroDeg * f
                p.ry = sourcePose.ry * (1f - f) - 14f * sin(f * 3.141593f) +
                    engine.gxR * tune.gyroDeg * f + turn * 180f
                p.z = sourcePose.z * (1f - f) + sin(f * 3.141593f) * 80f * d
                p.shade = sourcePose.shade * (1f - f)
                p.alpha = 1f - queueFlight.value
                p.x += queueFlight.value * width
                p.y -= queueFlight.value * height * 0.35f
                painter.project(p, queueFlight.value * 20f)
                if (turn < 0.5f)
                    drawIntoCanvas {
                        painter.draw(
                            it.nativeCanvas,
                            focusBitmap,
                            tile.spec,
                            p,
                            lerp(6f, 12f, f) * d,
                            engine.gxR,
                            engine.gyR,
                            0f,
                            1.5f,
                            disc.value,
                            discAngle
                        )
                    }
            }
            if (back || flip.isRunning)
                Box(
                    Modifier
                        .offset(with(density) { backX.toDp() }, with(density) { backY.toDp() })
                        .size(with(density) { backSize.toDp() })
                        .graphicsLayer {
                            val f = flight.value
                            val turn = flip.value
                            val tx = lerp(heroX, backX, turn)
                            val ty = lerp(heroY, backY, turn)
                            val ts = lerp(heroSize, backSize, turn)
                            val faceSize = lerp(sourcePose.size, ts, f)
                            translationX = lerp(sourcePose.x, tx, f) + faceSize / 2f -
                                (backX + backSize / 2f) + queueFlight.value * width
                            translationY = lerp(sourcePose.y, ty, f) + faceSize / 2f -
                                (backY + backSize / 2f) - queueFlight.value * height * 0.35f
                            transformOrigin = TransformOrigin.Center
                            scaleX = faceSize / backSize *
                                (lerp(sourcePose.scale, 1f, f) + sin(f * 3.141593f) * 0.10f) *
                                (1f - queueFlight.value * 0.6f)
                            scaleY = scaleX
                            rotationX = sourcePose.rx * (1f - f) - engine.gyR * tune.gyroDeg * f
                            rotationY = turn * 180f - 180f + engine.gxR * tune.gyroDeg * f
                            rotationZ = queueFlight.value * 20f
                            cameraDistance = 900f * d
                            alpha = if (turn >= 0.5f) 1f - queueFlight.value else 0f
                        }
                ) {
                    WallTrackFace(
                        tile.album,
                        state.mediaId,
                        onPlayTrack = { index ->
                            if (!engine.busy && !closing) {
                            cbPlay.value(tile.album, index)
                            preview = false
                            back = false
                            focusJob[0]?.cancel()
                            focusJob[0] = scope.launch {
                                engine.busy = true
                                haptic(4, "播放")
                                flip.animateTo(0f, tween(240))
                                if (tune.vinyl) disc.animateTo(1f, tween(560))
                                engine.busy = false
                            }
                            }
                        },
                        onPlayAll = {
                            if (!engine.busy && !closing) {
                            cbPlay.value(tile.album, 0)
                            preview = false
                            flipFocus(false)
                            }
                        },
                        onQueue = {
                            if (!engine.busy) {
                                cbQueue.value(tile.album)
                                haptic(2, "加入下一首")
                                focusJob[0]?.cancel()
                                focusJob[0] = scope.launch {
                                    engine.busy = true
                                    queueFlight.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                                    tile.ps = 0.6f
                                    engine.releaseTile(tile)
                                    releaseFocus()
                                    flight.snapTo(0f)
                                    flip.snapTo(0f)
                                    queueFlight.snapTo(0f)
                                }
                            }
                        },
                        onClose = { flipFocus(false) }
                    )
                }
            // 默认显示歌词：封面与底部控件之间（或横屏/平板右侧统一分栏），随飞行淡入；翻到曲目背面时隐藏。
            val focusSong = if (preview || !state.hasItem) tile.album.tracks.firstOrNull()
                else tile.album.tracks.firstOrNull { it.id == state.mediaId }
                    ?: songs.firstOrNull { it.id == state.mediaId }
            val playToggleAction = {
                haptic(2, "播放控制")
                if (preview) {
                    preview = false
                    cbPlay.value(tile.album, 0)
                } else cbToggle.value()
            }
            val prevAction = {
                haptic(2, "上一首")
                cbPrev.value()
            }
            val nextAction = {
                haptic(2, "下一首")
                val tracks = tile.album.tracks
                val idx = tracks.indexOfFirst { it.id == state.mediaId }
                if (idx < 0 || idx >= tracks.size - 1) leaveToRandom() else cbNext.value()
            }
            val seekAction: (Long) -> Unit = {
                haptic(1, "进度", 7)
                cbSeek.value(it)
            }
            if (focusLayout.isWallWide) {
                Column(
                    Modifier
                        .offset(
                            with(density) { focusLayout.rightPaneX.toDp() },
                            with(density) { focusLayout.rightPaneTop.toDp() }
                        )
                        .width(with(density) { focusLayout.rightPaneW.toDp() })
                        .height(with(density) { focusLayout.rightPaneH.toDp() })
                        .graphicsLayer {
                            alpha = ((flight.value - 0.64f) / 0.36f).coerceIn(0f, 1f) *
                                (1f - flip.value) * (1f - pull.value.coerceIn(0f, 1f))
                            translationY = (1f - flight.value) * 30f * d
                        }
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = if (focusLayout.isCompactLandscape) 4.dp else 12.dp,
                                end = if (focusLayout.isCompactLandscape) 46.dp else 12.dp
                            )
                    ) {
                        if (!back && !closing && focusSong != null)
                            InlineLyrics(
                                song = focusSong,
                                positionMs = if (preview || !state.hasItem) 0L else state.positionMs,
                                prefs = prefs,
                                onSeek = { if (!preview && state.hasItem) cbSeek.value(it) },
                                isPlaying = state.isPlaybackActive && !preview && state.hasItem,
                                compact = true,
                                modifier = Modifier.fillMaxSize()
                            )
                    }
                    if (!back && !closing)
                        WallPlaybackControls(
                            state = state,
                            album = tile.album,
                            preview = preview,
                            coverColor = Color(tile.spec.mainColor),
                            compact = focusLayout.isCompactLandscape,
                            wide = !focusLayout.isCompactLandscape,
                            onToggle = playToggleAction,
                            onPrev = prevAction,
                            onNext = nextAction,
                            onSeek = seekAction,
                            onTracks = { flipFocus(true) }
                        )
                }
            } else {
                Box(
                    Modifier
                        .offset(with(density) { focusLayout.rightPaneX.toDp() }, with(density) { focusLayout.lyricsTop.toDp() })
                        .width(with(density) { focusLayout.rightPaneW.toDp() })
                        .height(with(density) { focusLayout.lyricsHeight.toDp() })
                        .graphicsLayer {
                            alpha = ((flight.value - 0.64f) / 0.36f).coerceIn(0f, 1f) *
                                (1f - flip.value) * (1f - pull.value.coerceIn(0f, 1f))
                            translationY = (1f - flight.value) * 30f * d
                        }
                ) {
                    if (!back && !closing && focusSong != null)
                        InlineLyrics(
                            song = focusSong,
                            positionMs = if (preview || !state.hasItem) 0L else state.positionMs,
                            prefs = prefs,
                            onSeek = { if (!preview && state.hasItem) cbSeek.value(it) },
                            isPlaying = state.isPlaybackActive && !preview && state.hasItem,
                            compact = true,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                        )
                }
                Box(
                    Modifier
                        .offset(with(density) { focusLayout.rightPaneX.toDp() }, with(density) { focusLayout.controlsTop.toDp() })
                        .width(with(density) { focusLayout.rightPaneW.toDp() })
                        .height(with(density) { focusLayout.controlsHeight.toDp() })
                        .graphicsLayer {
                            alpha = ((flight.value - 0.64f) / 0.36f).coerceIn(0f, 1f) *
                                (1f - flip.value) * (1f - pull.value.coerceIn(0f, 1f))
                            translationY = (1f - flight.value) * 30f * d
                        }
                ) {
                    if (!back && !closing)
                        WallPlaybackControls(
                            state = state,
                            album = tile.album,
                            preview = preview,
                            coverColor = Color(tile.spec.mainColor),
                            onToggle = playToggleAction,
                            onPrev = prevAction,
                            onNext = nextAction,
                            onSeek = seekAction,
                            onTracks = { flipFocus(true) }
                        )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = iconsAlpha }
                    .padding(
                        top = with(density) { focusLayout.topBarPaddingPx.toDp() },
                        start = with(density) { safeLeft.toDp() } + 14.dp,
                        end = with(density) { safeRight.toDp() } + 14.dp
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton("chevron") {
                    engine.markInput(wallNow())
                    wakeUp()
                    haptic(2, "返回")
                    if (back) flipFocus(false) else closeFocus()
                }
                GlassIconButton("tune") {
                    engine.markInput(wallNow())
                    wakeUp()
                    tuning = true
                    haptic(2, "调参")
                }
            }
        }
    }
    if (tuning)
        ModalBottomSheet(
            onDismissRequest = {
                tuning = false
                saveTune(context, tune)
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xF2121218)
        ) {
            TuneSheet(
                tune,
                onReset = {
                    resetTune(tune)
                    saveTune(context, tune)
                    haptic(2, "恢复默认")
                    wakeUp()
                },
                onShuffle = {
                    tune.seed = Random.nextInt(1, 99999)
                    saveTune(context, tune)
                    haptic(2, "换个布局")
                    wakeUp()
                },
                onChanged = {
                    saveTune(context, tune)
                    engine.markInput(wallNow())
                    wakeUp()
                }
            )
        }
}
