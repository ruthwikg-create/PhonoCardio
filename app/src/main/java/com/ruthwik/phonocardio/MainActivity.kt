package com.ruthwik.phonocardio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruthwik.phonocardio.audio.AudioCaptureEngine
import com.ruthwik.phonocardio.data.PhonoRepository
import com.ruthwik.phonocardio.model.*
import com.ruthwik.phonocardio.signal.ProcessedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhonoViewModel : ViewModel() {
    private val _state = MutableStateFlow(SignalSnapshot())
    val state = _state.asStateFlow()
    private var engine: AudioCaptureEngine? = null
    private var repo: PhonoRepository? = null
    var calibration by mutableStateOf(CalibrationProfile()); private set
    var measurements by mutableStateOf(emptyList<MeasurementRecord>()); private set
    var validations by mutableStateOf(emptyList<ReferenceValidationRecord>()); private set
    var calibrating by mutableStateOf(false); private set
    var calibrationProgress by mutableStateOf(0f); private set
    var calibrationMessage by mutableStateOf("Connect the microphone to begin calibration."); private set
    private var measurementStartedAt = 0L
    private var calibrationStartedAt = 0L
    private var baselineSum = 0.0
    private var baselineN = 0
    private var contactPeak = 0f
    private var contactQuality = 0f

    fun attach(context: android.content.Context) {
        if (engine == null) engine = AudioCaptureEngine(context.applicationContext)
        if (repo == null) {
            repo = PhonoRepository(context.applicationContext)
            calibration = repo!!.getCalibration()
            measurements = repo!!.getMeasurements()
            validations = repo!!.getValidations()
        }
    }

    fun toggle(scope: CoroutineScope): Boolean {
        val e = engine ?: return false
        if (_state.value.running) {
            val final = _state.value
            e.stop()
            saveMeasurement(final)
            _state.value = final.copy(running = false, status = "READY")
            return true
        }
        if (!calibration.completed) {
            _state.value = _state.value.copy(status = "CALIBRATION_REQUIRED")
            return false
        }
        val started = e.start(scope) { p: ProcessedChunk ->
            val elapsed = if (measurementStartedAt == 0L) 0 else ((System.currentTimeMillis() - measurementStartedAt) / 1000L).toInt()
            _state.value = _state.value.copy(
                samples = p.waveform, envelope = p.envelope, heartRateBpm = p.bpm,
                beatCount = p.beatCount, rrMs = p.rrMs, lastBeatAgeMs = p.lastBeatAgeMs,
                snrDb = p.snrDb, quality = p.quality, confidence = p.confidence,
                headsetDetected = e.hasHeadset(), running = true, status = p.status,
                processingLatencyMs = p.processingLatencyMs, measurementDurationSec = elapsed
            )
        }
        if (started) {
            measurementStartedAt = System.currentTimeMillis()
            _state.value = _state.value.copy(running = true, headsetDetected = e.hasHeadset(), status = "ACQUIRING")
        }
        return started
    }

    fun startCalibration(scope: CoroutineScope): Boolean {
        if (calibrating) return false
        val e = engine ?: return false
        calibration = CalibrationProfile()
        calibrating = true
        calibrationProgress = 0f
        calibrationMessage = "Phase 1/2: keep microphone still and quiet for 2 seconds."
        calibrationStartedAt = System.currentTimeMillis()
        baselineSum = 0.0; baselineN = 0; contactPeak = 0f; contactQuality = 0f
        val started = e.start(scope) { p ->
            val elapsed = (System.currentTimeMillis() - calibrationStartedAt) / 1000f
            calibrationProgress = (elapsed / 6f).coerceIn(0f, 1f)
            val window = p.waveform.takeLast(2000)
            val meanAbs = if (window.isEmpty()) 0f else window.map { abs(it) }.average().toFloat()
            if (elapsed < 2f) {
                calibrationMessage = "Phase 1/2: quiet baseline."
                baselineSum += meanAbs; baselineN++
            } else {
                calibrationMessage = "Phase 2/2: gently place microphone on the precordial area."
                contactPeak = maxOf(contactPeak, window.maxOfOrNull { abs(it) } ?: 0f)
                contactQuality = maxOf(contactQuality, p.quality)
            }
            if (elapsed >= 6f) finishCalibration(e)
        }
        if (!started) {
            calibrating = false
            calibrationMessage = "Could not start microphone capture. Check permission/input."
        }
        return started
    }

    private fun finishCalibration(e: AudioCaptureEngine) {
        if (!calibrating) return
        e.stop()
        val baseline = if (baselineN == 0) 0f else (baselineSum / baselineN).toFloat()
        val valid = e.hasHeadset() && contactPeak > maxOf(0.0008f, baseline * 1.35f) && contactQuality >= 0.20f
        calibration = CalibrationProfile(completed = valid, timestampMs = System.currentTimeMillis(), baselineNoise = baseline, peakLevel = contactPeak, quality = contactQuality)
        repo?.saveCalibration(calibration)
        calibrating = false; calibrationProgress = 1f
        calibrationMessage = if (valid) "Calibration complete. Input conditions accepted." else "Calibration rejected. Reconnect microphone and repeat."
        _state.value = _state.value.copy(running = false, status = if (valid) "CALIBRATED" else "CALIBRATION_FAILED")
    }

    fun resetCalibration() {
        engine?.stop(); repo?.clearCalibration(); calibration = CalibrationProfile()
        calibrating = false; calibrationProgress = 0f
        calibrationMessage = "Calibration cleared. Repeat the guided workflow."
        _state.value = _state.value.copy(running = false, status = "CALIBRATION_REQUIRED")
    }

    private fun saveMeasurement(s: SignalSnapshot) {
        if (measurementStartedAt == 0L) return
        val duration = ((System.currentTimeMillis() - measurementStartedAt) / 1000L).toInt()
        if (duration < 3) return
        repo?.saveMeasurement(MeasurementRecord(System.currentTimeMillis(), System.currentTimeMillis(), duration, s.heartRateBpm, s.rrMs, s.beatCount, s.quality, s.confidence, s.snrDb, s.status, s.sampleRate))
        measurements = repo?.getMeasurements().orEmpty()
        measurementStartedAt = 0L
    }

    fun addValidation(measured: Int, reference: Int, source: String) {
        if (measured !in 30..220 || reference !in 30..220) return
        val signed = (measured - reference).toFloat()
        val absolute = abs(signed)
        repo?.saveValidation(ReferenceValidationRecord(System.currentTimeMillis(), System.currentTimeMillis(), measured, reference, source.ifBlank { "Reference device" }, absolute, signed, absolute / reference * 100f))
        validations = repo?.getValidations().orEmpty()
    }

    fun clearHistory() { repo?.clearMeasurements(); measurements = emptyList() }
    fun clearValidation() { repo?.clearValidations(); validations = emptyList() }
    override fun onCleared() { engine?.stop(); super.onCleared() }
}

private val Bg = Color(0xFF06101D)
private val Panel = Color(0xFF0D1A2B)
private val Primary = Color(0xFF43E6D3)
private val Text = Color(0xFFEAF3FF)
private val Muted = Color(0xFF8EA4BD)

@Composable
private fun Metric(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = Text, style = MaterialTheme.typography.headlineSmall)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LiveWaveform(samples: List<Float>, envelope: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Panel)) {
        if (samples.size < 2) return@Canvas
        val maxV = samples.maxOf { abs(it) }.coerceAtLeast(.0005f)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = size.width * i / samples.lastIndex.coerceAtLeast(1)
            val y = size.height / 2 - v / maxV * size.height * .38f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Muted.copy(alpha = .15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        drawPath(path, Primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        if (envelope.size > 1) {
            val ep = Path()
            envelope.forEachIndexed { i, v ->
                val x = size.width * i / envelope.lastIndex.coerceAtLeast(1)
                val y = size.height * .92f - v * size.height * .18f
                if (i == 0) ep.moveTo(x, y) else ep.lineTo(x, y)
            }
            drawPath(ep, Primary.copy(alpha = .35f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms == 0L) "Not calibrated" else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun HomeScreen(vm: PhonoViewModel, onMeasure: () -> Unit, onCalibrate: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("PhonoCardio", color = Text, style = MaterialTheme.typography.headlineLarge)
                Text("Digital heart-sound research platform", color = Primary, style = MaterialTheme.typography.labelLarge)
            }
            Surface(shape = RoundedCornerShape(50), color = if (vm.calibration.completed) Primary.copy(alpha = .14f) else Muted.copy(alpha = .12f)) {
                Text(if (vm.calibration.completed) "READY" else "SETUP", color = if (vm.calibration.completed) Primary else Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start a measurement", color = Text, style = MaterialTheme.typography.titleLarge)
                Text("Use a wired headset or contact microphone. The microphone captures acoustic heart sounds; the earphone speaker is not the sensor.", color = Muted)
                Button(onClick = { if (vm.calibration.completed) onMeasure() else onCalibrate() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(if (vm.calibration.completed) "START MEASUREMENT" else "SET UP & CALIBRATE")
                }
            }
        }
        Text("HOW IT WORKS", color = Muted, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("01", "INPUT", "Connect mic", Modifier.weight(1f))
            Metric("02", "CALIBRATE", "Quiet + contact", Modifier.weight(1f))
            Metric("03", "MEASURE", "Record + analyze", Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you start", color = Text, style = MaterialTheme.typography.titleMedium)
                Text("1  Connect the microphone\n2  Keep the phone still\n3  Place the microphone gently on the precordial area\n4  Complete calibration\n5  Run a measurement for at least 10 seconds", color = Muted)
            }
        }
        Text("CURRENT STATUS", color = Muted, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("INPUT", if (state.headsetDetected) "Connected" else "Not detected", "Headset microphone", Modifier.weight(1f))
            Metric("CALIBRATION", if (vm.calibration.completed) "Valid" else "Required", if (vm.calibration.completed) formatTime(vm.calibration.timestampMs) else "Open Calibrate", Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10233A)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Research workflow", color = Text, style = MaterialTheme.typography.titleMedium)
                Text("Every completed session is stored locally in Measurement History. Reference Validation lets you pair the measured heart rate with simultaneous ECG, PPG, or another validated reference.", color = Muted)
            }
        }
        Text("Research / educational prototype. Heart-rate performance must be established against a reference method before clinical interpretation.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CalibrationScreen(vm: PhonoViewModel, scope: CoroutineScope) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Calibration", color = Text, style = MaterialTheme.typography.headlineMedium)
        Text("6-second input workflow: 2 seconds quiet baseline, then 4 seconds gentle precordial contact. This calibrates input conditions, not clinical accuracy.", color = Muted)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (vm.calibration.completed) "✓ CALIBRATION VALID" else "CALIBRATION REQUIRED", color = if (vm.calibration.completed) Primary else Muted)
                Text(vm.calibrationMessage, color = Text)
                LinearProgressIndicator(progress = { vm.calibrationProgress }, modifier = Modifier.fillMaxWidth())
                if (vm.calibration.completed) Text("Peak %.5f • Quality %.0f%% • %s".format(vm.calibration.peakLevel, vm.calibration.quality * 100f, formatTime(vm.calibration.timestampMs)), color = Muted)
            }
        }
        Button(onClick = { vm.startCalibration(scope) }, enabled = !vm.calibrating && !state.running, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.calibrating) "CALIBRATING…" else "START / RECALIBRATE")
        }
        OutlinedButton(onClick = vm::resetCalibration, enabled = !vm.calibrating, modifier = Modifier.fillMaxWidth()) { Text("CLEAR CALIBRATION") }
    }
}

@Composable
private fun LiveScreen(vm: PhonoViewModel, scope: CoroutineScope, requestPermission: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PhonoCardio", color = Text, style = MaterialTheme.typography.headlineMedium)
        Text(if (state.running) "● LIVE" else "○ IDLE", color = if (state.running) Primary else Muted)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp)) {
                Text(if (state.headsetDetected) "● MICROPHONE INPUT CONNECTED" else "○ CONNECT A HEADSET MICROPHONE", color = if (state.headsetDetected) Primary else Muted)
                Text("Hold the microphone gently over the precordial area. Minimize cable and body movement.", color = Muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("HEART RATE", state.heartRateBpm?.let { it.toString() + " BPM" } ?: "--", state.status, Modifier.weight(1f))
            Metric("CONFIDENCE", (state.confidence * 100).toInt().toString() + "%", "Multi-beat agreement", Modifier.weight(1f))
            Metric("QUALITY", (state.quality * 100).toInt().toString() + "%", "SNR %.1f dB".format(state.snrDb), Modifier.weight(1f))
        }
        LiveWaveform(state.samples, state.envelope, Modifier.fillMaxWidth().height(240.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("R–R", state.rrMs?.let { it.toString() + " ms" } ?: "--", "Beat interval", Modifier.weight(1f))
            Metric("BEATS", state.beatCount.toString(), "Session count", Modifier.weight(1f))
            Metric("DURATION", state.measurementDurationSec.toString() + "s", "Current session", Modifier.weight(1f))
        }
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermission() else vm.toggle(scope)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.running) "STOP & SAVE MEASUREMENT" else if (!vm.calibration.completed) "CALIBRATE BEFORE MEASUREMENT" else "START LIVE MEASUREMENT")
        }
        Text("Research prototype. Reference validation is required before diagnostic interpretation.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryScreen(vm: PhonoViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Measurement History", color = Text, style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = vm::clearHistory, enabled = vm.measurements.isNotEmpty()) { Text("Clear") }
        }
        Text(vm.measurements.size.toString() + " saved local sessions", color = Muted)
        vm.measurements.forEach { m ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp)) {
                    Text(formatTime(m.timestampMs), color = Text)
                    Text((m.heartRateBpm?.toString() ?: "--") + " BPM • " + m.durationSec + "s • " + m.beatCount + " beats", color = Primary)
                    Text("Quality " + (m.quality * 100).toInt() + "% • Confidence " + (m.confidence * 100).toInt() + "% • SNR %.1f dB".format(m.snrDb), color = Muted)
                    Text("Status " + m.status + " • " + m.algorithmVersion + " • " + m.sampleRate + " Hz", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (vm.measurements.isEmpty()) Text("No completed sessions yet. Sessions of at least 3 seconds are saved locally.", color = Muted)
    }
}

@Composable
private fun ValidationScreen(vm: PhonoViewModel) {
    var measured by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("Reference ECG/PPG") }
    val current = vm.state.collectAsState().value.heartRateBpm
    val data = vm.validations
    val mae = if (data.isEmpty()) null else data.map { it.absoluteErrorBpm }.average()
    val rmse = if (data.isEmpty()) null else sqrt(data.map { it.signedErrorBpm * it.signedErrorBpm }.average())
    val bias = if (data.isEmpty()) null else data.map { it.signedErrorBpm }.average()
    val within5 = if (data.isEmpty()) null else data.count { it.absoluteErrorBpm <= 5f } * 100.0 / data.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Reference Validation", color = Text, style = MaterialTheme.typography.headlineMedium)
        Text("Pair PhonoCardio with a simultaneous ECG, PPG, or validated reference monitor. These metrics describe reference agreement; they do not certify clinical accuracy.", color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric("N", data.size.toString(), "Paired", Modifier.weight(1f))
            Metric("MAE", mae?.let { "%.2f".format(it) + " BPM" } ?: "--", "Mean absolute error", Modifier.weight(1f))
            Metric("RMSE", rmse?.let { "%.2f".format(it) + " BPM" } ?: "--", "Root mean square", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric("BIAS", bias?.let { "%+.2f".format(it) + " BPM" } ?: "--", "Signed error", Modifier.weight(1f))
            Metric("≤5 BPM", within5?.let { "%.1f".format(it) + "%" } ?: "--", "Paired records", Modifier.weight(1f))
        }
        OutlinedTextField(measured, { measured = it.filter(Char::isDigit).take(3) }, label = { Text("PhonoCardio HR (BPM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (current != null) TextButton(onClick = { measured = current.toString() }) { Text("Use current live HR: " + current + " BPM") }
        OutlinedTextField(reference, { reference = it.filter(Char::isDigit).take(3) }, label = { Text("Reference HR (BPM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(source, { source = it.take(60) }, label = { Text("Reference source") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            val a = measured.toIntOrNull()
            val b = reference.toIntOrNull()
            if (a != null && b != null) { vm.addValidation(a, b, source); measured = ""; reference = "" }
        }, enabled = measured.toIntOrNull() in 30..220 && reference.toIntOrNull() in 30..220, modifier = Modifier.fillMaxWidth()) { Text("SAVE PAIRED VALIDATION") }
        data.take(20).forEach { v ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp)) {
                    Text(v.measuredBpm.toString() + " vs " + v.referenceBpm + " BPM", color = Text)
                    Text("Error %.1f BPM • Bias %+.1f BPM • %.1f%%".format(v.absoluteErrorBpm, v.signedErrorBpm, v.percentError), color = Muted)
                    Text(v.source + " • " + formatTime(v.timestampMs), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = vm::clearValidation, enabled = data.isNotEmpty()) { Text("Clear validation data") }
    }
}

@Composable
fun App(vm: PhonoViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { vm.attach(context) }
    val destinations = listOf("Home", "Measure", "Calibrate", "History", "Validate")
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Primary, onPrimary = Bg, background = Bg, surface = Panel,
            onSurface = Text, surfaceVariant = Color(0xFF15263B), onSurfaceVariant = Muted
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF091522)) {
                    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.MonitorHeart, Icons.Rounded.Tune, Icons.Rounded.History, Icons.Rounded.Science)
                    destinations.forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            icon = { Icon(icons[i], contentDescription = title) },
                            label = { Text(title, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(vm, onMeasure = { selectedTab = 1 }, onCalibrate = { selectedTab = 2 })
                    1 -> LiveScreen(vm, scope) { launcher.launch(Manifest.permission.RECORD_AUDIO) }
                    2 -> CalibrationScreen(vm, scope)
                    3 -> HistoryScreen(vm)
                    else -> ValidationScreen(vm)
                }
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
