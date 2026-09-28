package com.ruthwik.phonocardio.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.core.content.ContextCompat
import com.ruthwik.phonocardio.signal.PhonoSignalProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioCaptureEngine(private val context: Context) {
    private val sampleRate=16_000
    private var record:AudioRecord?=null
    private var job:Job?=null
    private val processor=PhonoSignalProcessor(sampleRate)
    fun start(scope:CoroutineScope,onChunk:(com.ruthwik.phonocardio.signal.ProcessedChunk)->Unit):Boolean{
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false
        stop()
        val min=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0)return false
        val audio=AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,(min*4).coerceAtLeast(sampleRate))
        if(audio.state!=AudioRecord.STATE_INITIALIZED)return false
        record=audio;processor.reset();audio.startRecording()
        job=scope.launch(Dispatchers.IO){
            val buffer=ShortArray(sampleRate/25);var lastUiNs=0L
            while(isActive){
                val n=audio.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                if(n>0){
                    val p=processor.process(if(n==buffer.size)buffer else buffer.copyOf(n));val now=System.nanoTime()
                    if(now-lastUiNs>=50_000_000L){lastUiNs=now;withContext(Dispatchers.Main.immediate){onChunk(p)}}
                }
            }
        };return true
    }
    fun hasHeadset():Boolean{
        val m=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return m.getDevices(AudioManager.GET_DEVICES_INPUTS).any{it.type==AudioDeviceInfo.TYPE_WIRED_HEADSET||it.type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||it.type==AudioDeviceInfo.TYPE_USB_HEADSET||it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==AudioDeviceInfo.TYPE_BLE_HEADSET}
    }
    fun stop(){job?.cancel();job=null;record?.runCatching{stop()};record?.release();record=null}
}
