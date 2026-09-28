package com.ruthwik.phonocardio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruthwik.phonocardio.audio.AudioCaptureEngine
import com.ruthwik.phonocardio.model.SignalSnapshot
import com.ruthwik.phonocardio.signal.ProcessedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class PhonoViewModel : ViewModel() {
    private val _state=MutableStateFlow(SignalSnapshot())
    val state=_state.asStateFlow()
    private var engine:AudioCaptureEngine?=null
    fun attach(context:android.content.Context){if(engine==null)engine=AudioCaptureEngine(context.applicationContext)}
    fun toggle(scope:CoroutineScope){
        val e=engine?:return
        if(_state.value.running){e.stop();_state.value=_state.value.copy(running=false,status="READY");return}
        if(e.start(scope){p:ProcessedChunk->
            _state.value=_state.value.copy(samples=p.waveform,envelope=p.envelope,heartRateBpm=p.bpm,beatCount=p.beatCount,rrMs=p.rrMs,lastBeatAgeMs=p.lastBeatAgeMs,snrDb=p.snrDb,quality=p.quality,confidence=p.confidence,headsetDetected=e.hasHeadset(),running=true,status=p.status,processingLatencyMs=p.processingLatencyMs)
        })_state.value=_state.value.copy(running=true,headsetDetected=e.hasHeadset(),status="ACQUIRING")
    }
    override fun onCleared(){engine?.stop();super.onCleared()}
}

private val Bg=Color(0xFF06101D)
private val Panel=Color(0xFF0D1A2B)
private val Primary=Color(0xFF43E6D3)
private val Text=Color(0xFFEAF3FF)
private val Muted=Color(0xFF8EA4BD)

@Composable
private fun LiveWaveform(samples:List<Float>,envelope:List<Float>,modifier:Modifier=Modifier){
    Canvas(modifier.background(Panel)){
        if(samples.size<2)return@Canvas
        drawLine(Muted.copy(alpha=.15f),Offset(0f,size.height/2),Offset(size.width,size.height/2),1f)
        val maxV=samples.maxOf{abs(it)}.coerceAtLeast(.0005f)
        val path=Path()
        samples.forEachIndexed{i,v->
            val x=size.width*i/samples.lastIndex.coerceAtLeast(1)
            val y=size.height/2-v/maxV*size.height*.38f
            if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
        }
        drawPath(path,Primary,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3f))
        if(envelope.size>1){
            val ep=Path()
            envelope.forEachIndexed{i,v->
                val x=size.width*i/envelope.lastIndex.coerceAtLeast(1)
                val y=size.height*.92f-v*size.height*.18f
                if(i==0)ep.moveTo(x,y)else ep.lineTo(x,y)
            }
            drawPath(ep,Primary.copy(alpha=.35f),style=androidx.compose.ui.graphics.drawscope.Stroke(width=2f))
        }
    }
}

@Composable
private fun BeatPulse(active:Boolean){
    val t=rememberInfiniteTransition(label="beat")
    val a by t.animateFloat(.35f,1f,infiniteRepeatable(tween(650),RepeatMode.Reverse),label="alpha")
    Canvas(Modifier.size(16.dp)){drawCircle(if(active)Primary.copy(alpha=a) else Muted.copy(alpha=.4f),5f)}
}

@Composable
private fun Metric(title:String,value:String,detail:String,modifier:Modifier=Modifier){
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)){
        Column(Modifier.padding(14.dp)){Text(title,color=Muted,style=MaterialTheme.typography.labelSmall);Spacer(Modifier.height(4.dp));Text(value,color=Text,style=MaterialTheme.typography.headlineSmall);Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
fun App(vm:PhonoViewModel=viewModel()){
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val state by vm.state.collectAsState()
    var permission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it}
    LaunchedEffect(Unit){vm.attach(context)}
    MaterialTheme(colorScheme=darkColorScheme(primary=Primary,background=Bg,surface=Panel,onSurface=Text)){
        Surface(Modifier.fillMaxSize(),color=Bg){
            Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Column{Text("PhonoCardio",color=Text,style=MaterialTheme.typography.headlineMedium);Text("LIVE PHONOCARDIOGRAPHY ENGINE",color=Primary,style=MaterialTheme.typography.labelSmall)}
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){BeatPulse(state.running);Text(if(state.running)"LIVE"else"IDLE",color=Muted)}
                }
                Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(if(state.headsetDetected)"● MICROPHONE INPUT CONNECTED"else"○ CONNECT A HEADSET MICROPHONE",color=if(state.headsetDetected)Primary else Muted)
                        Text("Hold the microphone gently over the precordial area. Minimize cable and body movement.",color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    Metric("HEART RATE",state.heartRateBpm?.let{it.toString()+" BPM"}?:"--",state.status,Modifier.weight(1f))
                    Metric("CONFIDENCE",(state.confidence*100).toInt().toString()+"%","Multi-beat agreement",Modifier.weight(1f))
                    Metric("QUALITY",(state.quality*100).toInt().toString()+"%","SNR "+("%.1f".format(state.snrDb))+" dB",Modifier.weight(1f))
                }
                Text("REAL-TIME ACOUSTIC TRACE",color=Muted,style=MaterialTheme.typography.labelMedium)
                LiveWaveform(state.samples,state.envelope,Modifier.fillMaxWidth().height(245.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    Metric("R–R",state.rrMs?.let{it.toString()+" ms"}?:"--","Beat interval",Modifier.weight(1f))
                    Metric("BEATS",state.beatCount.toString(),"Session count",Modifier.weight(1f))
                    Metric("DSP",state.processingLatencyMs.toString()+" ms","Chunk latency",Modifier.weight(1f))
                }
                LinearProgressIndicator(progress={state.quality.coerceIn(0f,1f)},Modifier.fillMaxWidth())
                Button(onClick={if(!permission)launcher.launch(Manifest.permission.RECORD_AUDIO)else vm.toggle(scope)},Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){
                    Text(if(state.running)"STOP LIVE MEASUREMENT"else"START LIVE MEASUREMENT")
                }
                Text("Research prototype. The acoustic chain and algorithms require reference-device validation before diagnostic use.",color=Muted,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App()}}
}
