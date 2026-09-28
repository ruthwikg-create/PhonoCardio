package com.ruthwik.phonocardio.data

import android.content.Context
import com.ruthwik.phonocardio.model.CalibrationProfile
import com.ruthwik.phonocardio.model.MeasurementRecord
import com.ruthwik.phonocardio.model.ReferenceValidationRecord
import org.json.JSONArray
import org.json.JSONObject

class PhonoRepository(context: Context) {
    private val prefs = context.getSharedPreferences("phonocardio_research", Context.MODE_PRIVATE)

    fun saveCalibration(profile: CalibrationProfile) {
        prefs.edit().putString("calibration", JSONObject().apply {
            put("completed", profile.completed)
            put("timestampMs", profile.timestampMs)
            put("sampleRate", profile.sampleRate)
            put("baselineNoise", profile.baselineNoise.toDouble())
            put("peakLevel", profile.peakLevel.toDouble())
            put("quality", profile.quality.toDouble())
            put("version", profile.version)
        }.toString()).apply()
    }

    fun getCalibration(): CalibrationProfile {
        val raw = prefs.getString("calibration", null) ?: return CalibrationProfile()
        return runCatching {
            val o = JSONObject(raw)
            CalibrationProfile(
                completed = o.optBoolean("completed"),
                timestampMs = o.optLong("timestampMs"),
                sampleRate = o.optInt("sampleRate", 16000),
                baselineNoise = o.optDouble("baselineNoise").toFloat(),
                peakLevel = o.optDouble("peakLevel").toFloat(),
                quality = o.optDouble("quality").toFloat(),
                version = o.optString("version", "CAL-1.0")
            )
        }.getOrDefault(CalibrationProfile())
    }

    fun clearCalibration() = prefs.edit().remove("calibration").apply()

    fun saveMeasurement(record: MeasurementRecord) {
        val all = JSONArray(prefs.getString("measurements", "[]"))
        all.put(JSONObject().apply {
            put("id", record.id)
            put("timestampMs", record.timestampMs)
            put("durationSec", record.durationSec)
            put("heartRateBpm", record.heartRateBpm ?: JSONObject.NULL)
            put("rrMs", record.rrMs ?: JSONObject.NULL)
            put("beatCount", record.beatCount)
            put("quality", record.quality.toDouble())
            put("confidence", record.confidence.toDouble())
            put("snrDb", record.snrDb.toDouble())
            put("status", record.status)
            put("sampleRate", record.sampleRate)
            put("algorithmVersion", record.algorithmVersion)
        })
        while (all.length() > 50) all.remove(0)
        prefs.edit().putString("measurements", all.toString()).apply()
    }

    fun getMeasurements(): List<MeasurementRecord> {
        val all = JSONArray(prefs.getString("measurements", "[]"))
        return (0 until all.length()).mapNotNull { i ->
            runCatching {
                val o = all.getJSONObject(i)
                MeasurementRecord(
                    id = o.getLong("id"),
                    timestampMs = o.getLong("timestampMs"),
                    durationSec = o.getInt("durationSec"),
                    heartRateBpm = if (o.isNull("heartRateBpm")) null else o.getInt("heartRateBpm"),
                    rrMs = if (o.isNull("rrMs")) null else o.getInt("rrMs"),
                    beatCount = o.getInt("beatCount"),
                    quality = o.getDouble("quality").toFloat(),
                    confidence = o.getDouble("confidence").toFloat(),
                    snrDb = o.getDouble("snrDb").toFloat(),
                    status = o.getString("status"),
                    sampleRate = o.getInt("sampleRate"),
                    algorithmVersion = o.optString("algorithmVersion", "DSP-0.2")
                )
            }.getOrNull()
        }.reversed()
    }

    fun clearMeasurements() = prefs.edit().remove("measurements").apply()

    fun saveValidation(record: ReferenceValidationRecord) {
        val all = JSONArray(prefs.getString("validations", "[]"))
        all.put(JSONObject().apply {
            put("id", record.id)
            put("timestampMs", record.timestampMs)
            put("measuredBpm", record.measuredBpm)
            put("referenceBpm", record.referenceBpm)
            put("source", record.source)
            put("absoluteErrorBpm", record.absoluteErrorBpm.toDouble())
            put("signedErrorBpm", record.signedErrorBpm.toDouble())
            put("percentError", record.percentError.toDouble())
        })
        while (all.length() > 200) all.remove(0)
        prefs.edit().putString("validations", all.toString()).apply()
    }

    fun getValidations(): List<ReferenceValidationRecord> {
        val all = JSONArray(prefs.getString("validations", "[]"))
        return (0 until all.length()).mapNotNull { i ->
            runCatching {
                val o = all.getJSONObject(i)
                ReferenceValidationRecord(
                    id = o.getLong("id"),
                    timestampMs = o.getLong("timestampMs"),
                    measuredBpm = o.getInt("measuredBpm"),
                    referenceBpm = o.getInt("referenceBpm"),
                    source = o.getString("source"),
                    absoluteErrorBpm = o.getDouble("absoluteErrorBpm").toFloat(),
                    signedErrorBpm = o.getDouble("signedErrorBpm").toFloat(),
                    percentError = o.getDouble("percentError").toFloat()
                )
            }.getOrNull()
        }.reversed()
    }

    fun clearValidations() = prefs.edit().remove("validations").apply()
}
