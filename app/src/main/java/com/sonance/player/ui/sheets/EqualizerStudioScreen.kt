package com.sonance.player.ui.sheets

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sonance.player.SonanceViewModel
import com.sonance.player.playback.AudioOutputRoute
import com.sonance.player.playback.EQ_BAND_COUNT
import com.sonance.player.playback.EQ_CENTER_FREQS
import com.sonance.player.playback.EQ_MAX_DB
import com.sonance.player.playback.EQ_MIN_DB
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.EqResponse
import com.sonance.player.playback.PresetIntensity
import com.sonance.player.playback.SoundFxParams
import com.sonance.player.playback.SoundPreset
import com.sonance.player.playback.SoundPresetCategory
import com.sonance.player.playback.SoundPresets
import com.sonance.player.playback.TONE_MAX_DB
import com.sonance.player.playback.effectPreampBudgetDb
import com.sonance.player.playback.eqAutoPreampDb
import com.sonance.player.playback.eqBandLabel
import com.sonance.player.playback.eqDbFromSlider
import com.sonance.player.playback.presetNetCurve
import com.sonance.player.playback.presetToneBands
import com.sonance.player.ui.components.GlassActionChip
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.SonanceTheme
import com.sonance.player.ui.theme.glassCardBg
import com.sonance.player.ui.theme.glassCardBorder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class StudioTab(val label: String) {
    PRESETS("音色预设"),
    EQUALIZER("均衡器"),
    EFFECTS("声场效果"),
    KARAOKE("伴奏消音")
}

private enum class PresetFilter(val label: String, val category: SoundPresetCategory?) {
    ALL("全部", null),
    SONANCE("自然", SoundPresetCategory.SONANCE),
    HEADPHONE("耳机", SoundPresetCategory.HEADPHONE),
    SPEAKER("外放", SoundPresetCategory.SPEAKER),
    SPACE("空间", SoundPresetCategory.SPACE),
    ORIGINAL("原声", SoundPresetCategory.ORIGINAL)
}

/**
 * 音效工作室专属配色：中国红丹漆暗金 identity 不跟随应用主题色，
 * 但应用设为亮色主题时整套切到亮色丹漆变体（背景亮、文字深，红金点缀保留）。
 * 所有颜色经 [LocalStudioPalette] 下发，下面的同名 val 是 composable 读取点。
 */
internal data class StudioPalette(
    val isLight: Boolean,
    val bg: Color,
    val wellBg: Color,
    val wellBorder: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val pillBg: Color,
    val trackBg: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textDim: Color,
    val textMuted: Color,
    val crimson: Color,
    val coral: Color,
    val crimsonDark: Color,
    val onRed: Color,
    val crimsonGlow: Color,
    val chipSelectedBg: Color,
    val chipSelectedText: Color,
    val gold: Color,
    val goldSoft: Color,
    val surface: Color,
    val danger: Color,
    val heroGlowTop: Color,
    val heroGlowBottom: Color,
    val statusActiveBg: Color,
    val statusIdleBg: Color,
    val statusBypassText: Color,
    val statusIdleBorder: Color,
    val statusBypassDotBg: Color,
    val statusIdleText: Color,
    val tabsTrayBg: Color,
    val tabsTrayBorder: Color,
    val tabSelectedTop: Color,
    val tabSelectedBottom: Color,
    val heroStripBg: Color,
    val presetCardSelTop: Color,
    val presetCardSelBottom: Color,
    val eqWellInner: Color,
    val eqGridLine: Color,
    val eqBandBg: Color,
    val eqThumbInactive: Color,
    val eqLabelInactive: Color,
    val curveRefZero: Color,
    val curveRefOther: Color,
    val curveGrid: Color,
    val curveInactiveStroke: Color,
    val chipUnselectedBg: Color
) {
    val cardBrush: Brush = Brush.verticalGradient(listOf(cardTop, cardBottom))
    val heroBrush: Brush = Brush.verticalGradient(listOf(heroTop, heroBottom))
}

internal val StudioDarkPalette = StudioPalette(
    isLight = false,
    bg = Color(0xFF1A090B),
    wellBg = Color(0xFF110507),
    wellBorder = Color(0xFF3D191D),
    cardTop = Color(0xFF2B1215),
    cardBottom = Color(0xFF210D10),
    heroTop = Color(0xFF38151A),
    heroBottom = Color(0xFF240E11),
    pillBg = Color(0xFF321519),
    trackBg = Color(0xFF451D22),
    border = Color(0xFF471E22),
    borderStrong = Color(0xFF5E282E),
    text = Color(0xFFFAF0EE),
    textDim = Color(0xFFDCC2BF),
    textMuted = Color(0xFFA07E7B),
    crimson = Color(0xFFE03E44),
    coral = Color(0xFFFF6B6B),
    crimsonDark = Color(0xFFC8161D),
    onRed = Color(0xFFFFF7F6),
    crimsonGlow = Color(0x2EE03E44),
    chipSelectedBg = Color(0xFF4C161C),
    chipSelectedText = Color(0xFFFFD6D8),
    gold = Color(0xFFF0B44C),
    goldSoft = Color(0x2EF0B44C),
    surface = Color(0xFF250F12),
    danger = Color(0xFFFF7A5A),
    heroGlowTop = Color(0xFF5C1118),
    heroGlowBottom = Color(0xFF330A0E),
    statusActiveBg = Color(0xFF3D141A),
    statusIdleBg = Color(0xFF230E11),
    statusBypassText = Color(0xFFF7C96E),
    statusIdleBorder = Color(0xFF3A181C),
    statusBypassDotBg = Color(0xFF1A090B),
    statusIdleText = Color(0xFF7D5C5A),
    tabsTrayBg = Color(0xFF140709),
    tabsTrayBorder = Color(0xFF3A181C),
    tabSelectedTop = Color(0xFF42171D),
    tabSelectedBottom = Color(0xFF311116),
    heroStripBg = Color(0xFF281014),
    presetCardSelTop = Color(0xFF45151B),
    presetCardSelBottom = Color(0xFF2A0E12),
    eqWellInner = Color(0xFF2B1216),
    eqGridLine = Color(0xFF4F2328),
    eqBandBg = Color(0xFF2D1317),
    eqThumbInactive = Color(0xFF5C383B),
    eqLabelInactive = Color(0xFF8F6B69),
    curveRefZero = Color(0xFF4F2328),
    curveRefOther = Color(0xFF301418),
    curveGrid = Color(0xFF260F12),
    curveInactiveStroke = Color(0xFF6E4B4E),
    chipUnselectedBg = Color(0xFF240E12)
)

internal val StudioLightPalette = StudioPalette(
    isLight = true,
    bg = Color(0xFFF7F1EF),
    wellBg = Color(0xFFEFE4E1),
    wellBorder = Color(0xFFE0CCC8),
    cardTop = Color(0xFFFBF6F4),
    cardBottom = Color(0xFFF3E9E6),
    heroTop = Color(0xFFF6E3E0),
    heroBottom = Color(0xFFFBEFEC),
    pillBg = Color(0xFFF0E2DF),
    trackBg = Color(0xFFE7D4D0),
    border = Color(0xFFE3CFCA),
    borderStrong = Color(0xFFD3B6B0),
    text = Color(0xFF3A1F1E),
    textDim = Color(0xFF6B4A47),
    textMuted = Color(0xFF9A7B77),
    crimson = Color(0xFFD22B32),
    coral = Color(0xFFE05A5A),
    crimsonDark = Color(0xFFA81218),
    onRed = Color(0xFFFFF7F6),
    crimsonGlow = Color(0x1FC8161D),
    chipSelectedBg = Color(0xFFF3D9D6),
    chipSelectedText = Color(0xFF8C1F26),
    gold = Color(0xFFB07A1E),
    goldSoft = Color(0x2EB07A1E),
    surface = Color(0xFFF3E9E6),
    danger = Color(0xFFD94F2E),
    heroGlowTop = Color(0xFFF2D9D4),
    heroGlowBottom = Color(0xFFF8E8E4),
    statusActiveBg = Color(0xFFF3D9D6),
    statusIdleBg = Color(0xFFEFE2DF),
    statusBypassText = Color(0xFF9A6A10),
    statusIdleBorder = Color(0xFFE0CCC8),
    statusBypassDotBg = Color(0xFFF7F1EF),
    statusIdleText = Color(0xFF8A6A66),
    tabsTrayBg = Color(0xFFEDE0DC),
    tabsTrayBorder = Color(0xFFE0CCC8),
    tabSelectedTop = Color(0xFFFBEFEC),
    tabSelectedBottom = Color(0xFFF3E2DE),
    heroStripBg = Color(0xFFF3E9E6),
    presetCardSelTop = Color(0xFFF6E0DC),
    presetCardSelBottom = Color(0xFFEFDCD8),
    eqWellInner = Color(0xFFE9D8D4),
    eqGridLine = Color(0xFFD8BFB9),
    eqBandBg = Color(0xFFEFE2DF),
    eqThumbInactive = Color(0xFFC9A9A3),
    eqLabelInactive = Color(0xFF8F6B69),
    curveRefZero = Color(0xFFD8BFB9),
    curveRefOther = Color(0xFFE2CEC9),
    curveGrid = Color(0xFFEBDCD8),
    curveInactiveStroke = Color(0xFFB3928C),
    chipUnselectedBg = Color(0xFFEFE2DF)
)

private val LocalStudioPalette = androidx.compose.runtime.staticCompositionLocalOf { StudioDarkPalette }

private val StudioBg: Color
    @Composable get() = LocalStudioPalette.current.bg
private val StudioWellBg: Color
    @Composable get() = LocalStudioPalette.current.wellBg
private val StudioWellBorder: Color
    @Composable get() = LocalStudioPalette.current.wellBorder
private val StudioCardTop: Color
    @Composable get() = LocalStudioPalette.current.cardTop
private val StudioCardBottom: Color
    @Composable get() = LocalStudioPalette.current.cardBottom
private val StudioHeroTop: Color
    @Composable get() = LocalStudioPalette.current.heroTop
private val StudioHeroBottom: Color
    @Composable get() = LocalStudioPalette.current.heroBottom
private val StudioPillBg: Color
    @Composable get() = LocalStudioPalette.current.pillBg
private val StudioTrackBg: Color
    @Composable get() = LocalStudioPalette.current.trackBg
private val StudioBorder: Color
    @Composable get() = LocalStudioPalette.current.border
private val StudioBorderStrong: Color
    @Composable get() = LocalStudioPalette.current.borderStrong
private val StudioText: Color
    @Composable get() = LocalStudioPalette.current.text
private val StudioTextDim: Color
    @Composable get() = LocalStudioPalette.current.textDim
private val StudioTextMuted: Color
    @Composable get() = LocalStudioPalette.current.textMuted
private val StudioCrimson: Color
    @Composable get() = LocalStudioPalette.current.crimson
private val StudioCoral: Color
    @Composable get() = LocalStudioPalette.current.coral
private val StudioCrimsonDark: Color
    @Composable get() = LocalStudioPalette.current.crimsonDark
private val StudioOnRed: Color
    @Composable get() = LocalStudioPalette.current.onRed
private val StudioCrimsonGlow: Color
    @Composable get() = LocalStudioPalette.current.crimsonGlow
private val StudioChipSelectedBg: Color
    @Composable get() = LocalStudioPalette.current.chipSelectedBg
private val StudioChipSelectedText: Color
    @Composable get() = LocalStudioPalette.current.chipSelectedText
private val StudioGold: Color
    @Composable get() = LocalStudioPalette.current.gold
private val StudioGoldSoft: Color
    @Composable get() = LocalStudioPalette.current.goldSoft

private val StudioCardBrush: Brush
    @Composable get() = LocalStudioPalette.current.cardBrush
private val StudioHeroBrush: Brush
    @Composable get() = LocalStudioPalette.current.heroBrush

/**
 * 音效工作室专属中国红丹漆配色：identity 独立于应用主题（不跟随皮肤强调色），
 * 亮色主题下自动切到亮色丹漆变体。
 */
internal val StudioConsoleSkin: Skin
    @Composable get() {
        val p = LocalStudioPalette.current
        return Skin(
            id = "studio_console",
            name = "中国红调音台",
            tagline = "丹漆暗金 · 独立专业调音台配色",
            isLight = p.isLight,
            bg = p.bg,
            surface = p.surface,
            surface2 = p.pillBg,
            surface3 = p.trackBg,
            border = p.border,
            border2 = p.borderStrong,
            text = p.text,
            textDim = p.textDim,
            textMuted = p.textMuted,
            accent = p.crimson,
            accentDim = p.crimsonDark,
            onAccent = p.onRed,
            accentGlow = p.crimsonGlow,
            danger = p.danger,
            gradients = listOf(listOf(p.heroTop, p.bg)),
            glass = null,
            immersive = false
        )
    }

@Composable
private fun Modifier.studioCardContainer(
    radius: androidx.compose.ui.unit.Dp = 20.dp,
    highlighted: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(StudioCardBrush)
    .border(
        width = 0.8.dp,
        color = if (highlighted) StudioCrimson.copy(alpha = 0.45f) else StudioBorder,
        shape = RoundedCornerShape(radius)
    )

/**
 * 全屏音效工作室：整合精选音色预设、实时 10 段图形均衡器（竖向混音台/精细滑条双视图）、
 * DSP 空间微调、输出通道自动记忆与伴奏消音。
 */
@Composable
fun EqualizerStudioScreen(
    vm: SonanceViewModel,
    onDismiss: () -> Unit
) {
    val outerSkin = LocalSkin.current
    val view = LocalView.current
    var selectedTab by rememberSaveable { mutableStateOf(StudioTab.PRESETS) }
    var presetFilter by rememberSaveable { mutableStateOf(PresetFilter.ALL) }
    var horizontalEqMode by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }

    // 面板销毁时确保退出 A/B 旁通，并恢复外层主题对应的状态栏/导航栏明暗。
    DisposableEffect(outerSkin.isLight) {
        onDispose {
            vm.setSoundFxBypass(false)
            if (!view.isInEditMode) {
                (view.context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = outerSkin.isLight
                        isAppearanceLightNavigationBars = outerSkin.isLight
                    }
                }
            }
        }
    }

    val hasAnyProcessing = vm.soundFxEnabled || vm.eqEnabled
    val scrollState = rememberScrollState()

    val studioPalette = if (outerSkin.isLight) StudioLightPalette else StudioDarkPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalStudioPalette provides studioPalette) {
        SonanceTheme(StudioConsoleSkin) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioBg)
                // 吸收底层页面点击，避免退出动画或空白区域的点击穿透到底层播放页或曲库列表。
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}
                )
        ) {
            // 顶部中国红丹漆氛围光晕
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                LocalStudioPalette.current.heroGlowTop.copy(alpha = 0.72f),
                                LocalStudioPalette.current.heroGlowBottom.copy(alpha = 0.36f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 820.dp)
                        .fillMaxSize()
                ) {
                    StudioTopHeader(
                        route = vm.currentOutputRoute,
                        processingActive = hasAnyProcessing,
                        bypassing = vm.soundFxBypassed,
                        onBypassHold = { vm.setSoundFxBypass(it) },
                        onDismiss = onDismiss
                    )

                    StudioSegmentedTabs(
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        presetsActive = vm.soundFxEnabled && vm.soundPreset.id != SoundPresets.OFF.id,
                        eqActive = vm.eqEnabled,
                        fxActive = vm.soundFxEnabled && !vm.soundFxParams.isNeutral,
                        karaokeActive = vm.karaokeEnabled,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (selectedTab) {
                            StudioTab.PRESETS -> PresetsTabContent(
                                vm = vm,
                                filter = presetFilter,
                                onFilterChange = { presetFilter = it }
                            )

                            StudioTab.EQUALIZER -> EqualizerTabContent(
                                vm = vm,
                                horizontalMode = horizontalEqMode,
                                onToggleMode = { horizontalEqMode = !horizontalEqMode }
                            )

                            StudioTab.EFFECTS -> EffectsTabContent(vm = vm)

                            StudioTab.KARAOKE -> KaraokeTabContent(vm = vm)
                        }

                        Text(
                            text = SoundPreset.ORIGIN_SELF,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

/* ============================================================
 * 顶部导航与分段页签
 * ============================================================ */

@Composable
private fun StudioTopHeader(
    route: AudioOutputRoute,
    processingActive: Boolean,
    bypassing: Boolean,
    onBypassHold: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(StudioPillBg)
                .border(0.8.dp, StudioBorder, CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = SonanceIcons.ArrowLeft,
                contentDescription = "返回",
                tint = StudioText,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = "音效工作室",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = StudioText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                bypassing -> StudioGold
                                processingActive -> StudioCrimson
                                else -> StudioTextMuted
                            }
                        )
                )
                Text(
                    text = when {
                        bypassing -> "${route.label} · 原声对比中"
                        processingActive -> "${route.label} · 软 DSP 实时处理"
                        else -> "${route.label} · 原声直通"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        bypassing -> StudioGold
                        processingActive -> StudioCoral
                        else -> StudioTextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val abBg by animateColorAsState(
            targetValue = when {
                bypassing -> StudioGold
                processingActive -> LocalStudioPalette.current.statusActiveBg
                else -> LocalStudioPalette.current.statusIdleBg
            },
            animationSpec = tween(150),
            label = "abHoldBg"
        )
        val abBorderColor = when {
            bypassing -> LocalStudioPalette.current.statusBypassText
            processingActive -> StudioCrimson.copy(alpha = 0.65f)
            else -> LocalStudioPalette.current.statusIdleBorder
        }
        val abTextColor = when {
            bypassing -> LocalStudioPalette.current.statusBypassDotBg
            processingActive -> StudioText
            else -> LocalStudioPalette.current.statusIdleText
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(abBg)
                .border(
                    width = if (processingActive || bypassing) 1.dp else 0.8.dp,
                    color = abBorderColor,
                    shape = CircleShape
                )
                .semantics {
                    contentDescription = "按住对比原声"
                    stateDescription = if (bypassing) "当前为原声" else "当前为已处理音效"
                    if (processingActive) {
                        onClick(label = "切换原声对比") {
                            onBypassHold(!bypassing)
                            true
                        }
                    }
                }
                .pointerInput(processingActive) {
                    if (!processingActive) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            hapticTick(context)
                            onBypassHold(true)
                            tryAwaitRelease()
                            onBypassHold(false)
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (processingActive && !bypassing) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StudioCrimson)
                )
            }
            Text(
                text = if (bypassing) "原声监听中" else "按住对比",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = abTextColor
            )
        }
    }
}

@Composable
private fun StudioSegmentedTabs(
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    presetsActive: Boolean,
    eqActive: Boolean,
    fxActive: Boolean,
    karaokeActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalStudioPalette.current.tabsTrayBg)
            .border(0.8.dp, LocalStudioPalette.current.tabsTrayBorder, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StudioTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val isActiveDot = when (tab) {
                StudioTab.PRESETS -> presetsActive
                StudioTab.EQUALIZER -> eqActive
                StudioTab.EFFECTS -> fxActive
                StudioTab.KARAOKE -> karaokeActive
            }
            val bgBrush = if (isSelected) {
                Brush.verticalGradient(
                    listOf(LocalStudioPalette.current.tabSelectedTop, LocalStudioPalette.current.tabSelectedBottom)
                )
            } else {
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgBrush)
                    .then(
                        if (isSelected) {
                            Modifier.border(0.9.dp, StudioCrimson.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        }
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isActiveDot) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StudioCrimson)
                        )
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) StudioText else StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/* ============================================================
 * 页签 1：音色预设（精简监测卡 + 强度切换 + 分类精选网格 + 底部设备/系统协同）
 * ============================================================ */

@Composable
private fun PresetsTabContent(
    vm: SonanceViewModel,
    filter: PresetFilter,
    onFilterChange: (PresetFilter) -> Unit
) {
    StudioHeroCard(vm = vm)
    PresetFilterBar(selected = filter, onSelect = onFilterChange)

    val presets = remember(filter) {
        val cat = filter.category
        if (cat == null) SoundPresets.ALL else SoundPresets.ALL.filter { it.category == cat }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 560.dp -> 3
            maxWidth >= 320.dp -> 2
            else -> 1
        }
        val rows = remember(presets, columns) { presets.chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { preset ->
                        val selected = preset.id == vm.soundPreset.id &&
                            (preset.id == SoundPresets.OFF.id || vm.soundFxEnabled)
                        PresetStudioCard(
                            preset = preset,
                            intensity = vm.presetIntensity,
                            selected = selected,
                            onClick = { vm.chooseSoundPreset(preset) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    OutputRouteAutoCard(vm = vm)
    SystemAudioEffectBridgeCard(vm = vm)
}

@Composable
private fun StudioHeroCard(vm: SonanceViewModel) {
    val fxOn = vm.soundFxEnabled
    val eqOn = vm.eqEnabled
    val preset = vm.soundPreset
    val isPurePassThrough = !fxOn && !eqOn
    val isCustomCurve = eqOn && !vm.eqFromSoundPreset

    val activeTitle = when {
        vm.soundFxBypassed -> "原声旁通对比中"
        isPurePassThrough -> "原声直通"
        fxOn && preset.id != SoundPresets.OFF.id -> preset.name
        isCustomCurve && vm.eqPreset != EqPreset.OFF -> "均衡器 · ${vm.eqPreset.label}"
        eqOn -> "自定义频响曲线"
        else -> "仅效果引擎开启"
    }

    val statusBadge = when {
        vm.soundFxBypassed -> "临时旁通"
        vm.soundFxCustom || (eqOn && vm.eqCustom) -> "已微调"
        !isPurePassThrough -> vm.presetIntensity.label
        else -> "直通"
    }

    val netCurve = remember(vm.eqBands, eqOn, vm.soundFxParams, fxOn) {
        computeLiveNetCurve(
            eqBands = vm.eqBands,
            eqEnabled = eqOn,
            fxParams = vm.soundFxParams,
            fxEnabled = fxOn,
            points = 56
        )
    }

    val toneBands = remember(vm.eqBands, eqOn, vm.soundFxParams, fxOn) {
        computeLiveToneBands(
            eqBands = vm.eqBands,
            eqEnabled = eqOn,
            fxParams = vm.soundFxParams,
            fxEnabled = fxOn
        )
    }

    val ghostCurve = remember(eqOn, fxOn, vm.eqCustom, preset.id, vm.presetIntensity) {
        if (eqOn && fxOn && vm.eqCustom && preset.id != SoundPresets.OFF.id) {
            presetNetCurve(preset, 56, intensity = vm.presetIntensity)
        } else {
            null
        }
    }

    val heroBorder = when {
        vm.soundFxBypassed -> StudioGold.copy(alpha = 0.60f)
        !isPurePassThrough -> StudioCrimson.copy(alpha = 0.45f)
        else -> StudioBorder
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(StudioHeroBrush)
            .border(
                width = if (!isPurePassThrough || vm.soundFxBypassed) 1.dp else 0.8.dp,
                color = heroBorder,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StudioText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StudioTagPill(
                        label = statusBadge,
                        highlighted = !isPurePassThrough,
                        bypassing = vm.soundFxBypassed
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (isPurePassThrough) {
                        "无损原始音频直出，选择下方预设即可实时调音"
                    } else {
                        "低 ${formatShortDb(toneBands[0])} · 中 ${formatShortDb(toneBands[1])} · 高 ${formatShortDb(toneBands[2])} dB" +
                            if (fxOn && preset.id != SoundPresets.OFF.id) "  ·  ${preset.character}" else ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            StudioSwitch(
                checked = fxOn,
                onChange = { vm.toggleSoundFx(it) },
                label = "音效总开关"
            )
        }

        StudioCurveGraph(
            curve = netCurve,
            ghostCurve = ghostCurve,
            heightDp = 84f,
            accentActive = !isPurePassThrough && !vm.soundFxBypassed
        )

        // 预设强度三档切换（低 / 中 / 高）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(LocalStudioPalette.current.tabsTrayBg)
                .border(0.8.dp, StudioWellBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "预设强度",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioText
                )
                Text(
                    text = vm.presetIntensity.shortDesc,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = StudioTextMuted
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetIntensity.entries.forEach { level ->
                    val on = level == vm.presetIntensity
                    val pillBrush = if (on) {
                        Brush.horizontalGradient(listOf(StudioCrimsonDark, StudioCrimson))
                    } else {
                        Brush.horizontalGradient(
                            listOf(LocalStudioPalette.current.heroStripBg, LocalStudioPalette.current.heroStripBg)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pillBrush)
                            .border(
                                width = if (on) 0.dp else 0.7.dp,
                                color = if (on) Color.Transparent else StudioBorder,
                                shape = CircleShape
                            )
                            .selectable(
                                selected = on,
                                role = Role.RadioButton,
                                onClick = { vm.changePresetIntensity(level) }
                            )
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) StudioOnRed else StudioTextDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputRouteAutoCard(vm: SonanceViewModel) {
    val route = vm.currentOutputRoute
    val routeIcon = routeIconOf(route)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 18.dp, highlighted = vm.autoPresetByOutputEnabled)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(StudioChipSelectedBg)
                .border(0.7.dp, StudioCrimson.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = routeIcon,
                contentDescription = null,
                tint = StudioCoral,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "输出记忆 · ${route.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = StudioText
            )
            Text(
                text = if (vm.autoPresetByOutputEnabled) {
                    "已启用 · 切换耳机/蓝牙/外放自动还原各自音效"
                } else {
                    "开启后按输出设备分别记忆专属调音"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        StudioSwitch(
            checked = vm.autoPresetByOutputEnabled,
            onChange = { vm.toggleAutoPresetByOutput(it) },
            label = "按输出设备自动记忆"
        )
    }
}

/**
 * 手机 / 平板自带系统音效接入卡片（杜比全景声 / 小米音效 / OReality / SoundAlive / Histen 等）。
 * 在全屏音效工作室与轻量弹窗面板间复用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemAudioEffectBridgeCard(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val hasInternalProcessing = vm.eqEnabled || vm.soundFxEnabled
    val hasPanel = remember(context) { vm.hasDedicatedSystemAudioEffectPanel() }
    val isConsole = skin.id == StudioConsoleSkin.id
    val highlighted = vm.systemAudioEffectEnabled && !vm.usbExclusiveEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isConsole) {
                    Modifier.studioCardContainer(radius = 18.dp, highlighted = highlighted)
                } else {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(skin.glassCardBg())
                        .border(
                            width = 0.8.dp,
                            color = skin.glassCardBorder(highlighted = highlighted),
                            shape = RoundedCornerShape(18.dp)
                        )
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isConsole) StudioChipSelectedBg else skin.accentGlow)
                    .then(
                        if (isConsole) {
                            Modifier.border(0.7.dp, StudioCrimson.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SonanceIcons.Equalizer,
                    contentDescription = null,
                    tint = if (isConsole) StudioCoral else skin.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "系统音效（杜比 / 本机音效）",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text
                )
                Text(
                    text = when {
                        vm.usbExclusiveEnabled ->
                            "USB 独占直通开启中，将绕过系统混音与系统音效"
                        !vm.systemAudioEffectEnabled && hasInternalProcessing ->
                            "内置调音工作中（已关闭会话级系统音效防叠加）；若控制中心开着全局杜比建议关闭"
                        !vm.systemAudioEffectEnabled ->
                            "已关闭本应用系统音频会话接入"
                        hasInternalProcessing ->
                            "当前与内置音效同时开启可能重复叠加发闷，建议仅保留一种"
                        else ->
                            "已开放系统音频会话 · 本机杜比全景声 / 音效可生效"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StudioSwitch(
                checked = vm.systemAudioEffectEnabled,
                onChange = { vm.changeSystemAudioEffect(it) },
                label = "系统音效接入"
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassActionChip(
                label = if (hasPanel) "打开系统音效面板" else "前往系统声音设置",
                accent = true,
                onClick = { vm.openSystemAudioEffectPanel(context) }
            )
            if (hasInternalProcessing) {
                GlassActionChip(
                    label = "仅用系统音效",
                    onClick = { vm.useSystemAudioEffectOnly() }
                )
            }
        }
    }
}

@Composable
private fun PresetFilterBar(
    selected: PresetFilter,
    onSelect: (PresetFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PresetFilter.entries.forEach { item ->
            StudioFilterPillChip(
                label = item.label,
                selected = item == selected,
                onClick = { onSelect(item) },
                horizontalPadding = 14.dp,
                verticalPadding = 7.dp
            )
        }
    }
}

@Composable
private fun PresetStudioCard(
    preset: SoundPreset,
    intensity: PresetIntensity,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewCurve = remember(preset.id, intensity) {
        presetNetCurve(preset, 32, intensity = intensity)
    }
    val cardBg = if (selected) {
        Brush.verticalGradient(
            listOf(LocalStudioPalette.current.presetCardSelTop, LocalStudioPalette.current.presetCardSelBottom)
        )
    } else {
        StudioCardBrush
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
            .border(
                width = if (selected) 1.2.dp else 0.8.dp,
                color = if (selected) StudioCrimson else StudioBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) StudioOnRed else StudioText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(StudioCrimson),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SonanceIcons.Check,
                        contentDescription = "已选中",
                        tint = StudioOnRed,
                        modifier = Modifier.size(10.dp)
                    )
                }
            } else {
                val isReference = preset.id == SoundPresets.EXTREME_HIFI.id
                Text(
                    text = if (isReference) "参考" else preset.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isReference) StudioGold else StudioTextMuted
                )
            }
        }

        PresetMiniSparkline(
            curve = previewCurve,
            highlighted = selected,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )

        Text(
            text = preset.character,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) StudioChipSelectedText else StudioTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================================================
 * 页签 2：10 段均衡器（实时频响图 + 竖向混音台/精细滑条 + 曲风快选）
 * ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerTabContent(
    vm: SonanceViewModel,
    horizontalMode: Boolean,
    onToggleMode: () -> Unit
) {
    val eqLabel = when {
        vm.eqCustom -> "自定义调音"
        vm.eqFromSoundPreset -> "随预设：${vm.soundPreset.name}"
        else -> vm.eqPreset.label
    }

    val curve = remember(vm.eqBands, vm.eqEnabled, vm.soundFxParams, vm.soundFxEnabled) {
        computeLiveNetCurve(
            eqBands = vm.eqBands,
            eqEnabled = vm.eqEnabled,
            fxParams = vm.soundFxParams,
            fxEnabled = vm.soundFxEnabled,
            points = 64
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 20.dp, highlighted = vm.eqEnabled)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "10 段图形均衡器",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = StudioText
                )
                Text(
                    text = if (vm.eqEnabled) "$eqLabel · 双击频段标签可单项归零" else "当前已关闭（拖动任一频段自动激活）",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vm.eqEnabled) StudioTextDim else StudioTextMuted
                )
            }
            StudioSwitch(
                checked = vm.eqEnabled,
                onChange = { vm.toggleEq(it) },
                label = "均衡器总开关"
            )
        }

        StudioCurveGraph(
            curve = curve,
            ghostCurve = null,
            heightDp = 96f,
            accentActive = vm.eqEnabled
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassActionChip(
                label = if (horizontalMode) "竖向混音台" else "精细横条",
                onClick = onToggleMode
            )
            Spacer(Modifier.weight(1f))
            GlassActionChip(
                label = "全部归零",
                onClick = { vm.resetEqBands() },
                enabled = vm.eqEnabled || vm.eqBands.any { abs(it) > 0.05f }
            )
        }

        if (horizontalMode) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                EQ_CENTER_FREQS.forEachIndexed { index, hz ->
                    val db = vm.eqBands.getOrElse(index) { 0f }
                    HorizontalEqBandRow(
                        label = eqBandLabel(hz),
                        db = db,
                        minDb = vm.eqMinDb,
                        maxDb = vm.eqMaxDb,
                        highlighted = vm.eqEnabled && abs(db) > 0.05f,
                        onDrag = { vm.setEqBand(index, it) },
                        onResetBand = { vm.setEqBand(index, 0f) }
                    )
                }
            }
        } else {
            VerticalEqMixerBank(
                bands = vm.eqBands,
                eqEnabled = vm.eqEnabled,
                minDb = vm.eqMinDb,
                maxDb = vm.eqMaxDb,
                onChangeBand = { index, db -> vm.setEqBand(index, db) }
            )
        }
    }

    // 曲风快捷预设卡片
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "曲风快捷曲线",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = StudioText
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EqPreset.entries.forEach { preset ->
                val isSelected = !vm.eqCustom && !vm.eqFromSoundPreset &&
                    preset == vm.eqPreset &&
                    (preset != EqPreset.OFF || !vm.eqEnabled)
                StudioFilterPillChip(
                    label = preset.label,
                    selected = isSelected,
                    onClick = { vm.chooseEqPreset(preset) },
                    horizontalPadding = 13.dp,
                    verticalPadding = 7.dp
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VerticalEqMixerBank(
    bands: FloatArray,
    eqEnabled: Boolean,
    minDb: Float,
    maxDb: Float,
    onChangeBand: (Int, Float) -> Unit
) {
    val context = LocalContext.current
    val span = (maxDb - minDb).coerceAtLeast(0.001f)
    val palette = LocalStudioPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(StudioWellBg)
            .border(1.dp, StudioWellBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            EQ_CENTER_FREQS.forEachIndexed { index, hz ->
                val db = bands.getOrElse(index) { 0f }
                val active = eqEnabled && abs(db) > 0.05f
                val normalized = ((db - minDb) / span).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatShortDb(db),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (active) StudioChipSelectedText else StudioTextMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = {},
                                onDoubleClick = {
                                    hapticTick(context)
                                    onChangeBand(index, 0f)
                                }
                            )
                            .padding(vertical = 2.dp)
                    )

                    Spacer(Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp)
                            .semantics {
                                contentDescription = "${eqBandLabel(hz)} 频段"
                                stateDescription = "%.1f 分贝".format(db)
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    db.coerceIn(minDb, maxDb),
                                    minDb..maxDb
                                )
                                setProgress { requested ->
                                    onChangeBand(index, requested.coerceIn(minDb, maxDb))
                                    true
                                }
                            }
                            .pointerInput(index, minDb, maxDb) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val slop = viewConfiguration.touchSlop
                                    var dx = 0f
                                    var dy = 0f
                                    var dragging = false
                                    var abandoned = false

                                    fun fractionAt(y: Float): Float =
                                        (1f - (y / h)).coerceIn(0f, 1f)

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        dx += change.position.x - change.previousPosition.x
                                        dy += change.position.y - change.previousPosition.y

                                        if (!dragging && !abandoned) {
                                            if (abs(dy) > slop && abs(dy) >= abs(dx) * 0.85f) {
                                                dragging = true
                                                onChangeBand(index, eqDbFromSlider(fractionAt(change.position.y), minDb, maxDb))
                                                change.consume()
                                            } else if (abs(dx) > slop * 1.4f && abs(dx) > abs(dy) * 1.3f) {
                                                abandoned = true
                                            }
                                        } else if (dragging) {
                                            onChangeBand(index, eqDbFromSlider(fractionAt(change.position.y), minDb, maxDb))
                                            change.consume()
                                        }
                                    }

                                    if (!dragging && !abandoned) {
                                        onChangeBand(index, eqDbFromSlider(fractionAt(down.position.y), minDb, maxDb))
                                    }
                                }
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val padV = 8.dp.toPx()
                            val trackTop = padV
                            val trackBottom = size.height - padV
                            val trackLen = (trackBottom - trackTop).coerceAtLeast(1f)
                            val trackW = 4.dp.toPx()
                            val halfGain = maxOf(abs(minDb), abs(maxDb)) * 0.5f

                            fun yForDb(targetDb: Float): Float {
                                val r = ((targetDb - minDb) / span).coerceIn(0f, 1f)
                                return trackBottom - r * trackLen
                            }

                            val zeroY = yForDb(0f)
                            val thumbY = trackBottom - normalized * trackLen

                            // 调音台刻度横标（±半程细刻 + 0 dB 中线）
                            listOf(halfGain, -halfGain).forEach { refDb ->
                                val ry = yForDb(refDb)
                                drawLine(
                                    color = palette.eqWellInner,
                                    start = Offset(3.dp.toPx(), ry),
                                    end = Offset(size.width - 3.dp.toPx(), ry),
                                    strokeWidth = 0.7.dp.toPx()
                                )
                            }
                            drawLine(
                                color = palette.eqGridLine,
                                start = Offset(2.dp.toPx(), zeroY),
                                end = Offset(size.width - 2.dp.toPx(), zeroY),
                                strokeWidth = 1.dp.toPx()
                            )

                            // 轨道凹槽
                            drawRoundRect(
                                color = palette.eqBandBg,
                                topLeft = Offset(cx - trackW / 2f, trackTop),
                                size = Size(trackW, trackLen),
                                cornerRadius = CornerRadius(trackW / 2f)
                            )

                            // 0 dB 起点的信号能量填充段
                            val fillTop = minOf(zeroY, thumbY)
                            val fillH = abs(thumbY - zeroY)
                            if (fillH > 0.5f) {
                                val barBrush = if (eqEnabled) {
                                    Brush.verticalGradient(listOf(palette.coral, palette.crimsonDark))
                                } else {
                                    Brush.verticalGradient(listOf(palette.eqThumbInactive, palette.eqThumbInactive))
                                }
                                drawRoundRect(
                                    brush = barBrush,
                                    topLeft = Offset(cx - trackW / 2f, fillTop),
                                    size = Size(trackW, fillH),
                                    cornerRadius = CornerRadius(trackW / 2f)
                                )
                            }

                            // 精密推子旋钮头（开/关保持丹漆/暖铜金属质感）
                            val thumbR = 6.5.dp.toPx()
                            if (eqEnabled) {
                                drawCircle(
                                    color = palette.crimson.copy(alpha = 0.26f),
                                    radius = thumbR + 3.dp.toPx(),
                                    center = Offset(cx, thumbY)
                                )
                            }
                            drawCircle(
                                color = if (active || eqEnabled) palette.crimson else palette.eqLabelInactive,
                                radius = thumbR,
                                center = Offset(cx, thumbY)
                            )
                            drawCircle(
                                color = palette.wellBg,
                                radius = thumbR * 0.36f,
                                center = Offset(cx, thumbY)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = eqBandLabel(hz),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) StudioText else StudioTextDim,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = {},
                                onDoubleClick = {
                                    hapticTick(context)
                                    onChangeBand(index, 0f)
                                }
                            )
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizontalEqBandRow(
    label: String,
    db: Float,
    minDb: Float,
    maxDb: Float,
    highlighted: Boolean,
    onDrag: (Float) -> Unit,
    onResetBand: () -> Unit
) {
    val span = (maxDb - minDb).coerceAtLeast(0.001f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) StudioCoral else StudioTextMuted,
            modifier = Modifier
                .width(36.dp)
                .clickable(onClick = onResetBand)
        )
        SonanceSlider(
            value = ((db - minDb) / span).coerceIn(0f, 1f),
            onValueChange = { onDrag(eqDbFromSlider(it, minDb, maxDb)) },
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "$label 频段"
                    stateDescription = "%.1f 分贝".format(db)
                    progressBarRangeInfo = ProgressBarRangeInfo(db.coerceIn(minDb, maxDb), minDb..maxDb)
                    setProgress { requested ->
                        onDrag(requested.coerceIn(minDb, maxDb))
                        true
                    }
                },
            trackHeight = 3.dp,
            thumbSize = 13.dp,
            alwaysShowThumb = true,
            neutralPoint = ((0f - minDb) / span).coerceIn(0f, 1f)
        )
        Text(
            text = formatShortDb(db),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum"
            ),
            color = if (highlighted) StudioChipSelectedText else StudioTextDim,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(44.dp)
                .padding(start = 6.dp)
        )
    }
}

/* ============================================================
 * 页签 3：DSP 效果微调
 * ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsTabContent(vm: SonanceViewModel) {
    val fx = vm.soundFxParams

    // 顶栏状态与一键还原
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 18.dp, highlighted = vm.soundFxEnabled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "DSP 声场与色调引擎",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = StudioText
            )
            Text(
                text = when {
                    !vm.soundFxEnabled -> "当前已旁路 · 调节任一滑杆自动开启"
                    vm.soundFxCustom -> "已基于「${vm.soundPreset.name}」手动微调"
                    else -> "当前使用「${vm.soundPreset.name}」· 强度 ${vm.presetIntensity.label}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.soundFxEnabled) StudioTextDim else StudioTextMuted
            )
        }
        if (vm.soundFxCustom) {
            GlassActionChip(
                label = "还原预设",
                onClick = {
                    if (vm.soundPreset.id == SoundPresets.OFF.id) {
                        vm.chooseSoundPreset(SoundPresets.OFF)
                    } else {
                        vm.updateSoundFx(vm.soundPreset.scaled(vm.presetIntensity).second)
                    }
                    vm.showToast("已还原当前预设效果参数")
                },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        StudioSwitch(
            checked = vm.soundFxEnabled,
            onChange = { vm.toggleSoundFx(it) },
            label = "DSP 效果引擎开关"
        )
    }

    // 模块一：Poweramp 式高低音宽搁架与谐波低音
    FxModuleCard(title = "高低音色调校", subtitle = "双二阶宽搁架滤波 · 低音气垫与空气光泽") {
        FxSliderControl(
            label = "低音气垫（Bass 90Hz）",
            hint = "整体托起 30~110 Hz 下潜饱满度与弹跳感",
            value = fx.bassBoostDb,
            min = -TONE_MAX_DB,
            max = TONE_MAX_DB,
            valueLabel = formatDbSigned(fx.bassBoostDb),
            neutralPoint = 0.5f,
            onValue = { raw ->
                val snapped = if (abs(raw) < 0.25f) 0f else raw
                vm.updateSoundFx(fx.copy(bassBoostDb = snapped))
            }
        )

        FxSliderControl(
            label = "高音通透（Treble 9.5kHz）",
            hint = "丝滑平托极高频泛音与空气亮度，清澈不刺耳",
            value = fx.trebleBoostDb,
            min = -TONE_MAX_DB,
            max = TONE_MAX_DB,
            valueLabel = formatDbSigned(fx.trebleBoostDb),
            neutralPoint = 0.5f,
            onValue = { raw ->
                val snapped = if (abs(raw) < 0.25f) 0f else raw
                vm.updateSoundFx(fx.copy(trebleBoostDb = snapped))
            }
        )

        FxSliderControl(
            label = "虚拟低音谐波",
            hint = "动态合成二/三次谐波，小耳机与外放也能听清鼓点轮廓",
            value = fx.virtualBass,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.virtualBass),
            onValue = { vm.updateSoundFx(fx.copy(virtualBass = it)) }
        )
    }

    // 模块一B：超重低音引擎（ViPER 级多模式次低音强化）
    FxModuleCard(title = "超重低音引擎", subtitle = "可调谐振下潜 · 瞬态鼓点增强 · 215 Hz 去浊净声") {
        Text(
            text = "重低音模式",
            style = MaterialTheme.typography.labelLarge,
            color = StudioText
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0 to "自然重低音",
                1 to "蝰蛇炮震",
                2 to "超潜次低音"
            ).forEach { (modeId, title) ->
                StudioMiniFilterChip(
                    label = title,
                    selected = fx.viperBassMode == modeId,
                    onClick = { vm.updateSoundFx(fx.copy(viperBassMode = modeId)) }
                )
            }
        }
        FxSliderControl(
            label = "超重低音强度",
            hint = when (fx.viperBassMode) {
                1 -> "高能共振 + 瞬态冲击增强并自动抑浑，低音澎湃不遮人声"
                2 -> "实时合成二分频次谐波（f/2），拉满极低频物理震感"
                else -> "电子管温润染色 + 紧致鼓点下潜，清晰耐听"
            },
            value = fx.viperBass,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.viperBass),
            onValue = { vm.updateSoundFx(fx.copy(viperBass = it)) }
        )
        FxSliderControl(
            label = "下潜谐振频率",
            hint = "较低频率侧重胸腔潜震（35~50 Hz），较高侧重鼓点敲击感（55~95 Hz）",
            value = fx.viperBassFreqHz,
            min = 35f,
            max = 95f,
            valueLabel = "${fx.viperBassFreqHz.roundToInt()} Hz",
            onValue = { vm.updateSoundFx(fx.copy(viperBassFreqHz = it)) }
        )
        FxSliderControl(
            label = "高频明晰度与微细节",
            hint = "高通空气谐波 + 瞬态边缘强化并抑制 7.8 kHz 齿音，提升乐器分离度与解析力",
            value = fx.clarity,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.clarity),
            onValue = { vm.updateSoundFx(fx.copy(clarity = it)) }
        )
    }

    // 模块二：双耳声场与空间
    FxModuleCard(title = "声场定位与空间反射", subtitle = "低音锁相展宽 · BS2B 串扰 · 立体空间反射") {
        FxSliderControl(
            label = "立体声宽度",
            hint = "210 Hz 以下稳固锁定中心，展宽或收束中高频声像",
            value = fx.stereoWidth,
            min = 0f,
            max = 2f,
            valueLabel = "${percent(fx.stereoWidth)}${if (abs(fx.stereoWidth - 1f) < 0.015f) " · 原宽" else ""}",
            neutralPoint = 0.5f,
            onValue = { raw ->
                val snapped = if (abs(raw - 1f) < 0.025f) 1f else raw
                vm.updateSoundFx(fx.copy(stereoWidth = snapped))
            }
        )

        FxSliderControl(
            label = "双耳交叉馈送（BS2B）",
            hint = "模拟近场音箱自然串扰，缓解耳机头中压迫感",
            value = fx.crossfeed,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.crossfeed),
            onValue = { vm.updateSoundFx(fx.copy(crossfeed = it)) }
        )

        FxSliderControl(
            label = "空间混响湿度",
            hint = "保留 22 ms 纯净直达人声，营造现场或厅堂空气包围",
            value = fx.reverbWet,
            min = 0f,
            max = 0.6f,
            valueLabel = percent(fx.reverbWet),
            onValue = { vm.updateSoundFx(fx.copy(reverbWet = it)) }
        )

        AnimatedVisibility(
            visible = fx.reverbWet > 0.005f,
            enter = expandVertically(tween(180)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
        ) {
            FxSliderControl(
                label = "混响衰减长度",
                hint = "小值偏紧凑舞台，大值偏空灵厅堂",
                value = fx.reverbDecay,
                min = 0f,
                max = 1f,
                valueLabel = percent(fx.reverbDecay),
                onValue = { vm.updateSoundFx(fx.copy(reverbDecay = it)) }
            )
        }
    }

    // 模块三：外放保护与峰值限幅
    FxModuleCard(title = "外放保护与增益余量", subtitle = "物理护喇叭高通 · 防削波安全保护") {
        FxSliderControl(
            label = "外放保护高通截止",
            hint = "切除外放易破音的超低频震动；耳机建议保持关闭",
            value = fx.speakerHpfHz,
            min = 0f,
            max = 160f,
            valueLabel = if (fx.speakerHpfHz < 20f) "关闭" else "${fx.speakerHpfHz.roundToInt()} Hz",
            onValue = { raw ->
                val quantized = if (raw < 25f) 0f else raw.coerceIn(30f, 160f)
                vm.updateSoundFx(fx.copy(speakerHpfHz = quantized))
            }
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0f to "关闭（耳机）",
                38f to "车载 38Hz",
                46f to "平板 46Hz",
                58f to "手机 58Hz"
            ).forEach { (hz, tag) ->
                val active = (hz == 0f && fx.speakerHpfHz < 20f) ||
                    (hz > 0f && abs(fx.speakerHpfHz - hz) < 2f)
                StudioMiniFilterChip(
                    label = tag,
                    selected = active,
                    onClick = { vm.updateSoundFx(fx.copy(speakerHpfHz = hz)) }
                )
            }
        }

        FxSliderControl(
            label = "前置余量补偿（Preamp）",
            hint = "手动大幅推高 EQ 后可适当下压防止数字削波",
            value = fx.preampDb,
            min = -12f,
            max = 0f,
            valueLabel = if (fx.preampDb > -0.05f) "0.0 dB" else "%.1f dB".format(fx.preampDb),
            neutralPoint = 1f,
            onValue = { vm.updateSoundFx(fx.copy(preampDb = it)) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "软拐点峰值保护（Limiter）",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioText
                )
                Text(
                    text = "开启外放或超大重低音时建议开启防止爆音",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted
                )
            }
            StudioSwitch(
                checked = fx.limiter,
                onChange = { vm.updateSoundFx(fx.copy(limiter = it)) },
                label = "峰值保护"
            )
        }

        if (fx.limiter) {
            Spacer(Modifier.height(10.dp))
            FxSliderControl(
                label = "限幅启动阈值",
                hint = "高于此电平开始柔和收口，外放推荐 -0.4 dB",
                value = fx.limiterThresholdDb,
                min = -12f,
                max = 0f,
                valueLabel = "%.1f dB".format(fx.limiterThresholdDb),
                neutralPoint = 1f,
                onValue = { vm.updateSoundFx(fx.copy(limiterThresholdDb = it)) }
            )
        }
    }
}

/* ============================================================
 * 页签 4：人声与伴奏（卡拉OK 中置消除）
 * ============================================================ */

@Composable
private fun KaraokeTabContent(vm: SonanceViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 20.dp, highlighted = vm.karaokeEnabled)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (vm.karaokeEnabled) StudioChipSelectedBg else StudioPillBg)
                    .border(
                        width = 0.7.dp,
                        color = if (vm.karaokeEnabled) StudioCrimson.copy(alpha = 0.45f) else StudioBorder,
                        shape = RoundedCornerShape(13.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SonanceIcons.Mic,
                    contentDescription = null,
                    tint = if (vm.karaokeEnabled) StudioCoral else StudioTextDim,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "人声消音 / 伴奏模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = StudioText
                )
                Text(
                    text = if (vm.karaokeEnabled) "正在消减中置人声频段，保留两侧乐器伴奏" else "当前保持完整原唱立体声输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vm.karaokeEnabled) StudioChipSelectedText else StudioTextMuted
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeChoiceTile(
                title = "原唱模式",
                subtitle = "完整人声与伴奏立体声",
                selected = !vm.karaokeEnabled,
                onClick = { vm.setKaraoke(false) },
                modifier = Modifier.weight(1f)
            )
            ModeChoiceTile(
                title = "伴奏模式",
                subtitle = "Mid/Side 中置人声削减",
                selected = vm.karaokeEnabled,
                onClick = { vm.setKaraoke(true) },
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(StudioWellBg)
                .border(0.8.dp, StudioWellBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "处理原理与说明",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = StudioText
            )
            Text(
                text = "• 保留超低频贝斯与底鼓重心不空虚，仅对中置人声核心频段做相位相消。\n" +
                    "• 消音效果取决于录音混音时主唱是否居正中，对带有强混响的双声道现场录音残留会略多。\n" +
                    "• 此模式独立于上方的均衡器与音效预设，切回原唱即刻无损还原。",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted
            )
        }
    }
}

/* ============================================================
 * 可视化频响图表与共享模块控件
 * ============================================================ */

@Composable
private fun StudioCurveGraph(
    curve: FloatArray,
    ghostCurve: FloatArray?,
    heightDp: Float,
    accentActive: Boolean
) {
    val palette = LocalStudioPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StudioWellBg)
            .border(1.dp, StudioWellBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
        ) {
            val minDb = -12f
            val maxDb = 12f
            val span = maxDb - minDb
            fun yFor(db: Float): Float =
                size.height * (1f - ((db - minDb) / span).coerceIn(0f, 1f))

            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            // +6 dB / 0 dB / -6 dB 参考横线
            listOf(6f, 0f, -6f).forEach { refDb ->
                val y = yFor(refDb)
                drawLine(
                    color = if (refDb == 0f) palette.curveRefZero else palette.curveRefOther,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (refDb == 0f) 1.dp.toPx() else 0.7.dp.toPx(),
                    pathEffect = if (refDb == 0f) null else dash
                )
            }

            // 倍频程纵向参考刻度（对应 125Hz / 500Hz / 2kHz / 8kHz 在 31..16k 对数轴的位置）
            listOf(125.0, 500.0, 2000.0, 8000.0).forEach { hz ->
                val ratio = (kotlin.math.ln(hz / 31.0) / kotlin.math.ln(16_000.0 / 31.0)).toFloat()
                val x = size.width * ratio
                drawLine(
                    color = palette.curveGrid,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 0.7.dp.toPx()
                )
            }

            val middleY = yFor(0f)

            fun addSmoothSegments(path: Path, samples: FloatArray) {
                val step = size.width / (samples.size - 1)
                var prevX = 0f
                var prevY = yFor(samples[0])
                path.moveTo(prevX, prevY)
                for (i in 1 until samples.size) {
                    val x = i * step
                    val y = yFor(samples[i])
                    val midX = (prevX + x) * 0.5f
                    path.cubicTo(midX, prevY, midX, y, x, y)
                    prevX = x
                    prevY = y
                }
            }

            if (ghostCurve != null && ghostCurve.size >= 2) {
                val ghostPath = Path()
                addSmoothSegments(ghostPath, ghostCurve)
                drawPath(
                    path = ghostPath,
                    color = palette.gold.copy(alpha = 0.48f),
                    style = Stroke(
                        width = 1.3.dp.toPx(),
                        pathEffect = dash,
                        cap = StrokeCap.Round
                    )
                )
            }

            if (curve.size >= 2) {
                val inactiveStroke = palette.curveInactiveStroke
                val line = Path()
                addSmoothSegments(line, curve)
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, middleY)
                    lineTo(0f, middleY)
                    close()
                }
                if (accentActive) {
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            listOf(
                                palette.crimson.copy(alpha = 0.28f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = line,
                        brush = Brush.horizontalGradient(
                            listOf(palette.crimsonDark, palette.crimson, palette.coral, palette.gold)
                        ),
                        style = Stroke(
                            width = 2.3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                } else {
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            listOf(
                                inactiveStroke.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = line,
                        color = inactiveStroke,
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("31", "125", "500", "2k", "8k", "16k Hz").forEach { freq ->
                Text(
                    text = freq,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (accentActive) StudioTextDim else StudioTextMuted
                )
            }
        }
    }
}

@Composable
private fun PresetMiniSparkline(
    curve: FloatArray,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalStudioPalette.current
    Canvas(modifier = modifier) {
        val minDb = -8f
        val maxDb = 8f
        val span = maxDb - minDb
        fun yFor(db: Float): Float =
            size.height * (1f - ((db - minDb) / span).coerceIn(0f, 1f))

        val zeroY = yFor(0f)
        drawLine(
            color = palette.tabsTrayBorder,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 0.7.dp.toPx()
        )

        if (curve.size >= 2) {
            val path = Path()
            val step = size.width / (curve.size - 1)
            var prevX = 0f
            var prevY = yFor(curve[0])
            path.moveTo(prevX, prevY)
            for (idx in 1 until curve.size) {
                val x = idx * step
                val y = yFor(curve[idx])
                val midX = (prevX + x) * 0.5f
                path.cubicTo(midX, prevY, midX, y, x, y)
                prevX = x
                prevY = y
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, zeroY)
                lineTo(0f, zeroY)
                close()
            }

            val strokeColor = if (highlighted) palette.coral else palette.textMuted
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(
                        strokeColor.copy(alpha = if (highlighted) 0.28f else 0.14f),
                        Color.Transparent
                    )
                )
            )
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
private fun FxModuleCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .studioCardContainer(radius = 20.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = StudioText
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }
        content()
    }
}

@Composable
private fun FxSliderControl(
    label: String,
    hint: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: String,
    neutralPoint: Float? = null,
    onValue: (Float) -> Unit
) {
    val span = (max - min).coerceAtLeast(0.0001f)
    val fraction = ((value - min) / span).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = StudioText
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold,
                color = StudioChipSelectedText
            )
        }
        SonanceSlider(
            value = fraction,
            onValueChange = { onValue(min + it * span) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(min, max), min..max)
                    setProgress { requested ->
                        onValue(requested.coerceIn(min, max))
                        true
                    }
                },
            trackHeight = 3.dp,
            thumbSize = 14.dp,
            alwaysShowThumb = true,
            neutralPoint = neutralPoint
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted
        )
    }
}

@Composable
private fun ModeChoiceTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) StudioChipSelectedBg else StudioPillBg)
            .border(
                width = if (selected) 1.1.dp else 0.8.dp,
                color = if (selected) StudioCrimson else StudioBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) StudioOnRed else StudioText
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) StudioChipSelectedText else StudioTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StudioFilterPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) StudioChipSelectedBg else LocalStudioPalette.current.chipUnselectedBg)
            .border(
                width = if (selected) 1.dp else 0.8.dp,
                color = if (selected) StudioCrimson.copy(alpha = 0.82f) else StudioBorder,
                shape = CircleShape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(
            text = label,
            style = if (verticalPadding <= 5.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) StudioOnRed else StudioTextDim
        )
    }
}

@Composable
private fun StudioMiniFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    StudioFilterPillChip(
        label = label,
        selected = selected,
        onClick = onClick,
        horizontalPadding = 11.dp,
        verticalPadding = 5.dp
    )
}

@Composable
private fun StudioTagPill(
    label: String,
    highlighted: Boolean,
    bypassing: Boolean = false
) {
    val bg = when {
        bypassing -> StudioGoldSoft
        highlighted -> StudioChipSelectedBg
        else -> StudioPillBg
    }
    val fg = when {
        bypassing -> StudioGold
        highlighted -> StudioChipSelectedText
        else -> StudioTextMuted
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlighted || bypassing) FontWeight.SemiBold else FontWeight.Medium,
            color = fg
        )
    }
}

@Composable
private fun StudioSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val skin = LocalSkin.current
    val trackBg = when {
        checked -> skin.accent
        skin.immersive && skin.isLight -> Color.White.copy(alpha = 0.70f)
        skin.immersive -> Color.White.copy(alpha = 0.14f)
        else -> skin.surface2
    }
    val trackBorder = when {
        checked -> skin.accent
        skin.immersive && skin.isLight -> Color.Black.copy(alpha = 0.14f)
        skin.immersive -> Color.White.copy(alpha = 0.18f)
        else -> skin.border2
    }
    val thumbBg = if (checked) Color.White else skin.textMuted
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 36.dp)
            .semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(trackBg)
                .border(0.9.dp, trackBorder, RoundedCornerShape(12.dp)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(thumbBg)
            )
        }
    }
}

/* ============================================================
 * 工具函数与 DSP 曲线预览计算
 * ============================================================ */

private fun computeLiveNetCurve(
    eqBands: FloatArray,
    eqEnabled: Boolean,
    fxParams: SoundFxParams,
    fxEnabled: Boolean,
    points: Int
): FloatArray {
    val safePoints = points.coerceAtLeast(2)
    val bands = if (eqEnabled) eqBands else FloatArray(EQ_BAND_COUNT)
    val bassTone = if (fxEnabled) fxParams.bassBoostDb else 0f
    val trebleTone = if (fxEnabled) fxParams.trebleBoostDb else 0f
    val response = EqResponse(bands, 48_000, bassTone, trebleTone)
    val fxBudget = if (fxEnabled) effectPreampBudgetDb(fxParams, channels = 2) else 0f
    val manualPreamp = if (fxEnabled) fxParams.preampDb else 0f
    val hasTone = fxEnabled && (abs(bassTone) > 0.01f || abs(trebleTone) > 0.01f)
    val autoTrim = if (eqEnabled || hasTone) eqAutoPreampDb(response.maxBoostDb() + fxBudget) else -fxBudget
    return FloatArray(safePoints) { i ->
        val hz = 31.0 * (16_000.0 / 31.0).pow(i.toDouble() / (safePoints - 1))
        response.dbAt(hz) + autoTrim + manualPreamp
    }
}

private fun computeLiveToneBands(
    eqBands: FloatArray,
    eqEnabled: Boolean,
    fxParams: SoundFxParams,
    fxEnabled: Boolean
): FloatArray {
    val bands = if (eqEnabled) eqBands else FloatArray(EQ_BAND_COUNT)
    val bassTone = if (fxEnabled) fxParams.bassBoostDb else 0f
    val trebleTone = if (fxEnabled) fxParams.trebleBoostDb else 0f
    val response = EqResponse(bands, 48_000, bassTone, trebleTone)
    val fxBudget = if (fxEnabled) effectPreampBudgetDb(fxParams, channels = 2) else 0f
    val manualPreamp = if (fxEnabled) fxParams.preampDb else 0f
    val hasTone = fxEnabled && (abs(bassTone) > 0.01f || abs(trebleTone) > 0.01f)
    val trim = if (eqEnabled || hasTone) eqAutoPreampDb(response.maxBoostDb() + fxBudget) else -fxBudget
    fun avg(vararg hz: Double): Float {
        var sum = 0.0
        for (frequency in hz) sum += response.dbAt(frequency)
        return (sum / hz.size).toFloat() + trim + manualPreamp
    }
    return floatArrayOf(
        avg(62.0, 125.0),
        avg(250.0, 500.0, 1000.0, 2000.0),
        avg(4000.0, 8000.0, 16000.0)
    )
}

private fun routeIconOf(route: AudioOutputRoute): ImageVector = when (route) {
    AudioOutputRoute.PHONE_SPEAKER -> SonanceIcons.Volume
    AudioOutputRoute.TABLET_SPEAKER -> SonanceIcons.Volume
    AudioOutputRoute.WIRED_HEADPHONE -> SonanceIcons.Waveform
    AudioOutputRoute.BLUETOOTH -> SonanceIcons.Waveform
    AudioOutputRoute.USB_DAC -> SonanceIcons.Music
}

private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"

private fun formatShortDb(db: Float): String = when {
    db > 0.05f -> "+%.1f".format(db)
    db < -0.05f -> "%.1f".format(db)
    else -> "0"
}

private fun formatDbSigned(db: Float): String = when {
    db > 0.05f -> "+%.1f dB".format(db)
    db < -0.05f -> "%.1f dB".format(db)
    else -> "0.0 dB"
}
