package com.tyler.primelauncher

import android.media.audiofx.Visualizer
import android.os.SystemClock

/**
 * Loudness of what's playing (Prime's voice) for the call halo, from the output-mix Visualizer (session 0).
 * Many phones exclude voice-call audio from that mix; if it stays silent we report null and the halo uses a smooth rhythmic pulse.
 */
object CallLevel {
    private var vis: Visualizer? = null; private var startedAt = 0L; private var heard = false
    private val m = Visualizer.MeasurementPeakRms()

    private var failedAt = -1L
    fun start() {
        if (vis != null || (failedAt >= 0 && SystemClock.elapsedRealtime() - failedAt < 30_000)) return
        vis = runCatching { Visualizer(0).apply { measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS; scalingMode = Visualizer.SCALING_MODE_AS_PLAYED; enabled = true } }.getOrNull()
        startedAt = SystemClock.elapsedRealtime(); heard = false; failedAt = if (vis == null) startedAt else -1L
    }
    fun stop() { runCatching { vis?.enabled = false; vis?.release() }; vis = null }

    /** 0..1, or null when no real level is available. */
    fun read(): Double? {
        val v = vis ?: return null
        if (runCatching { v.getMeasurementPeakRms(m) }.getOrDefault(-1) != Visualizer.SUCCESS) return null
        val lvl = rmsToLevel(m.mRms)
        if (lvl > 0.04) heard = true
        return if (heard) lvl else null
    }
    /** mB (-9600 = silence .. 0 = full scale) -> 0..1 on a speech-friendly curve (unit-tested). */
    fun rmsToLevel(mB: Int): Double = ((mB + 5400) / 4200.0).coerceIn(0.0, 1.0)
}
