package com.sonance.player.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** One persistent final scale for every pager page, independent of the track identity. */
internal class PlayerCoverMotion(initialPlaying: Boolean) {
    var scale: Float = if (initialPlaying) 1f else 0.96f
        private set
    private var base = scale
    private var from = base
    private var target = base
    private var elapsed = 600f
    private var phase = 0f
    private var envelope = 0f
    private var energy = 0f
    private var resumeDelay = 0f
    private var lastTrack: Long? = null

    val settled: Boolean get() = elapsed >= 600f && envelope <= 0f && resumeDelay <= 0f

    fun advance(dtMs: Float, playing: Boolean, frozen: Boolean, trackId: Long, audioEnergy: Float = 0f): Float {
        val dt = dtMs.coerceIn(0f, 50f)
        val changedTrack = lastTrack != null && lastTrack != trackId
        lastTrack = trackId
        if (frozen || changedTrack) {
            resumeDelay = 180f
            return scale // Freeze the displayed result, not just one factor of it.
        }
        if (resumeDelay > 0f) {
            resumeDelay = (resumeDelay - dt).coerceAtLeast(0f)
            return scale // Absorb the settled-page -> media transition/buffering handoff.
        }
        val desired = if (playing) 1f else 0.96f
        if (desired != target) {
            from = base
            target = desired
            elapsed = 0f
        }
        elapsed = (elapsed + dt).coerceAtMost(600f)
        val t = elapsed / 600f
        val eased = t * t * (3f - 2f * t)
        base = from + (target - from) * eased
        envelope = (envelope + if (playing) dt / 450f else -dt / 600f).coerceIn(0f, 1f)
        if (envelope > 0f) phase = (phase + dt) % 4600f
        val input = if (playing) audioEnergy.coerceIn(0f, 1f) else 0f
        val k = (dt / if (input > energy) 120f else 450f).coerceIn(0f, 1f)
        energy += (input - energy) * k
        val periodic = (0.5 - 0.5 * cos(2.0 * PI * phase / 4600f)).toFloat()
        scale = base * (1f + envelope * (0.012f * periodic + 0.002f * energy))
        if (!playing && abs(scale - 0.96f) < 0.00001f && elapsed >= 600f && envelope == 0f) scale = 0.96f
        return scale
    }
}
