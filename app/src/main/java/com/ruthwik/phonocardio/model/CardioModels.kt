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
    val sampleRate: Int = 16_000
)
