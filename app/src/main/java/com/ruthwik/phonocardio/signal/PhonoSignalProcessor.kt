package com.ruthwik.phonocardio.signal

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class PhonoSignalProcessor(private val sampleRate: Int = 16_000) {
    private var dc=0.0; private var hpX=0.0; private var hpY=0.0
    private var lp1=0.0; private var lp2=0.0; private var env=0.0; private var noise=0.002
    private var samplesProcessed=0L; private var lastBeatSample=Long.MIN_VALUE; private var previousEnergy=0.0
    private val beatTimes=ArrayDeque<Long>(); private val rrHistory=ArrayDeque<Double>()
    private val waveform=ArrayDeque<Float>(); private val envelope=ArrayDeque<Float>()

    fun process(input: ShortArray): ProcessedChunk {
        val startNs=System.nanoTime(); val out=FloatArray(input.size); val envOut=FloatArray(input.size)
        var signalPower=0.0; var residualPower=0.0; var clipped=0; var localMax=0.0
        input.forEachIndexed { i,raw ->
            val x=raw/32768.0
            if(abs(raw.toInt())>32000) clipped++
            dc=0.9985*dc+0.0015*x
            val centered=x-dc
            val hp=0.992*(hpY+centered-hpX); hpX=centered; hpY=hp
            lp1+=0.055*(hp-lp1); lp2+=0.055*(lp1-lp2); val y=lp2
            val a=abs(y); val quiet=if(a<noise*3.0) 0.985 else 0.999
            noise=quiet*noise+(1.0-quiet)*a
            val energy=y*y; val shannon=-energy*log10(energy+1e-12)
            env+=0.12*(shannon-env)
            val ne=(env/max(noise*noise*3.0,1e-8)).coerceIn(0.0,8.0)/8.0
            out[i]=y.toFloat(); envOut[i]=ne.toFloat()
            signalPower+=y*y; residualPower+=(centered-y)*(centered-y); localMax=max(localMax,a)
            val threshold=max(0.0025,noise*4.2); val rising=ne>0.34 && ne>previousEnergy*0.96
            val idx=samplesProcessed+i
            if(rising && localMax>threshold && idx-lastBeatSample>=(sampleRate*0.30).toLong() &&
                i>=2 && abs(out[i])>=abs(out[i-1])*0.92) registerBeat(idx)
            previousEnergy=ne
        }
        samplesProcessed+=input.size
        out.forEach { waveform.addLast(it); if(waveform.size>sampleRate*6/10) waveform.removeFirst() }
        envOut.forEach { envelope.addLast(it); if(envelope.size>sampleRate*6/10) envelope.removeFirst() }
        val signal=signalPower/max(1,input.size); val residual=residualPower/max(1,input.size)
        val snr=10.0*log10((signal+1e-10)/(residual+1e-10))
        val clip=clipped.toDouble()/max(1,input.size); val flat=if(localMax<0.0008) 1.0 else 0.0
        val quality=(0.45*((snr+5.0)/30.0).coerceIn(0.0,1.0)+0.35*(1.0-clip*12.0).coerceIn(0.0,1.0)+0.20*(1.0-flat)).coerceIn(0.0,1.0)
        val bpm=estimateBpm(); val rr=rrHistory.lastOrNull()?.toInt()
        val agreement=if(rrHistory.size>=3){val m=rrHistory.average(); val v=rrHistory.map{(it-m)*(it-m)}.average();(1.0-sqrt(v)/max(m,1.0)).coerceIn(0.0,1.0)}else 0.25
        val confidence=if(bpm!=null)(0.55*quality+0.45*agreement).coerceIn(0.0,1.0)else quality*0.35
        val status=when{quality<0.22->"POOR_SIGNAL";bpm==null->"ACQUIRING";confidence<0.55->"LOW_CONFIDENCE";else->"LIVE_MEASUREMENT"}
        val age=if(lastBeatSample==Long.MIN_VALUE)null else (samplesProcessed-lastBeatSample)*1000L/sampleRate
        return ProcessedChunk(waveform.toList(),envelope.toList(),bpm,beatTimes.size,rr,age,
            snr.coerceIn(-40.0,60.0).toFloat(),quality.toFloat(),confidence.toFloat(),status,(System.nanoTime()-startNs)/1_000_000L)
    }
    private fun registerBeat(sample:Long){
        if(lastBeatSample!=Long.MIN_VALUE){val rr=(sample-lastBeatSample).toDouble()*1000.0/sampleRate;if(rr in 300.0..2000.0){rrHistory.addLast(rr);while(rrHistory.size>8)rrHistory.removeFirst()}else return}
        lastBeatSample=sample;beatTimes.addLast(sample);while(beatTimes.size>120)beatTimes.removeFirst()
    }
    private fun estimateBpm():Int?{if(rrHistory.size<2)return null;val s=rrHistory.toList().sorted();val med=s[s.size/2];return(60000.0/med).toInt().takeIf{it in 30..200}}
    fun reset(){dc=0.0;hpX=0.0;hpY=0.0;lp1=0.0;lp2=0.0;env=0.0;noise=0.002;samplesProcessed=0;lastBeatSample=Long.MIN_VALUE;previousEnergy=0.0;beatTimes.clear();rrHistory.clear();waveform.clear();envelope.clear()}
}
data class ProcessedChunk(val waveform:List<Float>,val envelope:List<Float>,val bpm:Int?,val beatCount:Int,val rrMs:Int?,val lastBeatAgeMs:Long?,val snrDb:Float,val quality:Float,val confidence:Float,val status:String,val processingLatencyMs:Long)
