package com.ruthwik.phonocardio.model

data class SignalSnapshot(
    val samples: List<Float> = emptyList(),
    val envelope: List<Float> = emptyList(),
    val heartRateBpm: Int? = null,
    val beatCount: Int = 0,
    val lastBeatAgeMs: Long? = null,
    val rrMs: Int? = null,
    val snrDb: Float = 0f,
    val quality: Float = 0f,
    val confidence: Float = 0f,
    val headsetDetected: Boolean = false,
    val running: Boolean = false,
    val status: String = "READY",
    val processingLatencyMs: Long = 0,
    val sampleRate: Int = 16_000,
    val measurementDurationSec: Int = 0
)

data class CalibrationProfile(
    val completed: Boolean = false,
    val timestampMs: Long = 0L,
    val sampleRate: Int = 16_000,
    val baselineNoise: Float = 0f,
    val peakLevel: Float = 0f,
    val quality: Float = 0f,
    val version: String = "CAL-1.0"
)

data class MeasurementRecord(
    val id: Long,
    val timestampMs: Long,
    val durationSec: Int,
    val heartRateBpm: Int?,
    val rrMs: Int?,
    val beatCount: Int,
    val quality: Float,
    val confidence: Float,
    val snrDb: Float,
    val status: String,
    val sampleRate: Int,
    val algorithmVersion: String = "DSP-0.2"
)

data class ReferenceValidationRecord(
    val id: Long,
    val timestampMs: Long,
    val measuredBpm: Int,
    val referenceBpm: Int,
    val source: String,
    val absoluteErrorBpm: Float,
    val signedErrorBpm: Float,
    val percentError: Float
)
