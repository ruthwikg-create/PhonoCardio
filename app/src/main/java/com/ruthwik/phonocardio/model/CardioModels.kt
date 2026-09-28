package com.ruthwik.phonocardio.model

data class SignalSnapshot(
    val samples: List<Float> = emptyList(),
    val heartRateBpm: Int? = null,
    val beatCount: Int = 0,
    val snrDb: Float = 0f,
    val quality: Float = 0f,
    val confidence: Float = 0f,
    val headsetDetected: Boolean = false,
    val running: Boolean = false
)
