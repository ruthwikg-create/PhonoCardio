package com.ruthwik.phonocardio.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.ruthwik.phonocardio.signal.PhonoSignalProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioCaptureEngine(private val context: Context) {
    private val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val processor = PhonoSignalProcessor(sampleRate)

    fun start(scope: CoroutineScope, onChunk: (List<Float>, Int?, Int, Float, Float, Float) -> Unit): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stop()
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, (min * 2).coerceAtLeast(sampleRate / 2))
        if (audio.state != AudioRecord.STATE_INITIALIZED) return false
        record = audio
        processor.reset()
        audio.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(sampleRate / 50)
            while (isActive) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    val c = processor.process(buffer.copyOf(n))
                    onChunk(c.waveform, c.bpm, c.beatCount, c.snrDb, c.quality, c.confidence)
                }
            }
        }
        return true
    }

    fun hasHeadset(): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    fun stop() {
        job?.cancel(); job = null
        record?.runCatching { stop() }
        record?.release(); record = null
    }
}
