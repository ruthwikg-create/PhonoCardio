package com.ruthwik.phonocardio.signal

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class PhonoSignalProcessor(private val sampleRate: Int = 16_000) {
    private var dc = 0.0
    private var hpX = 0.0
    private var hpY = 0.0
    private var lpY = 0.0
    private var noisePower = 1e-5
    private val beatTimes = ArrayDeque<Long>()
    private var samplesProcessed = 0L

    fun process(input: ShortArray): ProcessedChunk {
        val out = FloatArray(input.size)
        var signalPower = 0.0
        var residualPower = 0.0
        input.forEachIndexed { i, raw ->
            val x = raw / 32768.0
            dc = 0.995 * dc + 0.005 * x
            val centered = x - dc
            val hp = 0.985 * (hpY + centered - hpX)
            hpX = centered
            hpY = hp
            lpY += 0.08 * (hp - lpY)
            val y = lpY
            signalPower += y * y
            val residual = centered - y
            residualPower += residual * residual
            noisePower = 0.995 * noisePower + 0.005 * residual * residual
            out[i] = y.toFloat()
        }
        val snr = 10.0 * log10((signalPower / max(residualPower, 1e-9)) + 1e-9)
        val threshold = max(0.008, sqrt(noisePower) * 3.2)
        val start = samplesProcessed
        for (i in 1 until out.lastIndex) {
            val v = abs(out[i])
            if (v > threshold && v >= abs(out[i - 1]) && v >= abs(out[i + 1])) {
                val t = start + i
                if (beatTimes.isEmpty() || t - beatTimes.last() > sampleRate * 0.28) {
                    beatTimes.addLast(t)
                    while (beatTimes.size > 12) beatTimes.removeFirst()
                }
            }
        }
        samplesProcessed += input.size
        val bpm = if (beatTimes.size >= 2) {
            val intervals = beatTimes.zipWithNext().map { it.second - it.first }.sorted()
            val median = intervals[intervals.size / 2]
            if (median in (sampleRate * 0.3)..(sampleRate * 2.0)) (60.0 * sampleRate / median).toInt() else null
        } else null
        val quality = (snr / 25.0).coerceIn(0.0, 1.0).toFloat()
        val confidence = if (bpm != null) (quality * 0.8f + (beatTimes.size / 8f).coerceAtMost(1f) * 0.2f).coerceIn(0f, 1f) else quality * 0.5f
        return ProcessedChunk(out.toList(), bpm, beatTimes.size, snr.toFloat(), quality, confidence)
    }
    fun reset() {
        dc = 0.0; hpX = 0.0; hpY = 0.0; lpY = 0.0; noisePower = 1e-5
        beatTimes.clear(); samplesProcessed = 0
    }
}
data class ProcessedChunk(val waveform: List<Float>, val bpm: Int?, val beatCount: Int, val snrDb: Float, val quality: Float, val confidence: Float)
