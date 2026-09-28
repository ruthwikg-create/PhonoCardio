package com.ruthwik.phonocardio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruthwik.phonocardio.audio.AudioCaptureEngine
import com.ruthwik.phonocardio.model.SignalSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhonoViewModel : ViewModel() {
    private val _state = MutableStateFlow(SignalSnapshot())
    val state = _state.asStateFlow()
    private var engine: AudioCaptureEngine? = null
    fun attach(context: android.content.Context) { engine = AudioCaptureEngine(context.applicationContext) }
    fun toggle(scope: kotlinx.coroutines.CoroutineScope) {
        val e = engine ?: return
        if (_state.value.running) {
            e.stop(); _state.value = _state.value.copy(running = false)
            return
        }
        val started = e.start(scope) { wave, bpm, beats, snr, quality, confidence ->
            _state.value = _state.value.copy(samples = wave.takeLast(800), heartRateBpm = bpm, beatCount = beats, snrDb = snr, quality = quality, confidence = confidence, headsetDetected = e.hasHeadset(), running = true)
        }
        if (started) _state.value = _state.value.copy(running = true, headsetDetected = e.hasHeadset())
    }
    override fun onCleared() { engine?.stop(); super.onCleared() }
}

@Composable
fun Waveform(samples: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFFF7F8FA))) {
        if (samples.size < 2) return@Canvas
        val maxValue = samples.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = size.width * i / samples.lastIndex.coerceAtLeast(1)
            val y = size.height / 2f - (v / maxValue) * size.height * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFFB42318), style = Stroke(width = 3f))
    }
}

@Composable
fun App(viewModel: PhonoViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    var permission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) {
        viewModel.attach(context)
        permission = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F8FA)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("PhonoCardio", style = MaterialTheme.typography.headlineMedium)
                Text("Digital phonocardiography research prototype", color = Color.Gray)
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.headsetDetected) "● Headset microphone detected" else "○ Connect a headset microphone")
                        Text("Place the microphone over the precordial area. Keep the phone and cable still.")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Heart rate")
                            Text(state.heartRateBpm?.let { "$it BPM" } ?: "--", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Confidence")
                            Text("${(state.confidence * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                Waveform(state.samples, Modifier.fillMaxWidth().height(220.dp))
                Text("SNR: %.1f dB   •   Quality: %d%%   •   Beats: %d".format(state.snrDb, (state.quality * 100).toInt(), state.beatCount))
                Button(onClick = { if (!permission) launcher.launch(Manifest.permission.RECORD_AUDIO) else viewModel.toggle(scope) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.running) "Stop measurement" else "Start measurement")
                }
                Text("Research prototype — not a medical diagnostic device.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
