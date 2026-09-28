package com.ruthwik.phonocardio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruthwik.phonocardio.R
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
        repo?.saveMeasurement(
            MeasurementRecord(
                System.currentTimeMillis(), System.currentTimeMillis(), duration,
                s.heartRateBpm, s.rrMs, s.beatCount, s.quality, s.confidence,
                s.snrDb, s.status, s.sampleRate
            )
        )
        measurements = repo?.getMeasurements().orEmpty()
        measurementStartedAt = 0L
    }

    fun addValidation(measured: Int, reference: Int, source: String) {
        if (measured !in 30..220 || reference !in 30..220) return
        val signed = (measured - reference).toFloat()
        val absolute = abs(signed)
        repo?.saveValidation(
            ReferenceValidationRecord(
                System.currentTimeMillis(), System.currentTimeMillis(),
                measured, reference, source.ifBlank { "Reference device" },
                absolute, signed, absolute / reference * 100f
            )
        )
        validations = repo?.getValidations().orEmpty()
    }

    fun clearHistory() { repo?.clearMeasurements(); measurements = emptyList() }
    fun clearValidation() { repo?.clearValidations(); validations = emptyList() }
    override fun onCleared() { engine?.stop(); super.onCleared() }
}

private val Paper = Color(0xFFF4F1EA)
private val Surface = Color(0xFFFFFEFA)
private val Ink = Color(0xFF171B1F)
private val Slate = Color(0xFF66717B)
private val Border = Color(0xFFD8D4CA)
private val Signal = Color(0xFF3159D5)
private val SignalSoft = Color(0xFFE8EDFF)
private val Sage = Color(0xFF3F776B)
private val SageSoft = Color(0xFFE7F1ED)
private val Amber = Color(0xFFB56A1A)
private val Red = Color(0xFFB64A4A)
private val Grid = Color(0xFFE3E0D8)

private val GeistSans = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold)
)
private val GeistMono = FontFamily(Font(R.font.geist_mono_regular, FontWeight.Normal))

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-1.25).sp),
    headlineLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.35).sp),
    headlineSmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.55.sp),
    labelMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.65.sp)
)

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(Locale.getDefault()), color = Slate, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun StatusDot(active: Boolean, color: Color = Sage) {
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (active) 1f else 0.65f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statusAlpha"
    )
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (active) color.copy(alpha = alpha) else Border)
    )
}

@Composable
private fun MetricReadout(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    accent: Color = Ink
) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(Locale.getDefault()), color = Slate, style = MaterialTheme.typography.labelSmall)
        Text(value, color = accent, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = GeistMono))
        Text(detail, color = Slate, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalPanel(
    samples: List<Float>,
    envelope: List<Float>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            for (i in 1 until 8) {
                val x = size.width * i / 8f
                drawLine(Grid, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            for (i in 1 until 5) {
                val y = size.height * i / 5f
                drawLine(Grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawLine(Slate.copy(alpha = 0.25f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)

            if (samples.size >= 2) {
                val maxV = samples.maxOf { abs(it) }.coerceAtLeast(0.0005f)
                val path = Path()
                samples.forEachIndexed { i, v ->
                    val x = size.width * i / samples.lastIndex.coerceAtLeast(1)
                    val y = size.height / 2f - (v / maxV) * size.height * 0.34f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Ink, style = Stroke(width = 2.2f))
            }

            if (envelope.size >= 2) {
                val ep = Path()
                envelope.forEachIndexed { i, v ->
                    val x = size.width * i / envelope.lastIndex.coerceAtLeast(1)
                    val y = size.height * 0.88f - v * size.height * 0.20f
                    if (i == 0) ep.moveTo(x, y) else ep.lineTo(x, y)
                }
                drawPath(ep, Signal, style = Stroke(width = 1.8f))
            }
        }
    }
}

@Composable
private fun WorkflowStep(number: String, title: String, description: String, active: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = CircleShape,
            color = if (active) Signal else Paper,
            border = if (active) null else androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = if (active) Color.White else Slate, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(description, color = Slate, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InstrumentRow(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    valueColor: Color = Ink,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HomeScreen(vm: PhonoViewModel, onMeasure: () -> Unit, onCalibrate: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("PHONOCARDIO", color = Ink, style = MaterialTheme.typography.titleLarge, letterSpacing = 1.4.sp)
                Text("Acoustic acquisition console", color = Slate, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusDot(vm.calibration.completed)
                Text(if (vm.calibration.completed) "READY" else "SETUP", color = if (vm.calibration.completed) Sage else Slate, style = MaterialTheme.typography.labelSmall)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (vm.calibration.completed) "Ready to record." else "Configure the input first.", color = Ink, style = MaterialTheme.typography.headlineLarge)
            Text(
                "A focused workflow for heart-sound acquisition, signal inspection and reference validation.",
                color = Slate,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Surface(
            color = if (vm.calibration.completed) SignalSoft else Surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (vm.calibration.completed) Signal.copy(alpha = 0.25f) else Border)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SectionLabel(if (vm.calibration.completed) "Measurement" else "Setup")
                        Text(
                            if (vm.calibration.completed) "Begin a live acquisition" else "Complete microphone calibration",
                            color = Ink,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            if (vm.calibration.completed) "Use a wired headset microphone or contact microphone and keep the sensor stable."
                            else "The headset microphone is the sensor. The earphone speaker is not used for acquisition.",
                            color = Slate,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(
                        if (vm.calibration.completed) Icons.Rounded.ArrowForward else Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (vm.calibration.completed) Signal else Slate,
                        modifier = Modifier.size(28.dp)
                    )
                }
                PrimaryAction(
                    if (vm.calibration.completed) "Start measurement" else "Open calibration",
                    if (vm.calibration.completed) Icons.Rounded.PlayArrow else Icons.Rounded.Tune,
                    if (vm.calibration.completed) onMeasure else onCalibrate
                )
            }
        }

        Column {
            SectionLabel("System state")
            Spacer(Modifier.height(4.dp))
            InstrumentRow(
                icon = { Icon(Icons.Rounded.MicNone, null, tint = Slate) },
                title = "Input",
                value = if (state.headsetDetected) "Connected" else "Not detected",
                valueColor = if (state.headsetDetected) Sage else Slate
            )
            HorizontalDivider(color = Border)
            InstrumentRow(
                icon = { Icon(Icons.Rounded.Verified, null, tint = Slate) },
                title = "Calibration",
                value = if (vm.calibration.completed) "Valid" else "Required",
                valueColor = if (vm.calibration.completed) Sage else Amber,
                onClick = onCalibrate
            )
            HorizontalDivider(color = Border)
            InstrumentRow(
                icon = { Icon(Icons.Rounded.AccessTime, null, tint = Slate) },
                title = "Saved sessions",
                value = vm.measurements.size.toString(),
                valueColor = Ink
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Workflow")
            WorkflowStep("01", "Connect", "Attach the headset microphone and keep the phone stable.", active = !state.running)
            WorkflowStep("02", "Calibrate", "Capture a quiet baseline followed by gentle precordial contact.", active = vm.calibrating)
            WorkflowStep("03", "Acquire", "Record a sustained session while monitoring signal quality.", active = state.running)
            WorkflowStep("04", "Review", "Inspect saved measurements and compare against a reference method.")
        }

        Surface(
            color = Surface,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Research status")
                Text(
                    "Research / educational prototype. Performance should be established against a reference method before clinical interpretation.",
                    color = Slate,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CalibrationScreen(vm: PhonoViewModel, scope: CoroutineScope) {
    val state by vm.state.collectAsState()
    val progress by animateFloatAsState(vm.calibrationProgress, tween(260), label = "calibrationProgress")
    val phaseTwo = progress >= (2f / 6f)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionLabel("Calibration")
            Text("Establish the input baseline.", color = Ink, style = MaterialTheme.typography.headlineLarge)
            Text(
                "This workflow checks the acoustic input path. It does not establish clinical accuracy.",
                color = Slate,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SectionLabel(if (vm.calibrating) "Calibration in progress" else "Calibration status")
                        Text(
                            when {
                                vm.calibration.completed -> "Input accepted"
                                vm.calibrating -> if (phaseTwo) "Contact phase" else "Quiet baseline"
                                else -> "Calibration required"
                            },
                            color = Ink,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Text(
                        "%02d%%".format((progress * 100).toInt()),
                        color = Signal,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMono)
                    )
                }

                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Paper)
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Signal))
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkflowStep("01", "Quiet baseline", "Keep the microphone still for approximately two seconds.", active = vm.calibrating && !phaseTwo)
                    WorkflowStep("02", "Contact", "Gently place the microphone on the precordial area for the remaining phase.", active = vm.calibrating && phaseTwo)
                }

                Text(vm.calibrationMessage, color = Slate, style = MaterialTheme.typography.bodyMedium)

                AnimatedVisibility(visible = vm.calibrating, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
                    Surface(color = SignalSoft, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Keep the phone still while calibration runs.",
                            color = Signal,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (vm.calibration.completed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        MetricReadout("Peak", "%.5f".format(vm.calibration.peakLevel), "Input level")
                        MetricReadout("Quality", "%d%%".format((vm.calibration.quality * 100f).toInt()), "Calibration window")
                    }
                }

                PrimaryAction(
                    if (vm.calibrating) "Calibrating…" else if (vm.calibration.completed) "Recalibrate input" else "Start calibration",
                    Icons.Rounded.Tune,
                    { vm.startCalibration(scope) },
                    enabled = !vm.calibrating && !state.running
                )

                OutlinedButton(
                    onClick = vm::resetCalibration,
                    enabled = !vm.calibrating,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear calibration")
                }
            }
        }
    }
}

@Composable
private fun LiveScreen(vm: PhonoViewModel, scope: CoroutineScope, requestPermission: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val pulse = rememberInfiniteTransition(label = "livePulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state.running) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseScale"
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Acquisition")
                Text("Live signal", color = Ink, style = MaterialTheme.typography.headlineLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusDot(state.running, if (state.running) Signal else Slate)
                Text(if (state.running) "RECORDING" else "IDLE", color = if (state.running) Signal else Slate, style = MaterialTheme.typography.labelSmall)
            }
        }

        Surface(
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (state.headsetDetected) Sage.copy(alpha = 0.38f) else Border)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MicNone, null, tint = if (state.headsetDetected) Sage else Slate)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.headsetDetected) "Microphone input connected" else "Microphone not detected",
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (state.headsetDetected) "Keep the sensor stable on the precordial area."
                            else "Connect a wired headset microphone before recording.",
                            color = Slate,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Surface(
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel("Heart rate")
                        Text(
                            state.heartRateBpm?.toString() ?: "—",
                            color = Ink,
                            style = MaterialTheme.typography.displayLarge.copy(fontFamily = GeistMono)
                        )
                    }
                    Text("BPM", color = Slate, style = MaterialTheme.typography.titleSmall)
                }

                SignalPanel(state.samples, state.envelope, Modifier.fillMaxWidth().height(240.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MetricReadout("R–R", state.rrMs?.let { "$it ms" } ?: "—", "Beat interval", Modifier.weight(1f))
                    MetricReadout("Quality", "%d%%".format((state.quality * 100).toInt()), "SNR %.1f dB".format(state.snrDb), Modifier.weight(1f))
                    MetricReadout("Confidence", "%d%%".format((state.confidence * 100).toInt()), "Beat agreement", Modifier.weight(1f))
                }

                HorizontalDivider(color = Border)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Session", color = Slate, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "%02d:%02d".format(state.measurementDurationSec / 60, state.measurementDurationSec % 60),
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMono)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Processor", color = Slate, style = MaterialTheme.typography.labelSmall)
                        Text(
                            state.processingLatencyMs.toString() + " ms",
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMono)
                        )
                    }
                }
            }
        }

        PrimaryAction(
            if (state.running) "Stop and save session" else if (!vm.calibration.completed) "Calibrate before recording" else "Start live acquisition",
            if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermission()
                } else {
                    vm.toggle(scope)
                }
            },
            enabled = state.running || vm.calibration.completed
        )

        AnimatedVisibility(visible = state.running, enter = fadeIn(), exit = fadeOut()) {
            Surface(color = SageSoft, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).scale(pulseScale).clip(CircleShape).background(Sage))
                    Spacer(Modifier.width(9.dp))
                    Text("Acquisition is active. Avoid moving the phone or cable.", color = Sage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            "Research prototype. Reference validation is required before diagnostic interpretation.",
            color = Slate,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HistoryScreen(vm: PhonoViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Records")
                Text("Measurement history", color = Ink, style = MaterialTheme.typography.headlineLarge)
            }
            TextButton(onClick = vm::clearHistory, enabled = vm.measurements.isNotEmpty()) {
                Text("Clear", color = if (vm.measurements.isNotEmpty()) Red else Slate)
            }
        }
        Text(vm.measurements.size.toString() + " locally stored sessions", color = Slate, style = MaterialTheme.typography.bodySmall)

        if (vm.measurements.isEmpty()) {
            Surface(color = Surface, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Text(
                    "No completed sessions yet. Sessions of at least three seconds are stored locally when stopped.",
                    color = Slate,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            vm.measurements.forEachIndexed { index, m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(formatTime(m.timestampMs), color = Ink, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    m.heartRateBpm?.let { it.toString() + " BPM" },
                                    m.durationSec.toString() + "s",
                                    m.beatCount.toString() + " beats"
                                ).joinToString("  ·  "),
                                color = Signal,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono)
                            )
                        }
                        Text(
                            "%d%%".format((m.quality * 100).toInt()),
                            color = Ink,
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = GeistMono)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Confidence " + (m.confidence * 100).toInt() + "%  ·  SNR " + "%.1f".format(m.snrDb) + " dB  ·  " + m.status,
                        color = Slate,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (index != vm.measurements.lastIndex) {
                        HorizontalDivider(color = Border, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Validation")
            Text("Reference comparison", color = Ink, style = MaterialTheme.typography.headlineLarge)
            Text(
                "Pair a PhonoCardio reading with a simultaneous reference method and inspect agreement metrics.",
                color = Slate,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            MetricReadout("Pairs", data.size.toString(), "Stored", Modifier.weight(1f))
            MetricReadout("MAE", mae?.let { "%.2f".format(it) } ?: "—", "BPM", Modifier.weight(1f))
            MetricReadout("RMSE", rmse?.let { "%.2f".format(it) } ?: "—", "BPM", Modifier.weight(1f))
        }

        Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel("New paired record")
                OutlinedTextField(
                    measured,
                    { measured = it.filter(Char::isDigit).take(3) },
                    label = { Text("PhonoCardio HR (BPM)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (current != null) {
                    TextButton(onClick = { measured = current.toString() }) {
                        Text("Use current live HR: " + current + " BPM", color = Signal)
                    }
                }
                OutlinedTextField(
                    reference,
                    { reference = it.filter(Char::isDigit).take(3) },
                    label = { Text("Reference HR (BPM)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    source,
                    { source = it.take(60) },
                    label = { Text("Reference source") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val a = measured.toIntOrNull()
                        val b = reference.toIntOrNull()
                        if (a != null && b != null) {
                            vm.addValidation(a, b, source)
                            measured = ""
                            reference = ""
                        }
                    },
                    enabled = measured.toIntOrNull() in 30..220 && reference.toIntOrNull() in 30..220,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) {
                    Icon(Icons.Rounded.Verified, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save paired validation")
                }
            }
        }

        if (data.isNotEmpty()) {
            SectionLabel("Recent pairs")
            data.take(20).forEachIndexed { index, v ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            v.measuredBpm.toString() + " vs " + v.referenceBpm + " BPM",
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMono),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "%+.1f BPM".format(v.signedErrorBpm),
                            color = if (v.absoluteErrorBpm <= 5f) Sage else Amber,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMono)
                        )
                    }
                    Text(
                        v.source + "  ·  " + formatTime(v.timestampMs),
                        color = Slate,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (index != data.take(20).lastIndex) {
                        HorizontalDivider(color = Border, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            TextButton(onClick = vm::clearValidation) {
                Icon(Icons.Rounded.DeleteOutline, null)
                Spacer(Modifier.width(6.dp))
                Text("Clear validation data", color = Red)
            }
        }

        Surface(color = Paper, shape = RoundedCornerShape(8.dp)) {
            Text(
                "Validation metrics describe agreement with the chosen reference dataset. They do not certify clinical accuracy.",
                color = Slate,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms == 0L) "Not calibrated" else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun App(vm: PhonoViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { vm.attach(context) }

    val destinations = listOf("Home", "Measure", "Calibrate", "History", "Validate")
    val icons = listOf(
        Icons.Rounded.Home,
        Icons.Rounded.MonitorHeart,
        Icons.Rounded.Tune,
        Icons.Rounded.History,
        Icons.Rounded.Science
    )

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Signal,
            onPrimary = Color.White,
            primaryContainer = SignalSoft,
            onPrimaryContainer = Ink,
            secondary = Sage,
            onSecondary = Color.White,
            secondaryContainer = SageSoft,
            onSecondaryContainer = Ink,
            background = Paper,
            surface = Surface,
            surfaceVariant = Paper,
            onSurface = Ink,
            onSurfaceVariant = Slate,
            outline = Border,
            outlineVariant = Border,
            error = Red,
            onError = Color.White
        ),
        typography = AppTypography
    ) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                Surface(color = Paper, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        destinations.forEachIndexed { i, title ->
                            val selected = selectedTab == i
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { selectedTab = i }
                                    .padding(vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    Modifier
                                        .width(if (selected) 22.dp else 6.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (selected) Signal else Color.Transparent)
                                )
                                Icon(
                                    icons[i],
                                    contentDescription = title,
                                    tint = if (selected) Ink else Slate,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    title,
                                    color = if (selected) Ink else Slate,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = selectedTab, label = "screenTransition") { tab ->
                    when (tab) {
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
