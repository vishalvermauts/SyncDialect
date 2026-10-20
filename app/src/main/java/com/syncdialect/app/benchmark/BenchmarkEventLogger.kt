package com.syncdialect.app.benchmark

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * High-precision, zero-allocation-when-disabled event logger for benchmarking.
 * All event timestamps are strictly captured in [SystemClock.elapsedRealtimeNanos].
 */
object BenchmarkEventLogger {

    @Volatile var isEnabled: Boolean = false
    @Volatile var currentRunId: String = ""
    @Volatile var currentAttemptId: String = ""
    @Volatile var currentRecordingId: String = ""
    @Volatile var currentTurnId: String = ""
    @Volatile var currentSegmentId: String = ""
    @Volatile var currentInferenceId: String = ""

    private var logWriter: FileWriter? = null
    private val memoryLog = ConcurrentLinkedQueue<JSONObject>()

    // Listeners for test framework correlation
    var onEventListener: ((JSONObject) -> Unit)? = null

    @Synchronized
    fun startSession(runId: String, outputFile: File) {
        currentRunId = runId
        outputFile.parentFile?.mkdirs()
        logWriter = FileWriter(outputFile, true)
        memoryLog.clear()
        isEnabled = true
        Log.i("BenchmarkEventLogger", "Started benchmark session: $runId -> ${outputFile.absolutePath}")
    }

    @Synchronized
    fun setContext(
        attemptId: String = currentAttemptId,
        recordingId: String = currentRecordingId,
        turnId: String = currentTurnId,
        segmentId: String = currentSegmentId,
        inferenceId: String = currentInferenceId
    ) {
        currentAttemptId = attemptId
        currentRecordingId = recordingId
        currentTurnId = turnId
        currentSegmentId = segmentId
        currentInferenceId = inferenceId
    }

    fun logEvent(
        eventType: String,
        details: Map<String, Any?> = emptyMap(),
        timestampNanos: Long = SystemClock.elapsedRealtimeNanos(),
        utteranceId: String? = null,
        segmentIdOverride: String? = null,
        inferenceIdOverride: String? = null,
        turnIdOverride: String? = null,
        attemptIdOverride: String? = null,
        recordingIdOverride: String? = null,
        attribution: TurnAttribution? = null
    ) {
        if (!isEnabled) return

        try {
            val json = JSONObject()
            json.put("event_type", eventType)
            json.put("timestamp_nanos", timestampNanos)
            json.put("run_id", currentRunId)
            json.put("attempt_id", attribution?.attemptId ?: attemptIdOverride ?: currentAttemptId)
            json.put("recording_id", attribution?.recordingId ?: recordingIdOverride ?: currentRecordingId)
            json.put("turn_id", attribution?.turnId ?: turnIdOverride ?: currentTurnId)
            json.put("segment_id", attribution?.segmentId ?: segmentIdOverride ?: currentSegmentId)
            json.put("inference_id", attribution?.inferenceId ?: inferenceIdOverride ?: currentInferenceId)
            if (utteranceId != null) {
                json.put("utterance_id", utteranceId)
            }

            for ((k, v) in details) {
                json.put(k, v ?: JSONObject.NULL)
            }

            val line = json.toString()
            synchronized(this) {
                logWriter?.write(line + "\n")
                logWriter?.flush()
            }
            memoryLog.offer(json)
            onEventListener?.invoke(json)
        } catch (e: Exception) {
            Log.e("BenchmarkEventLogger", "Failed to log event $eventType: ${e.message}")
        }
    }

    @Synchronized
    fun flushAndClose() {
        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (e: Exception) {
            Log.w("BenchmarkEventLogger", "Error closing logWriter: ${e.message}")
        } finally {
            logWriter = null
            isEnabled = false
        }
    }

    fun getLoggedEvents(): List<JSONObject> = memoryLog.toList()
}
